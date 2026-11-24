package com.shivansh.raasta.tools

import com.shivansh.raasta.core.Delhi
import com.shivansh.raasta.core.Geo
import com.shivansh.raasta.core.LatLon
import com.shivansh.raasta.core.PlaceDb
import com.shivansh.raasta.core.PlaceKind
import com.shivansh.raasta.core.PlaceText
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.TreeMap
import java.util.zip.GZIPInputStream
import kotlin.math.floor

class SrcPlace(
    val name: String, val kind: PlaceKind, val label: String, val lat: Double, val lon: Double,
    val rank: Int, val conf: Double, val address: String, val osm: Boolean, val alt: String = "",
    /** The address names the area the pin is in. */
    val sure: Boolean = osm,
)

/**
 * Turns the raw Overture dump (311k rows, much of it people's pages, wrong pins and duplicates) into places worth
 * riding to, merged with OpenStreetMap's, which are better placed.
 */
object OvertureCleaner {
    private class Row(
        val lat: Double, val lon: Double, val conf: Double, val top: String, val basic: String, val primary: String,
        val postcode: String, val address: String, val name: String,
    )

    /** Things you don't ride to, unless the sources are very sure. */
    private val OFFICE = setOf(
        "real_estate_service", "professional_service", "financial_service", "manufacturer", "corporate_or_business_office",
        "b2b_service", "supplier_or_distributor", "design_service", "technical_service", "b2b_office_and_professional_service",
        "b2b_industrial_and_machine_service", "b2b_transportation_and_storage_service", "b2b_energy_and_utility_service",
        "b2b_science_and_technology_service", "media_service", "event_or_party_service", "travel_service", "home_service",
        "building_or_construction_service", "farm", "wholesaler", "agricultural_service", "environmental_or_ecological_service",
        "industrial_facility_or_service", "legal_service", "attorney_or_law_firm", "housing_or_property_service",
        "social_or_community_service", "civic_organization", "political_organization", "youth_organization", "labor_union",
        "religious_organization", "educational_service", "research_institute", "sport_team", "sport_league",
        "psychic_advising", "astrological_advising", "arts_and_entertainment", "community_and_government",
        "telecommunications_service", "printing_service", "music_venue", "travel_and_transportation", "rental_service", "family_service",
    )
    private val BAD = setOf("adult_entertainment_venue", "casino", "river", "lake", "mountain", "waterfall", "beach", "canal", "geographic_entities", "apartment", "jail_or_prison")
    private val JUNK = Regex(
        "(?i)fan ?page|official\\b.*(page|account)|^(my )?(sweet )?(home|house|ghar|room|office|shop|school|college|park|market|hostel|pg|flat|terrace|bed|gym|temple|mandir|delhi|new delhi|india|gurgaon|gurugram|noida|ghaziabad|gaziabad|faridabad|bahadurgarh|delhi ?ncr|dilli)( ?sweet home)?[ .!]*$|" +
            "red ?light|escort|call girl|massage (boy|girl)|sexy|whatsapp|telegram|instagram|youtube|\\btiktok|memes|lovers$|army$|vlogs?$|photography$.*page",
    )
    private val LETTER = Regex("[A-Za-z\\u0900-\\u097F]")

    fun run(file: File, osm: List<SrcPlace>, areas: List<Pair<String, LatLon>>): List<SrcPlace> {
        val rows = ArrayList<Row>()
        GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).forEachLine { l ->
            if (l.startsWith("#")) return@forEachLine
            val p = l.split('\t')
            if (p.size >= 11) rows.add(Row(p[0].toDouble(), p[1].toDouble(), p[2].toDouble(), p[3], p[4], p[5], p[7], p[9], p[10].trim()))
        }
        val stats = TreeMap<String, Int>()
        fun drop(why: String): Boolean { stats.merge(why, 1, Int::plus); return false }

        // Many pages are dropped on one spot (the middle of "New Delhi"): such a spot says nothing about where they are.
        val stack = HashMap<Long, Int>()
        fun spot(r: Row) = Math.round(r.lat * 1e4) * 10_000_000L + Math.round(r.lon * 1e4)
        for (r in rows) stack.merge(spot(r), 1, Int::plus)

