package com.shivansh.raasta.tools

import com.shivansh.raasta.core.AreaKind
import com.shivansh.raasta.core.Delhi
import com.shivansh.raasta.core.Geo
import com.shivansh.raasta.core.LatLon
import com.shivansh.raasta.core.MapCodec
import com.shivansh.raasta.core.PlaceKind
import com.shivansh.raasta.core.Poi
import com.shivansh.raasta.core.RoadClass
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.cos

/** Pulls the non-road parts of the map out of OSM: places, parks and water, metro lines and stations, area names. */
class MapExtras {
    class Area(val kind: AreaKind, val pts: List<LatLon>)
    class Station(val name: String, val lat: Double, val lon: Double)
    class RelMember(val id: Long, val kind: AreaKind)

    val pois = ArrayList<Poi>()
    /** Everything findable by name, including the small shops the map tiles leave out. */
    val all = ArrayList<SrcPlace>()
    /** Named residential pockets, blocks, societies. */
    val pockets = ArrayList<Pair<String, LatLon>>()
    val labels = ArrayList<Triple<String, Int, LatLon>>()
    val stations = ArrayList<Station>()
    val areas = ArrayList<Area>()

    /** way id -> (line name, colour) for metro tracks. */
    val metroWays = HashMap<Long, MutableSet<Pair<String, Int>>>()
    val metroGeometry = HashMap<Long, List<LatLon>>()

    /** Area relations (multipolygons): outer way ids. */
    val areaRelations = ArrayList<Pair<AreaKind, LongArray>>()
    val neededWays = HashSet<Long>()
    val wayRefs = HashMap<Long, LongArray>()

    // ------------------------------------------------ relations (first pass)

    fun relation(tags: Map<String, String>, members: LongArray, types: IntArray, roles: List<String>) {
        if (tags["type"] == "route" && tags["route"] in setOf("subway", "light_rail", "monorail")) {
            val net = tags["network"].orEmpty()
            if (net.contains("Jaipur", true)) return
            val name = when {
                net.contains("RapidX", true) -> "Namo Bharat (RRTS)"
                tags["ref"] == "RMGL" -> "Rapid Metro"
                else -> tags["name"].orEmpty().substringBefore('(').substringBefore(':').trim()
            }
            val color = parseColor(tags["colour"])
            for (i in members.indices) if (types[i] == 1 && !roles[i].startsWith("platform")) {
                metroWays.getOrPut(members[i]) { LinkedHashSet() }.add(name to color)
            }
            return
        }
        if (tags["type"] == "multipolygon") {
            val kind = areaKind(tags) ?: return
            val outer = members.indices.filter { types[it] == 1 && (roles[it] == "outer" || roles[it] == "") }.map { members[it] }.toLongArray()
            if (outer.isNotEmpty()) { areaRelations.add(kind to outer); outer.forEach { neededWays.add(it) } }
        }
    }

    private fun parseColor(c: String?): Int = when {
        c == null -> 0x9E9E9E
        c.startsWith("#") && c.length == 7 -> c.drop(1).toInt(16)
        c.equals("gray", true) || c.equals("grey", true) -> 0x2E9BDA // Rapid Metro is tagged "gray"; its trains are blue
        else -> 0x9E9E9E
    }

    private fun areaKind(t: Map<String, String>): AreaKind? = when {
        t["natural"] == "water" || t["waterway"] == "riverbank" || t["landuse"] in setOf("reservoir", "basin") -> AreaKind.WATER
        t["leisure"] in setOf("park", "garden", "golf_course", "nature_reserve") || t["natural"] == "wood" ||
            t["landuse"] in setOf("forest", "recreation_ground") -> AreaKind.GREEN
        else -> null
    }

    // ------------------------------------------------ nodes and ways (second pass)

