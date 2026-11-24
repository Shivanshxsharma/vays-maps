package com.shivansh.raasta.tools

import com.shivansh.raasta.core.Delhi
import com.shivansh.raasta.core.Geo
import com.shivansh.raasta.core.LatLon
import com.shivansh.raasta.core.RoadClass
import com.shivansh.raasta.core.RoadTiles
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Cuts Delhi's drivable roads out of an OSM .pbf extract into ~1 km tiles (tiles/<row>_<col>.json.gz)
 * that the app downloads along a route. Run once per data refresh; the output is what goes on GitHub.
 */
object TileCutter {
    private val KEEP_TAGS = setOf(
        "highway", "name", "name:en", "ref", "oneway", "bridge", "tunnel", "layer", "junction", "lanes",
        "maxspeed", "destination", "destination:en", "service", "access", "motor_vehicle", "motorcar", "area",
    )
    private val ROADS = Regex("^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service)(_link)?$")
    private const val MARGIN = 0.03

    fun run(pbf: File, outDir: File, cityFile: File = File("app/src/main/assets/city.map")) {
        val t0 = System.currentTimeMillis()
        val extras = MapExtras()
        // Relations come last in the file but say which ways matter (metro tracks, river outlines): read them first.
        PbfReader(pbf).read(object : PbfReader.Sink {
            override fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) {}
            override fun way(id: Long, refs: LongArray, tags: Map<String, String>) {}
            override fun relation(id: Long, tags: Map<String, String>, members: LongArray, types: IntArray, roles: List<String>) =
                extras.relation(tags, members, types, roles)
        }, wantWays = false, wantNodes = false, wantRelations = true)
        val cityRoads = ArrayList<MapExtras.CityRoadSrc>()
        val ids = LongArrayBuilder()
        val lats = IntArrayBuilder()
        val lons = IntArrayBuilder()
        val signals = HashSet<Long>()
        class Lm(val name: String, val kind: String, val lat: Double, val lon: Double)
        val landmarks = ArrayList<Lm>()
        class W(val id: Long, val refs: LongArray, val tags: Map<String, String>)
        val ways = ArrayList<W>()
        var sorted = true
        var lastId = Long.MIN_VALUE
        var nodeIndex: LongArray? = null
        var latArr = IntArray(0)
        var lonArr = IntArray(0)
        fun locate(id: Long): LatLon? {
            val i = java.util.Arrays.binarySearch(nodeIndex ?: return null, id)
            return if (i < 0) null else LatLon(latArr[i] / 1e7, lonArr[i] / 1e7)
        }

        PbfReader(pbf).read(object : PbfReader.Sink {
            override fun node(id: Long, lat: Double, lon: Double, tags: Map<String, String>) {
                if (lat < Delhi.MIN_LAT - MARGIN || lat > Delhi.MAX_LAT + MARGIN || lon < Delhi.MIN_LON - MARGIN || lon > Delhi.MAX_LON + MARGIN) return
                if (id <= lastId) sorted = false
                lastId = id
                ids.add(id); lats.add(Math.round(lat * 1e7).toInt()); lons.add(Math.round(lon * 1e7).toInt())
                if (tags.isEmpty()) return
                extras.node(id, lat, lon, tags)
                if (tags["highway"] == "traffic_signals" || tags["crossing"] == "traffic_signals") signals.add(id)
                val name = tags["name:en"] ?: tags["name"]
                if (tags["railway"] == "station" && name != null) {
                    val metro = tags["station"] == "subway" || tags["subway"] == "yes" || tags["network"]?.contains("Delhi Metro", true) == true
                    landmarks.add(Lm(name, if (metro) "metro" else "station", lat, lon))
                } else if (tags["amenity"] == "fuel") {
                    landmarks.add(Lm(tags["brand"] ?: name ?: "", "fuel", lat, lon))
                }
            }

            override fun way(id: Long, refs: LongArray, tags: Map<String, String>) {
                // Nodes come before ways in a PBF, so the Delhi node list is complete by now.
                val known = nodeIndex ?: ids.toArray().also { nodeIndex = it; latArr = lats.toArray(); lonArr = lons.toArray() }
                val hw = tags["highway"]
                val road = hw != null && ROADS.matches(hw)
                if (!road && tags.isEmpty() && id !in extras.neededWays && id !in extras.metroWays) return
                if (refs.none { java.util.Arrays.binarySearch(known, it) >= 0 }) return
                if (id in extras.neededWays) extras.wayRefs[id] = refs
                if (!road || id in extras.metroWays) {
                    extras.way(id, refs.toList().mapNotNull { locate(it) }, refs.size > 3 && refs.first() == refs.last(), tags)
                }
                if (!road) return
                val cls = RoadClass.of(hw!!)
                if (cls != null && cls.rank >= RoadClass.TERTIARY.rank && !hw.endsWith("_link")) {
                    val pts = refs.toList().mapNotNull { locate(it) }
                    if (pts.size > 1) cityRoads.add(MapExtras.CityRoadSrc(cls, tags["name:en"] ?: tags["name"], pts))
                }
                ways.add(W(id, refs, tags.filterKeys { it in KEEP_TAGS }))
            }
        })
        check(sorted) { "PBF nodes are not sorted by id" }
        extras.assembleRelations { locate(it) }
        extras.dedupePois()
        extras.writeCity(cityFile, cityRoads)
        val nid = ids.toArray(); val nlat = lats.toArray(); val nlon = lons.toArray()
        println("Read ${nid.size} nodes in/near Delhi, ${ways.size} road ways, ${signals.size} signals, ${landmarks.size} landmarks " +
            "in ${(System.currentTimeMillis() - t0) / 1000}s")

