package com.shivansh.raasta.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class Edge(val from: Int, val to: Int, val way: Int, val length: Double, val time: Double, val bearing: Double)

/** Directed road graph of one route pack. Small (a corridor, not a city), so plain arrays and linear scans are fine. */
class RoadGraph(val pack: RoutePack) {
    val lat = pack.lat
    val lon = pack.lon
    val ways = pack.ways
    val edges: List<Edge>
    val out: Array<IntArray>

    init {
        val list = ArrayList<Edge>()
        val outL = Array(lat.size) { ArrayList<Int>(3) }
        fun add(a: Int, b: Int, w: Int) {
            val len = Geo.dist(lat[a], lon[a], lat[b], lon[b])
            outL[a].add(list.size)
            list.add(Edge(a, b, w, len, len / ways[w].speedMps(), Geo.bearing(lat[a], lon[a], lat[b], lon[b])))
        }
        ways.forEachIndexed { wi, w ->
            for (k in 0 until w.nodes.size - 1) {
                val a = w.nodes[k]
                val b = w.nodes[k + 1]
                if (a == b) continue
                if (w.oneway >= 0) add(a, b, wi)
                if (w.oneway <= 0) add(b, a, wi)
            }
        }
        edges = list
        out = Array(lat.size) { outL[it].toIntArray() }
    }

    fun way(e: Int) = ways[edges[e].way]

    fun edgeBetween(a: Int, b: Int): Int {
        var best = -1
        for (e in out[a]) if (edges[e].to == b && (best < 0 || edges[e].time < edges[best].time)) best = e
        return best
    }

    /** True if e2 just drives back along e1 (same two nodes, opposite direction). */
    fun isReverse(e1: Int, e2: Int) = edges[e1].from == edges[e2].to && edges[e1].to == edges[e2].from

    /** Ways you can actually take after driving along [e] (no turning back on the same road). */
    fun choices(e: Int): List<Int> = out[edges[e].to].filter { !isReverse(e, it) }

    private fun turnPenalty(e1: Int, e2: Int): Double {
        if (isReverse(e1, e2)) return 90.0
        val t = Geo.turn(edges[e1].bearing, edges[e2].bearing)
        val a = abs(t)
        return when {
            a < 30 -> 0.0
            a > 150 -> 40.0
            // India drives on the left: right turns cross oncoming traffic.
            t > 0 -> if (a > 100) 14.0 else 9.0
            else -> if (a > 100) 7.0 else 3.0
        }
    }

    /**
     * Edge-based A* (so turn costs and "no instant U-turn" work).
     * [starts] = (edge, cost already spent to reach the end of that edge). Returns the edge list, or null.
     */
    fun route(starts: List<Pair<Int, Double>>, goal: Int, maxTime: Double = 4 * 3600.0): IntArray? {
        class Item(val e: Int, val f: Double)
        val g = HashMap<Int, Double>()
        val parent = HashMap<Int, Int>()
        val closed = HashSet<Int>()
        val pq = PriorityQueue<Item>(compareBy { it.f })
        val gl = lat[goal]
        val gn = lon[goal]
        fun h(e: Int) = Geo.dist(lat[edges[e].to], lon[edges[e].to], gl, gn) / MAX_SPEED
        for ((e, c) in starts) {
            if (c < (g[e] ?: Double.POSITIVE_INFINITY)) { g[e] = c; pq.add(Item(e, c + h(e))) }
        }
        while (pq.isNotEmpty()) {
            val e = pq.poll().e
            if (!closed.add(e)) continue
            val ge = g.getValue(e)
            if (edges[e].to == goal) {
                val path = ArrayList<Int>()
                var cur: Int? = e
                while (cur != null) { path.add(cur); cur = parent[cur] }
                path.reverse()
                return path.toIntArray()
            }
            if (ge > maxTime) break
            for (e2 in out[edges[e].to]) {
                if (e2 in closed) continue
                val c = ge + edges[e2].time + turnPenalty(e, e2)
                if (c < (g[e2] ?: Double.POSITIVE_INFINITY)) {
                    g[e2] = c; parent[e2] = e; pq.add(Item(e2, c + h(e2)))
                }
            }
        }
        return null
    }

    /** Route from node [a] to node [b]. */
    fun routeNodes(a: Int, b: Int, maxTime: Double = 4 * 3600.0): IntArray? {
        if (a == b) return IntArray(0)
        return route(out[a].map { it to edges[it].time }, b, maxTime)
    }

    class Snap(val edge: Int, val t: Double, val dist: Double, val lat: Double, val lon: Double)

