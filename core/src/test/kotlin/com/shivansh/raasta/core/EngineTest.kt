package com.shivansh.raasta.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses real route packs built from Delhi OpenStreetMap data (src/test/resources). */
class EngineTest {
    private fun pack(name: String) = PackIO.read(javaClass.getResourceAsStream("/$name")!!)

    private fun route(name: String): Pair<RoadGraph, Path> {
        val p = pack(name)
        val g = RoadGraph(p)
        return g to Path.fromNodes(g, p.routes[0].nodePath)!!
    }

    @Test fun jsonParses() {
        val v = Json.parse("""{"a":[1,2.5,-3e2],"b":"x\"y\u00e9","c":null,"d":true}""").asMap()
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("x\"yé", v["b"])
        assertEquals(true, v["d"])
    }

    @Test fun packRoundTrips() {
        val p = pack("aiims-dhaulakuan.rpk")
        val out = ByteArrayOutputStream()
        PackIO.write(p, out)
        val q = PackIO.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(p.meta.title, q.meta.title)
        assertEquals(p.ways.size, q.ways.size)
        assertEquals(p.routes[0].nodePath.toList(), q.routes[0].nodePath.toList())
    }

    @Test fun ringRoadFlyoversAreTaken() {
        val (_, path) = route("aiims-dhaulakuan.rpk")
        val ms = Guidance.build(path, "Dhaula Kuan")
        val taken = ms.filter { it.kind == Kind.FLYOVER_TAKE }.mapNotNull { it.structure }
        for (f in listOf("AIIMS Flyover", "Raj Nagar Flyover", "Bikaji Cama Place Flyover", "Moti Bagh Flyover")) {
            assertTrue(f in taken, "expected to take $f, got $taken")
        }
        assertEquals(Kind.ARRIVE, ms.last().kind)
    }

    @Test fun ashramUnderpassIsTaken() {
        val (_, path) = route("ito-ashram.rpk")
        val ms = Guidance.build(path, "Ashram")
        assertTrue(ms.any { it.kind == Kind.UNDERPASS_TAKE && it.structure == "Ashram Underpass" }, ms.joinToString("\n"))
    }

    @Test fun simulatedDriveAnnouncesFlyoversInTimeAndArrives() {
        val (g, path) = route("aiims-dhaulakuan.rpk")
        val said = ArrayList<Pair<Double, String>>()
        lateinit var nav: Navigator
        nav = Navigator(g, path, "Dhaula Kuan") { t, _ -> said.add(nav.progress to t) }
        nav.start()
        val demo = DemoDriver(nav, kmh = 40.0)
        var s = nav.state
        var n = 0
        while (s.status != NavStatus.ARRIVED && n++ < 3000) s = nav.onFix(demo.next())
        assertEquals(NavStatus.ARRIVED, s.status)
        // Every flyover gets an early warning at least 300 m before the split.
        for (m in nav.maneuvers.filter { it.kind == Kind.FLYOVER_TAKE }) {
            val phrase = Phrases.action(m)
            val early = said.any { (at, text) -> text.contains(phrase) && m.at - at >= 300 } ||
                (m.at < 700 && said.first().second.contains(phrase))
            assertTrue(early, "no early warning for ${m.structure}: $said")
        }
        assertTrue(said.none { it.second.startsWith("Rerouting") }, "unexpected reroute: $said")
    }

    @Test fun missingTheFlyoverReroutesOffline() {
        val (g, path) = route("aiims-dhaulakuan.rpk")
        val said = ArrayList<String>()
        val nav = Navigator(g, path, "Dhaula Kuan") { t, _ -> said.add(t) }
        val target = nav.maneuvers.first { it.kind == Kind.FLYOVER_TAKE && it.structure == "Raj Nagar Flyover" }
        // Drive normally up to the split...
        var d = 0.0
        while (d < target.at) {
            val p = path.pointAt(d)
            nav.onFix(Fix(p.lat, p.lon, 6.0, 11.0, path.bearingAt(d)))
            d += 11.0
        }
        // ...then stay below instead of going up: follow the other branch.
        val inE = path.edges[target.node - 1]
        val wrong = g.choices(inE).first { it != path.edges[target.node] }
        var e = wrong
        val fixes = ArrayList<Fix>()
        var run = 0.0
        while (run < 600) {
            val ed = g.edges[e]
            var s = 0.0
            while (s < ed.length) {
                val t = s / ed.length
                fixes.add(Fix(g.lat[ed.from] + (g.lat[ed.to] - g.lat[ed.from]) * t, g.lon[ed.from] + (g.lon[ed.to] - g.lon[ed.from]) * t, 6.0, 11.0, ed.bearing))
                s += 11.0
            }
            run += ed.length
            e = g.choices(e).minByOrNull { Geo.absTurn(ed.bearing, g.edges[it].bearing) } ?: break
        }
        val before = nav.pathVersion
        for (f in fixes) nav.onFix(f)
        assertTrue(nav.pathVersion > before, "expected a reroute; said=$said")
        assertTrue(said.any { it.startsWith("Rerouting") })
        // And the new route still gets us there.
        val demo = DemoDriver(nav, kmh = 40.0, noiseM = 2.0)
        var st = nav.state
        var n = 0
        while (st.status != NavStatus.ARRIVED && n++ < 3000) st = nav.onFix(demo.next())
        assertEquals(NavStatus.ARRIVED, st.status)
    }

