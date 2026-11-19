package com.shivansh.raasta.core

import kotlin.math.abs

enum class Kind {
    TURN, FORK, UTURN, ROUNDABOUT, SLIP_ROAD, SERVICE_ROAD,
    FLYOVER_TAKE, FLYOVER_SKIP, FLYOVER_STAY, FLYOVER_EXIT,
    UNDERPASS_TAKE, UNDERPASS_SKIP,
    ARRIVE;

    val structural get() = this in FLYOVER_TAKE..UNDERPASS_SKIP
}

enum class Side { LEFT, RIGHT, MIDDLE }

class Maneuver(
    /** Index into Path.nodes. */
    val node: Int,
    /** Metres from the start of the path. */
    val at: Double,
    val kind: Kind,
    val side: Side,
    val angle: Double,
    val onto: String? = null,
    val towards: String? = null,
    /** Flyover / underpass name, if the map has one. */
    val structure: String? = null,
    val exit: Int = 0,
    val near: String? = null,
    val signal: Boolean = false,
    /** How many roads the rider sees at this split (2–4), or 0 for a plain turn. */
    val branches: Int = 0,
    /** Which of them to take, counted from the left (0 = leftmost). */
    val index: Int = -1,
) {
    override fun toString() = "%6.0f m  %-14s %-6s %s".format(at, kind, side, Phrases.action(this))
}

/**
 * Turns a path into spoken decisions for riders. At every split it counts the roads you will actually see
 * and says which one to take by position ("two roads, take the right one"), never by name. Whether a
 * branch climbs a flyover or dips into an underpass is only a short extra cue.
 */
object Guidance {
    /** How far past a split we look to see whether a branch climbs onto a flyover or drops into an underpass. */
    const val LOOK = 230.0
    /** Shorter bridges than this are nallah / railway crossings, not flyovers. */
    const val MIN_FLYOVER = 100.0
    const val MIN_UNDERPASS = 60.0

    private class Branch(
        val edge: Int,
        val angle: Double,
        val length: Double,
        /** Distance to where a real flyover starts on this branch, or +inf. */
        val upAt: Double,
        val upName: String?,
        /** Share of the look-ahead spent on a bridge. */
        val upFrac: Double,
        val downAt: Double,
        val downName: String?,
        val way: PackWay,
    )

    fun build(path: Path, destinationLabel: String? = null): List<Maneuver> {
        val g = path.graph
        val out = ArrayList<Maneuver>()
        var i = 1
        var bigCircleUntil = -1
        while (i < path.nodes.size - 1) {
            val node = path.nodes[i]
            val inE = path.edges[i - 1]
            val outE = path.edges[i]
            val inWay = g.way(inE)
            val outWay = g.way(outE)

            // Roundabouts: one instruction at the entry, with the exit number.
            if (!inWay.roundabout && outWay.roundabout) {
                var exits = 0
                var j = i + 1
                while (j < path.edges.size && path.way(j).roundabout) {
                    val n = path.nodes[j]
                    // Count real roads only, not driveways and lanes into buildings.
                    if (g.out[n].any { !g.way(it).roundabout && g.way(it).cls != RoadClass.SERVICE && g.way(it).cls != RoadClass.LIVING }) exits++
                    j++
                }
                val after = if (j < path.edges.size) path.way(j).label else null
                val onCircle = path.cum[minOf(j, path.nodes.size - 1)] - path.cum[i]
                if (exits > 5 || onCircle > 400) {
                    // A big circle like Connaught Place: "join the Outer Circle", then the exit is a normal decision.
                    out.add(Maneuver(i, path.cum[i], Kind.ROUNDABOUT, Side.LEFT, 0.0, onto = after, structure = outWay.name,
                        exit = -1, near = landmarkNear(g, node)))
                    bigCircleUntil = j
                    i++
                    continue
                }
                out.add(Maneuver(i, path.cum[i], Kind.ROUNDABOUT, Side.MIDDLE, 0.0, onto = after, exit = exits + 1,
                    near = landmarkNear(g, node), signal = false))
                i = maxOf(j, i + 1)
                continue
            }
            if (inWay.roundabout && i > bigCircleUntil) { i++; continue }

            val choiceEdges = g.choices(inE)
            if (choiceEdges.size < 2) { i++; continue }

            val inB = bearingBack(path, i)
            val branches = choiceEdges.map { e ->
                if (e == outE) branchAlongPath(path, i, inB) else branchFollowing(g, e, inB)
            }
            val chosen = branches.first { it.edge == outE }
            val others = branches.filter { it.edge != outE && it.length >= 40.0 }
            if (others.isEmpty()) { i++; continue }

            val m = decide(path, i, inWay, chosen, others)
            if (m != null) out.add(m)
            i++
        }
        val merged = mergeTurns(out)
        merged.add(Maneuver(path.nodes.size - 1, path.length, Kind.ARRIVE, Side.MIDDLE, 0.0, onto = destinationLabel))
        return merged
    }