    /** Closest drivable edge to a GPS fix, preferring edges that point the way the car is moving. */
    fun snap(plat: Double, plon: Double, heading: Double?, maxDist: Double): Snap? {
        val dLat = maxDist / 111_000.0
        val dLon = dLat / max(0.2, cos(Math.toRadians(plat)))
        var best: Snap? = null
        var bestScore = Double.POSITIVE_INFINITY
        for ((i, e) in edges.withIndex()) {
            val alat = lat[e.from]; val alon = lon[e.from]; val blat = lat[e.to]; val blon = lon[e.to]
            if (plat < min(alat, blat) - dLat || plat > max(alat, blat) + dLat) continue
            if (plon < min(alon, blon) - dLon || plon > max(alon, blon) + dLon) continue
            val p = Geo.project(plat, plon, alat, alon, blat, blon)
            if (p.dist > maxDist) continue
            var score = p.dist
            if (heading != null) {
                val a = Geo.absTurn(heading, e.bearing)
                score += if (a > 100) 1000.0 else a * 0.25
            }
            if (score < bestScore) { bestScore = score; best = Snap(i, p.t, p.dist, p.lat, p.lon) }
        }
        return best?.takeIf { bestScore < 1000 }
    }

    fun nearestNode(plat: Double, plon: Double): Int {
        var best = 0
        var bd = Double.POSITIVE_INFINITY
        for (i in lat.indices) {
            if (out[i].isEmpty()) continue
            val d = Geo.dist(plat, plon, lat[i], lon[i])
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    /** The [k] nearest nodes that have roads leaving them, closest first. */
    fun nearestNodes(plat: Double, plon: Double, k: Int): List<Int> =
        lat.indices.asSequence().filter { out[it].isNotEmpty() }
            .map { it to Geo.dist(plat, plon, lat[it], lon[it]) }
            .sortedBy { it.second }.take(k).map { it.first }.toList()

    companion object { const val MAX_SPEED = 75 / 3.6 }
}

/** A drivable path: a chain of graph edges with cumulative distances. */
class Path(val graph: RoadGraph, val edges: IntArray) {
    init { require(edges.isNotEmpty()) { "Empty path" } }

    val nodes = IntArray(edges.size + 1).also {
        it[0] = graph.edges[edges[0]].from
        for (i in edges.indices) it[i + 1] = graph.edges[edges[i]].to
    }

    /** cum[i] = metres from the start of the path to node i. */
    val cum = DoubleArray(nodes.size).also {
        for (i in edges.indices) it[i + 1] = it[i] + graph.edges[edges[i]].length
    }

    val length get() = cum.last()
    val time: Double get() = edges.sumOf { graph.edges[it].time }

    fun lat(i: Int) = graph.lat[nodes[i]]
    fun lon(i: Int) = graph.lon[nodes[i]]
    fun edge(i: Int) = graph.edges[edges[i]]
    fun way(i: Int) = graph.ways[graph.edges[edges[i]].way]

    /** Index of the edge that contains distance [d]. */
    fun segmentAt(d: Double): Int {
        if (d <= 0) return 0
        var lo = 0
        var hi = edges.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= d) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun pointAt(d: Double): LatLon {
        val dd = d.coerceIn(0.0, length)
        val s = segmentAt(dd)
        val len = cum[s + 1] - cum[s]
        val t = if (len <= 0) 0.0 else (dd - cum[s]) / len
        return LatLon(lat(s) + (lat(s + 1) - lat(s)) * t, lon(s) + (lon(s + 1) - lon(s)) * t)
    }

    fun bearingAt(d: Double): Double = edge(segmentAt(d.coerceIn(0.0, length))).bearing

    /** Driving time from distance [d] to the end. */
    fun timeFrom(d: Double): Double {
        val s = segmentAt(d)
        val e = edge(s)
        val frac = if (e.length <= 0) 0.0 else ((cum[s + 1] - d) / e.length).coerceIn(0.0, 1.0)
        var t = e.time * frac
        for (i in s + 1 until edges.size) t += edge(i).time
        return t
    }

    companion object {
        /** Build from a node sequence; gaps (missing direct edges) are filled with short A* hops. */
        fun fromNodes(graph: RoadGraph, nodePath: IntArray): Path? {
            if (nodePath.isEmpty()) return null
            val out = ArrayList<Int>()
            var cur = nodePath[0]
            var fails = 0
            for (i in 1 until nodePath.size) {
                val b = nodePath[i]
                if (b == cur) continue
                val e = graph.edgeBetween(cur, b)
                if (e >= 0) { out.add(e); cur = b; fails = 0; continue }
                // Not directly connected (data mismatch): bridge the gap, or skip this node and try the next.
                // Only short, sensible hops: a long one means this node is bad data, not a gap.
                val straight = Geo.dist(graph.lat[cur], graph.lon[cur], graph.lat[b], graph.lon[b])
                var hop = graph.routeNodes(cur, b, maxTime = 20.0 + straight / 4.0)
                if (hop == null && fails >= 4 && fails % 3 == 1) {
                    // Stuck for a while (the source route used a road we don't keep, e.g. a private lane):
                    // allow a real detour around it and rejoin the route further on.
                    hop = graph.routeNodes(cur, b, maxTime = 120.0 + straight / 3.0)
                }
                if (hop == null) {
                    fails++
                    // Stuck right at the start (e.g. the route begins on a driveway stub we don't have):
                    // drop the little bit built so far and start from here instead.
                    if (out.sumOf { graph.edges[it].length } < 150.0) { out.clear(); cur = b }
                    continue
                }
                hop.forEach { out.add(it) }
                cur = b
                fails = 0
            }
            return if (out.isEmpty()) null else Path(graph, out.toIntArray())
        }
    }
}