    fun node(id: Long, lat: Double, lon: Double, t: Map<String, String>) {
        if (t.isEmpty()) return
        val name = nameOf(t)
        if (t["railway"] == "station" && name != null) {
            val metro = t["station"] in setOf("subway", "light_rail") || t["subway"] == "yes" ||
                t["network"].orEmpty().let { it.contains("Metro", true) || it.contains("RapidX", true) }
            if (metro) { stations.add(Station(cleanStation(name), lat, lon)); return }
        }
        if (name != null) when (t["place"]) {
            "city", "town" -> labels.add(Triple(name, 0, LatLon(lat, lon)))
            "suburb" -> labels.add(Triple(name, 1, LatLon(lat, lon)))
            "village", "neighbourhood", "quarter" -> labels.add(Triple(name, 2, LatLon(lat, lon)))
        }
        findable(t, lat, lon)
        place(t, lat, lon)
    }

    fun way(id: Long, pts: List<LatLon>, closed: Boolean, t: Map<String, String>) {
        if (id in metroWays) metroGeometry[id] = pts
        if (t.isEmpty() || pts.size < 3) return
        if (closed) areaKind(t)?.let { areas.add(Area(it, pts)) }
        // Big places are mapped as building outlines: use their middle.
        if (t["highway"] == null) {
            val la = pts.sumOf { it.lat } / pts.size; val lo = pts.sumOf { it.lon } / pts.size
            findable(t, la, lo)
            place(t, la, lo)
        }
    }

    private fun findable(t: Map<String, String>, lat: Double, lon: Double) {
        if (!Delhi.contains(lat, lon)) return
        val name = nameOf(t) ?: return
        if (t["landuse"] == "residential" || t["place"] in setOf("city_block", "plot", "locality", "quarter") || t["building"] in setOf("apartments", "residential")) {
            pockets.add(name to LatLon(lat, lon))
            return
        }
        val key = listOf("amenity", "shop", "tourism", "historic", "leisure", "office", "healthcare", "craft").firstOrNull { it in t } ?: return
        if (t["railway"] != null || t["amenity"] in setOf("bench", "parking_space", "waste_basket", "toilets", "drinking_water", "shelter", "vending_machine")) return
        val v = t.getValue(key)
        if (v == "yes" && key != "shop") return
        var kind = PlaceKind.of(key, v, name, t)
        if (kind == PlaceKind.METRO || kind == PlaceKind.METRO_GATE) return
        if (key == "shop") kind = when (v) {
            "mall", "department_store" -> PlaceKind.MALL
            "car_repair", "motorcycle_repair", "tyres", "car_parts", "motorcycle", "car" -> PlaceKind.GARAGE
            "hairdresser", "beauty", "massage" -> PlaceKind.SALON
            "chemist", "medical_supply" -> PlaceKind.PHARMACY
            "bakery", "confectionery", "pastry", "tea", "coffee" -> PlaceKind.CAFE
            else -> PlaceKind.SHOP
        }
        if (key == "healthcare") kind = PlaceKind.HOSPITAL
        if (key == "office") kind = PlaceKind.OFFICE
        if (key == "craft") kind = PlaceKind.SHOP
        if (v in setOf("fitness_centre", "sports_centre", "gym")) kind = PlaceKind.GYM
        if (v == "stadium") kind = PlaceKind.LANDMARK
        val rank = when {
            "wikidata" in t || "wikipedia" in t -> 1
            kind == PlaceKind.OFFICE || kind == PlaceKind.OTHER -> 4
            else -> 3
        }
        val addr = listOfNotNull(t["addr:housenumber"], t["addr:street"], t["addr:suburb"] ?: t["addr:neighbourhood"]).joinToString(", ")
        val label = when {
            v == "yes" || kind == PlaceKind.METRO -> kind.label
            key == "shop" && kind == PlaceKind.SHOP -> v.replace('_', ' ').replaceFirstChar { it.uppercase() } + " shop"
            else -> kind.label
        }
        all.add(SrcPlace(name, kind, label, lat, lon, rank, 0.85, addr, true, altOf(t, name)))
    }