    private fun decide(path: Path, i: Int, inWay: PackWay, chosen: Branch, others: List<Branch>): Maneuver? {
        val g = path.graph
        val node = path.nodes[i]
        // Branches you could plausibly confuse with the chosen one (roughly the same direction).
        val close = others.filter { abs(Geo.turn(chosen.angle, it.angle)) < 60.0 }
        val side = sideOf(chosen, close.ifEmpty { others })
        val onto = ontoName(path, i, inWay)
        val towards = chosen.way.destination?.split(';')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val near = landmarkNear(g, node)
        val signal = signalNear(path, i)
        val structuralSplit = listOf(chosen.upAt, chosen.downAt).any { it <= LOOK } || close.any { it.upAt <= LOOK || it.downAt <= LOOK }
        // The fan of roads in front of the rider, left to right: the chosen one plus look-alikes in roughly
        // the same direction (not driveways). Up to 4; beyond that position words stop helping.
        val fan = (close.filter { it.length >= 60.0 && (it.way.cls != RoadClass.SERVICE || chosen.way.cls == RoadClass.SERVICE || structuralSplit) } + chosen)
            .sortedBy { it.angle }
        val fanCount = if (fan.size in 2..4) fan.size else 0
        val fanIndex = if (fanCount > 0) fan.indexOf(chosen) else -1
        fun mk(kind: Kind, structure: String? = null, s: Side = side): Maneuver {
            val split = kind != Kind.TURN && kind != Kind.UTURN && fanCount > 0
            return Maneuver(i, path.cum[i], kind, s, chosen.angle, onto, towards, structure, 0, near, signal,
                branches = if (split) fanCount else 0, index = if (split) fanIndex else -1)
        }

        // 1) Structure decisions: the reason this app exists.
        if (close.isNotEmpty()) {
            if (!inWay.bridge) {
                val chUp = chosen.upAt <= LOOK
                val chDown = chosen.downAt <= LOOK
                if (chUp && close.any { it.upAt > LOOK }) return mk(Kind.FLYOVER_TAKE, chosen.upName)
                if (!chUp && close.any { it.upAt <= LOOK }) {
                    val other = close.first { it.upAt <= LOOK }
                    return mk(Kind.FLYOVER_SKIP, other.upName)
                }
                if (chDown && close.any { it.downAt > LOOK }) return mk(Kind.UNDERPASS_TAKE, chosen.downName)
                if (!chDown && close.any { it.downAt <= LOOK }) {
                    val other = close.first { it.downAt <= LOOK }
                    return mk(Kind.UNDERPASS_SKIP, other.downName)
                }
            } else {
                val stays = chosen.upFrac >= 0.6
                if (stays && close.any { it.upFrac < 0.4 }) return mk(Kind.FLYOVER_STAY, inWay.name)
                if (!stays && close.any { it.upFrac >= 0.6 }) return mk(Kind.FLYOVER_EXIT, inWay.name)
            }
        }

        val a = chosen.angle
        // 2) Real turns.
        if (abs(a) >= 150) return mk(Kind.UTURN, s = if (a > 0) Side.RIGHT else Side.LEFT)
        if (abs(a) >= 40) {
            // A bend where every other option is a sharper turn the same way is not worth announcing.
            val straighter = others.any { abs(it.angle) < abs(a) - 15 }
            val oppositeSide = others.any { it.angle * a < 0 && abs(it.angle) >= 25 }
            if (straighter || oppositeSide) return mk(Kind.TURN, s = if (a > 0) Side.RIGHT else Side.LEFT)
            return null
        }

        // 3) Small-angle splits.
        val rivals = others.filter { abs(Geo.turn(a, it.angle)) < 40.0 && significant(it.way, chosen.way) }
        if (rivals.isEmpty()) return null
        val fs = sideOf(chosen, rivals)
        if (fanCount == 0) {
            // Too many look-alike roads to count: fall back to a side.
            return Maneuver(i, path.cum[i], Kind.FORK, fs, chosen.angle, onto, towards, null, 0, near, signal)
        }
        return when {
            chosen.way.cls == RoadClass.SERVICE && inWay.cls != RoadClass.SERVICE -> mk(Kind.SERVICE_ROAD, s = fs)
            chosen.way.link && !inWay.link -> mk(Kind.SLIP_ROAD, s = fs)
            else -> mk(Kind.FORK, s = fs)
        }
    }

