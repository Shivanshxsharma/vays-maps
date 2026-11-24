package com.shivansh.raasta.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The bundled place file and the search over it, on the queries people really type. */
class PlaceSearchTest {
    companion object {
        private val db: PlaceDb by lazy {
            RandomAccessFile(File("../app/src/main/assets/places.db"), "r").channel.use { PlaceDb(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())) }
        }
        private val paschimVihar = LatLon(28.6680, 77.1000)
    }

    private fun top(q: String, at: LatLon? = paschimVihar, n: Int = 5) = db.search(q, at, n, typing = false)

    /** One of the first [within] results has this in its name, no further than [km]. */
    private fun finds(q: String, name: String, km: Double = 60.0, within: Int = 1, kind: PlaceKind? = null, at: LatLon? = paschimVihar) {
        val r = top(q, at, 8)
        val ok = r.take(within).any { it.place.name.contains(name, true) && (at == null || it.distance < km * 1000) && (kind == null || it.place.kind == kind) }
        assertTrue(ok, "“$q” should find “$name”, got " + r.joinToString { "${it.place.name} (${it.distance.toInt()} m)" })
    }

    @Test fun theDosaCorner() {
        for (q in listOf("She Durga Dosa corner Paschim Vihar", "Shree Durga Dosa Corner", "shri durga dosa meera bagh"))
            finds(q, "Shree Durga Dosa Corner", 2.0)
        finds("sree durga dosa", "Durga Dosa", 2.0)
    }

    @Test fun bigEnoughToBeUseful() {
        assertTrue(db.size > 200_000)
        var n = 0
        db.inBox(28.655, 77.08, 28.685, 77.125, 4) { n++ }
        assertTrue(n > 1500, "Paschim Vihar has $n places")
    }

    @Test fun spellings() {
        finds("qutub minar", "Qut", within = 2)
        finds("qutb minar", "Qutb Minar")
        finds("sarojni nagar market", "Sarojini Nagar Market")
        finds("pashchim vihar west metro", "Paschim Vihar West", kind = PlaceKind.METRO)
        finds("sitaram diwan chand", "Sita Ram Diwan Chand")
        finds("safdarjang hospital", "Safdarj", within = 3)
        finds("akshardam", "Akshardham", within = 2)
        finds("mcdonlds", "McDonald", 3.0, within = 3)
    }

    @Test fun chainsGiveTheNearest() {
        finds("haldiram", "Haldiram", 1.0)
        finds("mc donalds", "McDonald", 2.0)
        finds("dominos", "Domino", 1.0)
        finds("sandoz restaurant", "Sandoz", 1.5)
        finds("five star dhaba", "Five Star Dhaba", 1.5)
        finds("haldirams connaught place", "Haldiram", 14.0)
        assertTrue(top("haldirams connaught place").first().distance > 9_000, "the area you typed wins over the one near you")
    }

    @Test fun kindsGiveTheNearest() {
        fun kinds(q: String, vararg k: PlaceKind) {
            val r = top(q)
            assertTrue(r.size >= 4 && r.all { it.distance < 3000 }, "“$q” near: " + r.joinToString { "${it.place.name} ${it.distance.toInt()}" })
            assertTrue(r.count { it.place.kind in k } >= r.size - 1, "“$q” kinds: " + r.joinToString { "${it.place.name} ${it.place.kind}" })
        }
        kinds("chemist near me", PlaceKind.PHARMACY)
        kinds("medical store", PlaceKind.PHARMACY)
        kinds("puncture shop", PlaceKind.GARAGE)
        kinds("salon", PlaceKind.SALON)
        kinds("gym", PlaceKind.GYM)
        kinds("atm", PlaceKind.BANK)
        kinds("restaurant", PlaceKind.FOOD)
        val p = top("petrol pump")
        assertTrue(p.first().place.kind == PlaceKind.FUEL && p.first().distance < 2500)
    }

    @Test fun kindInAnArea() {
        val saket = LatLon(28.5245, 77.2066)
        val r = top("chemist saket")
        assertTrue(r.take(3).all { it.place.kind == PlaceKind.PHARMACY && Geo.dist(it.place.latLon, saket) < 3500 }, r.joinToString { it.place.name })
        assertTrue(top("momos lajpat nagar").take(3).any { "momo" in it.place.name.lowercase() && Geo.dist(it.place.latLon, LatLon(28.5677, 77.2433)) < 3000 })
    }

    @Test fun initialsAndFamousPlaces() {
        finds("nsut", "Netaji Subhas University", within = 2)
        finds("india gate", "India Gate", kind = PlaceKind.LANDMARK)
        finds("red fort", "Red Fort", kind = PlaceKind.LANDMARK)
        finds("shadipur metro station", "Shadipur", kind = PlaceKind.METRO)
        finds("new delhi railway station", "New Delhi", within = 2, kind = PlaceKind.RAIL)
        finds("paschim vihar", "Paschim Vihar", 2.0, kind = PlaceKind.AREA)
        finds("igi airport terminal 3", "Terminal 3", kind = PlaceKind.AIRPORT)
    }

    @Test fun famousPlacesAreNotPinnedAllOverTown() {
        val far = db.search("india gate", null, 20).filter { it.place.name.equals("India Gate", true) && it.place.kind == PlaceKind.LANDMARK }
        assertTrue(far.isNotEmpty() && far.all { Geo.dist(it.place.latLon, LatLon(28.6129, 77.2295)) < 1500 })
        assertNotEquals("Select City Walk Mall", top("select city walk").first().place.name)
    }

    @Test fun worksWhileTyping() {
        fun typed(q: String) = db.search(q, paschimVihar, 8, typing = true).map { it.place.name }
        assertTrue(typed("sha").isNotEmpty())
        assertTrue(typed("shree durga d").any { "Durga Dosa" in it })
        assertTrue(typed("hald").first().contains("Haldiram"))
        assertTrue(db.search("a", paschimVihar).isEmpty())
        assertTrue(db.search("zzqxv kkwq", paschimVihar).isEmpty())
        assertTrue(db.search("  ", paschimVihar).isEmpty())
    }

    @Test fun nearestIsSortedAndComplete() {
        val got = db.near(28.668, 77.1, 30, 3000.0) { db.kind(it) == PlaceKind.FOOD }
        assertEquals(got.sortedBy { it.second }, got)
        val brute = (0 until db.size).filter { db.kind(it) == PlaceKind.FOOD }.map { Geo.dist(28.668, 77.1, db.lat(it), db.lon(it)) }.sorted().take(30)
        assertEquals(brute.map { it.toInt() }, got.map { it.second.toInt() })
    }

    @Test fun sounds() {
        fun same(vararg w: String) = assertEquals(1, w.map { PlaceText.sound(it) }.toSet().size, w.joinToString())
        same("qutub", "qutab", "qutb"); same("shri", "sri", "shree"); same("paschim", "pashchim"); same("lakshmi", "laxmi")
        assertEquals(1, PlaceText.edits("teh", "the", 2))
        assertEquals(listOf("haldirams", "cp"), PlaceText.tokens("Haldiram's (CP)"))
    }
}