    /** Short and other names; Hindi too, so it can be typed. */
    private fun altOf(t: Map<String, String>, name: String): String =
        listOfNotNull(t["short_name"], t["alt_name"], t["old_name"], t["name"], t["name:hi"], t["official_name"])
            .flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotEmpty() && it != name && it.length <= 50 }.distinct().joinToString(" ").take(120).replace('\t', ' ')

    val alts = HashMap<String, String>()

    private fun nameOf(t: Map<String, String>) = (t["name:en"] ?: t["name"])?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 && READABLE.containsMatchIn(it) && !JUNK.containsMatchIn(it) }

    /** Latin or Devanagari, so every label can be read here; and no jokes or smears. */
    private val READABLE = Regex("[A-Za-z0-9\u0900-\u097F]")
    private val JUNK = Regex("(?i)red ?lights? (district|area)|^(test|asdf|xyz|unknown|none|my (home|house))$")

    private fun cleanStation(n: String) = n.replace(Regex("(?i)\\s*(delhi\\s+)?metro\\s+station.*$"), "").replace(Regex("\\s*\\(.*\\)\\s*$"), "").trim()

    private fun place(t: Map<String, String>, lat: Double, lon: Double) {
        if (!Delhi.contains(lat, lon)) return
        val key = listOf("railway", "aeroway", "amenity", "shop", "tourism", "historic", "leisure").firstOrNull { it in t } ?: return
        if (key == "railway" && t["railway"] !in setOf("station", "subway_entrance")) return
        if (key == "shop" && t["shop"] !in setOf("mall", "department_store", "supermarket")) return
        if (key == "aeroway" && t["aeroway"] !in setOf("aerodrome", "terminal")) return
        if (key == "historic" && t["historic"] in setOf("yes", "boundary_stone", "milestone")) return
        if (key == "leisure" && t["leisure"] !in setOf("park", "garden", "stadium", "golf_course")) return
        var name = nameOf(t)
        var kind = PlaceKind.of(key, t[key], name.orEmpty(), t)
        if (t["leisure"] == "stadium") kind = PlaceKind.LANDMARK
        if (kind == PlaceKind.OTHER || kind == PlaceKind.METRO || kind == PlaceKind.ROAD) return
        if (name == null) name = when (kind) {
            PlaceKind.FUEL -> t["brand"] ?: t["operator"] ?: "Petrol pump"
            PlaceKind.METRO_GATE -> t["ref"]?.let { "Gate $it" } ?: return
            PlaceKind.BANK -> t["brand"] ?: t["operator"] ?: return
            else -> return
        }
        if (kind == PlaceKind.FUEL && t["amenity"] == "charging_station") return
        val famous = "wikidata" in t || "wikipedia" in t
        var rank = when (kind) {
            PlaceKind.AIRPORT, PlaceKind.RAIL -> 0
            PlaceKind.MALL -> if (t["shop"] == "mall") 1 else 3
            PlaceKind.HOSPITAL -> if (t["amenity"] == "hospital") 1 else 3
            PlaceKind.LANDMARK -> if (famous || t["tourism"] == "museum" || t["leisure"] == "stadium") 1 else 2
            PlaceKind.BUS, PlaceKind.MARKET -> 1
            PlaceKind.FUEL, PlaceKind.METRO_GATE, PlaceKind.PARK, PlaceKind.CINEMA, PlaceKind.POLICE -> 2
            PlaceKind.EDUCATION -> if (t["amenity"] in setOf("university", "college")) 1 else 3
            PlaceKind.HOTEL, PlaceKind.WORSHIP -> 2
            else -> 3
        }
        if (famous && rank > 0) rank--
        if (t["tourism"] == "artwork" || t["historic"] == "memorial" && !famous) rank = 3
        altOf(t, name).let { if (it.isNotEmpty()) alts[name] = it }
        pois.add(Poi(name, kind, lat, lon, rank))
    }

    // ------------------------------------------------ finishing

    /** Same place mapped twice (a point and its building): keep one. */
    fun dedupePois() {
        val seen = HashMap<String, MutableList<Poi>>()
        val keep = ArrayList<Poi>()
        for (p in pois.sortedBy { it.rank }) {
            val l = seen.getOrPut(p.name.lowercase() + "|" + p.kind) { ArrayList() }
            if (l.any { Geo.dist(it.lat, it.lon, p.lat, p.lon) < 120 }) continue
            l.add(p); keep.add(p)
        }
        pois.clear(); pois.addAll(keep)
    }

    /** Join the outer ways of multipolygons (the Yamuna, the Ridge forest) into closed rings. */
    fun assembleRelations(locate: (Long) -> LatLon?) {
        for ((kind, outer) in areaRelations) {
            val parts = outer.toList().mapNotNull { wayRefs[it] }.filter { it.size > 1 }.map { it.toList() }.toMutableList()
            while (parts.isNotEmpty()) {
                var ring = parts.removeAt(parts.size - 1)
                var grew = true
                while (grew && ring.first() != ring.last()) {
                    grew = false
                    for (i in parts.indices) {
                        val p = parts[i]
                        val joined = when {
                            p.first() == ring.last() -> ring + p.drop(1)
                            p.last() == ring.last() -> ring + p.reversed().drop(1)
                            p.last() == ring.first() -> p + ring.drop(1)
                            p.first() == ring.first() -> p.reversed() + ring.drop(1)
                            else -> null
                        } ?: continue
                        ring = joined; parts.removeAt(i); grew = true
                        break
                    }
                }
                val pts = ring.mapNotNull(locate)
                if (pts.size >= 4) areas.add(Area(kind, pts))
            }
        }
    }

    class Line(val name: String, val color: Int, val tracks: List<List<LatLon>>)

    fun metroLines(): List<Line> {
        val by = LinkedHashMap<String, Pair<Int, MutableList<List<LatLon>>>>()
        for ((id, lines) in metroWays) {
            val pts = metroGeometry[id] ?: continue
            if (pts.none { Delhi.contains(it) }) continue
            for ((name, color) in lines) by.getOrPut(name) { color to ArrayList() }.second.add(Geo.simplify(pts, 4.0))
        }
        return by.map { (n, v) -> Line(n, v.first, v.second) }.sortedBy { it.name }
    }

    class MergedStation(val name: String, val lat: Double, val lon: Double, val lines: List<Int>)

    /** One entry per station, with every line whose track passes it. */
    fun mergedStations(lines: List<Line>): List<MergedStation> {
        val groups = ArrayList<MutableList<Station>>()
        for (s in stations.filter { Delhi.contains(it.lat, it.lon) }) {
            val g = groups.firstOrNull { it[0].name.equals(s.name, true) && Geo.dist(it[0].lat, it[0].lon, s.lat, s.lon) < 700 }
            if (g != null) g.add(s) else groups.add(mutableListOf(s))
        }
        return groups.map { g ->
            val on = lines.indices.filter { li ->
                g.any { s -> lines[li].tracks.any { tr -> near(tr, s.lat, s.lon) < 130 } }
            }
            MergedStation(g[0].name, g.sumOf { it.lat } / g.size, g.sumOf { it.lon } / g.size, on)
        }.filter { it.lines.isNotEmpty() }
    }

    private fun near(l: List<LatLon>, lat: Double, lon: Double): Double {
        var best = Double.MAX_VALUE
        for (i in 0 until l.size - 1) best = minOf(best, Geo.project(lat, lon, l[i].lat, l[i].lon, l[i + 1].lat, l[i + 1].lon).dist)
        return best
    }

    companion object {
        fun areaM2(p: List<LatLon>): Double {
            val k = cos(Math.toRadians(p[0].lat)) * 111_320.0
            var s = 0.0
            for (i in p.indices) {
                val a = p[i]; val b = p[(i + 1) % p.size]
                s += (a.lon * k) * (b.lat * 111_320.0) - (b.lon * k) * (a.lat * 111_320.0)
            }
            return abs(s) / 2
        }

        fun json(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
            sb.append('"')
        }

        fun poiJson(sb: StringBuilder, p: Poi) {
            sb.append('['); json(sb, p.name); sb.append(',').append('"').append(p.kind.name).append('"')
            sb.append(',').append(Math.round(p.lat * 1e5)).append(',').append(Math.round(p.lon * 1e5)).append(',').append(p.rank).append(']')
        }

        fun areaJson(sb: StringBuilder, kind: AreaKind, pts: List<LatLon>) {
            sb.append("[\"").append(if (kind == AreaKind.WATER) 'W' else 'G').append("\",")
            MapCodec.encode(DoubleArray(pts.size) { pts[it].lat }, DoubleArray(pts.size) { pts[it].lon }, sb)
            sb.append(']')
        }
    }

    class CityRoadSrc(val cls: RoadClass, val name: String?, val pts: List<LatLon>)

    /** The bundled whole-Delhi overview (app/src/main/assets/city.map). */
    fun writeCity(file: File, roads: List<CityRoadSrc>) {
        val sb = StringBuilder(2_000_000)
        val keep = roads.filter { r -> r.pts.any { Delhi.contains(it) } }
        sb.append("{\"v\":1,\"r\":[")
        keep.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            val s = Geo.simplify(r.pts, if (r.cls.rank >= RoadClass.PRIMARY.rank) 7.0 else 12.0)
            sb.append('[').append(r.cls.ordinal).append(','); json(sb, r.name.orEmpty()); sb.append(',')
            MapCodec.encode(DoubleArray(s.size) { s[it].lat }, DoubleArray(s.size) { s[it].lon }, sb); sb.append(']')
        }
        sb.append("],\"a\":[")
        var first = true
        for (a in areas) {
            if (areaM2(a.pts) < 60_000 || a.pts.none { Delhi.contains(it) }) continue
            if (!first) sb.append(','); first = false
            areaJson(sb, a.kind, Geo.simplify(a.pts, 14.0))
        }
        sb.append("],\"m\":[")
        val lines = metroLines()
        lines.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append('['); json(sb, l.name); sb.append(',').append(l.color).append(",[")
            l.tracks.forEachIndexed { k, t ->
                if (k > 0) sb.append(',')
                MapCodec.encode(DoubleArray(t.size) { t[it].lat }, DoubleArray(t.size) { t[it].lon }, sb)
            }
            sb.append("]]")
        }
        sb.append("],\"s\":[")
        val st = mergedStations(lines)
        st.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append('['); json(sb, s.name)
            sb.append(',').append(Math.round(s.lat * 1e5)).append(',').append(Math.round(s.lon * 1e5)).append(",[").append(s.lines.joinToString(",")).append("]]")
        }
        sb.append("],\"l\":[")
        val lab = labels.filter { Delhi.contains(it.third) }.distinctBy { it.first.lowercase() + it.third.lat.toInt() }
        lab.forEachIndexed { i, l ->
            if (i > 0) sb.append(',')
            sb.append('['); json(sb, l.first)
            sb.append(',').append(l.second).append(',').append(Math.round(l.third.lat * 1e5)).append(',').append(Math.round(l.third.lon * 1e5)).append(']')
        }
        sb.append("],\"p\":[")
        val top = pois.filter { it.rank == 0 }
        top.forEachIndexed { i, p -> if (i > 0) sb.append(','); poiJson(sb, p) }
        sb.append("]}")
        file.parentFile.mkdirs()
        GZIPOutputStream(file.outputStream()).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
        println("City map: ${keep.size} roads, ${lines.size} metro lines (${lines.joinToString { it.name }}), ${st.size} stations " +
            "(${st.count { it.lines.size > 1 }} interchanges), ${lab.size} area names, ${top.size} top places, ${file.length() / 1024} KB")
    }
}
