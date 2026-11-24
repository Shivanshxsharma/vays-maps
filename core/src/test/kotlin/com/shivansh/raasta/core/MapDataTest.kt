package com.shivansh.raasta.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MapDataTest {
    private fun city(): CityMap = File("../app/src/main/assets/city.map").inputStream().use { CityMap.read(it) }

    @Test
    fun metroLinesAndInterchangesAreRight() {
        val c = city()
        fun lines(n: String) = c.stations.first { it.name == n }.lines.map { c.metro[it].name }.toSet()
        assertEquals(setOf("Blue Line", "Yellow Line"), lines("Rajiv Chowk"))
        assertEquals(setOf("Red Line", "Violet Line", "Yellow Line"), lines("Kashmere Gate"))
        assertEquals(setOf("Blue Line"), lines("Shadipur"))
        assertTrue(c.stations.size > 200)
        assertEquals(c.stations.size, c.stations.map { it.name }.toSet().size, "no station twice")
        assertTrue(c.metro.all { it.tracks.isNotEmpty() })
    }

    @Test
    fun saysWhereYouAreWithoutInternet() {
        val c = city()
        assertTrue(c.describe(28.6515, 77.1583).endsWith("at Shadipur metro"), c.describe(28.6515, 77.1583))
        assertTrue("Rajiv Chowk metro" in c.describe(28.6315, 77.2167), c.describe(28.6315, 77.2167))
        // Nowhere near anything: plain coordinates, never a wrong name.
        assertEquals("27.17510, 78.04210", c.describe(27.1751, 78.0421))
    }

    @Test
    fun suggestsOfflineWhileTyping() {
        val c = city()
        assertEquals("Shadipur" to PlaceKind.METRO, c.suggest("shadipur metro").first().let { it.name to it.kind })
        assertEquals(PlaceKind.METRO, c.suggest("hauz").first().kind)
        assertTrue(c.suggest("khas hauz").any { it.name == "Hauz Khas" }, "word order doesn't matter")
        assertTrue(c.suggest("zzzz").isEmpty())
        assertTrue(c.suggest("h").isEmpty())
    }

    private val tileJson = """{"v":1,"n":[1,286450000,771550000,0,2,286455000,771555000,1,3,286610000,771710000,0],
        "w":[[10,[1,2,3],{"highway":"residential","name":"Test Marg"}],[11,[1,2],{"highway":"service","service":"parking_aisle"}]],
        "l":[],"p":[["Dhaba","FOOD",2864520,7715520,3],["X","NOT_A_KIND",1,1,1]],"a":[["G",[2864500,7715500,10,0,0,10]]]}"""

    @Test
    fun tileHasPlacesParksAndOnlyItsOwnRoads() {
        val d = OsmData().also { it.addTile(tileJson) }
        assertEquals(listOf("Dhaba"), d.pois.map { it.name }, "unknown kinds from newer data are ignored")
        assertEquals(AreaKind.GREEN, d.areas.single().kind)
        val here = TileShapes.of(RoadTiles.key(28.645, 77.155), d)
        assertEquals(listOf(2), here.roads.map { it.line.size }, "clipped to the tile, parking aisle dropped")
        // Every segment is drawn by exactly one tile.
        val all = (2862..2868).flatMap { r -> (7713..7719).map { "${r}_$it" } }.sumOf { k -> TileShapes.of(k, d).roads.sumOf { it.line.size - 1 } }
        assertEquals(2, all)
    }

    @Test
    fun rideKeepsItsPlacesOnDisk() {
        val meta = PackMeta("t", "t", "a", "b", 1L, LatLon(28.6, 77.1), LatLon(28.7, 77.2), 1.0, 2.0, "", 0)
        val p = RoutePack(meta, longArrayOf(1), doubleArrayOf(28.6), doubleArrayOf(77.1), byteArrayOf(0), emptyList(), emptyList(), emptyList(),
            listOf(Poi("AIIMS", PlaceKind.HOSPITAL, 28.567, 77.21, 0)), listOf(AreaShape(AreaKind.WATER, doubleArrayOf(28.6, 28.61, 28.6), doubleArrayOf(77.1, 77.1, 77.11))))
        val out = ByteArrayOutputStream().also { PackIO.write(p, it) }
        val q = PackIO.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("AIIMS" to PlaceKind.HOSPITAL, q.pois.single().let { it.name to it.kind })
        assertEquals(AreaKind.WATER, q.areas.single().kind)
        assertEquals(28.61, q.areas.single().lat[1], 1e-5)
    }

    @Test
    fun ridesSavedByOlderVersionsStillOpen() {
        for (f in File("../testdata").listFiles { f -> f.name.endsWith(".rpk") }.orEmpty()) {
            val p = PackIO.read(f)
            assertNotNull(p.routes.firstOrNull(), f.name)
        }
    }

    @Test
    fun newDataReplacesTilesKeptFromOldData() {
        val web = Files.createTempDirectory("web").toFile()
        val cache = Files.createTempDirectory("cache").toFile()
        val key = RoadTiles.key(28.645, 77.155)
        File(web, "tiles").mkdirs()
        fun publish(json: String, stamp: Int) {
            File(web, "tiles/$key.json.gz").outputStream().use { o -> GZIPOutputStream(o).use { it.write(json.toByteArray()) } }
            File(web, "index.json").writeText("""{"v":1,"created":$stamp,"tiles":["$key"]}""")
        }
        fun tiles() = RoadTiles(Http(), listOf("file:${web.path}"), cache)
        publish(tileJson.replace("\"p\":[[\"Dhaba\"", "\"p\":[[\"Old\""), 1)
        assertEquals("Old", tiles().shapes(key)!!.pois.first().name)
        publish(tileJson, 2)
        assertEquals("Dhaba", tiles().shapes(key)!!.pois.first().name)
        // Published data gone (offline): what we have still works.
        web.deleteRecursively()
        assertEquals("Dhaba", tiles().shapes(key)!!.pois.first().name)
        assertEquals("Dhaba", tiles().shapes(key, allowNetwork = false)!!.pois.first().name)
    }
}
