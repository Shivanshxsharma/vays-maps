package com.shivansh.raasta.core

import java.io.IOException
import kotlin.math.cos
import kotlin.math.floor

/**
 * Builds a route pack while online: the planned routes plus a ~250 m corridor of roads around them,
 * so the trip (including small detours and re-routes) works with no internet.
 *
 * Road data comes from the Delhi tiles on GitHub ([tiles]); Overpass is only a fallback.
 * Routes come from OSRM; if OSRM is down we route ourselves on a wider block of tiles.
 */
class PackBuilder(http: Http, private val tiles: RoadTiles?, private val useOverpassFallback: Boolean = true) {
    private val osrm = Osrm(http)
    private val overpass = Overpass(http)

    class OutsideDelhi : IOException("Vays Maps only covers Delhi for now. Pick a start and destination inside Delhi.")

    /** Candidate routes to show before downloading. Null means the routing server is unreachable (we route offline later). */
    fun findRoutes(from: LatLon, to: LatLon): List<OsrmRoute>? {
        if (!Delhi.contains(from) || !Delhi.contains(to)) throw OutsideDelhi()
        return try { osrm.routes(from, to) } catch (e: IOException) { null }
    }

    fun build(
        from: Place,
        to: Place,
        routes: List<OsrmRoute>?,
        corridorM: Double = 250.0,
        progress: (String) -> Unit = {},
    ): RoutePack {
        if (!Delhi.contains(from.latLon) || !Delhi.contains(to.latLon)) throw OutsideDelhi()
        if (routes.isNullOrEmpty()) return buildWithoutServer(from, to, progress)

        val polylines = routes.map { it.coords }
        val data = fetchCorridor(polylines, corridorM, progress)
        progress("Building the road map…")
        val base = toPack(data, from, to, keepNear = polylines, radius = corridorM)
        val graph = RoadGraph(base)

        progress("Working out flyovers and turns…")
        val planned = ArrayList<PlannedRoute>()
        var mainGuidance: List<Maneuver> = emptyList()
        for ((k, r) in routes.withIndex()) {
            var path = mapRoute(graph, r)
            if (path == null || path.length < 0.6 * r.distance) {
                // Couldn't follow the server's line (it may use roads we leave out, like private lanes):
                // plan this one ourselves inside the downloaded corridor.
                path = routeInCorridor(graph, r) ?: continue
                if (path.length < 0.6 * r.distance) continue
            }
            val ms = Guidance.build(path, to.name)
            if (planned.isEmpty()) mainGuidance = ms
            planned.add(PlannedRoute(path.nodes, r.distance, r.duration, label(path, k)))
        }
        if (planned.isEmpty()) throw IOException("Could not line the route up with the map data. Try again.")
        return finish(base, from, to, planned, mainGuidance)
    }

    private fun fetchCorridor(polylines: List<List<LatLon>>, radius: Double, progress: (String) -> Unit): OsmData {
        if (tiles != null) {
            try {
                val keys = RoadTiles.along(polylines, radius)
                progress("Downloading Delhi road tiles (0/${keys.size})…")
                return tiles.load(keys) { d, t -> progress("Downloading Delhi road tiles ($d/$t)…") }
            } catch (e: IOException) {
                if (!useOverpassFallback) throw IOException("Couldn't download road tiles: ${e.message}", e)
            }
        }
        progress("Downloading roads from OpenStreetMap…")
        val thin = polylines.map { thin(Geo.simplify(it, 12.0), 150) }
        return OsmData.fromOverpass(overpass.query(Overpass.corridorQuery(thin, radius.toInt())))
    }

    /** No routing server: download a block of tiles around the trip and route on the phone. */
    private fun buildWithoutServer(from: Place, to: Place, progress: (String) -> Unit): RoutePack {
        val t = tiles ?: throw IOException("Routing server is unreachable. Check your internet and try again.")
        val margin = 1200.0 + 0.2 * Geo.dist(from.latLon, to.latLon)
        val keys = RoadTiles.around(from.latLon, to.latLon, margin)
        if (keys.size > 220) throw IOException("Routing server is unreachable and this trip is too long to plan offline. Try again later.")
        progress("Routing server is down; planning on the phone. Downloading ${keys.size} tiles…")
        val data = t.load(keys) { d, n -> progress("Downloading Delhi road tiles ($d/$n)…") }
        val base = toPack(data, from, to, keepNear = null, radius = 0.0)
        val g = RoadGraph(base)
        val a = g.nearestNode(from.lat, from.lon)
        val b = g.nearestNode(to.lat, to.lon)
        val edges = g.routeNodes(a, b) ?: throw IOException("Couldn't find a driving route between these places.")
        if (edges.isEmpty()) throw IOException("Start and destination are the same place.")
        val path = Path(g, edges)
        val ms = Guidance.build(path, to.name)
        val planned = listOf(PlannedRoute(path.nodes, path.length, path.time, label(path, 0)))
        return finish(base, from, to, planned, ms)
    }

