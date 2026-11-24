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
class PackItem(val file: File, val meta: PackMeta, val preview: LoadedPack? = null)

/** Where the phone is, right now. */
class Here(val fix: Fix, val at: Long) {
    val latLon get() = LatLon(fix.lat, fix.lon)
    /** Good enough to start a ride from without checking. */
    val sharp get() = fix.accuracy <= 60
}

enum class LocState { NO_PERMISSION, SWITCHED_OFF, SEARCHING, OK }

/** A point chosen on the map, with what's known about it. */
class Picked(val place: Place, val exact: Boolean)

/** A loaded pack plus its graph and the guidance for each route. */
class LoadedPack(val file: File, val pack: RoutePack) {
    val graph = RoadGraph(pack)
    val paths: List<Path> = pack.routes.mapNotNull { Path.fromNodes(graph, it.nodePath) }
    val guidance: List<List<Maneuver>> = paths.map { Guidance.build(it, pack.meta.toName) }
    /** Map geometry, built here on a background thread so opening a ride never stalls the UI. */
    val cells = com.shivansh.raasta.ui.CellSet.ofPack(pack)
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val http = Http()
    private val store = PackStore(File(app.filesDir, "packs"))
    private val geocoder = Geocoder(http)
    private val roadTiles = RoadTiles(http, Config.TILE_BASES, File(app.filesDir, "tiles"))
    private val builder = PackBuilder(http, roadTiles)
    val tiles = LiveTiles(roadTiles, viewModelScope)
    val saved = SavedPlaces(app)
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

    // ---- the map ----
    var city by mutableStateOf<com.shivansh.raasta.ui.CityScene?>(null)
        private set
    var layersOn by mutableStateOf(com.shivansh.raasta.ui.MapLayersOn())

    // ---- where you are ----
    var here by mutableStateOf<Here?>(null)
        private set
    /** Exact address when known (online, plan screen only), else worked out offline from the map. */
    var herePlace by mutableStateOf<Place?>(null)
        private set
    var loc by mutableStateOf(LocState.NO_PERMISSION)
        private set
    private var liveOn = false
    private var addressOf: LatLon? = null
    private var addressAt = 0L
    private var addressJob: Job? = null

    // ---- saved places ----
    var home by mutableStateOf(saved.get("home"))
        private set
    var work by mutableStateOf(saved.get("work"))
        private set
    var recents by mutableStateOf(saved.recents())
        private set

    init {
        refresh()
        viewModelScope.launch {
            city = withContext(Dispatchers.Default) {
                runCatching { com.shivansh.raasta.ui.CityScene(app.assets.open("city.map").use { CityMap.read(it) }) }.getOrNull()
            }
            here?.let { describeHere(it) }
        }
    }

    /** Live location while the app is on screen. */
    fun startLive() {
        if (navigator != null) return
        liveOn = true
        loc = when {
            !gps.permitted -> LocState.NO_PERMISSION
            !gps.switchedOn -> LocState.SWITCHED_OFF
            here != null -> LocState.OK
            else -> LocState.SEARCHING
        }
        if (gps.permitted) gps.startLive { f ->
            here = Here(f, f.time)
            loc = LocState.OK
            describeHere(here!!)
        }
    }

    fun stopLive() {
        liveOn = false
        if (navigator == null) gps.stop()
    }

    /** Rough location only: the phone was asked for approximate. */
    val approximateOnly get() = gps.permitted && !gps.fine
    val inDelhi get() = here?.let { Delhi.contains(it.fix.lat, it.fix.lon) } ?: true