        // Where each postcode really is: the middle of everything that claims it. A place far from there has a wrong pin or address.
        val byPin = rows.filter { it.postcode.matches(Regex("[1-9]\\d{5}")) && stack.getValue(spot(it)) < 6 && it.conf > 0.5 }.groupBy { it.postcode }
        val pinAt = byPin.filter { it.value.size >= 8 }.mapValues { (_, l) ->
            LatLon(l.map { it.lat }.sorted()[l.size / 2], l.map { it.lon }.sorted()[l.size / 2])
        }
        val pinSpread = pinAt.mapValues { (k, c) -> byPin.getValue(k).map { Geo.dist(c.lat, c.lon, it.lat, it.lon) }.sorted().let { it[it.size * 3 / 4] } }
        // Where each named area is.
        val areaAt = HashMap<String, MutableList<LatLon>>()
        for ((n, p) in areas) PlaceText.tokens(n).joinToString(" ").let { if (it.length >= 5) areaAt.getOrPut(it) { ArrayList() }.add(p) }
        val areaNames = areas.map { PlaceText.tokens(it.first).joinToString(" ") }.toHashSet()

        /** +1 the address agrees with the pin, -1 it says somewhere else, 0 can't tell. */
        fun agrees(r: Row): Int {
            var good = false; var bad = false
            pinAt[r.postcode]?.let { c ->
                val d = Geo.dist(c.lat, c.lon, r.lat, r.lon)
                if (d < maxOf(1800.0, pinSpread.getValue(r.postcode) * 1.6)) good = true else if (d > maxOf(5000.0, pinSpread.getValue(r.postcode) * 3)) bad = true
            }
            val t = PlaceText.tokens(r.address)
            var i = 0
            while (i < t.size) {
                var used = 1
                for (len in minOf(3, t.size - i) downTo 1) {
                    val at = areaAt[t.subList(i, i + len).joinToString(" ")] ?: continue
                    val d = at.minOf { Geo.dist(it.lat, it.lon, r.lat, r.lon) }
                    if (d < 2500) good = true else if (d > 6000) bad = true
                    used = len
                    break
                }
                i += used
            }
            return if (bad && !good) -1 else if (good && !bad) 1 else 0
        }

        val cleaned = ArrayList<SrcPlace>()
        for (r in rows) {
            var sure = false
            val keep = run {
                val n = r.name
                if (n.length < 3 || n.length > 70 || !LETTER.containsMatchIn(n) || '_' in n) return@run drop("name")
                if (JUNK.containsMatchIn(n)) return@run drop("junk")
                if (n.any { Character.isSurrogate(it) } && r.conf < 0.6) return@run drop("emoji")
                if (PlaceText.tokens(n).joinToString(" ") in areaNames && r.basic !in setOf("shopping_mall", "train_station", "market")) return@run drop("area name")
                if (r.basic in BAD) return@run drop("category")
                if (Regex("(?i)metro station").containsMatchIn(n) && r.top == "travel_and_transportation") return@run drop("metro (OSM has it)")
                val a = agrees(r)
                if (a < 0) return@run drop("address elsewhere")
                val s = stack.getValue(spot(r))
                val addr = r.address.length >= 10
                sure = a > 0 && addr
                // Crowded spots: malls and hospitals are fine, city middles are not.
                if (s >= 60 && !(a > 0 && r.conf >= 0.75)) return@run drop("crowded spot")
                if (s >= 8 && !(a > 0 && r.conf >= 0.5) && r.conf < 0.8) return@run drop("crowded spot")
                val need = when {
                    r.basic.isEmpty() -> 0.6
                    r.basic in OFFICE -> if (addr) 0.55 else 0.75
                    r.basic == "historic_site" -> 0.8
                    r.basic == "park" || r.basic == "hospital" && !addr -> 0.6
                    a > 0 && addr -> 0.22
                    addr -> 0.34
                    else -> 0.48
                }
                if (r.conf < need) return@run drop("not sure enough")
                true
            }
            if (!keep) continue
            val kind = kindOf(r.top, r.basic, r.primary)
            val rank = when {
                r.basic in OFFICE || r.basic.isEmpty() || r.basic == "historic_site" -> 5
                kind in setOf(PlaceKind.OFFICE, PlaceKind.OTHER) -> 5
                kind in setOf(PlaceKind.FOOD, PlaceKind.CAFE, PlaceKind.PHARMACY, PlaceKind.BANK, PlaceKind.MALL, PlaceKind.CINEMA, PlaceKind.FUEL, PlaceKind.POLICE, PlaceKind.HOSPITAL, PlaceKind.HOTEL, PlaceKind.WORSHIP, PlaceKind.SHOP) ->
                    if (r.conf >= 0.7) 3 else 4
                r.conf >= 0.8 -> 3
                else -> 4
            }
            cleaned.add(SrcPlace(tidy(r.name), kind, label(r.primary.ifEmpty { r.basic }, kind), r.lat, r.lon, rank, r.conf, address(r.address), false, sure = sure))
        }
        println("Overture: ${rows.size} rows, kept ${cleaned.size}; dropped $stats")