    private fun finish(base: RoutePack, from: Place, to: Place, planned: List<PlannedRoute>, ms: List<Maneuver>): RoutePack {
        val meta = PackMeta(
            id = "r" + System.currentTimeMillis().toString(36),
            title = "${from.name} → ${to.name}",
            fromName = from.name, toName = to.name,
            createdAt = System.currentTimeMillis(),
            from = from.latLon, to = to.latLon,
            distance = planned[0].distance, duration = planned[0].duration,
            summary = Guidance.summary(ms),
            routeCount = planned.size,
        )
        return base.withRoutes(meta, planned)
    }

    /** Keep at most [max] points, evenly spread (Overpass dislikes huge "around" lines). */
    private fun thin(points: List<LatLon>, max: Int): List<LatLon> {
        if (points.size <= max) return points
        val step = points.size.toDouble() / max
        val out = ArrayList<LatLon>()
        var x = 0.0
        while (x < points.size) { out.add(points[x.toInt()]); x += step }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }

    private fun label(path: Path, index: Int): String {
        // "via <the named road we spend longest on>"
        val byName = HashMap<String, Double>()
        for (i in path.edges.indices) path.way(i).label?.let { byName[it] = (byName[it] ?: 0.0) + path.edge(i).length }
        val via = byName.maxByOrNull { it.value }?.key
        val head = if (index == 0) "Fastest" else "Alternative $index"
        return if (via != null) "$head · via $via" else head
    }

    fun mapRoute(graph: RoadGraph, r: OsrmRoute): Path? {
        // Match by position, not id: OSRM's full geometry is exactly the OSM node coordinates, while its id list
        // can be shifted by one and its JSON rounds ids above ~1e10.
        val grid = HashMap<Long, MutableList<Int>>()
        fun gk(la: Double, lo: Double) = floor(la / 0.0002).toLong() * 10_000_000L + floor(lo / 0.0002).toLong()
        for (i in graph.lat.indices) grid.getOrPut(gk(graph.lat[i], graph.lon[i])) { ArrayList(1) }.add(i)
        val seq = ArrayList<Int>()
        for (p in r.coords) {
            var best: Int? = null
            var bestScore = Double.MAX_VALUE
            for (a in -1..1) for (b in -1..1) {
                for (i in grid[gk(p.lat + a * 0.0002, p.lon + b * 0.0002)].orEmpty()) {
                    val d = Geo.dist(p.lat, p.lon, graph.lat[i], graph.lon[i])
                    if (d > 2.5) continue
                    // Two nodes on the same spot (e.g. a flyover over a road): prefer the one connected to the last.
                    val linked = seq.isNotEmpty() && graph.edgeBetween(seq.last(), i) >= 0
                    val score = d - if (linked) 10.0 else 0.0
                    if (score < bestScore) { bestScore = score; best = i }
                }
            }
            if (best != null && (seq.isEmpty() || seq.last() != best)) seq.add(best)
        }
        val coverage = if (r.coords.isEmpty()) 0.0 else seq.size.toDouble() / r.coords.size
        if (coverage >= 0.5) Path.fromNodes(graph, seq.toIntArray())?.let { return it }
        return routeInCorridor(graph, r)
    }

    /** Our own A* between the route's ends, inside the downloaded corridor. */
    private fun routeInCorridor(graph: RoadGraph, r: OsrmRoute): Path? {
        // The very nearest node can sit on a cut-off stub (a closed avenue, a gated lane), so try a few at each end.
        val starts = graph.nearestNodes(r.coords.first().lat, r.coords.first().lon, 6)
        val ends = graph.nearestNodes(r.coords.last().lat, r.coords.last().lon, 6)
        for (a in starts) for (b in ends) {
            val e = graph.routeNodes(a, b) ?: continue
            if (e.isNotEmpty()) return Path(graph, e)
        }
        return null
    }