        fun find(id: Long): Int = java.util.Arrays.binarySearch(nid, id)

        // Assign ways to tiles.
        val tileWays = HashMap<String, MutableList<Int>>()
        var kept = 0
        for ((wi, w) in ways.withIndex()) {
            val tiles = HashSet<String>()
            var prev = -1
            var inside = false
            for (r in w.refs) {
                val i = find(r)
                if (i < 0) continue
                val la = nlat[i] / 1e7; val lo = nlon[i] / 1e7
                if (Delhi.contains(la, lo)) inside = true
                tiles.add(RoadTiles.key(la, lo))
                if (prev >= 0) {
                    // Long segments may cross a tile without a node in it.
                    val pla = nlat[prev] / 1e7; val plo = nlon[prev] / 1e7
                    val d = Geo.dist(pla, plo, la, lo)
                    var s = 150.0
                    while (s < d) { val t = s / d; tiles.add(RoadTiles.key(pla + (la - pla) * t, plo + (lo - plo) * t)); s += 150.0 }
                }
                prev = i
            }
            if (!inside) continue
            kept++
            for (t in tiles) tileWays.getOrPut(t) { ArrayList() }.add(wi)
        }
        val tileLms = HashMap<String, MutableList<Lm>>()
        for (l in landmarks) if (Delhi.contains(l.lat, l.lon)) tileLms.getOrPut(RoadTiles.key(l.lat, l.lon)) { ArrayList() }.add(l)

        val tilePois = extras.pois.groupBy { RoadTiles.key(it.lat, it.lon) }
        // A park or lake goes into every tile its outline touches; the biggest ones are in the bundled city map instead.
        val tileAreas = HashMap<String, MutableList<MapExtras.Area>>()
        var areaCount = 0
        for (a in extras.areas) {
            val m2 = MapExtras.areaM2(a.pts)
            if (m2 < 900 || m2 > 2_500_000) continue
            val keysOf = HashSet<String>()
            for (p in a.pts) if (Delhi.contains(p)) keysOf.add(RoadTiles.key(p.lat, p.lon))
            if (keysOf.isEmpty()) continue
            areaCount++
            for (k in keysOf) tileAreas.getOrPut(k) { ArrayList() }.add(a)
        }
        println("Extras: ${extras.pois.size} places, $areaCount parks/water")
        val dir = File(outDir, "tiles").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        var bytes = 0L
        val keys = tileWays.keys.sorted()
        for (key in keys) {
            val wl = tileWays.getValue(key)
            val usedNodes = LinkedHashSet<Long>()
            for (wi in wl) for (r in ways[wi].refs) usedNodes.add(r)
            val sb = StringBuilder(64 * 1024)
            sb.append("{\"v\":1,\"n\":[")
            var first = true
            for (id in usedNodes) {
                val i = find(id)
                if (i < 0) continue
                if (!first) sb.append(',')
                first = false
                sb.append(id).append(',').append(nlat[i]).append(',').append(nlon[i]).append(',').append(if (id in signals) 1 else 0)
            }
            sb.append("],\"w\":[")
            wl.forEachIndexed { k, wi ->
                val w = ways[wi]
                if (k > 0) sb.append(',')
                sb.append('[').append(w.id).append(",[")
                w.refs.forEachIndexed { j, r -> if (j > 0) sb.append(','); sb.append(r) }
                sb.append("],{")
                w.tags.entries.forEachIndexed { j, (tk, tv) -> if (j > 0) sb.append(','); str(sb, tk); sb.append(':'); str(sb, tv) }
                sb.append("}]")
            }
            sb.append("],\"l\":[")
            tileLms[key].orEmpty().forEachIndexed { k, l ->
                if (k > 0) sb.append(',')
                sb.append('['); str(sb, l.name); sb.append(','); str(sb, l.kind)
                sb.append(',').append("%.6f".format(java.util.Locale.ROOT, l.lat)).append(',').append("%.6f".format(java.util.Locale.ROOT, l.lon)).append(']')
            }
            sb.append("],\"p\":[")
            tilePois[key].orEmpty().sortedBy { it.rank }.forEachIndexed { k, p -> if (k > 0) sb.append(','); MapExtras.poiJson(sb, p) }
            sb.append("],\"a\":[")
            tileAreas[key].orEmpty().forEachIndexed { k, a -> if (k > 0) sb.append(','); MapExtras.areaJson(sb, a.kind, Geo.simplify(a.pts, 3.0)) }
            sb.append("]}")
            val f = File(dir, "$key.json.gz")
            GZIPOutputStream(f.outputStream()).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            bytes += f.length()
        }
        val index = StringBuilder("{\"v\":1,\"size\":${RoadTiles.SIZE},\"source\":\"${pbf.name}\",\"created\":${System.currentTimeMillis()},\"tiles\":[")
        keys.forEachIndexed { i, k -> if (i > 0) index.append(','); index.append('"').append(k).append('"') }
        index.append("]}")
        File(outDir, "index.json").writeText(index.toString())
        println("Wrote ${keys.size} tiles ($kept roads), ${bytes / 1024 / 1024} MB total, in ${(System.currentTimeMillis() - t0) / 1000}s")
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append(String.format("\\u%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}

class IntArrayBuilder {
    private var a = IntArray(1024)
    var size = 0
        private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun toArray(): IntArray = a.copyOf(size)
}
