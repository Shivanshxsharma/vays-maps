package com.shivansh.raasta.core

import java.nio.ByteBuffer
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** How place names are compared: the same way for building the index and for what you type. */
object PlaceText {
    private val MARKS = Regex("\\p{Mn}+")
    private val APOS = Regex("['’`´]")

    fun fold(s: String): String {
        var plain = true
        for (c in s) if (c.code > 127) { plain = false; break }
        if (plain) return s.lowercase()
        // Keep Devanagari vowel signs: they are marks too, but they carry the word.
        val n = Normalizer.normalize(s, Normalizer.Form.NFKD)
        return (if (n.any { it in 'ऀ'..'ॿ' }) n else n.replace(MARKS, "")).lowercase()
    }

    /** Words of a name: "Haldiram's (CP)" -> haldirams, cp. */
    fun tokens(s: String): List<String> {
        val f = fold(s).replace(APOS, "").replace("&", " and ")
        val out = ArrayList<String>(6)
        val sb = StringBuilder()
        for (c in f) {
            if (c.isLetterOrDigit() || c in 'ऀ'..'ॿ') sb.append(c)
            else if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /**
     * A rough sound of the word, so the many spellings of one Indian name meet: Shree/Shri/Sri, Qutub/Qutab/Qutb,
     * Pashchim/Paschim, Laxmi/Lakshmi, Vishwas/Viswas, Chawri/Chaori.
     */
    fun sound(t: String): String {
        if (t.isEmpty() || t[0].code > 127 || t[0].isDigit()) return t
        val s = t.replace("ph", "f").replace("ksh", "ks").replace("x", "ks").replace("chh", "c").replace("sch", "sc")
            .replace("ch", "c").replace("sh", "s").replace("ck", "k").replace("q", "k").replace("z", "j").replace("w", "v")
        val sb = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            val first = i == 0
            when (c) {
                'a' -> if (first) sb.append('a')
                'e', 'i' -> if (first) sb.append('i')
                'o', 'u' -> if (first) sb.append('u')
                'y' -> if (first) sb.append('y')
                'h' -> if (first) sb.append('h')
                'c' -> sb.append(if (t.contains("ch")) 'c' else if (i + 1 < s.length && s[i + 1] in "ei") 's' else 'k')
                // "Chaori" / "Chawri": a v between vowels is often silent
                'v' -> if (first || i + 1 >= s.length || s[i - 1] !in "aou" || s[i + 1] in "aeiou") sb.append('v')
                else -> sb.append(c)
            }
        }
        val o = StringBuilder()
        for (c in sb) if (o.isEmpty() || o.last() != c) o.append(c)
        return o.toString()
    }

    /** Edit distance, giving up past [limit]. A swapped pair of letters counts once. */
    fun edits(a: String, b: String, limit: Int): Int {
        if (abs(a.length - b.length) > limit) return limit + 1
        var pp = IntArray(b.length + 1)
        var p = IntArray(b.length + 1) { it }
        var c = IntArray(b.length + 1)
        for (i in 1..a.length) {
            c[0] = i
            var best = i
            for (j in 1..b.length) {
                var v = min(min(p[j] + 1, c[j - 1] + 1), p[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, pp[j - 2] + 1)
                c[j] = v
                best = min(best, v)
            }
            if (best > limit) return limit + 1
            val t = pp; pp = p; p = c; c = t
        }
        return p[b.length]
    }
}

class PlaceCat(val label: String, val kind: PlaceKind) {
    val words: Set<String> = PlaceText.tokens(label).toSet()
}

/**
 * Every place in Delhi we know of (about a quarter of a million), in one read-only file that stays on disk and is
 * only touched where needed. Sorted by area, so the map can ask for "places in this box" and the search can ask for
 * "nearest"; with a word index for names. Written by tools/PlaceDbWriter.
 */
class PlaceDb(private val b: ByteBuffer) {
    class Hit(val id: Int, val place: Place, val score: Double, val distance: Double)

    val size: Int
    val cats: List<PlaceCat>
    private val oLat: Int; private val oLon: Int; private val oCat: Int; private val oRank: Int; private val oConf: Int
    private val oNameOff: Int; private val oNames: Int; private val oAddrOff: Int; private val oAddrs: Int
    private val nCells: Int; private val oCellKey: Int; private val oCellStart: Int
    private val nTok: Int; private val oTokOff: Int; private val oToks: Int; private val oPostOff: Int; private val oPost: Int
    private val oDf: Int; private val oSound: Int

    init {
        require(b.getInt(0) == MAGIC) { "Not a Vays Maps place file" }
        require(b.getInt(4) == VERSION) { "Unknown place file version" }
        size = b.getInt(8); nCells = b.getInt(12); nTok = b.getInt(16)
        val s = IntArray(16) { b.getInt(20 + 4 * it) }
        oLat = s[0]; oLon = s[1]; oCat = s[2]; oRank = s[3]; oConf = s[4]; oNameOff = s[5]; oNames = s[6]
        oAddrOff = s[7]; oAddrs = s[8]; oCellKey = s[9]; oCellStart = s[10]; oTokOff = s[11]; oToks = s[12]
        oPostOff = s[13]; oPost = s[14]
        val oMisc = s[15]
        oDf = b.getInt(oMisc); oSound = b.getInt(oMisc + 4)
        val oCats = b.getInt(oMisc + 8)
        val text = str(oCats + 4, b.getInt(oCats))
        cats = text.split('\n').filter { it.isNotEmpty() }.map { l ->
            val (k, label) = l.split('\t')
            PlaceCat(label, PlaceKind.entries.firstOrNull { it.name == k } ?: PlaceKind.OTHER)
        }
    }

    fun lat(i: Int) = b.getInt(oLat + 4 * i) / 1e6
    fun lon(i: Int) = b.getInt(oLon + 4 * i) / 1e6
    fun cat(i: Int): PlaceCat = cats[b.getShort(oCat + 2 * i).toInt() and 0xFFFF]
    private fun catId(i: Int) = b.getShort(oCat + 2 * i).toInt() and 0xFFFF
    fun kind(i: Int) = cat(i).kind
    /** 0 = city-wide landmark … 3 = ordinary shop … 5 = offices and the like, only found by name. */
    fun rank(i: Int) = b.get(oRank + i).toInt()
    /** 0..1: how sure the sources are that this exists, here. */
    fun confidence(i: Int) = (b.get(oConf + i).toInt() and 0xFF) / 255.0
    fun name(i: Int) = str(oNames + b.getInt(oNameOff + 4 * i), b.getInt(oNameOff + 4 * i + 4) - b.getInt(oNameOff + 4 * i))
    private fun addrRaw(i: Int) = str(oAddrs + b.getInt(oAddrOff + 4 * i), b.getInt(oAddrOff + 4 * i + 4) - b.getInt(oAddrOff + 4 * i))
    fun address(i: Int) = addrRaw(i).substringAfter('\t')
    /** Other names it goes by ("AIIMS"), searchable but not shown. */
    fun alt(i: Int) = addrRaw(i).let { if ('\t' in it) it.substringBefore('\t') else "" }

    fun place(i: Int) = Place(name(i), address(i), lat(i), lon(i), kind(i), cat(i).label)

    private fun str(at: Int, len: Int): String {
        val a = ByteArray(len)
        for (k in 0 until len) a[k] = b.get(at + k)
        return String(a, Charsets.UTF_8)
    }

    private fun cellStart(c: Int) = b.getInt(oCellStart + 4 * c)

    private fun findCell(key: Int): Int {
        var lo = 0; var hi = nCells - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            val v = b.getInt(oCellKey + 4 * m)
            if (v == key) return m
            if (v < key) lo = m + 1 else hi = m - 1
        }
        return -1
    }

    /** Places in a box with rank at most [maxRank] (best first inside each ~500 m cell). */
    fun inBox(latMin: Double, lonMin: Double, latMax: Double, lonMax: Double, maxRank: Int, visit: (Int) -> Unit) {
        val r0 = floor(latMin / CELL).toInt(); val r1 = floor(latMax / CELL).toInt()
        val c0 = floor(lonMin / CELL).toInt(); val c1 = floor(lonMax / CELL).toInt()
        if ((r1 - r0 + 1L) * (c1 - c0 + 1L) > 4000) return
        for (r in r0..r1) for (c in c0..c1) {
            val cell = findCell(key(r, c))
            if (cell < 0) continue
            for (i in cellStart(cell) until cellStart(cell + 1)) {
                if (rank(i) > maxRank) break
                val la = lat(i); val lo = lon(i)
                if (la in latMin..latMax && lo in lonMin..lonMax) visit(i)
            }
        }
    }

    /** Nearest places that pass [test], nearest first. */
    fun near(lat: Double, lon: Double, limit: Int, maxM: Double, test: (Int) -> Boolean): List<Pair<Int, Double>> {
        val found = ArrayList<Pair<Int, Double>>()
        val r0 = floor(lat / CELL).toInt(); val c0 = floor(lon / CELL).toInt()
        val cellM = CELL * 111_320 * cos(Math.toRadians(lat)) // the narrow side
        var ring = 0
        while (true) {
            // Everything in ring n is at least (n-1) cells away.
            if ((ring - 1) * cellM > maxM) break
            if (found.size >= limit) {
                found.sortBy { it.second }
                if (found[limit - 1].second <= (ring - 1) * cellM) break
            }
            for (r in -ring..ring) for (c in -ring..ring) {
                if (max(abs(r), abs(c)) != ring) continue
                val cell = findCell(key(r0 + r, c0 + c))
                if (cell < 0) continue
                for (i in cellStart(cell) until cellStart(cell + 1)) if (test(i)) {
                    val d = Geo.dist(lat, lon, lat(i), lon(i))
                    if (d <= maxM) found.add(i to d)
                }
            }
            ring++
        }
        found.sortBy { it.second }
        return found.take(limit)
    }

    // ---------------------------------------------------------------- words

    private fun tok(i: Int) = str(oToks + b.getInt(oTokOff + 4 * i), b.getInt(oTokOff + 4 * i + 4) - b.getInt(oTokOff + 4 * i))
    private fun df(i: Int) = b.getInt(oDf + 4 * i)

    /** First word >= [s]. */
    private fun lower(s: String): Int {
        val t = s.toByteArray(Charsets.UTF_8)
        var lo = 0; var hi = nTok
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (cmp(m, t) < 0) lo = m + 1 else hi = m
        }
        return lo
    }

