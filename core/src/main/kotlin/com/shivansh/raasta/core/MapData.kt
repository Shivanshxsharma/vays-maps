package com.shivansh.raasta.core

import java.io.InputStream
import java.util.zip.GZIPInputStream

/** A named place drawn on the map. [rank] 0 = shown first when zooming in, 3 = only up close. */
class Poi(val name: String, val kind: PlaceKind, val lat: Double, val lon: Double, val rank: Int)

enum class AreaKind { WATER, GREEN }

/** A filled shape under the roads: river, lake, park, forest. */
class AreaShape(val kind: AreaKind, val lat: DoubleArray, val lon: DoubleArray)

class Polyline(val lat: DoubleArray, val lon: DoubleArray) {
    val size get() = lat.size
}

class MetroLine(val name: String, val color: Int, val tracks: List<Polyline>)

class MetroStation(val name: String, val lat: Double, val lon: Double, val lines: List<Int>) {
    val interchange get() = lines.size > 1
}

class CityRoad(val cls: RoadClass, val name: String?, val line: Polyline)

/** Name of an area on the map. [rank] 0 = district/town, 1 = suburb, 2 = neighbourhood. */
class AreaLabel(val name: String, val rank: Int, val lat: Double, val lon: Double)

/**
 * The whole of Delhi, simplified, shipped inside the app: main roads, river and big parks, area names, every metro
 * line and station. It is what you see zoomed out, with no internet and nothing downloaded.
 */
class CityMap(
    val roads: List<CityRoad>,
    val areas: List<AreaShape>,
    val metro: List<MetroLine>,
    val stations: List<MetroStation>,
    val labels: List<AreaLabel>,
    val places: List<Poi>,
) {
    fun nearestStation(lat: Double, lon: Double): Pair<MetroStation, Double>? =
        stations.map { it to Geo.dist(lat, lon, it.lat, it.lon) }.minByOrNull { it.second }

    /** "Patel Nagar, 300 m from Shadipur metro": where you are, worked out with no internet. */
    fun describe(lat: Double, lon: Double): String {
        val area = labels.map { it to Geo.dist(lat, lon, it.lat, it.lon) }
            .filter { it.second < 1800 }
            .minByOrNull { it.second * (if (it.first.rank == 2) 1.0 else 1.25) }?.first?.name
        val metro = nearestStation(lat, lon)?.takeIf { it.second < 2500 }?.let { (s, d) ->
            if (area == null) "Near ${s.name} metro, ${Phrases.shortDistance(d)}" else if (d < 80) "at ${s.name} metro" else "${Phrases.shortDistance(d)} from ${s.name} metro"
        }
        return listOfNotNull(area, metro).joinToString(", ")
            .ifEmpty { "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon) }
    }

    private class Entry(val place: Place, val low: String, val words: List<String>, val rank: Int)

    private val entries: List<Entry> by lazy {
        fun e(p: Place, rank: Int) = p.name.lowercase().let { n -> Entry(p, n, n.split(' ', '-', '(', ',').filter { it.isNotEmpty() }, rank) }
        stations.map { s -> e(Place(s.name, s.lines.joinToString(" · ") { metro[it].name }, s.lat, s.lon, PlaceKind.METRO), 0) } +
            places.map { p -> e(Place(p.name, areaNear(p.lat, p.lon).orEmpty(), p.lat, p.lon, p.kind), 1) } +
            labels.map { l -> e(Place(l.name, "", l.lat, l.lon, PlaceKind.AREA), 2) }
    }

    fun areaNear(lat: Double, lon: Double): String? =
        labels.map { it to Geo.dist(lat, lon, it.lat, it.lon) }.filter { it.second < 1800 }.minByOrNull { it.second }?.first?.name

    /** Instant, offline suggestions while typing: metro stations, landmarks and areas. Every typed word must match. */
    fun suggest(query: String, limit: Int = 6): List<Place> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val wantMetro = Regex("\\bmetro\\b").containsMatchIn(q)
        val toks = q.split(' ').filter { it.isNotEmpty() && it != "metro" && it != "station" }
        if (toks.isEmpty()) return emptyList()
        return entries.mapNotNull { e ->
            if (wantMetro && e.place.kind != PlaceKind.METRO) return@mapNotNull null
            if (!toks.all { t -> e.words.any { it.startsWith(t) } }) return@mapNotNull null
            val score = (if (e.low == q) 0 else if (e.low.startsWith(toks[0])) 1 else 2) * 10 + e.rank
            e to score
        }.sortedWith(compareBy({ it.second }, { it.first.low.length })).map { it.first.place }
            .distinctBy { it.name.lowercase() to it.kind }.take(limit)
    }

    companion object {
        fun read(input: InputStream): CityMap =
            parse(GZIPInputStream(input).readBytes().toString(Charsets.UTF_8))

        fun parse(json: String): CityMap {
            val m = Json.parse(json).asMap()
            fun line(v: Any?): Polyline = MapCodec.coords(v).let { Polyline(it.first, it.second) }
            fun int(v: Any?) = (v as Double).toInt()
            fun deg(v: Any?) = (v as Double) / 1e5
            return CityMap(
                roads = m["r"].asList().map { r ->
                    val a = r.asList()
                    CityRoad(RoadClass.entries[int(a[0])], (a[1] as String).ifEmpty { null }, line(a[2]))
                },
                areas = m["a"].asList().map { MapCodec.area(it) },
                metro = m["m"].asList().map { r ->
                    val a = r.asList()
                    MetroLine(a[0] as String, int(a[1]) or (0xFF shl 24), a[2].asList().map { line(it) })
                },
                stations = m["s"].asList().map { r ->
                    val a = r.asList()
                    MetroStation(a[0] as String, deg(a[1]), deg(a[2]), a[3].asList().map { int(it) })
                },
                labels = m["l"].asList().map { r ->
                    val a = r.asList()
                    AreaLabel(a[0] as String, int(a[1]), deg(a[2]), deg(a[3]))
                },
                places = m["p"].asList().mapNotNull { MapCodec.poi(it) },
            )
        }
    }
}