    companion object {
        private val SKIP_SERVICE = setOf("parking_aisle", "driveway", "drive-through", "emergency_access", "alley")

        /** OSM data -> pack (without routes), optionally trimmed to ways near the given polylines. */
        fun toPack(data: OsmData, from: Place, to: Place, keepNear: List<List<LatLon>>?, radius: Double): RoutePack {
            val near: Set<Long>? = keepNear?.let { corridorCells(it, radius) }
            fun inCorridor(la: Double, lo: Double) = near == null || cell(la, lo) in near

            class RawWay(val id: Long, val nodes: LongArray, val tags: Map<String, String>)
            val rawWays = ArrayList<RawWay>()
            for ((id, pair) in data.ways) {
                val (nodes, t) = pair
                val hw = t["highway"] ?: continue
                if (RoadClass.of(hw) == null) continue
                if (hw == "service" && t["service"] in SKIP_SERVICE) continue
                val access = t["motor_vehicle"] ?: t["motorcar"] ?: t["access"]
                if (access == "no" || access == "private") continue
                if (t["area"] == "yes") continue
                if (nodes.size < 2) continue
                if (near != null && nodes.none { n -> data.lat[n]?.let { inCorridor(it, data.lon.getValue(n)) } == true }) continue
                rawWays.add(RawWay(id, nodes, t))
            }
            val index = LinkedHashMap<Long, Int>()
            for (w in rawWays) for (n in w.nodes) if (n in data.lat && n !in index) index[n] = index.size
            val ids = LongArray(index.size)
            val lat = DoubleArray(index.size)
            val lon = DoubleArray(index.size)
            val flags = ByteArray(index.size)
            for ((id, i) in index) {
                ids[i] = id; lat[i] = data.lat.getValue(id); lon[i] = data.lon.getValue(id)
                if (id in data.signals) flags[i] = RoutePack.FLAG_SIGNAL
            }
            val ways = rawWays.mapNotNull { w ->
                val t = w.tags
                val hw = t["highway"]!!
                val nodes = w.nodes.toList().mapNotNull { index[it] }.toIntArray()
                if (nodes.size < 2) return@mapNotNull null
                val roundabout = t["junction"] == "roundabout" || t["junction"] == "circular"
                val oneway = when (t["oneway"]) {
                    "yes", "true", "1" -> 1
                    "-1", "reverse" -> -1
                    "no", "false", "0" -> 0
                    else -> if (roundabout || hw == "motorway" || hw == "motorway_link") 1 else 0
                }
                PackWay(
                    osmId = w.id, nodes = nodes, cls = RoadClass.of(hw)!!, link = hw.endsWith("_link"),
                    oneway = oneway,
                    bridge = t["bridge"].let { it != null && it != "no" },
                    tunnel = t["tunnel"].let { it != null && it != "no" && it != "building_passage" },
                    layer = t["layer"]?.toIntOrNull()?.coerceIn(-5, 5) ?: 0,
                    roundabout = roundabout,
                    name = (t["name:en"] ?: t["name"])?.take(80),
                    ref = t["ref"]?.take(30),
                    destination = (t["destination:en"] ?: t["destination"])?.take(80),
                    lanes = t["lanes"]?.toIntOrNull()?.coerceIn(0, 12) ?: 0,
                    maxspeed = t["maxspeed"]?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(0, 200) ?: 0,
                )
            }
            val landmarks = data.landmarks.values.filter { inCorridor(it.lat, it.lon) }
            val meta = PackMeta("", "", from.name, to.name, 0, from.latLon, to.latLon, 0.0, 0.0, "", 0)
            return RoutePack(meta, ids, lat, lon, flags, ways, landmarks, emptyList())
        }

        private const val CELL = 0.0015 // ~165 m

        private fun cell(la: Double, lo: Double): Long = floor(la / CELL).toLong() * 1_000_000L + floor(lo / CELL).toLong()

        private fun corridorCells(polylines: List<List<LatLon>>, radius: Double): Set<Long> {
            val out = HashSet<Long>()
            val dLat = radius / 111_320.0
            for (pl in polylines) RoadTiles.forEachPoint(pl, 40.0) { la, lo ->
                val dLon = dLat / cos(Math.toRadians(la))
                val steps = (radius / 120.0).toInt().coerceAtLeast(1)
                for (a in -steps..steps) for (b in -steps..steps) {
                    out.add(cell(la + dLat * a / steps, lo + dLon * b / steps))
                }
            }
            return out
        }
    }
}
