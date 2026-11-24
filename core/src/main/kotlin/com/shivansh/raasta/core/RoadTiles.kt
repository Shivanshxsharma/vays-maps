package com.shivansh.raasta.core

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.floor

/** Raw OSM road data, merged from tiles or an Overpass answer. */
class OsmData {
    val lat = HashMap<Long, Double>()
    val lon = HashMap<Long, Double>()
    val signals = HashSet<Long>()
    val ways = LinkedHashMap<Long, Pair<LongArray, Map<String, String>>>()
    val landmarks = LinkedHashMap<String, Landmark>()
    val pois = ArrayList<Poi>()
    val areas = ArrayList<AreaShape>()

    fun addNode(id: Long, la: Double, lo: Double, signal: Boolean) {
        lat[id] = la; lon[id] = lo
        if (signal) signals.add(id)
    }

    fun addLandmark(l: Landmark) { landmarks["${l.kind}|${l.name}|${"%.4f".format(java.util.Locale.ROOT, l.lat)}"] = l }

    companion object {
        /** Overpass JSON (out body) -> OsmData. */
        fun fromOverpass(json: String): OsmData {
            val d = OsmData()
            for (e in Json.parse(json).asMap()["elements"].asList()) {
                val m = e.asMap()
                val tags = m["tags"].asMap().mapValues { it.value.toString() }
                when (m.s("type")) {
                    "node" -> {
                        val id = m.d("id").toLong()
                        d.addNode(id, m.d("lat"), m.d("lon"), tags["highway"] == "traffic_signals" || tags["crossing"] == "traffic_signals")
                        val name = tags["name:en"] ?: tags["name"]
                        if (tags["railway"] == "station" && name != null) {
                            val metro = tags["station"] == "subway" || tags["subway"] == "yes" || tags["network"]?.contains("Delhi Metro", true) == true
                            d.addLandmark(Landmark(name, if (metro) "metro" else "station", m.d("lat"), m.d("lon")))
                        } else if (tags["amenity"] == "fuel") {
                            d.addLandmark(Landmark(tags["brand"] ?: name ?: "", "fuel", m.d("lat"), m.d("lon")))
                        }
                    }
                    "way" -> d.ways[m.d("id").toLong()] = m["nodes"].asList().map { (it as Double).toLong() }.toLongArray() to tags
                }
            }
            return d
        }
    }

    /** Adds one road tile (format written by tools/TileCutter). */
    fun addTile(json: String) {
        val root = Json.parse(json).asMap()
        val n = root["n"].asList()
        var i = 0
        while (i + 3 < n.size) {
            addNode((n[i] as Double).toLong(), (n[i + 1] as Double) / 1e7, (n[i + 2] as Double) / 1e7, (n[i + 3] as Double) != 0.0)
            i += 4
        }
        for (w in root["w"].asList()) {
            val a = w.asList()
            val id = (a[0] as Double).toLong()
            if (id in ways) continue
            ways[id] = a[1].asList().map { (it as Double).toLong() }.toLongArray() to a[2].asMap().mapValues { it.value.toString() }
        }
        for (l in root["l"].asList()) {
            val a = l.asList()
            addLandmark(Landmark(a[0] as String, a[1] as String, a[2] as Double, a[3] as Double))
        }
        root["p"].asList().forEach { v -> MapCodec.poi(v)?.let { pois.add(it) } }
        root["a"].asList().forEach { v -> areas.add(MapCodec.area(v)) }
    }
}

/**
 * Delhi road data cut into ~1.1 km tiles and hosted as static files (GitHub). A route only needs the
 * handful of tiles it passes through, so nobody has to download all of Delhi.
 */