    /** A rival branch matters if it is a real road, or at least as important as the one we take. */
    private fun significant(w: PackWay, chosen: PackWay) =
        w.cls.rank >= RoadClass.TERTIARY.rank || w.link || w.cls.rank >= chosen.cls.rank

    private fun sideOf(chosen: Branch, rivals: List<Branch>): Side {
        if (rivals.isEmpty()) return if (chosen.angle < -10) Side.LEFT else if (chosen.angle > 10) Side.RIGHT else Side.MIDDLE
        val left = rivals.all { Geo.turn(chosen.angle, it.angle) > 0 }   // every rival is to our right
        val right = rivals.all { Geo.turn(chosen.angle, it.angle) < 0 }
        return when {
            left -> Side.LEFT
            right -> Side.RIGHT
            else -> Side.MIDDLE
        }
    }

    // ---- branch analysis ----

    private fun bearingBack(path: Path, i: Int): Double {
        val p = path.pointAt(path.cum[i] - 20.0)
        val n = path.nodes[i]
        val g = path.graph
        return if (Geo.dist(p.lat, p.lon, g.lat[n], g.lon[n]) < 1.0) path.edge(i - 1).bearing
        else Geo.bearing(p.lat, p.lon, g.lat[n], g.lon[n])
    }

    private fun branchAlongPath(path: Path, i: Int, inB: Double): Branch {
        val chain = ArrayList<Int>()
        var d = 0.0
        var k = i
        while (k < path.edges.size && d < LOOK + 250) {
            if (k > i && majorSplit(path.graph, path.edges[k - 1])) {
                // Stop at the next real decision point, so its flyover isn't credited to this one —
                // unless we are already on the way up (the ramp continues).
                if (!path.way(k - 1).link && !path.way(k - 1).bridge) break
            }
            chain.add(path.edges[k]); d += path.edge(k).length; k++
        }
        return analyse(path.graph, chain, inB)
    }

    private fun branchFollowing(g: RoadGraph, first: Int, inB: Double): Branch {
        val chain = arrayListOf(first)
        var d = g.edges[first].length
        var cur = first
        while (d < LOOK + 250) {
            if (majorSplit(g, cur) && !g.way(cur).link && !g.way(cur).bridge) break
            val next = g.choices(cur).minByOrNull { Geo.absTurn(g.edges[cur].bearing, g.edges[it].bearing) } ?: break
            if (Geo.absTurn(g.edges[cur].bearing, g.edges[next].bearing) > 70) break
            chain.add(next); d += g.edges[next].length; cur = next
            if (chain.size > 400) break
        }
        return analyse(g, chain, inB)
    }

    private fun majorSplit(g: RoadGraph, e: Int): Boolean {
        val c = g.choices(e)
        return c.size >= 2 && c.count { g.way(it).cls.rank >= RoadClass.TERTIARY.rank || g.way(it).link } >= 2
    }