    @Test fun splitsAreSpokenByPositionNotName() {
        val (_, path) = route("aiims-dhaulakuan.rpk")
        val ms = Guidance.build(path, "Dhaula Kuan")
        val aiims = ms.first { it.kind == Kind.FLYOVER_TAKE && it.structure == "AIIMS Flyover" }
        assertTrue(aiims.branches >= 2, "AIIMS split should count its roads: $aiims")
        val text = Phrases.action(aiims)
        assertTrue(text.startsWith("the road splits in two") || text.contains("roads ahead"), text)
        assertTrue(!text.contains("AIIMS") && !text.contains("Marg"), "no names in speech: $text")
        for (m in ms) assertTrue(m.onto == null || !Phrases.action(m).contains(m.onto!!), "road name spoken: ${Phrases.action(m)}")
    }

    @Test fun threeAndFourWaySplitsUsePositionWords() {
        fun m(n: Int, i: Int, k: Kind = Kind.FORK) = Maneuver(1, 100.0, k, Side.MIDDLE, 0.0, branches = n, index = i)
        assertEquals("three roads ahead. Take the middle one", Phrases.action(m(3, 1)))
        assertEquals("three roads ahead. Take the left one, going up", Phrases.action(m(3, 0, Kind.FLYOVER_TAKE)))
        assertEquals("four roads ahead. Take the second one from the left", Phrases.action(m(4, 1)))
        assertEquals("Right road", Phrases.banner(m(2, 1)))
        assertEquals("Second from left", Phrases.banner(m(4, 1)))
    }

    /** A straight 3 km road heading north with a 50 km/h limit, nodes every 50 m. */
    private fun straightRoad(limit: Int): Pair<RoadGraph, Path> {
        val n = 61
        val lat = DoubleArray(n) { 28.60 + it * 50.0 / 111_320.0 }
        val lon = DoubleArray(n) { 77.20 }
        val way = PackWay(1, IntArray(n) { it }, RoadClass.PRIMARY, false, 0, false, false, 0, false, "Test Marg", null, null, 2, limit)
        val meta = PackMeta("t", "t", "A", "B", 0, LatLon(lat[0], lon[0]), LatLon(lat[n - 1], lon[n - 1]), 3000.0, 300.0, "", 1)
        val pack = RoutePack(meta, LongArray(n) { it + 1L }, lat, lon, ByteArray(n), listOf(way), emptyList(), emptyList())
        val g = RoadGraph(pack)
        return g to Path.fromNodes(g, IntArray(n) { it })!!
    }

    private fun ride(nav: Navigator, path: Path, kmh: Double, seconds: Int, fromM: Double = 0.0): NavState {
        var d = fromM
        var st = nav.state
        val v = kmh / 3.6
        for (t in 0 until seconds) {
            val p = path.pointAt(d)
            st = nav.onFix(Fix(p.lat, p.lon, 5.0, v, path.bearingAt(d), time = 1_000L * (t + 1)))
            d += v
        }
        return st
    }

    @Test fun speedingOnALimitedRoadWarnsOnce() {
        val (g, path) = straightRoad(limit = 50)
        val said = ArrayList<String>()
        val nav = Navigator(g, path, "B") { t, _ -> said.add(t) }
        val st = ride(nav, path, kmh = 70.0, seconds = 40)
        assertEquals(50, st.limitKmh)
        assertTrue(st.overLimit, "should be over the limit at 70 in a 50")
        assertTrue(st.speedKmh in 65.0..75.0, "speed ${st.speedKmh}")
        assertEquals(1, said.count { it.startsWith("Slow down") }, "one warning, not a nag: $said")
    }

    @Test fun ridingWithinTheLimitStaysQuiet() {
        val (g, path) = straightRoad(limit = 50)
        val said = ArrayList<String>()
        val nav = Navigator(g, path, "B") { t, _ -> said.add(t) }
        val st = ride(nav, path, kmh = 48.0, seconds = 40)
        assertTrue(!st.overLimit)
        assertTrue(said.none { it.startsWith("Slow down") })
    }

    @Test fun arrivalTimeFollowsYourActualPace() {
        // Map assumes ~46 km/h on a primary road with a 50 limit; ride at half that.
        val (g, path) = straightRoad(limit = 50)
        val nav = Navigator(g, path, "B") { _, _ -> }
        val st = ride(nav, path, kmh = 23.0, seconds = 120)
        val mapEstimate = path.timeFrom(st.progress)
        val ratio = st.remainingTime / mapEstimate
        assertTrue(ratio in 1.6..2.4, "expected ~2x the map's estimate, got $ratio")
    }

    @Test fun tilesCoverTheRoute() {
        val line = listOf(LatLon(28.5672, 77.2100), LatLon(28.5918, 77.1615))
        val keys = RoadTiles.along(listOf(line), 250.0)
        assertTrue(RoadTiles.key(28.5672, 77.2100) in keys && RoadTiles.key(28.5918, 77.1615) in keys)
        assertTrue(keys.size < 40, "too many tiles: ${keys.size}")
    }
}