        // A second pin of a known place (malls, hospitals, monuments…) somewhere else entirely: the known one is right.
        val known = HashMap<String, MutableList<SrcPlace>>()
        for (p in osm) if (p.kind in LANDMARKS && p.rank <= 2) flat(p.name).let { if (it.length >= 6) known.getOrPut(it) { ArrayList() }.add(p) }
        val strays = cleaned.filter { p ->
            p.kind in LANDMARKS + setOf(PlaceKind.OTHER, PlaceKind.OFFICE, PlaceKind.SHOP) &&
                flat(p.name).let { f ->
                    (known[f] ?: if (p.kind in setOf(PlaceKind.MALL, PlaceKind.LANDMARK) && f.length >= 8) known.entries.filter { f in it.key || it.key.length >= 8 && it.key in f }.flatMap { it.value }.ifEmpty { null } else null)
                        ?.let { l -> l.minOf { Geo.dist(it.lat, it.lon, p.lat, p.lon) } > (if (known[f] != null) 250 else 3000) } == true
                }
        }.toSet()
        println("Second pins of known places dropped: ${strays.size}")
        cleaned.removeAll(strays)

        // Famous places get pinned all over the city by their visitors. Believe the pins that others nearby mention
        // ("Croma, Select Citywalk"), that OSM has, or whose address fits; drop the rest.
        val near = HashMap<Long, MutableList<Pair<SrcPlace, String>>>()
        fun c5(la: Double, lo: Double) = floor(la / 0.005).toLong() * 1_000_000 + floor(lo / 0.005).toLong()
        fun snd(s: String) = PlaceText.tokens(s).joinToString("") { PlaceText.sound(it) }
        for (p in osm + cleaned) near.getOrPut(c5(p.lat, p.lon)) { ArrayList() }.add(p to snd(p.name) + "|" + snd(p.address))
        fun support(p: SrcPlace, key: String): Int {
            var n = 0
            val a = floor(p.lat / 0.005).toLong(); val b = floor(p.lon / 0.005).toLong()
            for (i in -1..1) for (j in -1..1) for ((q, s) in near[(a + i) * 1_000_000 + b + j].orEmpty())
                if (q !== p && key in s && Geo.dist(p.lat, p.lon, q.lat, q.lon) < 500) n++
            return n
        }
        val wrong = HashSet<SrcPlace>()
        for ((key, l) in (osm.filter { it.kind in FAMOUS } + cleaned.filter { it.kind in FAMOUS }).groupBy { flat(it.name) }) {
            if (key.length < 5 || l.size < 2) continue
            val far = l.any { x -> l.any { y -> Geo.dist(x.lat, x.lon, y.lat, y.lon) > 1000 } }
            if (!far) continue
            val sup = l.associateWith { if (it.osm) 99 else support(it, key) }
            val most = sup.values.max()
            if (most == 0) continue
            for (p in l) if (!p.osm && !(p.sure && sup.getValue(p) > 0) && sup.getValue(p) < (if (p.sure) 1 else maxOf(2, most / 3))) wrong.add(p)
        }
        // One pin only, nobody around mentions it, but people elsewhere do: that pin is wrong too.
        val everything = near.values.flatten()
        for (p in cleaned) {
            if (p in wrong || p.sure || p.kind !in setOf(PlaceKind.MALL, PlaceKind.LANDMARK, PlaceKind.MARKET)) continue
            val key = flat(p.name)
            if (key.length < 8 || support(p, key) > 0) continue
            if (everything.count { (q, s) -> key in s && Geo.dist(p.lat, p.lon, q.lat, q.lon) > 2000 } >= 4) wrong.add(p)
        }
        println("Unsupported pins of famous places dropped: ${wrong.size}")
        cleaned.removeAll(wrong)