    private fun describeHere(h: Here) {
        val old = addressOf
        val moved = old == null || Geo.dist(old, h.latLon) > 40
        if (moved || herePlace == null) {
            val c = city?.map
            herePlace = Place(c?.describe(h.fix.lat, h.fix.lon) ?: "%.5f, %.5f".format(java.util.Locale.ROOT, h.fix.lat, h.fix.lon), "", h.fix.lat, h.fix.lon)
            if (c != null) addressOf = h.latLon
        }
        // The exact address needs the internet, so only ask while planning a ride, and only when it's worth asking.
        val now = System.currentTimeMillis()
        if (screen == Screen.NewRoute && (moved || herePlace?.detail.isNullOrEmpty()) && h.fix.accuracy < 150 && now - addressAt > 6_000 &&
            addressJob?.isActive != true && Delhi.contains(h.fix.lat, h.fix.lon)
        ) {
            addressAt = now
            addressJob = viewModelScope.launch {
                val p = withContext(Dispatchers.IO) { runCatching { geocoder.reverse(h.fix.lat, h.fix.lon) }.getOrNull() } ?: return@launch
                val metro = city?.map?.nearestStation(h.fix.lat, h.fix.lon)?.takeIf { it.second < 1500 }
                    ?.let { (s, d) -> if (d < 80) "at ${s.name} metro" else "${Phrases.shortDistance(d)} from ${s.name} metro" }
                herePlace = Place(p.name, listOf(p.detail, metro.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), h.fix.lat, h.fix.lon)
                addressOf = h.latLon
            }
        }
    }

    // ---- choosing points on the map ----
    var picked by mutableStateOf<Picked?>(null)
    private var pickJob: Job? = null

    fun pick(place: Place) { pickJob?.cancel(); picked = Picked(place, true) }

