package com.shivansh.raasta.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    const val R = 6_371_008.8

    private fun rad(d: Double) = Math.toRadians(d)

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dp = p2 - p1
        val dl = rad(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }

    fun dist(a: LatLon, b: LatLon) = dist(a.lat, a.lon, b.lat, b.lon)

    /** Compass bearing from 1 to 2, 0..360, clockwise from north. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = rad(lat1)
        val p2 = rad(lat2)
        val dl = rad(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed change of heading from [a] to [b], in (-180, 180]. Positive = to the right. */
    fun turn(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    fun absTurn(a: Double, b: Double) = abs(turn(a, b))

    class Proj(val t: Double, val dist: Double, val lat: Double, val lon: Double)

    /** Closest point to P on segment AB (local flat projection; fine for city-scale segments). */
    fun project(plat: Double, plon: Double, alat: Double, alon: Double, blat: Double, blon: Double): Proj {
        val k = cos(rad(plat))
        val ax = (alon - plon) * k
        val ay = alat - plat
        val bx = (blon - plon) * k
        val by = blat - plat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val lat = alat + (blat - alat) * t
        val lon = alon + (blon - alon) * t
        return Proj(t, dist(plat, plon, lat, lon), lat, lon)
    }

    /** Point [meters] away from (lat, lon) towards [bearingDeg]. */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, meters: Double): LatLon {
        val b = rad(bearingDeg)
        val dLat = meters * cos(b) / R
        val dLon = meters * sin(b) / (R * cos(rad(lat)))
        return LatLon(lat + Math.toDegrees(dLat), lon + Math.toDegrees(dLon))
    }

    /** Douglas–Peucker simplification with a tolerance in metres. */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast().let { it[0] to it[1] }
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val p = points[i]
                val d = project(p.lat, p.lon, points[s].lat, points[s].lon, points[e].lat, points[e].lon).dist
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.addLast(intArrayOf(s, idx))
                stack.addLast(intArrayOf(idx, e))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

/** Vays Maps only covers Delhi for now: routes must start and end inside this box. */
object Delhi {
    const val MIN_LAT = 28.40
    const val MAX_LAT = 28.89
    const val MIN_LON = 76.83
    const val MAX_LON = 77.35

    fun contains(lat: Double, lon: Double) = lat in MIN_LAT..MAX_LAT && lon in MIN_LON..MAX_LON
    fun contains(p: LatLon) = contains(p.lat, p.lon)

    /** left,top,right,bottom as Nominatim wants it. */
    const val VIEWBOX = "$MIN_LON,$MAX_LAT,$MAX_LON,$MIN_LAT"
}
