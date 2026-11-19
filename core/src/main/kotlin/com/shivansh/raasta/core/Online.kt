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

/** What kind of place a search result is, for the label and icon. */
enum class PlaceKind(val label: String) {
    METRO("Metro station"), METRO_GATE("Metro gate"), RAIL("Station"), BUS("Bus stop"), AIRPORT("Airport"),
    FOOD("Restaurant"), CAFE("Café"), FUEL("Petrol pump"), HOSPITAL("Hospital"), SHOP("Shop"), MALL("Mall"),
    HOTEL("Hotel"), EDUCATION("School / college"), WORSHIP("Place of worship"), PARK("Park"),
    LANDMARK("Landmark"), AREA("Area"), ROAD("Road"), OTHER("Place");

    companion object {
        /** From OSM key/value (Nominatim category/type, Photon osm_key/osm_value) plus hints in the name/tags. */
        fun of(key: String?, value: String?, name: String, extra: Map<String, Any?> = emptyMap()): PlaceKind {
            val n = name.lowercase()
            val metroHint = n.contains("metro") || extra.s("station") == "subway" || extra.s("subway") == "yes" ||
                extra.s("network")?.contains("metro", true) == true
            return when (key) {
                "railway" -> when (value) {
                    "subway_entrance" -> METRO_GATE
                    "station", "halt", "stop", "subway" -> if (metroHint || value == "subway") METRO else RAIL
                    else -> OTHER
                }
                "public_transport" -> if (metroHint) METRO else if (value == "station") RAIL else BUS
                "building" -> if (metroHint && (value == "transportation" || value == "train_station" || value == "yes")) METRO else OTHER
                "amenity" -> when (value) {
                    "restaurant", "fast_food", "food_court", "bar", "pub" -> FOOD
                    "cafe", "ice_cream" -> CAFE
                    "fuel", "charging_station" -> FUEL
                    "hospital", "clinic", "doctors" -> HOSPITAL
                    "school", "college", "university" -> EDUCATION
                    "place_of_worship" -> WORSHIP
                    "bus_station" -> BUS
                    else -> OTHER
                }
                "shop" -> if (value == "mall" || value == "department_store") MALL else SHOP
                "tourism" -> when (value) {
                    "hotel", "guest_house", "hostel", "motel" -> HOTEL
                    "attraction", "museum", "viewpoint", "monument", "artwork" -> LANDMARK
                    else -> OTHER
                }
                "historic" -> LANDMARK
                "leisure" -> if (value == "park" || value == "garden" || value == "nature_reserve") PARK else OTHER
                "aeroway" -> AIRPORT
                "highway" -> if (value == "bus_stop") BUS else ROAD
                "place", "boundary" -> AREA
                else -> OTHER
            }
        }
    }
}

data class Place(val name: String, val detail: String, val lat: Double, val lon: Double, val kind: PlaceKind = PlaceKind.OTHER) {
    val latLon get() = LatLon(lat, lon)
}

/**
 * Place search limited to Delhi, using two free OpenStreetMap search services and merging them:
 * Nominatim (precise names) and Photon (forgiving, good at "restaurant in Connaught Place").
 * Words like "metro station" are understood as *what kind of place* you want, not as part of the name,
 * so "Shadipur metro station" finds the station OpenStreetMap simply calls "Shadipur".
 * Search on submit only, never per keystroke.
 */