    private fun cmp(i: Int, t: ByteArray): Int {
        val at = oToks + b.getInt(oTokOff + 4 * i); val n = b.getInt(oTokOff + 4 * i + 4) - b.getInt(oTokOff + 4 * i)
        for (k in 0 until min(n, t.size)) {
            val d = (b.get(at + k).toInt() and 0xFF) - (t[k].toInt() and 0xFF)
            if (d != 0) return d
        }
        return n - t.size
    }

    private fun postings(i: Int, visit: (Int) -> Unit) {
        var at = oPost + b.getInt(oPostOff + 4 * i)
        val end = oPost + b.getInt(oPostOff + 4 * i + 4)
        var doc = 0
        while (at < end) {
            var v = 0; var sh = 0
            while (true) {
                val x = b.get(at++).toInt()
                v = v or ((x and 0x7F) shl sh)
                if (x and 0x80 == 0) break
                sh += 7
            }
            doc += v
            visit(doc)
        }
    }

    private class Exp(val tok: Int, val weight: Double)

    /** Words in the index that could be what was typed, with how good a match each is. */
    private fun expand(t: String, typing: Boolean): List<Exp> {
        val out = HashMap<Int, Double>()
        fun put(i: Int, w: Double) { out.merge(i, w) { a, c -> max(a, c) } }
        fun exact(w: String): Int { val k = lower(w); return if (k < nTok && tok(k) == w) k else -1 }
        var i = lower(t)
        if (i < nTok && tok(i) == t) put(i, 1.0)
        // Haldiram / Haldiram's
        if (t.length >= 4) (if (t.endsWith("s")) exact(t.dropLast(1)) else exact(t + "s")).let { if (it >= 0) put(it, 0.97) }
        // She / Shri / Shree / Sri: one word to the ear.
        if (t in SHREE) for (w in SHREE) if (w != "she") exact(w).let { if (it >= 0) put(it, 0.96) }
        if (t.length >= 2 && (typing || t.length >= 4)) {
            var n = 0
            while (i < nTok && n < (if (t.length <= 2) 250 else 600)) {
                val w = tok(i)
                if (!w.startsWith(t)) break
                if (w != t) put(i, (if (typing) 0.92 else 0.62) - 0.02 * min(8, w.length - t.length))
                i++; n++
            }
        }
        if (t[0].isLetter() && t[0].code < 128 && t.length >= 3) {
            val snd = PlaceText.sound(t)
            if (snd.length >= 2) {
                // Words that sound the same.
                var lo = 0; var hi = nTok
                while (lo < hi) {
                    val m = (lo + hi) ushr 1
                    if (PlaceText.sound(tok(b.getInt(oSound + 4 * m))) < snd) lo = m + 1 else hi = m
                }
                while (lo < nTok) {
                    val k = b.getInt(oSound + 4 * lo)
                    val w = tok(k)
                    if (PlaceText.sound(w) != snd) break
                    val lim = max(2, t.length / 2)
                    val e = PlaceText.edits(t, w, lim)
                    if (e in 1..lim) put(k, 0.86 - 0.5 * e / max(t.length, w.length))
                    lo++
                }
            }
            // Plain typos: same first letter, one slip in short words, two in long ones.
            if (t.length >= 4) {
                val lim = if (t.length >= 7) 2 else 1
                val e0 = lower(t.substring(0, 1)); val e1 = lower((t[0] + 1).toString())
                for (k in e0 until e1) {
                    val len = b.getInt(oTokOff + 4 * k + 4) - b.getInt(oTokOff + 4 * k)
                    if (abs(len - t.length) > lim) continue
                    val e = PlaceText.edits(t, tok(k), lim)
                    if (e in 1..lim) put(k, if (e == 1) 0.7 else 0.5)
                }
            }
        }
        return out.map { Exp(it.key, it.value) }
    }