        // One entry per real place. OSM first (better pins), then the surest.
        val grid = HashMap<Long, MutableList<SrcPlace>>()
        fun cell(la: Double, lo: Double) = floor(la / 0.002).toLong() * 1_000_000 + floor(lo / 0.002).toLong()
        val out = ArrayList<SrcPlace>()
        var dups = 0
        for (p in osm + cleaned.sortedByDescending { it.conf }) {
            val key = sounds(p.name)
            val c = floor(p.lat / 0.002).toLong(); val d = floor(p.lon / 0.002).toLong()
            var dup = false
            loop@ for (i in -1..1) for (j in -1..1) for (q in grid[(c + i) * 1_000_000 + d + j].orEmpty()) {
                val dist = Geo.dist(p.lat, p.lon, q.lat, q.lon)
                if (p.osm && q.osm) { if (q.name == p.name && q.kind == p.kind && dist < 60) { dup = true; break@loop }; continue }
                if (dist < 170 && same(key, sounds(q.name)) && !(p.kind == PlaceKind.BANK && q.kind == PlaceKind.BANK && dist > 60)) { dup = true; break@loop }
            }
            if (dup) { dups++; continue }
            out.add(p)
            grid.getOrPut(cell(p.lat, p.lon)) { ArrayList() }.add(p)
        }
        println("Places: ${out.size} after merging with ${osm.size} from OSM ($dups duplicates)")
        return out
    }

    private val LANDMARKS = setOf(PlaceKind.LANDMARK, PlaceKind.MALL, PlaceKind.MARKET, PlaceKind.HOSPITAL, PlaceKind.EDUCATION, PlaceKind.RAIL,
        PlaceKind.METRO, PlaceKind.AIRPORT, PlaceKind.PARK, PlaceKind.CINEMA, PlaceKind.WORSHIP)
    private val FAMOUS = setOf(PlaceKind.LANDMARK, PlaceKind.MALL, PlaceKind.MARKET, PlaceKind.HOSPITAL, PlaceKind.EDUCATION, PlaceKind.RAIL, PlaceKind.AIRPORT,
        PlaceKind.PARK, PlaceKind.CINEMA)
    private val EXTRA = setOf("mall", "the", "new", "delhi", "hospital", "complex", "tourist", "monument")

    /** "Select City Walk Mall" and "Select CityWalk" are one place. Too-common names ("Shiv Mandir") are left alone. */
    private fun flat(n: String): String {
        val t = PlaceText.tokens(n)
        if (t.any { it in setOf("mandir", "temple", "masjid", "gurudwara", "park", "school", "church", "market", "public", "bank") }) return ""
        return t.filter { it !in EXTRA }.ifEmpty { t }.joinToString("") { PlaceText.sound(it) }
    }

    private val FILLER = setOf("the", "and", "pvt", "ltd", "limited", "private", "new", "delhi", "india", "restaurant", "shop", "store", "sweets", "hotel", "branch")

    private fun sounds(n: String): List<String> {
        val t = PlaceText.tokens(n.substringBefore(" - ").substringBefore(" | ").substringBefore(','))
        val f = t.filter { it !in FILLER }.ifEmpty { t }
        return f.map { PlaceText.sound(it).ifEmpty { it } }
    }

    private fun same(a: List<String>, b: List<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val x = a.toSet(); val y = b.toSet()
        val common = (x intersect y).size
        return common >= 2 && common == minOf(x.size, y.size) && maxOf(x.size, y.size) - common <= 1
    }

    private fun tidy(n: String): String {
        var s = n.replace(Regex("\\s*[|•~*\"]+\\s*"), " ").replace(Regex("[\\uD800-\\uDFFF\\u2600-\\u27BF\\uFE0F]"), "").replace(Regex("\\s+"), " ").trim().trim('-', ',', '.', ':').trim()
        // ALL CAPS and all lower case read badly on a map.
        if (s.length > 4 && s.any { it.isLetter() } && (s == s.uppercase() && ' ' in s || s == s.lowercase()))
            s = s.split(' ').joinToString(" ") { w -> if (w.length <= 3 && s == s.uppercase()) w else w.lowercase().replaceFirstChar { it.uppercase() } }
        return s
    }

    private val TAIL = Regex("(?i)[,\\s]*(new )?delhi([ -]*ncr)?[,\\s]*(\\d{6})?[,\\s]*(india)?[,.\\s]*$|[,\\s]*\\d{6}[,.\\s]*$|[,\\s]*india[,.\\s]*$")

    private fun address(a: String): String {
        var s = a.replace(Regex("\\s+"), " ").trim()
        repeat(3) { s = s.replace(TAIL, "").trim().trim(',') }
        if (s.length > 64) s = s.take(64).substringBeforeLast(if (',' in s.take(64).drop(24)) ',' else ' ').trim()
        return if (s.length < 4 || s.all { it.isDigit() }) "" else s
    }

    private fun label(cat: String, kind: PlaceKind): String {
        if (cat.isEmpty() || cat in setOf("shopping", "education", "health_care", "lodging", "food_and_drink", "travel_and_transportation")) return kind.label
        return NAMES[cat] ?: cat.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private val NAMES = mapOf(
        "gas_station" to "Petrol pump", "fueling_station" to "Petrol pump", "pharmacy" to "Chemist", "bank_or_credit_union" to "Bank", "credit_union" to "Bank",
        "hindu_place_of_worship" to "Temple", "muslim_place_of_worship" to "Mosque", "christian_place_of_worship" to "Church",
        "party_and_event_planning" to "Event planner", "doctors_office" to "Doctor", "college_university" to "College", "beauty_salon" to "Beauty parlour",
        "automotive_repair" to "Garage", "auto_parts_store" to "Auto parts", "mobile_phone_store" to "Mobile shop", "grocery_store" to "Grocery",
        "casual_eatery" to "Eatery", "food_stand" to "Food stall", "historic_site" to "Landmark", "resort" to "Hotel", "bus_station" to "Bus stop",
        "fashion_and_apparel_store" to "Clothes shop", "clothing_store" to "Clothes shop", "warehouse_club_store" to "Store", "movie_theater" to "Cinema",
        "attorney_or_law_firm" to "Lawyer", "atm" to "ATM", "it_service_and_computer_repair" to "Computer repair", "place_of_learning" to "Institute",
    )

    fun kindOf(top: String, basic: String, primary: String): PlaceKind = when {
        basic in setOf("cafe", "coffee_shop", "non_alcoholic_beverage_venue", "smoothie_juice_bar") || primary in setOf("tea_room", "bakery", "dessert_shop", "ice_cream_shop") -> PlaceKind.CAFE
        top == "food_and_drink" -> PlaceKind.FOOD
        basic in setOf("gas_station", "fueling_station", "ev_charging_station") -> PlaceKind.FUEL
        basic == "pharmacy_and_drug_store" -> PlaceKind.PHARMACY
        basic in setOf("bank_or_credit_union", "atm") -> PlaceKind.BANK
        basic in setOf("shopping_mall", "department_store") -> PlaceKind.MALL
        basic in setOf("market", "farmers_market") -> PlaceKind.MARKET
        basic in setOf("automotive_service", "vehicle_parts_store", "vehicle_service", "auto_dealer", "vehicle_dealer") -> PlaceKind.GARAGE
        top == "shopping" -> PlaceKind.SHOP
        basic in setOf("personal_or_beauty_service", "wellness_service") -> PlaceKind.SALON
        basic in setOf("laundry_service", "animal_or_pet_service", "shipping_or_delivery_service", "printing_service") -> PlaceKind.SHOP
        top == "health_care" -> PlaceKind.HOSPITAL
        top == "lodging" -> PlaceKind.HOTEL
        basic.endsWith("place_of_worship") -> PlaceKind.WORSHIP
        basic in setOf("monument", "fort", "castle", "museum", "historic_site", "zoo", "amusement_park", "stadium_arena", "cultural_center", "planetarium", "aquarium") -> PlaceKind.LANDMARK
        top == "education" -> PlaceKind.EDUCATION
        basic in setOf("movie_theater", "theatre_venue") -> PlaceKind.CINEMA
        basic in setOf("gym", "sport_or_fitness_facility", "fitness_studio", "swimming_pool", "sport_or_recreation_club") -> PlaceKind.GYM
        basic in setOf("park", "national_park", "playground", "garden", "nature_reserve") -> PlaceKind.PARK
        basic == "police_station" -> PlaceKind.POLICE
        basic == "train_station" -> PlaceKind.RAIL
        basic == "public_transit_facility_or_service" -> PlaceKind.BUS
        top == "services_and_business" || top == "community_and_government" -> PlaceKind.OFFICE
        else -> PlaceKind.OTHER
    }
}

