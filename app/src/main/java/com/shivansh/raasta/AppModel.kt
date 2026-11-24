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
class Picked(val place: Place, val exact: Boolean, /** A bare spot on the map, nothing known there. */ val bare: Boolean = false, val note: String? = null, /** We only know roughly where: a tap on the map sets it. */ val fix: Boolean = false)

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

    // ---- places ----
    /** Every place in Delhi, offline. Null for the first moment after launch. */
    var places by mutableStateOf<PlaceDb?>(null)
        private set
    var myPlaces by mutableStateOf(saved.mine())
        private set

    /** Where the "add a place" sheet is adding, while it's open. */
    var adding by mutableStateOf<LatLon?>(null)

    /** What we know about something tapped on the map. */
    fun known(p: Poi): Place {
        myPlaces.firstOrNull { it.name == p.name && Geo.dist(it.lat, it.lon, p.lat, p.lon) < 5 }?.let { return it }
        val db = places ?: return p.asPlace()
        var best = -1
        db.inBox(p.lat - 2e-5, p.lon - 2e-5, p.lat + 2e-5, p.lon + 2e-5, 9) { if (best < 0 && db.name(it) == p.name) best = it }
        return if (best >= 0) db.place(best) else p.asPlace()
    }

    fun isMine(p: Place) = myPlaces.any { it.name == p.name && Geo.dist(it.latLon, p.latLon) < 5 }

    fun addPlace(name: String, kind: PlaceKind, at: LatLon) {
        val p = Place(name.trim().take(60), city?.map?.areaNear(at.lat, at.lon).orEmpty(), at.lat, at.lon, kind, "Added by you")
        myPlaces = listOf(p) + myPlaces
        saved.setMine(myPlaces)
        picked = Picked(p, true)
    }

    fun removePlace(p: Place) {
        myPlaces = myPlaces.filterNot { it.name == p.name && Geo.dist(it.latLon, p.latLon) < 5 }
        saved.setMine(myPlaces)
        picked = null
    }

    /** Where a place without an address is, in words. */
    fun whereabouts(p: Place): String = p.detail.ifBlank { if (p.kind == PlaceKind.AREA) "" else city?.map?.areaNear(p.lat, p.lon).orEmpty() }

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
        viewModelScope.launch { places = withContext(Dispatchers.IO) { PlaceFile.open(app) } }
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
        picked = Picked(Place("Dropped pin", rough, p.lat, p.lon), false, bare = true)
        pickJob = viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { geocoder.reverse(p.lat, p.lon) }.getOrNull() } ?: return@launch
            picked = Picked(Place(r.name, r.detail.ifBlank { rough }, p.lat, p.lon), true, bare = true)
        }
    }

    /** A link is being read. */
    var reading by mutableStateOf(false)
        private set

    /** "It's here": moves the picked place to this spot and remembers it. */
    fun placeHere(at: LatLon) {
        val p = picked?.place ?: return
        if (!Delhi.contains(at)) { error = "That's outside Delhi. This app only knows Delhi's roads."; return }
        val moved = p.copy(lat = at.lat, lon = at.lon, label = if (p.label == "Added by you") p.label else "Set by you")
        myPlaces = listOf(moved) + myPlaces.filterNot { it.name == p.name && Geo.dist(it.latLon, p.latLon) < 5 }
        saved.setMine(myPlaces)
        picked = Picked(moved, true)
    }

    fun movePin() { picked = picked?.let { Picked(it.place, it.exact, note = it.note, fix = true) } }

    /** Something shared from Google Maps, WhatsApp or pasted: a link, a plus code, coordinates. */
    fun openShared(text: String) {
        // A plus code or coordinates pasted while we're asking where a shared place is: that's the answer.
        val unsure = picked?.takeIf { it.fix }
        if (unsure != null) {
            val near = unsure.place.latLon
            val point = if (text.startsWith("http")) null else MapLinks.read(text, near) { null }?.point
            if (point != null) { placeHere(point); return }
        }
        if (navigator != null) { speaker.say("Finish this ride first, then share the place again.", false); return }
        plan()
        if (!MapLinks.looksLikeOne(text)) {
            // Plain words: search for them.
            openSearch = true
            pendingQuery = text.lines().first().take(80)
            return
        }
        pickJob?.cancel()
        reading = true
        pickJob = viewModelScope.launch {
            try {
                // Right after a cold start the place list may still be opening.
                var waited = 0
                while (places == null && waited < 3000) { delay(100); waited += 100 }
                val near = here?.takeIf { inDelhi }?.latLon ?: LatLon(28.6315, 77.2167)
                val found = withContext(Dispatchers.IO) {
                    val link = MapLinks.read(text, near) { runCatching { http.redirect(it) }.getOrNull() } ?: return@withContext null
                    val l = LinkLocator.locate(link, places) { q -> geocoder.search(q) }
                    // A bare point: say what's there.
                    if (l != null && link.name == null) runCatching { geocoder.reverse(l.place.lat, l.place.lon) }.getOrNull()
                        ?.let { return@withContext Located(it.copy(label = "From your map link"), l.trust) }
                    l
                }
                if (found == null) {
                    error = "Couldn't read that link. It needs internet once; or paste the place's plus code or coordinates into search."
                } else if (!Delhi.contains(found.place.latLon)) {
                    error = "“${found.place.name}” is outside Delhi, where this map ends."
                } else {
                    val p = found.place
                    val known = places?.let { db -> var k = false; db.inBox(p.lat - 2e-5, p.lon - 2e-5, p.lat + 2e-5, p.lon + 2e-5, 9) { if (db.name(it) == p.name) k = true }; k } ?: false
                    // Yours from now on, findable offline.
                    if (!known && found.trust != Trust.ROUGH && p.name != "Shared place" && !isMine(p)) {
                        myPlaces = listOf(p) + myPlaces
                        saved.setMine(myPlaces)
                    }
                    picked = Picked(p, found.trust == Trust.EXACT, fix = found.trust == Trust.ROUGH, note = when (found.trust) {
                        Trust.EXACT -> null
                        Trust.LIKELY -> "From the link. Looks right for the address, but check the pin."
                        Trust.ROUGH -> "Google's link doesn't give the exact spot. This is about where the address is: tap the right place on the map, or paste its plus code."
                    })
                }
            } finally {
                reading = false
            }
        }
    }

    /** Words to search for as soon as the search panel opens. */
    var pendingQuery by mutableStateOf<String?>(null)

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
                } else places?.let { db ->
                    withContext(Dispatchers.Default) {
                        db.near(around.lat, around.lon, 40, 5000.0) { db.isKind(it, kinds) && db.rank(it) <= 4 && db.confidence(it) > 0.4 }
                            .map { (i, d) -> Poi(db.name(i), db.kind(i), db.lat(i), db.lon(i), 0) to d }
                            .distinctBy { it.first.name.lowercase() to (it.second / 150).toInt() }.take(20)
                    }
                } ?: tiles.nearby(around.lat, around.lon, kinds)
                if (nearby.isNullOrEmpty()) { nearby = null; error = "Nothing like that on the map within 5 km." }
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

    private var suggestJob: Job? = null

    private fun myMatches(q: String): List<Place> {
        val t = PlaceText.tokens(q)
        fun fits(p: Place) = PlaceText.tokens(p.name).let { w -> t.all { x -> w.any { it.startsWith(x) } } }
        return listOfNotNull(home?.copy(name = "Home"), work?.copy(name = "Work")).filter(::fits) + myPlaces.filter(::fits) + recents.filter(::fits).take(3)
    }

    private fun offline(q: String, limit: Int, typing: Boolean): List<Place> {
        val found = places?.search(q, here?.takeIf { inDelhi }?.latLon, limit, typing)?.map { it.place } ?: city?.map?.suggest(q).orEmpty()
        return (myMatches(q) + found).distinctBy { it.name.lowercase() to (it.lat * 700).toInt() }
    }

    /** Suggestions as you type, all from the phone. */
    fun suggest(query: String) {
        suggestJob?.cancel()
        searchedOnline = false
        error = null
        if (query.isBlank() || MapLinks.looksLikeOne(query)) { results = emptyList(); return }
        suggestJob = viewModelScope.launch {
            delay(40)
            val r = withContext(Dispatchers.Default) { offline(query, 16, true) }
            results = r
        }
    }

    var searchedOnline by mutableStateOf(false)
        private set

    /** Everything on the phone plus the two online search services, best first. */
    fun search(query: String) {
        if (query.isBlank()) return
        if (MapLinks.looksLikeOne(query)) { openShared(query); return }
        suggestJob?.cancel()
        searching = true
        error = null
        viewModelScope.launch {
            val local = withContext(Dispatchers.Default) { offline(query, 20, false) }
            results = local
            try {
                val found = withContext(Dispatchers.IO) { geocoder.search(query.trim()) }
                val extra = found.filter { f -> local.none { Geo.dist(it.latLon, f.latLon) < 120 && PlaceText.tokens(it.name).firstOrNull() == PlaceText.tokens(f.name).firstOrNull() } }
                results = local + extra
                if (results.isEmpty()) error = "Nothing found for “${query.trim()}”. Find the spot on the map, tap it, and add it yourself."
            } catch (e: Exception) {
                if (local.isEmpty()) error = "Not in the offline list, and there's no internet to look further. You can still pick the spot on the map."
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