    // ---------------------------------------------------------------- search

    private class Area(val place: LatLon, val name: String)

    private val areas: Map<String, List<Area>> by lazy {
        val m = HashMap<String, MutableList<Area>>()
        for (i in 0 until size) {
            val k = kind(i)
            if (k != PlaceKind.AREA && k != PlaceKind.METRO || rank(i) > 2) continue
            val n = name(i)
            val key = PlaceText.tokens(n).joinToString(" ")
            if (key.length >= 3) m.getOrPut(key) { ArrayList() }.add(Area(LatLon(lat(i), lon(i)), n))
        }
        m
    }
    private val areasBySound: Map<String, List<String>> by lazy {
        areas.keys.groupBy { k -> k.split(' ').joinToString(" ") { PlaceText.sound(it) } }
    }

    private fun areaFor(words: List<String>): List<Area>? {
        val key = words.joinToString(" ")
        areas[key]?.let { return it }
        if (key.length < 5) return null
        val s = areasBySound[words.joinToString(" ") { PlaceText.sound(it) }] ?: return null
        return s.map { it to PlaceText.edits(it, key, 2) }.filter { it.second <= 2 }.minByOrNull { it.second }?.let { areas[it.first] }
    }

    private val score = FloatArray(size)
    private val best = FloatArray(size)
    private val stamp = IntArray(size)
    private var touched = IntArray(4096)
    private var nTouched = 0
    private var run = 0

