package com.shivansh.raasta.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Network is only used while downloading a route pack. Everything after that is offline.
 * Uses public OpenStreetMap services (Nominatim, OSRM demo server, Overpass); for a public
 * release these should point at our own server (see README).
 */
class Http(private val userAgent: String = "VaysMaps/0.1 (offline Delhi navigation prototype)") {
    class Error(val code: Int, msg: String) : IOException(msg)

    fun get(url: String, timeoutMs: Int = 30_000): String = request(url, null, timeoutMs).toString(Charsets.UTF_8)

    fun getBytes(url: String, timeoutMs: Int = 30_000): ByteArray = request(url, null, timeoutMs)

    fun postForm(url: String, form: Map<String, String>, timeoutMs: Int = 180_000): String {
        val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        return request(url, body, timeoutMs).toString(Charsets.UTF_8)
    }

    private fun request(url: String, body: String?, timeoutMs: Int): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw Error(code, "HTTP $code from ${URL(url).host}: ${err.take(200)}")
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double) {
    val latLon get() = LatLon(lat, lon)
}

/** Place search limited to Delhi (Nominatim). Search on submit only, never per keystroke. */
class Geocoder(private val http: Http, private val base: String = "https://nominatim.openstreetmap.org") {
    fun search(query: String): List<Place> {
        val url = "$base/search?format=jsonv2&limit=8&countrycodes=in&bounded=1" +
            "&viewbox=${Delhi.VIEWBOX}&q=${Http.enc(query)}"
        return Json.parse(http.get(url)).asList().mapNotNull { r ->
            val m = r.asMap()
            val lat = m.d("lat"); val lon = m.d("lon")
            if (lat.isNaN() || !Delhi.contains(lat, lon)) return@mapNotNull null
            val display = m.s("display_name").orEmpty()
            val name = m.s("name")?.takeIf { it.isNotBlank() } ?: display.substringBefore(',')
            val detail = display.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != name }
                .take(3).joinToString(", ")
            Place(name, detail, lat, lon)
        }.distinctBy { it.name.lowercase() + "|" + it.detail.lowercase() }
    }
}

class OsrmRoute(val coords: List<LatLon>, val nodeIds: LongArray, val distance: Double, val duration: Double)

/** Driving routes (with OSM node ids so we can line them up with the downloaded road graph). */
class Osrm(private val http: Http, private val base: String = "https://router.project-osrm.org") {
    fun routes(from: LatLon, to: LatLon): List<OsrmRoute> {
        val url = "$base/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}" +
            "?overview=full&geometries=geojson&annotations=nodes&alternatives=2&steps=false"
        val root = Json.parse(http.get(url, 40_000)).asMap()
        if (root.s("code") != "Ok") throw IOException("No route found (${root.s("code")})")
        return root["routes"].asList().map { r ->
            val rm = r.asMap()
            val coords = rm["geometry"].asMap()["coordinates"].asList().map { p ->
                val l = p.asList(); LatLon(l[1] as Double, l[0] as Double)
            }
            val nodes = ArrayList<Long>()
            for (leg in rm["legs"].asList()) {
                for (n in leg.asMap()["annotation"].asMap()["nodes"].asList()) {
                    val id = (n as Double).toLong()
                    if (nodes.isEmpty() || nodes.last() != id) nodes.add(id)
                }
            }
            OsrmRoute(coords, nodes.toLongArray(), rm.d("distance"), rm.d("duration"))
        }
    }
}

/** Raw OSM road data for a corridor (Overpass API), with mirror fallback. */
class Overpass(
    private val http: Http,
    private val mirrors: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    ),
) {
    fun query(q: String, onMirror: (String) -> Unit = {}): String {
        var last: Exception? = null
        for (m in mirrors) {
            try {
                onMirror(URL(m).host)
                return http.postForm(m, mapOf("data" to q))
            } catch (e: IOException) {
                last = e
            }
        }
        throw IOException("Map data servers are busy, try again in a minute (${last?.message})", last)
    }

    companion object {
        const val HIGHWAYS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"

        /** Roads within [radius] m of each polyline, their nodes, plus metro stations and petrol pumps as landmarks. */
        fun corridorQuery(polylines: List<List<LatLon>>, radius: Int): String {
            val polys = polylines.map { pl -> pl.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.lat, it.lon) } }
            val sb = StringBuilder("[out:json][timeout:180];(")
            for (p in polys) sb.append("way[highway~\"^($HIGHWAYS)$\"](around:$radius,$p);")
            sb.append(")->.r;(")
            for (p in polys) {
                sb.append("node[railway=station](around:200,$p);")
                sb.append("node[amenity=fuel](around:100,$p);")
            }
            sb.append(")->.l;(.r;>;.l;);out body qt;")
            return sb.toString()
        }
    }
}
