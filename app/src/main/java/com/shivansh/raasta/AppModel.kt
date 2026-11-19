package com.shivansh.raasta

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shivansh.raasta.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data object NewRoute : Screen
    data class Pack(val id: String) : Screen
    data object Navigate : Screen
}

/** One row on the home screen. */
class PackItem(val file: java.io.File, val meta: PackMeta, val thumb: com.shivansh.raasta.ui.RouteThumbData?, val preview: LoadedPack? = null)

private fun thumbOf(pack: RoutePack): com.shivansh.raasta.ui.RouteThumbData? {
    val g = RoadGraph(pack)
    val path = pack.routes.firstOrNull()?.let { Path.fromNodes(g, it.nodePath) } ?: return null
    val step = maxOf(1, path.nodes.size / 60)
    val idx = (path.nodes.indices step step).toMutableList().also { if (it.last() != path.nodes.size - 1) it.add(path.nodes.size - 1) }
    val k = kotlin.math.cos(Math.toRadians(path.lat(0)))
    val xs = idx.map { path.lon(it) * k }; val ys = idx.map { -path.lat(it) }
    val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min(), 1e-9)
    val ox = (xs.min() + xs.max()) / 2; val oy = (ys.min() + ys.max()) / 2
    val xy = FloatArray(idx.size * 2)
    for (i in idx.indices) { xy[2 * i] = (0.5 + (xs[i] - ox) / span).toFloat(); xy[2 * i + 1] = (0.5 + (ys[i] - oy) / span).toFloat() }
    val bridge = BooleanArray(idx.size) { i ->
        if (i >= idx.size - 1) false else (idx[i] until idx[i + 1]).any { e -> e < path.edges.size && path.way(e).bridge }
    }
    return com.shivansh.raasta.ui.RouteThumbData(xy, bridge)
}

/** A loaded pack plus its graph and the guidance for each route. */
class LoadedPack(val file: File, val pack: RoutePack) {
    val graph = RoadGraph(pack)
    val paths: List<Path> = pack.routes.mapNotNull { Path.fromNodes(graph, it.nodePath) }
    val guidance: List<List<Maneuver>> = paths.map { Guidance.build(it, pack.meta.toName) }
    /** Map geometry, built here on a background thread so opening a ride never stalls the UI. */
    val layers = com.shivansh.raasta.ui.MapLayers(this)
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val http = Http()
    private val store = PackStore(File(app.filesDir, "packs"))
    private val geocoder = Geocoder(http)
    private val builder = PackBuilder(http, RoadTiles(http, Config.TILE_BASES))
    val speaker = Speaker(app)
    private val gps = Gps(app)

    var screen by mutableStateOf<Screen>(Screen.Home)
    var packs by mutableStateOf<List<PackItem>>(emptyList())
        private set