    /** A bare point on the map: name it offline at once, then with its address if we're online. */
    fun pickPoint(p: LatLon) {
        pickJob?.cancel()
        if (!Delhi.contains(p)) { error = "That's outside Delhi. This app only knows Delhi's roads."; return }
        val rough = city?.map?.describe(p.lat, p.lon) ?: "Point on map"
        picked = Picked(Place("Dropped pin", rough, p.lat, p.lon), false)
        pickJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { geocoder.reverse(p.lat, p.lon) }.getOrNull() } ?: return@launch
            picked = Picked(Place(r.name, r.detail.ifBlank { rough }, p.lat, p.lon), true)
        }
    }

    var nearby by mutableStateOf<List<Pair<Poi, Double>>?>(null)
    var nearbyBusy by mutableStateOf(false)
        private set
    private var nearbyJob: Job? = null

    fun findNearby(kinds: Set<PlaceKind>, around: LatLon) {
        nearbyJob?.cancel()
        nearbyBusy = true
        error = null
        nearbyJob = viewModelScope.launch {
            try {
                nearby = if (kinds == setOf(PlaceKind.METRO)) {
                    city?.map?.stations.orEmpty().map { Poi(it.name, PlaceKind.METRO, it.lat, it.lon, 0) to Geo.dist(around.lat, around.lon, it.lat, it.lon) }
                        .sortedBy { it.second }.take(10)
                } else tiles.nearby(around.lat, around.lon, kinds)
                if (nearby.isNullOrEmpty()) { nearby = null; error = "Nothing like that on the map within 2 km." }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                nearby = null
                error = "Needs internet the first time you look around a new area."
            } finally {
                nearbyBusy = false
            }
        }
    }

    fun closeNearby() { nearbyJob?.cancel(); nearby = null; nearbyBusy = false }

    fun saveHome(p: Place?) { saved.set("home", p); home = p }
    fun saveWork(p: Place?) { saved.set("work", p); work = p }
    private fun remember(p: Place) { saved.addRecent(p); recents = saved.recents() }

    fun swap() {
        val f = fromPlace ?: here?.let { hereAsPlace() }
        val t = toPlace
        fromPlace = t; fromHere = false
        toPlace = f
    }

    /** The start follows you until you choose another one. */
    var fromHere by mutableStateOf(true)

    fun hereAsPlace(): Place? = here?.let { h ->
        Place(herePlace?.name?.substringBefore(", ") ?: "My location", herePlace?.detail.orEmpty(), h.fix.lat, h.fix.lon)
    }

    fun setFrom(p: Place?) { fromHere = p == null; fromPlace = p; picked = null; closeNearby(); error = null }
    fun setTo(p: Place?) { toPlace = p; picked = null; closeNearby(); error = null }

    /** Open the planner with the search panel up. */
    var openSearch = false

    /** Open the planner, optionally already going somewhere. */
    fun plan(to: Place? = null, from: Place? = null) {
        error = null; picked = null; closeNearby(); clearResults()
        fromPlace = from; fromHere = from == null
        toPlace = to
        screen = Screen.NewRoute
        here?.let { describeHere(it) }
    }

    fun refresh() {
        viewModelScope.launch {
            // Show the list at once, then fill in the maps.
            val list = withContext(Dispatchers.IO) { store.list() }
            packs = list.map { (f, m) -> PackItem(f, m, packs.firstOrNull { it.meta.id == m.id }?.preview) }
            packs = withContext(Dispatchers.Default) {
                list.map { (f, m) ->
                    // Fully load each ride in the background: the home cards draw real mini maps, and opening is instant.
                    val lp = runCatching { LoadedPack(f, PackIO.read(f)) }.getOrNull()
                    PackItem(f, m, lp)
                }
            }
        }
    }

    /** Instant suggestions as you type, all from the phone. */
    fun suggest(query: String) {
        results = if (query.isBlank()) emptyList() else {
            val q = query.trim().lowercase()
            val mine = listOfNotNull(home?.copy(name = "Home"), work?.copy(name = "Work")).filter { it.name.lowercase().startsWith(q) } +
                recents.filter { p -> p.name.lowercase().split(' ').any { it.startsWith(q) } }.take(3)
            (mine + city?.map?.suggest(query).orEmpty()).distinctBy { it.name.lowercase() to (it.lat * 500).toInt() }.take(8)
        }
        searchedOnline = false
        error = null
    }

    var searchedOnline by mutableStateOf(false)
        private set

    fun search(query: String) {
        if (query.isBlank()) return
        searching = true
        error = null
        val local = city?.map?.suggest(query, 3).orEmpty()
        viewModelScope.launch {
            try {
                val found = withContext(Dispatchers.IO) { geocoder.search(query.trim()) }
                // Keep offline matches the online search missed (same spot = same place).
                val extra = local.filter { l -> found.none { Geo.dist(it.latLon, l.latLon) < 250 && it.kind == l.kind } }
                results = found + extra
                if (results.isEmpty()) error = "Nothing found in Delhi for “${query.trim()}”. Try a nearby metro station, or hold on the map."
            } catch (e: Exception) {
                results = local
                error = if (local.isEmpty()) "Searching needs internet. You can still pick a spot on the map." else "Offline: showing metro stations and areas only."
            } finally {
                searching = false
                searchedOnline = true
            }
        }
    }

    fun clearResults() { results = emptyList(); searchedOnline = false }

    fun download() {
        val from = if (fromHere) hereAsPlace() else fromPlace
        val to = toPlace ?: return
        if (from == null) { error = "Still finding you. Wait a moment, or choose a start point."; return }
        if (!Delhi.contains(from.latLon)) { error = "You're outside Delhi right now. Choose a start point in Delhi."; return }
        if (Geo.dist(from.latLon, to.latLon) < 120) { error = "Start and destination are the same place."; return }
        error = null
        busy = "Finding routes…"
        busyStep = 0
        remember(to)
        if (!fromHere) remember(from)
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
                fromPlace = null; toPlace = null; fromHere = true; results = emptyList()
                openPack(file.nameWithoutExtension)
            } catch (e: Exception) {
                error = if (e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException)
                    "No internet right now. Downloading a ride needs it; riding doesn't."
                else if (e.message.orEmpty().startsWith("Routing server is unreachable")) "Can't reach the route service. Check your internet and try again; riding saved rides still works." else e.message ?: "Download failed."
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
            NavService.onEnd = { stopNavigation() }
            NavService.start(getApplication(), lp.pack.meta.toName)
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
        here = Here(f, f.time)
        val st = n.onFix(f)
        nav = st
        if (!demo) NavService.update(getApplication(), lastLine(st))
        if (navManeuvers !== n.maneuvers) navManeuvers = n.maneuvers
    }

    private fun lastLine(s: NavState): String = when {
        s.status == NavStatus.ARRIVED -> "Arrived"
        s.next == null -> "Riding"
        else -> "${Phrases.shortDistance(s.distToNext)} · ${Phrases.banner(s.next!!)}"
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
        NavService.stop(getApplication())
        speaker.stop()
        navigator = null
        nav = null
        gpsLost = false
        if (demo) here = null // the demo moved "you" along the route
        demo = false
        if (liveOn && goHome) startLive()
        if (goHome) loaded?.let { screen = Screen.Pack(it.pack.meta.id) }
    }

    override fun onCleared() {
        stopNavigation(goHome = false)
        speaker.shutdown()
    }
}