    /**
     * Finds places by anything people type: part of a name, a wrong spelling, a kind of place ("chemist", "puncture"),
     * an area ("… paschim vihar"), in any order. [near] breaks ties towards you. Safe to call from any one thread at a time.
     */
    @Synchronized
    fun search(query: String, near: LatLon?, limit: Int = 12, typing: Boolean = true): List<Hit> {
        val raw = PlaceText.tokens(query)
        if (raw.isEmpty() || query.trim().length < 2) return emptyList()
        val nearMe = Regex("\\b(near me|nearby|around me|aas ?paas|paas me)\\b").containsMatchIn(query.lowercase())
        var words = raw.filter { it !in STOP }
        if (nearMe) words = words.filter { it != "me" && it != "near" && it != "nearby" && it != "around" }
        if (words.isEmpty()) words = raw
        // "puncture shop", "medical store": the first word says it all.
        if (words.size > 1 && words.any { it !in SHOPWORD && SYNONYMS.containsKey(it) }) words = words.filter { it !in SHOPWORD }

        // Which of the words name an area? The longest run wins; a query that is only an area stays a name search.
        var area: List<Area>? = null
        var areaFrom = -1; var areaTo = -1
        if (words.size >= 2) search@ for (len in min(3, words.size - 1) downTo 1) for (s in words.size - len downTo 0) {
            val w = words.subList(s, s + len)
            if (len == 1 && (w[0].length < 4 || w[0] in CAT_WORDS)) continue
            val a = areaFor(w) ?: continue
            area = a; areaFrom = s; areaTo = s + len
            break@search
        }

        nTouched = 0
        val idf = DoubleArray(words.size)
        val catSets = arrayOfNulls<Set<Int>>(words.size)
        var total = 0.0
        val joined = words.zipWithNext { a, c -> a + c }
        for ((w, t) in words.withIndex()) {
            run++
            val isArea = w in areaFrom until areaTo
            val exps = expand(t, typing && w == words.size - 1 && !query.endsWith(" "))
            var count = 0
            for (e in exps) if (e.weight >= 0.7) count += df(e.tok)
            idf[w] = if (isArea) 2.6 else if (t in SHREE) 1.6 else if (exps.isEmpty()) 3.5 else ln(1.0 + size / (1.0 + count)).coerceIn(0.4, 8.0)
            if (!isArea) catSets[w] = catsFor(t)
            if (catSets[w]?.isNotEmpty() == true) idf[w] = if (t in DISH) idf[w].coerceIn(2.5, 5.0) else min(idf[w], 3.4)
            total += idf[w]
            for (e in exps) postings(e.tok) { d -> add(d, (e.weight * idf[w]).toFloat()) }
            // "mc donalds" typed as two words, "dmart" written as two.
            for (k in listOf(w - 1, w)) if (k in joined.indices) {
                val j = lower(joined[k])
                if (j < nTok && tok(j) == joined[k]) postings(j) { d -> add(d, (0.95 * idf[w]).toFloat()) }
            }
            // "sitaram" typed as one word, "Sita Ram" on the board.
            if (count < 40 && t.length in 6..16) for (cut in 3..t.length - 3) {
                val x = lower(t.substring(0, cut)); if (x >= nTok || tok(x) != t.substring(0, cut)) continue
                val y = lower(t.substring(cut)); if (y >= nTok || tok(y) != t.substring(cut)) continue
                val few = if (df(x) < df(y)) x else y
                if (df(few) > 4000) continue
                val one = HashSet<Int>()
                postings(few) { one.add(it) }
                postings(if (few == x) y else x) { if (it in one) add(it, (0.93 * idf[w]).toFloat()) }
            }
            settle()
        }
        val centre = area?.let { l -> if (near == null) l[0].place else l.minByOrNull { Geo.dist(it.place, near) }!!.place }
        val anchor = centre ?: near

        // "chemist", "petrol pump", "dosa in rohini": the nearest of that kind are candidates too.
        val catAll = catSets.filterNotNull().filter { it.isNotEmpty() }
        if (catAll.isNotEmpty() && anchor != null) {
            run++
            val want = catAll.reduce { a, c -> a intersect c }.ifEmpty { catAll.minByOrNull { it.size }!! }
            for ((d, _) in near(anchor.lat, anchor.lon, 150, 6000.0) { catId(it) in want && rank(it) < 6 }) touch(d)
            settle()
        }
        if (nTouched == 0) return emptyList()

        val whole = words.joinToString(" ")
        val flat = words.joinToString("") { it.removeSuffix("s") }
        val coreWords = words.indices.filter { it !in areaFrom until areaTo }
        // Cheap pass first, then look closely at the most promising ones.
        val ids = (0 until nTouched).map { touched[it] }
        val rough = ids.sortedByDescending { d ->
            score[d].toDouble() + catBonus(d, words, catSets, idf) +
                (if (centre != null) prox(Geo.dist(centre.lat, centre.lon, lat(d), lon(d))) * 1.9 * (areaTo - areaFrom) else 0.0) +
                (if (anchor != null) -0.35 * ln(1 + Geo.dist(anchor.lat, anchor.lon, lat(d), lon(d)) / 1500) else 0.0) + confidence(d)
        }.take(500)
        val onlyKind = coreWords.isNotEmpty() && coreWords.all { catSets[it]?.isNotEmpty() == true && words[it] !in DISH }
        val hits = ArrayList<Hit>()
        for (d in rough) {
            val name = name(d)
            val toks = PlaceText.tokens(name)
            val alt = alt(d).let { if (it.isEmpty()) emptyList() else PlaceText.tokens(it) }
            val la = lat(d); val lo = lon(d)
            val addr = if (words.size > 1) PlaceText.tokens(address(d)) else emptyList()
            var got = 0.0
            var coreGot = 0.0
            for ((w, t) in words.withIndex()) {
                var m = match(t, toks, w == words.size - 1 && typing)
                if (w > 0 && joined[w - 1] in toks || w < joined.size && joined[w] in toks) m = max(m, 0.95)
                if (m < 0.95 && alt.isNotEmpty()) m = max(m, 0.95 * match(t, alt, false))
                catSets[w]?.let { if (catId(d) in it) m = max(m, if (t in DISH) 0.42 else if (onlyKind) 1.0 else 0.8) }
                if (w in areaFrom until areaTo) m = max(m, 0.72 * prox(Geo.dist(centre!!.lat, centre.lon, la, lo)))
                if (m < 0.6 && addr.isNotEmpty()) m = max(m, 0.62 * match(t, addr, false))
                got += m * idf[w]
                if (w in coreWords) coreGot += m * idf[w]
            }
            val coreTotal = coreWords.sumOf { idf[it] }
            if (got < 0.5 * total || coreGot < 0.45 * coreTotal) continue
            var s = got / total
            val full = toks.joinToString(" ")
            // The exact name counts most nearby: "HDFC ATM" 20 km away is not the one you mean.
            val close = if (anchor == null) 1.0 else 0.45 + 0.55 * Math.exp(-Geo.dist(anchor.lat, anchor.lon, la, lo) / 6000)
            if (onlyKind) { /* "chemist": a shop called just "Chemist" is no better than the next one */ }
            else if (full == whole) s += 0.2 * close
            else if (toks.joinToString("") { it.removeSuffix("s") } == flat || alt.isNotEmpty() && alt.joinToString(" ") == whole) s += 0.18 * close
            else if (coreWords.size < words.size && full == coreWords.joinToString(" ") { words[it] }) s += 0.15
            else if (full.startsWith(whole)) s += 0.06
            s -= 0.012 * (toks.size - coreWords.size).coerceIn(0, 8)
            s += 0.10 * confidence(d) + when (rank(d)) { 0 -> 0.16; 1 -> 0.09; 2 -> 0.05; 3 -> 0.03; 4 -> 0.0; else -> -0.06 }
            val dist = if (near != null) Geo.dist(near.lat, near.lon, la, lo) else Double.NaN
            if (anchor != null) {
                val ad = Geo.dist(anchor.lat, anchor.lon, la, lo)
                s -= if (onlyKind || nearMe) min(0.7, 0.17 * ln(1 + ad / 700)) else min(0.33, (if (coreWords.all { words[it] in DISH }) 0.1 else 0.065) * ln(1 + ad / 900))
            }
            hits.add(Hit(d, place(d), s, dist))
        }
        hits.sortByDescending { it.score }
        // The same shop from two sources, or an area and its twin.
        val out = ArrayList<Hit>()
        for (h in hits) {
            if (out.any { o -> o.place.name.equals(h.place.name, true) && Geo.dist(o.place.latLon, h.place.latLon) < 180 }) continue
            out.add(h)
            if (out.size >= limit) break
        }
        return out
    }

