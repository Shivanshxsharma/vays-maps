package com.shivansh.raasta.core

import java.net.URI
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** A place someone pointed at: a shared map link, a plus code, coordinates. */
class MapLink(
    val name: String?,
    val address: String,
    /** Stated exactly (coordinates, plus code). */
    val point: LatLon? = null,
    /** Usually right, sometimes a kilometre off: to be checked before it's believed. */
    val hint: LatLon? = null,
)

object MapLinks {
    private val URL = Regex("https?://\\S+")
    private val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "g.co", "app.goo.gl")
    private val COORDS = Regex("(-?\\d{1,2}\\.\\d{3,})\\s*°?\\s*[Nn]?\\s*[,; ]\\s*(-?\\d{1,3}\\.\\d{3,})\\s*°?\\s*[Ee]?")
    private val PLUS = Regex("(?i)(?:^|[\\s,(])([23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,3})(?=$|[\\s,)])")

    /** Is this something to open rather than search for? */
    fun looksLikeOne(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("geo:")) return true
        URL.find(t)?.let { m -> return isMap(m.value) }
        if (PLUS.containsMatchIn(t)) return true
        val c = COORDS.find(t) ?: return false
        return c.value.length >= t.length - 4
    }

    private fun host(u: String) = runCatching { URI(u.trimEnd('.', ',', ')')).host?.lowercase() }.getOrNull().orEmpty().removePrefix("www.")

    private fun isMap(u: String): Boolean {
        val h = host(u)
        return h in SHORT_HOSTS || h == "maps.apple.com" || h.startsWith("maps.google.") || h == "maps.apple" ||
            (h.startsWith("google.") && "/maps" in u) || h == "openstreetmap.org"
    }

    /** Reads whatever was shared. [redirect] gives the Location of a short link (one request, nothing else is fetched). */
    fun read(text: String, near: LatLon, redirect: (String) -> String?): MapLink? {
        val t = text.trim()
        if (t.startsWith("geo:")) return geo(t)
        val url = URL.find(t)?.value?.trimEnd('.', ',', ')')
        if (url != null && isMap(url)) {
            var u: String = url
            var hops = 0
            while (host(u) in SHORT_HOSTS && hops++ < 4) u = redirect(u) ?: break
            // Some countries get a consent page first; the real link is inside it.
            if (host(u).startsWith("consent.")) query(u, "continue")?.let { u = it }
            // "Haldiram's\nhttps://maps.app.goo.gl/…": the text before the link is the name.
            val before = t.substringBefore(url).trim().lines().firstOrNull { it.isNotBlank() }?.trim()?.trimEnd(':', '-')
            val l = fromUrl(u) ?: return null
            return if (l.name == null && before != null && before.length in 3..80) MapLink(before, l.address, l.point, l.hint) else l
        }
        PLUS.find(t)?.let { m ->
            val p = plusCode(m.groupValues[1].uppercase(), near) ?: return@let
            return MapLink(null, "", p)
        }
        COORDS.find(t)?.let { m ->
            val la = m.groupValues[1].toDouble(); val lo = m.groupValues[2].toDouble()
            if (abs(la) <= 90 && abs(lo) <= 180) return MapLink(null, "", LatLon(la, lo))
        }
        return null
    }

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s).trim()

    private fun query(u: String, key: String): String? =
        u.substringAfter('?', "").substringBefore('#').split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.let { dec(it) }

    private fun pair(s: String?): LatLon? = s?.let { Regex("^(?:loc:)?\\s*(-?\\d{1,2}\\.\\d+)\\s*,\\s*\\+?(-?\\d{1,3}\\.\\d+)").find(it) }
        ?.let { LatLon(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }

    private fun geo(t: String): MapLink? {
        val q = query(t, "q")
        val label = q?.let { Regex("\\(([^)]+)\\)\\s*$").find(it)?.groupValues?.get(1) }
        val at = pair(q) ?: pair(t.removePrefix("geo:").substringBefore('?'))?.takeIf { abs(it.lat) > 0.01 }
        if (at != null && (q == null || pair(q) != null || label != null)) return MapLink(label, "", at)
        return q?.takeIf { it.isNotBlank() }?.let { MapLink(it, it, null, at) }
    }

    internal fun fromUrl(u: String): MapLink? {
        val h = host(u)
        if (h == "maps.apple.com") {
            val at = pair(query(u, "coordinate")) ?: pair(query(u, "ll"))
            val n = query(u, "name") ?: query(u, "q")
            val a = query(u, "address").orEmpty()
            return if (at == null && n == null) null else MapLink(n, a.ifEmpty { n.orEmpty() }, at)
        }
        if (h == "openstreetmap.org") {
            val la = query(u, "mlat")?.toDoubleOrNull(); val lo = query(u, "mlon")?.toDoubleOrNull()
            return if (la != null && lo != null) MapLink(null, "", LatLon(la, lo)) else null
        }
        val path = u.substringBefore('?')
        var full = Regex("/place/([^/]+)").find(path)?.groupValues?.get(1)?.let { dec(it) }
            ?: Regex("/search/([^/]+)").find(path)?.groupValues?.get(1)?.let { dec(it) }
            ?: query(u, "q") ?: query(u, "query") ?: query(u, "destination") ?: query(u, "daddr")
        // The pin itself (3d/4d) is exact; the @ is only where the camera was.
        val exact = Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)").find(u)?.let { LatLon(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
            ?: pair(full)
        if (full != null && pair(full) != null) full = null
        val id = Regex("(?:!1s|ftid=)(0x[0-9a-f]+)(?::|%3A|%3a)0x").find(u)?.groupValues?.get(1)
        val hint = id?.let { cell(it) } ?: Regex("/@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)").find(u)?.let { LatLon(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
        if (full == null && exact == null && hint == null) return null
        // A "Dropped pin" arrives as 28°39'41.2"N 77°05'12.4"E
        full?.let { f -> dms(f)?.let { return MapLink(null, "", it) } }
        return MapLink(full?.let { nameOf(it) }, full.orEmpty(), exact, hint)
    }

    private fun dms(s: String): LatLon? {
        val m = Regex("(\\d+)°(\\d+)'([\\d.]+)\"N\\s*\\+?(\\d+)°(\\d+)'([\\d.]+)\"E").find(s) ?: return null
        fun v(i: Int) = m.groupValues[i].toDouble() + m.groupValues[i + 1].toDouble() / 60 + m.groupValues[i + 2].toDouble() / 3600
        return LatLon(v(1), v(4))
    }

    private val UNIT = Regex("(?i)^(shop|plot|unit|gf|ground|flat|house|h\\.? ?no|no|floor|first|second|[a-z]?-?\\d).{0,14}$")

    /** "Shop No: A, Wenger's, 16 Near Road" -> Wenger's. */
    internal fun nameOf(full: String): String {
        val parts = full.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return (parts.take(3).firstOrNull { !UNIT.matches(it) } ?: parts.firstOrNull() ?: full).take(60)
    }

    /** The first half of a Google place id is the S2 cell the place was first put in. */
    internal fun cell(hex: String): LatLon? = runCatching {
        val id = java.lang.Long.parseUnsignedLong(hex.removePrefix("0x"), 16)
        val face = (id ushr 61).toInt()
        if (face > 5) return null
        var o = face and 1
        var i = 0L; var j = 0L
        for (k in 0 until 30) {
            val b = ((id ushr (59 - 2 * k)) and 3).toInt()
            val ij = POS[o][b]
            i = (i shl 1) or (ij shr 1).toLong(); j = (j shl 1) or (ij and 1).toLong()
            o = o xor OR[b]
        }
        fun f(s: Double) = if (s >= 0.5) (4 * s * s - 1) / 3 else (1 - 4 * (1 - s) * (1 - s)) / 3
        val u = f((2 * i + 1) / 2147483648.0); val v = f((2 * j + 1) / 2147483648.0)
        val p = when (face) {
            0 -> doubleArrayOf(1.0, u, v); 1 -> doubleArrayOf(-u, 1.0, v); 2 -> doubleArrayOf(-u, -v, 1.0)
            3 -> doubleArrayOf(-1.0, -v, -u); 4 -> doubleArrayOf(v, -1.0, -u); else -> doubleArrayOf(v, u, -1.0)
        }
        LatLon(Math.toDegrees(atan2(p[2], hypot(p[0], p[1]))), Math.toDegrees(atan2(p[1], p[0])))
    }.getOrNull()

    private val POS = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(0, 2, 3, 1), intArrayOf(3, 2, 0, 1), intArrayOf(3, 1, 0, 2))
    private val OR = intArrayOf(1, 0, 0, 3)

    private const val ALPHA = "23456789CFGHJMPQRVWX"

    /** Open Location Code: full ("7JWVM35P+XY") or short ("M35P+XY"), completed from where you are. */
    internal fun plusCode(code: String, near: LatLon): LatLon? {
        val sep = code.indexOf('+')
        var digits = code.replace("+", "")
        if (digits.any { it !in ALPHA }) return null
        val short = sep < 8
        if (short) {
            if (sep % 2 != 0) return null
            digits = encode(near).take(8 - sep) + digits
        }
        if (digits.length < 10) return null
        var la = -90.0; var lo = -180.0
        var step = 20.0
        for (k in 0 until 5) {
            la += ALPHA.indexOf(digits[2 * k]) * step
            lo += ALPHA.indexOf(digits[2 * k + 1]) * step
            if (k < 4) step /= 20
        }
        var h = step; var w = step
        for (k in 10 until digits.length) {
            val v = ALPHA.indexOf(digits[k])
            h /= 5; w /= 4
            la += (v / 4) * h; lo += (v % 4) * w
        }
        la += h / 2; lo += w / 2
        if (short) {
            // The nearest place with this ending.
            val r = Math.pow(20.0, 2.0 - (8 - sep) / 2.0)
            while (la - near.lat > r / 2) la -= r
            while (near.lat - la > r / 2) la += r
            while (lo - near.lon > r / 2) lo -= r
            while (near.lon - lo > r / 2) lo += r
        }
        return LatLon(la, lo)
    }

    private fun encode(p: LatLon): String {
        var la = p.lat + 90; var lo = p.lon + 180
        var step = 20.0
        val sb = StringBuilder()
        for (k in 0 until 4) {
            val a = floor(la / step).toInt(); val o = floor(lo / step).toInt()
            sb.append(ALPHA[a]).append(ALPHA[o])
            la -= a * step; lo -= o * step
            step /= 20
        }
        return sb.toString()
    }
}