class RoadTiles(private val http: Http, private val bases: List<String>, private val cacheDir: File? = null) {
    companion object {
        const val SIZE = 0.01

        fun key(lat: Double, lon: Double) = "${floor(lat / SIZE).toInt()}_${floor(lon / SIZE).toInt()}"

        /** Tiles within [radius] metres of the polylines. */
        fun along(polylines: List<List<LatLon>>, radius: Double): Set<String> {
            val out = LinkedHashSet<String>()
            for (pl in polylines) forEachPoint(pl, 40.0) { la, lo ->
                val dLat = radius / 111_320.0
                val dLon = dLat / cos(Math.toRadians(la))
                for (a in -1..1) for (b in -1..1) out.add(key(la + a * dLat, lo + b * dLon))
            }
            return out
        }

        /** Tiles in a box around the straight line between two points (fallback when no route is known). */
        fun around(a: LatLon, b: LatLon, marginM: Double): Set<String> {
            val dLat = marginM / 111_320.0
            val dLon = dLat / cos(Math.toRadians(a.lat))
            val out = LinkedHashSet<String>()
            val r0 = floor((minOf(a.lat, b.lat) - dLat) / SIZE).toInt()
            val r1 = floor((maxOf(a.lat, b.lat) + dLat) / SIZE).toInt()
            val c0 = floor((minOf(a.lon, b.lon) - dLon) / SIZE).toInt()
            val c1 = floor((maxOf(a.lon, b.lon) + dLon) / SIZE).toInt()
            for (r in r0..r1) for (c in c0..c1) out.add("${r}_$c")
            return out
        }

        inline fun forEachPoint(pl: List<LatLon>, stepM: Double, fn: (Double, Double) -> Unit) {
            if (pl.isEmpty()) return
            fn(pl[0].lat, pl[0].lon)
            for (i in 1 until pl.size) {
                val a = pl[i - 1]; val b = pl[i]
                val d = Geo.dist(a, b)
                var s = stepM
                while (s < d) { val t = s / d; fn(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t); s += stepM }
                fn(b.lat, b.lon)
            }
        }
    }

    @Volatile private var known: Set<String>? = null

    init { cacheDir?.mkdirs() }

    /** Which tiles exist (index.json). Tiles outside this set have no roads, or are outside Delhi. */
    fun index(): Set<String> {
        known?.let { return it }
        // Offline, fall back to the last list we saw, so cached tiles still work.
        val cached = cacheDir?.let { File(it, "index.json") }
        val text = try {
            fetchText("index.json").also { t ->
                if (cached != null) {
                    // New data published: forget tiles from the old one.
                    if (cached.exists() && cached.readText() != t) cacheDir?.listFiles { f -> f.name.endsWith(".json.gz") }?.forEach { it.delete() }
                    save(cached, t.toByteArray())
                }
            }
        } catch (e: IOException) {
            if (cached?.exists() == true) cached.readText() else throw e
        }
        val set = Json.parse(text).asMap()["tiles"].asList().map { it as String }.toHashSet()
        known = set
        return set
    }

    fun load(keys: Collection<String>, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): OsmData {
        val wanted = keys.filter { it in index() }
        val data = OsmData()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = wanted.map { k -> pool.submit(Callable { tileText(k) }) }
            for ((i, f) in futures.withIndex()) {
                val text = try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? IOException ?: IOException(e.cause)) }
                data.addTile(text)
                progress(i + 1, wanted.size)
            }
        } finally {
            pool.shutdownNow()
        }
        return data
    }

    /** One tile for the live map: from the phone's cache if it's there, else from GitHub. Null = no such tile. */
    fun shapes(key: String, allowNetwork: Boolean = true): TileShapes? {
        // Check the published list once per session, so tiles kept from older data get replaced.
        if (allowNetwork && known == null) synchronized(this) { if (known == null) runCatching { index() } }
        val file = cacheDir?.let { File(it, "$key.json.gz") }
        if (file?.exists() != true && (!allowNetwork || key !in index())) return null
        return TileShapes.of(key, OsmData().also { it.addTile(tileText(key)) })
    }

    private fun tileText(key: String): String {
        val file = cacheDir?.let { File(it, "$key.json.gz") }
        if (file != null && file.exists()) {
            try { return gunzip(file.readBytes()) } catch (e: IOException) { file.delete() }
        }
        val bytes = fetchBytes("tiles/$key.json.gz")
        val text = gunzip(bytes) // make sure it's whole before caching it
        if (file != null) save(file, bytes)
        return text
    }

    /** Write then rename, so an interrupted write never leaves half a tile behind. */
    private fun save(f: File, b: ByteArray) {
        runCatching {
            val tmp = File(f.path + ".${Thread.currentThread().id}.tmp")
            tmp.writeBytes(b)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    private fun gunzip(b: ByteArray): String = GZIPInputStream(b.inputStream()).readBytes().toString(Charsets.UTF_8)

    private fun fetchText(path: String): String = fetchBytes(path).toString(Charsets.UTF_8)

    private fun fetchBytes(path: String): ByteArray {
        var last: IOException? = null
        for (b in bases) {
            try {
                if (b.startsWith("file:") || !b.contains("://")) return File(File(b.removePrefix("file:")), path).readBytes()
                return http.getBytes(b.trimEnd('/') + "/" + path)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("No tile source")
    }
}