class Geocoder(
    private val http: Http,
    private val nominatim: String = "https://nominatim.openstreetmap.org",
    private val photon: String = "https://photon.komoot.io",
) {
    private class Hit(val place: Place, var score: Double)

    fun search(query: String): List<Place> {
        val q = query.trim()
        val intent = intentOf(q)
        val core = coreOf(q)
        val tokens = core.lowercase().split(' ').filter { it.length >= 2 }
        val hits = ArrayList<Hit>()
        var lastError: Exception? = null
        fun collect(source: () -> List<Place>, bonus: Double) {
            try { source().forEach { hits.add(Hit(it, bonus)) } } catch (e: Exception) { lastError = e }
        }
        collect({ photon(q) }, 0.0)
        collect({ nominatim(q) }, 0.5)
        if (core.isNotBlank() && !core.equals(q, ignoreCase = true)) {
            Thread.sleep(1100) // Nominatim's fair-use limit is one request a second
            collect({ nominatim(core) }, 0.3)
        }
        if (hits.isEmpty() && lastError != null) throw lastError!!

        fun matches(h: Hit) = tokens.isEmpty() || tokens.any { t -> h.place.name.contains(t, true) || h.place.detail.contains(t, true) }
        // Once something matches your words, drop the rest (e.g. other metro stations when you asked for Shadipur).
        if (hits.any { matches(it) }) hits.retainAll { matches(it) }
        for (h in hits) {
            val n = h.place.name.lowercase()
            if (h.place.kind in intent) h.score += 5
            if (tokens.isNotEmpty() && tokens.all { n.contains(it) }) h.score += 3
            else if (tokens.any { n.contains(it) }) h.score += 1
            if (intent.isNotEmpty() && (h.place.kind == PlaceKind.AREA || h.place.kind == PlaceKind.ROAD)) h.score -= 1
            if (h.place.kind == PlaceKind.METRO_GATE) h.score -= 0.5
        }
        // Same place from both services (or several gates of one station): keep the best-scored one.
        val kept = ArrayList<Hit>()
        for (h in hits.sortedByDescending { it.score }) {
            val dup = kept.any { k ->
                val d = Geo.dist(k.place.latLon, h.place.latLon)
                val sameName = k.place.name.equals(h.place.name, true)
                d < 30 || (sameName && h.place.kind == PlaceKind.ROAD && d < 2000) || (d < 300 && (sameName ||
                    (h.place.kind == PlaceKind.METRO_GATE && k.place.kind in setOf(PlaceKind.METRO, PlaceKind.METRO_GATE))))
            }
            if (!dup) kept.add(h)
        }
        return kept.take(10).map { it.place }
    }

    private fun nominatim(q: String): List<Place> {
        val url = "$nominatim/search?format=jsonv2&limit=8&countrycodes=in&bounded=1&extratags=1" +
            "&viewbox=${Delhi.VIEWBOX}&q=${Http.enc(q)}"
        return Json.parse(http.get(url)).asList().mapNotNull { r ->
            val m = r.asMap()
            val lat = m.d("lat"); val lon = m.d("lon")
            if (lat.isNaN() || !Delhi.contains(lat, lon)) return@mapNotNull null
            val display = m.s("display_name").orEmpty()
            val name = m.s("name")?.takeIf { it.isNotBlank() } ?: display.substringBefore(',')
            val detail = display.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != name }
                .take(3).joinToString(", ")
            Place(name, detail, lat, lon, PlaceKind.of(m.s("category"), m.s("type"), name, m["extratags"].asMap()))
        }
    }

    private fun photon(q: String): List<Place> {
        val url = "$photon/api/?limit=10&lang=en&bbox=${Delhi.MIN_LON},${Delhi.MIN_LAT},${Delhi.MAX_LON},${Delhi.MAX_LAT}&q=${Http.enc(q)}"
        return Json.parse(http.get(url)).asMap()["features"].asList().mapNotNull { f ->
            val fm = f.asMap()
            val c = fm["geometry"].asMap()["coordinates"].asList()
            val lon = (c.getOrNull(0) as? Double) ?: return@mapNotNull null
            val lat = (c.getOrNull(1) as? Double) ?: return@mapNotNull null
            if (!Delhi.contains(lat, lon)) return@mapNotNull null
            val p = fm["properties"].asMap()
            val name = p.s("name") ?: p.s("street") ?: return@mapNotNull null
            val detail = listOfNotNull(p.s("street")?.takeIf { it != name }, p.s("locality"), p.s("district"), p.s("city"))
                .distinct().take(3).joinToString(", ")
            Place(name, detail, lat, lon, PlaceKind.of(p.s("osm_key"), p.s("osm_value"), name))
        }
    }

    companion object {
        private val INTENTS: List<Pair<Regex, Set<PlaceKind>>> = listOf(
            Regex("\\bmetro\\b") to setOf(PlaceKind.METRO, PlaceKind.METRO_GATE),
            Regex("\\b(station|stn)\\b") to setOf(PlaceKind.METRO, PlaceKind.RAIL),
            Regex("\\b(restaurant|restaurants|food|dhaba|eat|biryani|pizza)\\b") to setOf(PlaceKind.FOOD),
            Regex("\\b(cafe|café|coffee)\\b") to setOf(PlaceKind.CAFE),
            Regex("\\b(petrol|pump|fuel|cng)\\b") to setOf(PlaceKind.FUEL),
            Regex("\\b(hospital|clinic)\\b") to setOf(PlaceKind.HOSPITAL),
            Regex("\\bmall\\b") to setOf(PlaceKind.MALL),
            Regex("\\bhotel\\b") to setOf(PlaceKind.HOTEL),
            Regex("\\bairport\\b") to setOf(PlaceKind.AIRPORT),
            Regex("\\b(college|university|school)\\b") to setOf(PlaceKind.EDUCATION),
            Regex("\\b(park|garden)\\b") to setOf(PlaceKind.PARK),
        )

        /** Words that describe the kind of place rather than its name; dropped for the name search. */
        private val TYPE_WORDS = Regex(
            "\\b(metro station|railway station|metro|station|stn|restaurants?|food|dhaba|cafe|café|coffee|" +
                "petrol pump|petrol|pump|fuel|cng|hospital|clinic|mall|hotel|near|nearby|in|at|the)\\b",
            RegexOption.IGNORE_CASE,
        )

        fun intentOf(q: String): Set<PlaceKind> {
            val s = q.lowercase()
            return INTENTS.filter { it.first.containsMatchIn(s) }.flatMap { it.second }.toSet()
        }

        fun coreOf(q: String): String = q.replace(TYPE_WORDS, " ").replace(Regex("\\s+"), " ").trim()
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