enum class Trust { EXACT, LIKELY, ROUGH }

class Located(val place: Place, val trust: Trust)

/** Decides where a shared place really is, checking every source against the others. */
object LinkLocator {
    private val GENERIC = setOf("new", "delhi", "india", "the", "and", "road", "rd", "marg", "block", "shop", "no", "floor", "ground", "market", "near",
        "opp", "plot", "sector", "main", "nagar", "vihar", "metro", "station", "place", "building", "opposite", "uttar", "pradesh", "haryana")

    fun locate(link: MapLink, db: PlaceDb?, geocode: (String) -> List<Place>): Located? {
        val name = link.name ?: "Shared place"
        val addr = addressOf(link)
        fun at(p: LatLon, t: Trust) = Located(Place(name, addr, p.lat, p.lon, PlaceKind.OTHER, "From your map link"), t)
        link.point?.let { return at(it, Trust.EXACT) }
        val hint = link.hint?.takeIf { Delhi.contains(it) }
        if (db == null) return hint?.let { at(it, Trust.LIKELY) }

        val full = PlaceText.tokens(link.address)
        val areas = db.areasIn(link.address)
        // Where the address itself points, worked out from everyone else who gives the same market, road or block.
        val around = neighbours(link, db, areas)
        val found = if (link.name == null) emptyList() else
            (db.search(name, hint ?: areas.firstOrNull(), 12, typing = false) + areas.take(1).flatMap { db.search(name, it, 8, typing = false) })
                .distinctBy { it.id }.filter { sameName(name, it.place.name) }
        // Our list has it at the very spot too.
        if (hint != null) found.filter { Geo.dist(it.place.latLon, hint) < 250 }.minByOrNull { Geo.dist(it.place.latLon, hint) }?.let { h ->
            return Located(h.place.copy(name = name, detail = addr.ifEmpty { h.place.detail }, lat = hint.lat, lon = hint.lon), Trust.EXACT)
        }
        // The same door number or street in our list, somewhere else: the id was out of date.
        val streets = full.filter { it !in GENERIC && it.length >= 2 }.toSet()
        val own = PlaceText.tokens(name).toSet()
        found.map { h -> h to PlaceText.tokens(h.place.detail).count { it in streets && it !in own && (it.any(Char::isDigit) || it.length > 3) } }
            .filter { it.second >= 2 }.maxByOrNull { it.second }?.let { (h, _) -> return Located(h.place.copy(name = name, detail = addr), if (h.place.label.contains("unverified")) Trust.LIKELY else Trust.EXACT) }
        if (around != null) {
            // Id and address agree: good. They don't: the address is what people read, and ids go stale.
            if (hint != null && Geo.dist(hint, around.at) < around.spread + 350) return at(hint, Trust.LIKELY)
            found.filter { Geo.dist(it.place.latLon, around.at) < around.spread + 300 }.minByOrNull { Geo.dist(it.place.latLon, around.at) }
                ?.let { return Located(it.place.copy(name = name, detail = addr), Trust.LIKELY) }
            return at(around.at, Trust.ROUGH)
        }
        // A single branch in the area the address names.
        found.filter { h -> areas.any { Geo.dist(it, h.place.latLon) < 1800 } }.singleOrNull()?.let { return Located(it.place.copy(name = name, detail = addr), Trust.LIKELY) }
        val street = segments(link)
        for (n in 0 until (street.size - 1).coerceIn(0, 2)) {
            val r = runCatching { geocode(street.drop(n).take(3).joinToString(", ")) }.getOrNull()?.firstOrNull { r -> areas.any { Geo.dist(it, r.latLon) < 2000 } } ?: continue
            return if (hint != null && Geo.dist(hint, r.latLon) < 500) at(hint, Trust.LIKELY) else at(r.latLon, Trust.ROUGH)
        }
        // Nothing to check the id against but the area: near its middle it is probably right, further out it may not be.
        if (hint != null && areas.any { Geo.dist(it, hint) < 1500 }) return at(hint, Trust.LIKELY)
        if (hint != null && (areas.isEmpty() || areas.any { Geo.dist(it, hint) < 3000 })) return at(hint, Trust.ROUGH)
        return (areas.firstOrNull() ?: hint)?.let { at(it, Trust.ROUGH) }
    }

