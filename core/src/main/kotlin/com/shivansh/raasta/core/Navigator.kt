package com.shivansh.raasta.core

import kotlin.math.max
import kotlin.math.min

/** One GPS reading. speed in m/s and bearing in degrees, or negative when unknown. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracy: Double = 10.0,
    val speed: Double = -1.0,
    val bearing: Double = -1.0,
    val time: Long = 0,
)

enum class NavStatus { ON_ROUTE, REROUTING, OUTSIDE_AREA, ARRIVED }

class NavState(
    val status: NavStatus,
    /** Where we draw the car: snapped to the road when on route, raw GPS otherwise. */
    val position: LatLon,
    val heading: Double,
    val progress: Double,
    val remaining: Double,
    val remainingTime: Double,
    val next: Maneuver?,
    val distToNext: Double,
    /** The next few decisions (flyovers etc.) with distances, for the strip on screen. */
    val upcoming: List<Pair<Maneuver, Double>>,
    val path: Path,
    val pathVersion: Int,
    /** Your speed, smoothed (km/h), or -1 before the first moving fix. */
    val speedKmh: Double = -1.0,
    /** Speed limit of the road you're on (km/h), or 0 if the map doesn't have one. */
    val limitKmh: Int = 0,
    val overLimit: Boolean = false,
)

/**
 * Offline turn-by-turn. Feed it GPS fixes; it tracks progress along the path, decides what to say and when,
 * and re-routes inside the downloaded corridor when you leave the route.
 */