    private fun touch(d: Int) {
        if (stamp[d] != run) { stamp[d] = run; best[d] = 0f; grow(d) }
    }

    private fun add(d: Int, v: Float) {
        touch(d)
        if (v > best[d]) best[d] = v
    }

    private var fresh = IntArray(4096)
    private var nFresh = 0
    private fun grow(d: Int) {
        if (nFresh == fresh.size) fresh = fresh.copyOf(nFresh * 2)
        fresh[nFresh++] = d
    }

    private var seenRun = IntArray(size)
    private var queryNo = 0

    /** End of one word: move its best scores into the totals. */
    private fun settle() {
        if (nTouched == 0) queryNo++
        for (k in 0 until nFresh) {
            val d = fresh[k]
            if (seenRun[d] != queryNo) {
                seenRun[d] = queryNo
                score[d] = 0f
                if (nTouched == touched.size) touched = touched.copyOf(nTouched * 2)
                touched[nTouched++] = d
            }
            score[d] += best[d]
        }
        nFresh = 0
    }

    private fun prox(d: Double) = when { d < 1500 -> 1.0; d < 5000 -> 1.0 - (d - 1500) / 3500; else -> 0.0 }

    private fun catBonus(d: Int, words: List<String>, sets: Array<Set<Int>?>, idf: DoubleArray): Double {
        var s = 0.0
        for (w in words.indices) if (sets[w]?.contains(catId(d)) == true) s += 0.8 * idf[w]
        return s
    }