/** Shared compact encoding: coordinates as delta-coded 1e-5 degrees (about a metre). */
object MapCodec {
    fun encode(lat: DoubleArray, lon: DoubleArray, sb: StringBuilder) {
        var pa = 0L
        var po = 0L
        sb.append('[')
        for (i in lat.indices) {
            val a = Math.round(lat[i] * 1e5)
            val o = Math.round(lon[i] * 1e5)
            if (i > 0) sb.append(',')
            sb.append(a - pa).append(',').append(o - po)
            pa = a; po = o
        }
        sb.append(']')
    }

    fun coords(v: Any?): Pair<DoubleArray, DoubleArray> {
        val l = v.asList()
        val n = l.size / 2
        val lat = DoubleArray(n)
        val lon = DoubleArray(n)
        var a = 0.0
        var o = 0.0
        for (i in 0 until n) {
            a += l[2 * i] as Double
            o += l[2 * i + 1] as Double
            lat[i] = a / 1e5; lon[i] = o / 1e5
        }
        return lat to lon
    }

    fun area(v: Any?): AreaShape {
        val a = v.asList()
        val (lat, lon) = coords(a[1])
        return AreaShape(if (a[0] == "W") AreaKind.WATER else AreaKind.GREEN, lat, lon)
    }

    fun poi(v: Any?): Poi? {
        val a = v.asList()
        val kind = PlaceKind.entries.firstOrNull { it.name == a[1] } ?: return null
        return Poi(a[0] as String, kind, (a[2] as Double) / 1e5, (a[3] as Double) / 1e5, (a[4] as Double).toInt())
    }
}

/** One road as it is drawn: a polyline with what the renderer needs to style and label it. */
class RoadShape(
    val cls: RoadClass, val link: Boolean, val bridge: Boolean, val tunnel: Boolean,
    val name: String?, val line: Polyline,
)

/** Everything to draw for one ~1.1 km tile, clipped so neighbouring tiles don't draw the same road twice. */
class TileShapes(val roads: List<RoadShape>, val pois: List<Poi>, val areas: List<AreaShape>) {
    companion object {
        private val SKIP = setOf("parking_aisle", "driveway", "drive-through", "alley")

        fun of(key: String, d: OsmData): TileShapes {
            val roads = ArrayList<RoadShape>()
            for ((nodes, t) in d.ways.values) {
                val hw = t["highway"] ?: continue
                val cls = RoadClass.of(hw) ?: continue
                if (hw == "service" && t["service"] in SKIP) continue
                val bridge = t["bridge"].let { it != null && it != "no" }
                val tunnel = t["tunnel"].let { it != null && it != "no" } || (t["layer"]?.toIntOrNull() ?: 0) < 0
                val name = t["name:en"] ?: t["name"]
                var la = ArrayList<Double>()
                var lo = ArrayList<Double>()
                fun flush() {
                    if (la.size > 1) {
                        roads.add(RoadShape(cls, hw.endsWith("_link"), bridge, tunnel, name, Polyline(la.toDoubleArray(), lo.toDoubleArray())))
                    }
                    la = ArrayList(); lo = ArrayList()
                }
                for (i in 0 until nodes.size - 1) {
                    val a1 = d.lat[nodes[i]]
                    val a2 = d.lat[nodes[i + 1]]
                    if (a1 == null || a2 == null) { flush(); continue }
                    val o1 = d.lon.getValue(nodes[i])
                    val o2 = d.lon.getValue(nodes[i + 1])
                    // A segment belongs to the tile its middle is in.
                    if (RoadTiles.key((a1 + a2) / 2, (o1 + o2) / 2) != key) { flush(); continue }
                    if (la.isEmpty()) { la.add(a1); lo.add(o1) }
                    la.add(a2); lo.add(o2)
                }
                flush()
            }
            return TileShapes(roads, d.pois, d.areas)
        }
    }
}