class Navigator(
    val graph: RoadGraph,
    initial: Path,
    private val destName: String,
    private val speak: (text: String, urgent: Boolean) -> Unit,
) {
    var path: Path = initial
        private set
    var maneuvers: List<Maneuver> = Guidance.build(initial, destName)
        private set
    var pathVersion = 0
        private set
    var progress = 0.0
        private set

    private var seg = 0
    private var offCount = 0
    private val spoken = HashMap<Int, Int>()
    private var arrived = false
    private var outsideSince = -1L
    private var lastOutsideWarn = -1L
    private var lastHeading = 0.0

    // ---- speed ----
    private var speedMps = -1.0
    private var prevFix: Fix? = null
    private var limitKmh = 0
    private var overCount = 0
    private var overLimit = false
    private var fixN = 0
    private var lastLimitWarn = -1000
    // Your pace vs. the map's assumed road speeds, for the arrival time (decaying sums ≈ last 2 minutes).
    private var paceActual = 0.0
    private var paceExpected = 0.0
    private var paceProgress = -1.0
    private var paceTime = -1L

    var state: NavState = makeState(NavStatus.ON_ROUTE, LatLon(path.lat(0), path.lon(0)), path.edge(0).bearing)
        private set

    fun start() {
        speak(Phrases.briefing(destName, path.length, path.time, maneuvers), false)
        // The first maneuver was already read out in the briefing.
        maneuvers.firstOrNull()?.let { if (it.at < 600) spoken[0] = FAR or NEAR }
    }

    fun onFix(f: Fix): NavState {
        if (arrived) return state
        fixN++
        updateSpeed(f)
        val moving = f.speed > 2.0 && f.bearing >= 0
        val heading = if (moving) f.bearing else null
        val tol = (f.accuracy * 1.5).coerceIn(25.0, 50.0)

        var snap = snapToPath(f, heading, windowBack = 3, windowAheadM = 450.0)
        if (snap == null || snap.dist > tol) {
            // Maybe GPS jumped (tunnel, under a flyover): look along the whole path.
            val global = snapToPath(f, heading, windowBack = seg, windowAheadM = Double.MAX_VALUE)
            if (global != null && global.dist <= tol && (snap == null || global.dist < snap.dist)) snap = global
        }

        if (snap != null && snap.dist <= tol) {
            wrongBranch(f, heading, snap.dist)?.let { return it }
            offCount = 0
            outsideSince = -1
            seg = snap.seg
            progress = path.cum[snap.seg] + snap.t * (path.cum[snap.seg + 1] - path.cum[snap.seg])
            lastHeading = path.edge(seg).bearing
            updatePace(f)
            checkLimit()
            announce(if (speedMps > 0) speedMps else 8.0)
            if (path.length - progress < 30) {
                arrived = true
                speak("You have reached $destName.", true)
                state = makeState(NavStatus.ARRIVED, LatLon(snap.lat, snap.lon), lastHeading)
                return state
            }
            state = makeState(NavStatus.ON_ROUTE, LatLon(snap.lat, snap.lon), lastHeading)
            return state
        }

        offCount++
        val far = snap == null || snap.dist > 80
        // Faster riding = less patience: at ~45 km/h and up, two bad fixes are enough.
        val needed = if (speedMps > 12.5) 2 else 3
        if (offCount >= needed || (far && offCount >= 2)) {
            reroute(f, heading)?.let { return it }
        }
        if (heading != null) lastHeading = heading
        state = makeState(if (outsideSince > 0) NavStatus.OUTSIDE_AREA else NavStatus.ON_ROUTE, LatLon(f.lat, f.lon), lastHeading)
        return state
    }

    /**
     * The flyover problem: the road below runs ~15 m from the flyover, so plain "distance from route" can't
     * tell them apart. For a few hundred metres after each split we also measure the distance to the branch(es)
     * we did NOT want; if the car is consistently closer to one of those, it took the wrong one.
     */
    private class Watch(val at: Double, val chains: List<IntArray>)
    private var watches: List<Watch> = buildWatches()
    private var wrongCount = 0

    private fun buildWatches(): List<Watch> = maneuvers
        .filter { it.kind.structural || it.kind == Kind.FORK || it.kind == Kind.SLIP_ROAD || it.kind == Kind.SERVICE_ROAD }
        .mapNotNull { m ->
            if (m.node < 1 || m.node >= path.edges.size) return@mapNotNull null
            val inE = path.edges[m.node - 1]
            val alts = graph.choices(inE).filter { it != path.edges[m.node] }
            if (alts.isEmpty()) null else Watch(m.at, alts.map { followStraight(it, WATCH_M) })
        }

    private fun followStraight(first: Int, len: Double): IntArray {
        val out = arrayListOf(first)
        var d = graph.edges[first].length
        var cur = first
        while (d < len) {
            val next = graph.choices(cur).minByOrNull { Geo.absTurn(graph.edges[cur].bearing, graph.edges[it].bearing) } ?: break
            if (Geo.absTurn(graph.edges[cur].bearing, graph.edges[next].bearing) > 60) break
            out.add(next); d += graph.edges[next].length; cur = next
        }
        return out.toIntArray()
    }

    private fun wrongBranch(f: Fix, heading: Double?, dPath: Double): NavState? {
        val w = watches.firstOrNull { progress >= it.at - 5 && progress <= it.at + WATCH_M }
        if (w == null) { wrongCount = 0; return null }
        var bestEdge = -1
        var bestD = Double.POSITIVE_INFINITY
        for (chain in w.chains) for (e in chain) {
            val ed = graph.edges[e]
            if (heading != null && Geo.absTurn(heading, ed.bearing) > 60) continue
            val p = Geo.project(f.lat, f.lon, graph.lat[ed.from], graph.lon[ed.from], graph.lat[ed.to], graph.lon[ed.to])
            if (p.dist < bestD) { bestD = p.dist; bestEdge = e }
        }
        if (bestEdge >= 0 && bestD + 4.0 < dPath) wrongCount++ else wrongCount = max(0, wrongCount - 1)
        if (wrongCount < 3) return null
        wrongCount = 0
        return reroute(f, heading, forcedEdge = bestEdge)
    }

    private fun reroute(f: Fix, heading: Double?, forcedEdge: Int = -1): NavState? {
        val s = if (forcedEdge >= 0) {
            val ed = graph.edges[forcedEdge]
            val p = Geo.project(f.lat, f.lon, graph.lat[ed.from], graph.lon[ed.from], graph.lat[ed.to], graph.lon[ed.to])
            RoadGraph.Snap(forcedEdge, p.t, p.dist, p.lat, p.lon)
        } else graph.snap(f.lat, f.lon, heading, 60.0)
        val goal = path.nodes.last()
        val edges = s?.let { sn ->
            val e = graph.edges[sn.edge]
            graph.route(listOf(sn.edge to e.time * (1 - sn.t)), goal)
        }
        if (s == null || edges == null) {
            val now = f.time
            if (outsideSince < 0) outsideSince = max(1, now)
            if (lastOutsideWarn < 0 || now - lastOutsideWarn > 60_000) {
                lastOutsideWarn = now
                speak("You are outside the downloaded area. Head back to the route.", true)
            }
            state = makeState(NavStatus.OUTSIDE_AREA, LatLon(f.lat, f.lon), heading ?: lastHeading)
            return state
        }
        outsideSince = -1
        offCount = 0
        path = Path(graph, edges)
        pathVersion++
        maneuvers = Guidance.build(path, destName)
        watches = buildWatches()
        spoken.clear()
        seg = 0
        progress = s.t * graph.edges[s.edge].length
        val next = maneuvers.firstOrNull { it.at > progress + 5 }
        val msg = StringBuilder("Rerouting.")
        if (next != null && next.kind != Kind.ARRIVE) {
            val d = next.at - progress
            msg.append(" In ").append(Phrases.distance(d)).append(", ").append(Phrases.action(next)).append('.')
            val idx = maneuvers.indexOf(next)
            spoken[idx] = if (d < 250) FAR or NEAR else FAR
        }
        speak(msg.toString(), true)
        state = makeState(NavStatus.REROUTING, LatLon(s.lat, s.lon), graph.edges[s.edge].bearing)
        return state
    }

    private class PathSnap(val seg: Int, val t: Double, val dist: Double, val lat: Double, val lon: Double)

    private fun snapToPath(f: Fix, heading: Double?, windowBack: Int, windowAheadM: Double): PathSnap? {
        val from = max(0, seg - windowBack)
        var best: PathSnap? = null
        var bestScore = Double.POSITIVE_INFINITY
        var i = from
        while (i < path.edges.size) {
            if (i > seg + 2 && path.cum[i] > progress + windowAheadM) break
            val p = Geo.project(f.lat, f.lon, path.lat(i), path.lon(i), path.lat(i + 1), path.lon(i + 1))
            var score = p.dist
            if (heading != null) {
                val a = Geo.absTurn(heading, path.edge(i).bearing)
                score += if (a > 100) 200.0 else a * 0.2
            }
            // Gently prefer not going backwards.
            val d = path.cum[i] + p.t * (path.cum[i + 1] - path.cum[i])
            if (d < progress - 40) score += 15.0
            if (score < bestScore) { bestScore = score; best = PathSnap(i, p.t, p.dist, p.lat, p.lon) }
            i++
        }
        return best
    }

    private fun updateSpeed(f: Fix) {
        var v = f.speed
        val p = prevFix
        if (v < 0 && p != null && f.time > p.time) {
            v = Geo.dist(p.lat, p.lon, f.lat, f.lon) / ((f.time - p.time) / 1000.0)
        }
        prevFix = f
        if (v < 0 || v > 70) return // unknown or a GPS glitch
        speedMps = if (speedMps < 0) v else speedMps * 0.6 + v * 0.4
    }

    /** Learn how your pace compares with the map's assumed speeds; stops at signals count, that's real time. */
    private fun updatePace(f: Fix) {
        val dt = if (paceTime > 0 && f.time > paceTime) (f.time - paceTime) / 1000.0 else 1.0
        if (paceProgress >= 0) {
            val dProg = progress - paceProgress
            if (dProg in 0.0..250.0 && dt in 0.2..10.0) {
                val e = path.edge(seg)
                val expectedSpeed = if (e.time > 0) e.length / e.time else 8.0
                paceActual = paceActual * 0.99 + dt
                paceExpected = paceExpected * 0.99 + dProg / expectedSpeed
            }
        }
        paceProgress = progress
        paceTime = f.time
    }

    /** Multiplier for the map's time estimate, once we've seen enough of your riding. */
    private fun paceFactor(): Double =
        if (paceExpected < 30.0 || paceActual < 30.0) 1.0 else (paceActual / paceExpected).coerceIn(0.5, 2.5)

    private fun checkLimit() {
        limitKmh = path.way(seg).maxspeed.takeIf { it in 10..150 } ?: 0
        val kmh = speedMps * 3.6
        overCount = if (limitKmh > 0 && kmh > limitKmh + 5) overCount + 1 else 0
        overLimit = overCount >= 3
        // One calm reminder, then quiet for a while.
        if (overLimit && fixN - lastLimitWarn > 45) {
            lastLimitWarn = fixN
            speak("Slow down. The limit here is $limitKmh.", false)
        }
    }

    private fun announce(speedMps: Double) {
        val v = max(speedMps, 6.0)
        val idx = maneuvers.indexOfFirst { it.at > progress + 3 }
        if (idx < 0) return
        val m = maneuvers[idx]
        val d = m.at - progress
        val done = spoken[idx] ?: 0
        val farAt = max(500.0, v * 40)
        val nearAt = max(170.0, v * 13)
        val nowAt = max(35.0, v * 4)
        val prevAt = maneuvers.getOrNull(idx - 1)?.at ?: 0.0
        val then = maneuvers.getOrNull(idx + 1)?.takeIf { it.at - m.at < 180 && it.kind != Kind.ARRIVE }

        when {
            m.kind == Kind.ARRIVE -> {
                if (d <= nearAt && done and NEAR == 0) {
                    speak("In ${Phrases.distance(d)}, you will reach $destName.", false)
                    spoken[idx] = done or NEAR or FAR
                }
            }
            d <= nowAt && done and NOW == 0 -> {
                val s = StringBuilder("Now, ").append(Phrases.short(m))
                if (then != null) s.append(", then ").append(Phrases.short(then))
                speak(s.append('.').toString(), true)
                spoken[idx] = NOW or NEAR or FAR
            }
            d <= nearAt && d > nowAt && done and NEAR == 0 -> {
                val s = StringBuilder("In ").append(Phrases.distance(d)).append(", ").append(Phrases.action(m))
                if (then != null) s.append(". Then ").append(Phrases.short(then))
                speak(s.append('.').toString(), false)
                spoken[idx] = done or NEAR or FAR
            }
            d <= farAt && d > nearAt + 150 && done and FAR == 0 && m.at - prevAt > nearAt + 150 -> {
                // Early warning matters most for flyovers: you need to be in the right lane well before.
                if (m.kind.structural || m.kind == Kind.UTURN || m.kind == Kind.SLIP_ROAD || m.kind == Kind.FORK || d > 300) {
                    speak("In ${Phrases.distance(d)}, ${Phrases.action(m)}.", false)
                }
                spoken[idx] = done or FAR
            }
        }
    }

    private fun makeState(status: NavStatus, pos: LatLon, heading: Double): NavState {
        val idx = maneuvers.indexOfFirst { it.at > progress + 3 }
        val next = if (idx >= 0) maneuvers[idx] else null
        val upcoming = if (idx < 0) emptyList() else maneuvers.drop(idx)
            .filter { it.kind.structural || it.kind == Kind.UTURN || it.kind == Kind.ARRIVE }
            .take(4).map { it to it.at - progress }
        return NavState(
            status = status, position = pos, heading = heading, progress = progress,
            remaining = max(0.0, path.length - progress),
            remainingTime = path.timeFrom(min(progress, path.length)) * paceFactor(),
            next = next, distToNext = next?.let { it.at - progress } ?: 0.0,
            upcoming = upcoming, path = path, pathVersion = pathVersion,
            speedKmh = if (speedMps < 0) -1.0 else speedMps * 3.6, limitKmh = limitKmh, overLimit = overLimit,
        )
    }

    companion object {
        private const val FAR = 1
        private const val NEAR = 2
        private const val NOW = 4
        private const val WATCH_M = 450.0
    }
}

/** Fake GPS for testing without driving: moves along the navigator's current path. */
class DemoDriver(private val nav: Navigator, private val kmh: Double = 35.0, private val noiseM: Double = 3.0, seed: Long = 7) {
    private val rnd = java.util.Random(seed)
    private var d = 0.0
    private var version = -1
    private var t = 0L

    fun next(dtSeconds: Double = 1.0): Fix {
        if (nav.pathVersion != version) { version = nav.pathVersion; d = nav.progress }
        val v = kmh / 3.6 * (0.85 + rnd.nextDouble() * 0.3)
        d = min(d + v * dtSeconds, nav.path.length)
        val p = nav.path.pointAt(d)
        val b = nav.path.bearingAt(d)
        val n = Geo.offset(p.lat, p.lon, rnd.nextDouble() * 360, rnd.nextGaussian().let { kotlin.math.abs(it) } * noiseM)
        t += (dtSeconds * 1000).toLong()
        return Fix(n.lat, n.lon, 8.0, v, b, t)
    }

    val done get() = d >= nav.path.length - 1
}