    private fun match(t: String, toks: List<String>, prefix: Boolean): Double {
        var m = 0.0
        if (t in SHREE && toks.any { it in SHREE }) return 0.97
        for (j in 0 until toks.size - 1) {
            if (toks[j].length + toks[j + 1].length != t.length) continue
            if (toks[j] + toks[j + 1] == t) m = max(m, 0.95)
        }
        val snd = if (t.length >= 3) PlaceText.sound(t) else ""
        for (k in toks) {
            if (k == t) return 1.0
            if (t.length >= 4 && (k == t + "s" || t == k + "s")) m = max(m, 0.97)
            if (k.startsWith(t) && t.length >= 2) m = max(m, if (prefix) 0.9 else if (t.length >= 4) 0.62 else 0.0)
            if (snd.length >= 2 && PlaceText.sound(k) == snd) {
                val e = PlaceText.edits(t, k, max(2, t.length / 2))
                if (e <= max(2, t.length / 2)) m = max(m, 0.86 - 0.5 * e / max(t.length, k.length))
            }
            if (t.length >= 4 && k.isNotEmpty() && k[0] == t[0]) {
                val lim = if (t.length >= 7) 2 else 1
                val e = PlaceText.edits(t, k, lim)
                if (e <= lim) m = max(m, if (e == 1) 0.7 else 0.5)
            }
        }
        return m
    }

