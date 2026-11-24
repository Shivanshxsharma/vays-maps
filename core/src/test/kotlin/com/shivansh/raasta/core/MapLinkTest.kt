package com.shivansh.raasta.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Links as the Google Maps app really shares them (recorded), and every other way people pass a place on. */
class MapLinkTest {
    companion object {
        private val db: PlaceDb by lazy {
            RandomAccessFile(File("../app/src/main/assets/places.db"), "r").channel.use { PlaceDb(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())) }
        }
        private val delhi = LatLon(28.6315, 77.2167)
        private val shares = mapOf(
            "https://maps.app.goo.gl/8vBcEPgAimH2MogK6" to "https://www.google.com/maps/place/Haldiram's+-+Connaught+Place,+6,+Connaught+Cir,+Block+L,+Connaught+Place,+New+Delhi,+Delhi+110001,+India/data=!4m2!3m1!1s0x390cfd49daa29f51:0x69e7cc948429a889",
            "https://maps.app.goo.gl/b67sCEwG4Qj62sJW6" to "https://www.google.com/maps/place/Shop+No:+A,+Wenger's,+16+Near+Road,+Inner+Cir,+Block+A,+Connaught+Place,+New+Delhi,+Delhi+110001,+India/data=!4m2!3m1!1s0x390cfd37c5a1c75f:0x61c81f34254f7faa",
            "https://maps.app.goo.gl/3Z4be7ChWmPQyudC9" to "https://www.google.com/maps/place/Max+Super+Speciality+Hospital,+Shalimar+Bagh,+FC+50,+C+and+D+Block,+Shalimar+Place+Site,+Shalimar+Bagh,+New+Delhi,+Delhi,+110088,+India/data=!4m2!3m1!1s0x390d019eea1416d7:0xc4c7bcd628010c86",
            "https://maps.app.goo.gl/Tzo8isMVXxGB4T229" to "https://www.google.com/maps/place/Dolma+Aunty+Momo+Since+1994,+D-1,+Alankar+Cinema+Rd,+opp.+Police+Station,+Central+Market,+Lajpat+Nagar+II,+Lajpat+Nagar,+New+Delhi,+Delhi+110024,+India/data=!4m2!3m1!1s0x390ce3ad52a6ff6b:0x6533eb1ecc99e012",
            "https://maps.app.goo.gl/65TjvWLy66ajWvV56" to "https://www.google.com/maps/place/Sita+Ram+Diwan+Chand,+2243,+Rajguru+Marg,+Chuna+Mandi,+Paharganj,+New+Delhi,+Delhi+110055,+India/data=!4m2!3m1!1s0x390cfd3ec19dcea9:0x16328da9306ecf22",
            "https://maps.app.goo.gl/ARxRbMUGK2QRLtDq5" to "https://www.google.com/maps/place/Radisson+Blu+Hotel,+New+Delhi+Paschim+Vihar,+Outer+Ring+Rd,+Sunder+Vihar,+Paschim+Vihar,+New+Delhi,+Delhi,+110087,+India/data=!4m2!3m1!1s0x390d047ab4403917:0xa15c7ff58eabbf58",
            "https://maps.app.goo.gl/ZvHySQNpCs6gCah79" to "https://www.google.com/maps/place/Shop+No+487,+United+Hardware+And+Interiors,+63,+opp.+Punjab+National+Bank,+National+Market,+Peeragarhi+Village,+Paschim+Vihar,+New+Delhi,+Delhi,+110087,+India/data=!4m2!3m1!1s0x390d05a71e839877:0xdd9e3ca5893fcec9",
            "https://maps.app.goo.gl/BU6Bc7dNRuQxVx6K6" to "https://www.google.com/maps/place/Anand+Paints+%26+Hardware+Store,+Chaudhary+Kishan+Chand+Complex,+4+D+%2F+12+jwala+heri+mkt,+opposite+haldiram's,+Paschim+Vihar,+New+Delhi,+Delhi+110063,+India/data=!4m2!3m1!1s0x390d04787c000001:0xd86c81b872f7637e",
            "https://maps.app.goo.gl/ANhJb2u1vw1rAK2w8" to "https://www.google.com/maps/place/Krishna+Stationery,+Shop+no.+36,+B-1,+Market,+Dharam+Shastri+Marg,+B+1+Block,+Paschim+Vihar,+Delhi,+110063,+India/data=!4m2!3m1!1s0x390d0385f981e86b:0xd8eb5e83c5c3c5d0",
            "https://maps.app.goo.gl/wkMxVEbKk8gkp7Lj6" to "https://www.google.com/maps/place/Shree+Durga+Dosa+Corner,+GH+6,+Meera+Bagh+Road,+Paschim+Vihar,+near+Aggarwal+Bikaneri+Sweets,+Delhi,+110087,+India/data=!4m2!3m1!1s0x390d046394e1d58b:0xd5b6b4af275f88f4",
        )
    }

    private fun locate(text: String, geocode: (String) -> List<Place> = { emptyList() }): Located? =
        MapLinks.read(text, delhi) { shares[it.substringBefore('?')] }?.let { LinkLocator.locate(it, db, geocode) }

    private fun check(short: String, name: String, at: LatLon, m: Double, atLeast: Trust = Trust.LIKELY) {
        val l = assertNotNull(locate("https://maps.app.goo.gl/$short?g_st=ac"), name)
        assertTrue(l.place.name.contains(name), "${l.place.name} should be $name")
        val d = Geo.dist(l.place.latLon, at)
        assertTrue(d < m, "$name is ${d.toInt()} m off (${l.trust})")
        assertTrue(l.trust <= atLeast, "$name: ${l.trust}")
    }

    @Test fun realShares() {
        check("wkMxVEbKk8gkp7Lj6", "Shree Durga Dosa Corner", LatLon(28.66118, 77.08681), 60.0, Trust.EXACT)
        check("b67sCEwG4Qj62sJW6", "Wenger", LatLon(28.63338, 77.21807), 120.0, Trust.EXACT)
        check("Tzo8isMVXxGB4T229", "Dolma Aunty", LatLon(28.569207, 77.240611), 120.0, Trust.EXACT)
        check("ARxRbMUGK2QRLtDq5", "Radisson Blu", LatLon(28.667325, 77.091579), 120.0, Trust.EXACT)
        check("3Z4be7ChWmPQyudC9", "Max Super Speciality Hospital", LatLon(28.7277, 77.1527), 400.0)
    }

    /** Shops we don't have at all. The first one's id points six kilometres away. */
    @Test fun shopsNotInOurList() {
        val national = LatLon(28.6745, 77.0925)
        val u = assertNotNull(locate("https://maps.app.goo.gl/ZvHySQNpCs6gCah79"))
        assertTrue(u.place.name == "United Hardware And Interiors", u.place.name)
        assertTrue(u.trust == Trust.ROUGH, "a guess must not look like a pin")
        assertTrue(Geo.dist(u.place.latLon, national) < 1200, "${u.place.lat},${u.place.lon}")
        val an = assertNotNull(locate("https://maps.app.goo.gl/BU6Bc7dNRuQxVx6K6"))
        assertTrue(Geo.dist(an.place.latLon, LatLon(28.66713, 77.10213)) < 400, "${an.place.lat},${an.place.lon} ${an.trust}")
        val k = assertNotNull(locate("https://maps.app.goo.gl/ANhJb2u1vw1rAK2w8"))
        assertTrue(Geo.dist(k.place.latLon, LatLon(28.66994, 77.10879)) < 500, "${k.place.lat},${k.place.lon} ${k.trust}")
    }

    /** Google's id for this one is a kilometre out; the door number in the address gives it away. */
    @Test fun outdatedIdIsCaught() = check("65TjvWLy66ajWvV56", "Sita Ram Diwan Chand", LatLon(28.642363, 77.210388), 150.0, Trust.EXACT)

    @Test fun coordinatesInLinksAreExact() {
        fun at(t: String) = assertNotNull(MapLinks.read(t, delhi) { null }?.point, t)
        for (t in listOf(
            "https://www.google.com/maps/place/Foo/@28.5,77.1,17z/data=!3m1!4b1!4m6!3m5!1s0x390d046394e1d58b:0xd5!8m2!3d28.661178!4d77.086807",
            "https://maps.google.com/?q=28.661178,77.086807", "https://www.google.com/maps/search/28.661178,+77.086807?entry=tts",
            "https://www.google.com/maps/dir/?api=1&destination=28.661178%2C77.086807", "geo:28.661178,77.086807", "geo:0,0?q=28.661178,77.086807(Dosa)",
            "https://maps.apple.com/?ll=28.661178,77.086807&q=Dosa", "28.661178, 77.086807", "28.661178° N, 77.086807° E", "  28.661178 77.086807 ",
            "https://www.openstreetmap.org/?mlat=28.661178&mlon=77.086807#map=19/28.66/77.08",
        )) {
            val p = at(t)
            assertTrue(Geo.dist(p, LatLon(28.661178, 77.086807)) < 2, t)
        }
        assertTrue(Geo.dist(at("https://www.google.com/maps/place/28%C2%B039'40.2%22N+77%C2%B005'12.5%22E/@28.6,77.0"), LatLon(28.661178, 77.086807)) < 10)
        assertEquals("Dosa", MapLinks.read("geo:0,0?q=28.661178,77.086807(Dosa)", delhi) { null }!!.name)
    }

    @Test fun nameBeforeTheLink() {
        val l = MapLinks.read("Meet here: Ravi's shop\nhttps://maps.google.com/?q=28.66,77.08", delhi) { null }!!
        assertEquals("Meet here: Ravi's shop", l.name)
    }

    @Test fun plusCodes() {
        // Reference values from the Open Location Code test data and a hand-checked Delhi one.
        assertTrue(Geo.dist(MapLinks.plusCode("8FVC9G8F+6X", delhi)!!, LatLon(47.365625, 8.524938)) < 10)
        val p = LatLon(28.661178, 77.086807)
        val full = "7JWVM36P+FP"
        val c = MapLinks.plusCode(full, delhi)!!
        assertTrue(Geo.dist(c, p) < 25, "${c.lat},${c.lon}")
        assertTrue(Geo.dist(MapLinks.plusCode("M36P+FP", delhi)!!, p) < 25)
        assertTrue(Geo.dist(MapLinks.read("M36P+FP New Delhi, Delhi", delhi) { null }!!.point!!, p) < 25)
        assertNull(MapLinks.plusCode("ABCD+12", delhi))
    }

    @Test fun ordinaryTextIsASearch() {
        for (t in listOf("durga dosa corner", "gh 14 paschim vihar", "sector 12", "c++ classes", "https://example.com/menu", "shop 28.5 sqft"))
            assertTrue(!MapLinks.looksLikeOne(t), t)
        for (t in listOf("https://maps.app.goo.gl/abc", "M36P+FP New Delhi", "28.6612, 77.0868", "geo:0,0?q=x", "Dosa https://goo.gl/maps/xyz"))
            assertTrue(MapLinks.looksLikeOne(t), t)
    }

    @Test fun brokenLinksDoNotCrash() {
        for (t in listOf("https://maps.app.goo.gl/dead", "https://www.google.com/maps", "geo:", "geo:abc", "https://www.google.com/maps/place/", "0x", ""))
            MapLinks.read(t, delhi) { null }?.let { LinkLocator.locate(it, db) { emptyList() } }
        assertNull(MapLinks.cell("0xzz"))
    }

    @Test fun doubtfulPlacesAreOnlyFoundBySearch() {
        var shown = 0
        db.inBox(28.655, 77.08, 28.685, 77.125, 5) { if (db.rank(it) > 5) shown++ }
        assertEquals(0, shown)
        val n = (0 until db.size).count { db.rank(it) >= PlaceDb.UNVERIFIED }
        assertTrue(n > 30_000)
        val i = (0 until db.size).first { db.rank(it) == PlaceDb.UNVERIFIED }
        assertTrue(db.place(i).label.endsWith("unverified"))
    }
}