/** Writes the file that core/PlaceDb reads. */
object PlaceDbWriter {
    fun write(places: List<SrcPlace>, file: File) {
        val sorted = places.filter { Delhi.contains(it.lat, it.lon) }.sortedWith(
            compareBy({ floor(it.lat / PlaceDb.CELL).toInt() }, { floor(it.lon / PlaceDb.CELL).toInt() }, { it.rank }, { -it.conf }),
        )
        val n = sorted.size
        val cats = LinkedHashMap<String, Int>()
        val catOf = IntArray(n) { i -> sorted[i].let { cats.getOrPut("${it.kind.name}\t${it.label}") { cats.size } } }

        val alts = Array(n) { sorted[it].let { p -> listOf(p.alt, acronym(p)).filter { a -> a.isNotEmpty() }.joinToString(" ") } }
        val names = ByteArrayOutputStream(); val nameOff = IntArray(n + 1)
        val addrs = ByteArrayOutputStream(); val addrOff = IntArray(n + 1)
        val post = HashMap<String, ArrayList<Int>>()
        for ((i, p) in sorted.withIndex()) {
            nameOff[i] = names.size(); names.write(p.name.toByteArray())
            addrOff[i] = addrs.size(); addrs.write((if (alts[i].isEmpty()) p.address else alts[i] + "\t" + p.address).toByteArray())
            val t = PlaceText.tokens(p.name)
            val all = t.toMutableSet()
            all.addAll(PlaceText.tokens(alts[i]))
            // "D Mart" is also findable as "dmart", "M G Road" as "mg".
            t.zipWithNext { a, b -> if (a.length <= 2 || b.length <= 2) all.add(a + b) }
            for (w in all) if (w.length <= 24) post.getOrPut(w) { ArrayList() }.add(i)
        }
        nameOff[n] = names.size(); addrOff[n] = addrs.size()

        val toks = post.keys.sortedWith { a, b -> cmp(a.toByteArray(), b.toByteArray()) }
        val tokBlob = ByteArrayOutputStream(); val tokOff = IntArray(toks.size + 1)
        val postBlob = ByteArrayOutputStream(); val postOff = IntArray(toks.size + 1)
        val df = IntArray(toks.size)
        for ((k, t) in toks.withIndex()) {
            tokOff[k] = tokBlob.size(); tokBlob.write(t.toByteArray())
            postOff[k] = postBlob.size()
            var prev = 0
            for (d in post.getValue(t)) {
                var v = d - prev; prev = d
                while (v >= 0x80) { postBlob.write((v and 0x7F) or 0x80); v = v ushr 7 }
                postBlob.write(v)
            }
            df[k] = post.getValue(t).size
        }
        tokOff[toks.size] = tokBlob.size(); postOff[toks.size] = postBlob.size()
        val sound = toks.indices.sortedBy { PlaceText.sound(toks[it]) }.toIntArray()

        val cellKeys = ArrayList<Int>(); val cellStart = ArrayList<Int>()
        for ((i, p) in sorted.withIndex()) {
            val key = PlaceDb.key(floor(p.lat / PlaceDb.CELL).toInt(), floor(p.lon / PlaceDb.CELL).toInt())
            if (cellKeys.isEmpty() || cellKeys.last() != key) { cellKeys.add(key); cellStart.add(i) }
        }
        cellStart.add(n)
        check(cellKeys == cellKeys.sorted()) { "cells out of order" }

        val body = ByteArrayOutputStream()
        val d = DataOutputStream(body)
        val head = 20 + 16 * 4
        val off = ArrayList<Int>()
        fun at() { while (body.size() % 4 != 0) body.write(0); off.add(head + body.size()) }
        fun ints(a: IntArray) { at(); for (x in a) d.writeInt(x) }
        ints(IntArray(n) { Math.round(sorted[it].lat * 1e6).toInt() })
        ints(IntArray(n) { Math.round(sorted[it].lon * 1e6).toInt() })
        at(); for (c in catOf) d.writeShort(c)
        at(); for (p in sorted) d.writeByte(p.rank)
        at(); for (p in sorted) d.writeByte((p.conf.coerceIn(0.0, 1.0) * 255).toInt())
        ints(nameOff); at(); names.writeTo(d)
        ints(addrOff); at(); addrs.writeTo(d)
        ints(cellKeys.toIntArray()); ints(cellStart.toIntArray())
        ints(tokOff); at(); tokBlob.writeTo(d)
        ints(postOff); at(); postBlob.writeTo(d)
        // Misc block: document counts, words by sound, category table.
        ints(IntArray(3))
        val misc = off.removeAt(off.size - 1)
        ints(df); val oDf = off.removeAt(off.size - 1)
        ints(sound); val oSound = off.removeAt(off.size - 1)
        val catText = cats.keys.joinToString("\n").toByteArray()
        ints(intArrayOf(catText.size)); val oCats = off.removeAt(off.size - 1)
        d.write(catText)
        check(off.size == 15) { off.size }
        val bytes = body.toByteArray()
        fun put(pos: Int, v: Int) { for (k in 0..3) bytes[pos - head + k] = (v ushr (24 - 8 * k)).toByte() }
        put(misc, oDf); put(misc + 4, oSound); put(misc + 8, oCats)
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { o ->
            o.writeInt(PlaceDb.MAGIC); o.writeInt(PlaceDb.VERSION); o.writeInt(n); o.writeInt(cellKeys.size); o.writeInt(toks.size)
            for (x in off) o.writeInt(x)
            o.writeInt(misc)
            o.write(bytes)
        }
        println("Place file: $n places, ${toks.size} words, ${cats.size} categories, ${file.length() / 1024} KB " +
            "(names ${names.size() / 1024}, addresses ${addrs.size() / 1024}, index ${(tokBlob.size() + postBlob.size() + toks.size * 16) / 1024})")
    }

    /** "Netaji Subhas University of Technology" is NSUT to everyone. */
    private fun acronym(p: SrcPlace): String {
        if (p.kind !in setOf(PlaceKind.EDUCATION, PlaceKind.HOSPITAL, PlaceKind.LANDMARK, PlaceKind.OFFICE) || p.rank > 4 && !p.osm) return ""
        val t = PlaceText.tokens(p.name.substringBefore(',').substringBefore('(').substringBefore(" - ")).filter { it !in setOf("of", "and", "the", "for", "in") && it[0].isLetter() && it[0].code < 128 }
        if (t.size !in 3..6) return ""
        return t.joinToString("") { it.take(1) }
    }

    private fun cmp(a: ByteArray, b: ByteArray): Int {
        for (k in 0 until minOf(a.size, b.size)) {
            val d = (a[k].toInt() and 0xFF) - (b[k].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