    private val catCache = HashMap<String, Set<Int>>()

    private fun catsFor(t: String): Set<Int> = catCache.getOrPut(t) {
        val w = SYNONYMS[t] ?: if (t in CAT_WORDS) listOf(t) else return@getOrPut emptySet()
        cats.indices.filter { i -> w.any { x -> if (x.startsWith("=")) cats[i].kind.name == x.drop(1) else x in cats[i].words } }.toSet()
    }

    /** Category ids for a kind, for "nearby". */
    fun isKind(i: Int, kinds: Set<PlaceKind>) = kind(i) in kinds

    companion object {
        const val MAGIC = 0x56504C43 // "VPLC"
        const val VERSION = 1
        const val CELL = 0.005
        fun key(row: Int, col: Int) = row * 100_000 + col

        private val SHREE = setOf("shree", "shri", "sri", "shre", "shrii", "she", "sree")
        private val SHOPWORD = setOf("shop", "shops", "store", "stores", "dukan", "place", "wala", "wale")
        private val DISH = setOf("momos", "momo", "dosa", "idli", "biryani", "chaat", "chap", "kebab", "rolls", "chowmein", "paratha", "thali",
            "pizza", "burger", "juice", "chai", "tea", "cake", "coffee")
        private val STOP = setOf("in", "at", "the", "of", "ka", "ki", "ke", "and", "a", "to", "delhi", "near", "opp", "opposite")

        /** What people call kinds of places, and the category words (or =KIND) they mean. */
        private val SYNONYMS: Map<String, List<String>> = buildMap {
            fun m(words: String, vararg means: String) = words.split(' ').forEach { put(it, means.toList()) }
            m("chemist chemists medical medicine medicines pharmacy dawai", "=PHARMACY")
            m("petrol diesel pump fuel cng gas", "=FUEL")
            m("atm cash", "atm")
            m("bank banks", "bank")
            m("hospital hospitals aspatal", "=HOSPITAL")
            m("doctor clinic clinics", "doctors", "clinic", "outpatient", "=HOSPITAL")
            m("dentist dental", "dental", "dentistry")
            m("restaurant restaurants dhaba eatery food khana", "=FOOD")
            m("cafe café coffee chai tea", "=CAFE")
            m("momos momo dosa idli biryani chaat chap kebab rolls chowmein paratha thali", "restaurant", "eatery", "food", "stand")
            m("pizza burger", "pizza", "burger", "fast")
            m("sweets sweet mithai bakery cake halwai", "bakery", "dessert", "candy", "confectionery", "sweet")
            m("juice", "juice", "smoothie")
            m("bar pub beer", "bar", "pub", "lounge", "brewery")
            m("puncture tyre tire tyres mechanic garage repair workshop", "repair", "tire", "garage", "automotive", "wheel", "tyres")
            m("railway rail train", "=RAIL")
            m("station", "=RAIL", "=METRO")
            m("bike motorcycle scooter", "motorcycle", "bike", "bicycle")
            m("salon saloon parlour parlor barber haircut spa beauty", "=SALON")
            m("gym fitness yoga", "=GYM")
            m("grocery kirana ration supermarket provision", "grocery", "supermarket", "convenience", "superstore")
            m("mobile phone recharge", "phone", "mobile")
            m("clothes garments kapda boutique", "clothing", "clothes", "boutique", "apparel")
            m("tailor darzi", "sewing", "tailor", "alterations")
            m("shoes shoe footwear", "shoe")
            m("jeweller jewellers jewellery jewelry", "jewelry")
            m("electrician electrical electronics", "electronics", "electrician", "lighting")
            m("hardware paint sanitary", "hardware", "home", "paint")
            m("stationery stationary books book", "bookstore", "books", "stationery", "stationary")
            m("school schools", "school", "preschool", "elementary", "high")
            m("college university", "college", "university")
            m("coaching tuition classes", "tutoring", "coaching", "preparation")
            m("hotel hotels oyo lodge guest", "=HOTEL")
            m("temple mandir gurudwara gurdwara mosque masjid church", "=WORSHIP")
            m("park garden", "=PARK")
            m("market bazaar bazar mandi", "=MARKET", "=MALL")
            m("mall malls", "=MALL")
            m("police thana", "=POLICE")
            m("cinema movie movies theatre multiplex", "=CINEMA")
            m("metro", "=METRO")
            m("shop shops store dukan", "=SHOP")
            m("bus", "=BUS")
            m("parking", "parking")
            m("optician optical eyewear specs", "eyewear", "vision", "optometrist")
            m("laundry drycleaner", "laundry", "dry")
            m("courier", "courier", "shipping")
            m("lab pathology diagnostic", "laboratory", "diagnostic")
            m("vet veterinary", "veterinarian", "animal", "pet")
        }
        private val CAT_WORDS: Set<String> = SYNONYMS.keys + setOf("station", "toilet", "library", "stadium", "museum", "airport", "temple")
    }
}