    private fun analyse(g: RoadGraph, chain: List<Int>, inB: Double): Branch {
        val e0 = g.edges[chain[0]]
        // Direction of the branch ~20 m in.
        var d = 0.0
        var lat = g.lat[e0.from]; var lon = g.lon[e0.from]
        for (e in chain) {
            val ed = g.edges[e]
            if (d + ed.length >= 20.0) {
                val t = if (ed.length <= 0) 0.0 else (20.0 - d) / ed.length
                lat = g.lat[ed.from] + (g.lat[ed.to] - g.lat[ed.from]) * t
                lon = g.lon[ed.from] + (g.lon[ed.to] - g.lon[ed.from]) * t
                d = 20.0
                break
            }
            d += ed.length; lat = g.lat[ed.to]; lon = g.lon[ed.to]
        }
        val angle = if (d < 1.0) Geo.turn(inB, e0.bearing)
        else Geo.turn(inB, Geo.bearing(g.lat[e0.from], g.lon[e0.from], lat, lon))

        // Structure profile.
        var pos = 0.0
        var upAt = Double.POSITIVE_INFINITY; var upName: String? = null
        var downAt = Double.POSITIVE_INFINITY; var downName: String? = null
        var upLen = 0.0
        var runStart = -1.0; var runLen = 0.0; var runName: String? = null
        var dRunStart = -1.0; var dRunLen = 0.0; var dRunName: String? = null
        for (e in chain) {
            val ed = g.edges[e]
            val w = g.ways[ed.way]
            if (pos < LOOK && w.bridge) upLen += minOf(ed.length, LOOK - pos)
            if (w.bridge && w.layer >= 0) {
                if (runStart < 0) { runStart = pos; runLen = 0.0; runName = null }
                runLen += ed.length
                if (runName == null) runName = w.name?.takeIf { isStructureName(it) } ?: w.name
                if (upAt.isInfinite() && runStart <= LOOK && (runLen >= MIN_FLYOVER || isStructureName(w.name))) {
                    upAt = runStart; upName = runName
                }
            } else runStart = -1.0
            if (w.isUnderground) {
                if (dRunStart < 0) { dRunStart = pos; dRunLen = 0.0; dRunName = null }
                dRunLen += ed.length
                if (dRunName == null) dRunName = w.name
                if (downAt.isInfinite() && dRunStart <= LOOK && (dRunLen >= MIN_UNDERPASS || w.name?.contains("underpass", true) == true)) {
                    downAt = dRunStart; downName = dRunName
                }
            } else dRunStart = -1.0
            pos += ed.length
        }
        val lookLen = minOf(pos, LOOK)
        return Branch(
            edge = chain[0], angle = angle, length = pos,
            upAt = upAt, upName = upName?.takeIf { isStructureName(it) },
            upFrac = if (lookLen <= 0) 0.0 else upLen / lookLen,
            downAt = downAt, downName = downName?.takeIf { it.contains("underpass", true) },
            way = g.ways[e0.way],
        )
    }

    private fun isStructureName(n: String?) = n != null &&
        listOf("flyover", "bridge", "elevated", "setu", "overpass", "pul").any { n.contains(it, ignoreCase = true) }

    private fun ontoName(path: Path, i: Int, inWay: PackWay): String? {
        var d = 0.0
        var k = i
        while (k < path.edges.size && d < 120) {
            val w = path.way(k)
            val label = w.label
            if (label != null && !w.link) return if (label == inWay.label) null else label
            d += path.edge(k).length; k++
        }
        return null
    }

    private fun landmarkNear(g: RoadGraph, node: Int): String? {
        val lat = g.lat[node]; val lon = g.lon[node]
        return g.pack.landmarks
            .map { it to Geo.dist(lat, lon, it.lat, it.lon) }
            .filter { (l, d) -> d < if (l.kind == "metro") 160.0 else 90.0 }
            .minByOrNull { it.second }?.first?.spoken
    }

    private fun signalNear(path: Path, i: Int): Boolean {
        val g = path.graph
        for (k in maxOf(0, i - 3)..minOf(path.nodes.size - 1, i + 1)) {
            if (g.pack.isSignal(path.nodes[k]) && abs(path.cum[k] - path.cum[i]) < 35) return true
        }
        return false
    }

    /** Two quick same-side turns (crossing a divided road's median) become one turn or a U-turn. */
    private fun mergeTurns(list: List<Maneuver>): ArrayList<Maneuver> {
        val out = ArrayList<Maneuver>()
        var k = 0
        while (k < list.size) {
            val m = list[k]
            val n = list.getOrNull(k + 1)
            if (n != null && m.kind == Kind.TURN && n.kind == Kind.TURN && m.side == n.side && n.at - m.at < 50) {
                val sum = m.angle + n.angle
                val kind = if (abs(sum) >= 150) Kind.UTURN else Kind.TURN
                out.add(Maneuver(m.node, m.at, kind, m.side, sum, n.onto, n.towards, null, 0, m.near ?: n.near, m.signal || n.signal))
                k += 2
                continue
            }
            out.add(m)
            k++
        }
        return out
    }

    /** "Takes 3 flyovers (AIIMS Flyover, …), skips 1, 1 underpass". */
    fun summary(list: List<Maneuver>): String {
        val take = list.count { it.kind == Kind.FLYOVER_TAKE }
        val skip = list.count { it.kind == Kind.FLYOVER_SKIP }
        val under = list.count { it.kind == Kind.UNDERPASS_TAKE }
        val uturn = list.count { it.kind == Kind.UTURN }
        val parts = ArrayList<String>()
        if (take > 0) parts.add("takes $take flyover${if (take > 1) "s" else ""}")
        if (skip > 0) parts.add("skips $skip")
        if (under > 0) parts.add("$under underpass${if (under > 1) "es" else ""}")
        if (uturn > 0) parts.add("$uturn U-turn${if (uturn > 1) "s" else ""}")
        if (parts.isEmpty()) return "No flyover decisions"
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    }
}