    private class Around(val at: LatLon, val spread: Double)

    private val LEAD = setOf("opp", "opposite", "near", "behind", "beside", "next", "to", "the", "shop", "no", "nr", "adjacent", "infront", "front", "above", "below", "in", "of", "ground", "first", "second", "floor", "gf", "ff", "main")

    private fun segments(l: MapLink): List<String> =
        l.address.replace(l.name.orEmpty(), "").split(',', '/').map { it.trim() }
            .filter { it.length > 2 && !Regex("(?i)(new )?delhi( ?ncr)?( \\d{6})?|india|\\d{6}|haryana.*|uttar pradesh.*|gurugram|noida").matches(it) }

    /**
     * "…, National Market, Peeragarhi": the other shops that say "National Market" around Peeragarhi show where it is.
     * The part whose places lie closest together wins; parts shared by half the neighbourhood say nothing.
     */
    private fun neighbours(l: MapLink, db: PlaceDb, areas: List<LatLon>): Around? {
        if (areas.isEmpty()) return null
        val parts = segments(l).map { seg -> PlaceText.tokens(seg).dropWhile { it in LEAD || it.all(Char::isDigit) } }
            .filter { t -> t.isNotEmpty() && t.sumOf { it.length } >= 5 && t.any { it !in GENERIC && it.length >= 3 || it.any(Char::isDigit) } }.distinct()
        if (parts.isEmpty()) return null
        val keys = parts.map { t -> " " + t.joinToString(" ") + " " }
        val hits = List(keys.size) { ArrayList<LatLon>() }
        val seen = HashSet<Int>()
        for (c in areas.take(3)) {
            val dLat = 3000 / 111_320.0; val dLon = dLat / kotlin.math.cos(Math.toRadians(c.lat))
            db.inBox(c.lat - dLat, c.lon - dLon, c.lat + dLat, c.lon + dLon, 5) { i ->
                if (!seen.add(i)) return@inBox
                val kind = db.kind(i)
                // An area's own name is where we started; it can't narrow itself down.
                if (kind == PlaceKind.AREA && db.rank(i) <= 2) return@inBox
                val text = " " + PlaceText.tokens(db.name(i) + " , " + db.address(i)).joinToString(" ") + " "
                for (k in keys.indices) if (keys[k] in text) hits[k].add(LatLon(db.lat(i), db.lon(i)))
            }
        }
        var best: Around? = null
        var bestScore = Double.MAX_VALUE
        for (k in keys.indices) {
            val h = hits[k]
            if (h.isEmpty()) continue
            val mid = LatLon(h.map { it.lat }.sorted()[h.size / 2], h.map { it.lon }.sorted()[h.size / 2])
            val spread = h.map { Geo.dist(mid.lat, mid.lon, it.lat, it.lon) }.sorted()[((h.size - 1) * 0.7).toInt()]
            if (spread > 450 || h.size == 1 && parts[k].size < 2) continue
            // One mention could be a wrong pin: trust it less than several that agree.
            val score = spread + (if (h.size == 1) 260.0 else if (h.size == 2) 120.0 else 0.0)
            if (score < bestScore) { bestScore = score; best = Around(mid, spread) }
        }
        return best
    }

    private fun addressOf(l: MapLink): String {
        val n = l.name ?: return ""
        return l.address.substringAfter(n, "").split(',').map { it.trim() }.filter { it.isNotEmpty() && !Regex("(?i)(new )?delhi( \\d{6})?|india|\\d{6}").matches(it) }
            .distinct().take(4).joinToString(", ")
    }

    private val SOFT = setOf("the", "restaurant", "hotel", "new", "delhi", "shop", "store", "since", "and", "pvt", "ltd")

    internal fun sameName(a: String, b: String): Boolean {
        fun t(s: String) = PlaceText.tokens(s).filter { it !in SOFT && !it.all(Char::isDigit) }.map { PlaceText.sound(it.removeSuffix("s")).ifEmpty { it } }.toSet()
        val x = t(a); val y = t(b)
        if (x.isEmpty() || y.isEmpty()) return false
        val c = (x intersect y).size
        return c == minOf(x.size, y.size) && (c >= 2 || x == y || minOf(x.size, y.size) == 1 && c == 1 && x.first() == y.first())
    }
}