    // ---- new route ----
    var fromPlace by mutableStateOf<Place?>(null)
    var toPlace by mutableStateOf<Place?>(null)
    var results by mutableStateOf<List<Place>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    /** 0 finding routes, 1 downloading roads, 2 working out flyovers. */
    var busyStep by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)

    // ---- pack preview ----
    var loaded by mutableStateOf<LoadedPack?>(null)
        private set
    var routeIndex by mutableStateOf(0)

    // ---- navigation ----
    var nav by mutableStateOf<NavState?>(null)
        private set
    var demo by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var lastSpoken by mutableStateOf("")
        private set
    var gpsLost by mutableStateOf(false)
        private set
    var navManeuvers by mutableStateOf<List<Maneuver>>(emptyList())
        private set
    private var navigator: Navigator? = null
    private var demoJob: Job? = null
    private var watchdog: Job? = null
    private var lastFixAt = 0L

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            // Show the list at once, then fill in the route silhouettes.
            val list = withContext(Dispatchers.IO) { store.list() }
            packs = list.map { (f, m) -> PackItem(f, m, packs.firstOrNull { it.meta.id == m.id }?.thumb) }
            packs = withContext(Dispatchers.Default) {
                list.map { (f, m) ->
                    // Fully load each ride in the background: the home cards draw real mini maps, and opening is instant.
                    val lp = runCatching { LoadedPack(f, PackIO.read(f)) }.getOrNull()
                    PackItem(f, m, lp?.let { runCatching { thumbOf(it.pack) }.getOrNull() }, lp)
                }
            }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        searching = true
        error = null
        viewModelScope.launch {
            try {
                results = withContext(Dispatchers.IO) { geocoder.search(query.trim()) }
                if (results.isEmpty()) error = "Nothing found in Delhi for \"$query\"."
            } catch (e: Exception) {
                error = "Search needs internet. ${e.message ?: ""}".trim()
            } finally {
                searching = false
            }
        }
    }

    fun clearResults() { results = emptyList() }

    fun useMyLocation(): Boolean {
        val f = gps.lastKnown() ?: return false
        if (!Delhi.contains(f.lat, f.lon)) { error = "You're not in Delhi right now. Search for a start point instead."; return true }
        fromPlace = Place("My location", "%.5f, %.5f".format(f.lat, f.lon), f.lat, f.lon)
        return true
    }

    fun download() {
        val from = fromPlace ?: return
        val to = toPlace ?: return
        error = null
        busy = "Finding routes…"
        busyStep = 0
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val routes = builder.findRoutes(from.latLon, to.latLon)
                    val pack = builder.build(from, to, routes) { msg ->
                        viewModelScope.launch(Dispatchers.Main) {
                            busy = msg
                            busyStep = when {
                                msg.startsWith("Working") || msg.startsWith("Building") -> 2
                                msg.contains("Download") || msg.contains("tiles") -> 1
                                else -> busyStep
                            }
                        }
                    }
                    store.save(pack)
                }
                refresh()
                fromPlace = null; toPlace = null; results = emptyList()
                openPack(file.nameWithoutExtension)
            } catch (e: Exception) {
                error = e.message ?: "Download failed."
            } finally {
                busy = null
            }
        }
    }

    fun openPack(id: String) {
        screen = Screen.Pack(id)
        routeIndex = 0
        if (loaded?.pack?.meta?.id == id) return
        packs.firstOrNull { it.meta.id == id }?.preview?.let { loaded = it; return }
        loaded = null
        viewModelScope.launch {
            try {
                loaded = withContext(Dispatchers.Default) { LoadedPack(store.fileFor(id), store.load(id)) }
            } catch (e: Exception) {
                error = "Couldn't open this route: ${e.message}"
                screen = Screen.Home
            }
        }
    }

    fun deletePack(id: String) {
        store.delete(id)
        if (loaded?.pack?.meta?.id == id) loaded = null
        screen = Screen.Home
        refresh()
    }

    fun startNavigation(simulate: Boolean) {
        val lp = loaded ?: return
        val path = lp.paths.getOrNull(routeIndex) ?: return
        stopNavigation(goHome = false)
        demo = simulate
        val n = Navigator(lp.graph, path, lp.pack.meta.toName) { text, urgent ->
            viewModelScope.launch(Dispatchers.Main) {
                lastSpoken = text
                speaker.say(text, urgent)
            }
        }
        navigator = n
        nav = n.state
        navManeuvers = n.maneuvers
        screen = Screen.Navigate
        n.start()
        lastFixAt = System.currentTimeMillis()
        if (simulate) {
            val driver = DemoDriver(n, kmh = 40.0)
            demoJob = viewModelScope.launch {
                delay(2500)
                while (isActive && nav?.status != NavStatus.ARRIVED) {
                    onFix(driver.next(1.0))
                    delay(1000)
                }
            }
        } else {
            gps.start { onFix(it) }
            watchdog = viewModelScope.launch {
                while (isActive) {
                    delay(2000)
                    gpsLost = System.currentTimeMillis() - lastFixAt > 6000
                }
            }
        }
    }

    private fun onFix(f: Fix) {
        lastFixAt = System.currentTimeMillis()
        gpsLost = false
        val n = navigator ?: return
        nav = n.onFix(f)
        if (navManeuvers !== n.maneuvers) navManeuvers = n.maneuvers
    }

    fun toggleMute() {
        muted = !muted
        speaker.muted = muted
        if (muted) speaker.stop()
    }

    fun repeatLast() {
        val s = nav ?: return
        val m = s.next ?: return
        speaker.say(if (m.kind == Kind.ARRIVE) "You will reach your destination in ${Phrases.distance(s.distToNext)}."
            else "In ${Phrases.distance(s.distToNext)}, ${Phrases.action(m)}.", true)
    }

    fun stopNavigation(goHome: Boolean = true) {
        demoJob?.cancel(); demoJob = null
        watchdog?.cancel(); watchdog = null
        gps.stop()
        speaker.stop()
        navigator = null
        nav = null
        gpsLost = false
        if (goHome) loaded?.let { screen = Screen.Pack(it.pack.meta.id) }
    }

    override fun onCleared() {
        stopNavigation(goHome = false)
        speaker.shutdown()
    }
}
