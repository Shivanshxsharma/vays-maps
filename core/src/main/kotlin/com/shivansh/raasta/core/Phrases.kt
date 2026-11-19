package com.shivansh.raasta.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What Vays Maps says. Written for riders with one earphone in: short, and by *position*, never by road name.
 * "The road splits in two. Take the right one." / "Three roads ahead. Take the middle one."
 * Kept in one place so a Hindi phrasebook can slot in later.
 */
object Phrases {
    private fun ordinal(n: Int) = when (n) {
        1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"; else -> "${n}th"
    }

    private fun count(n: Int) = when (n) { 2 -> "two"; 3 -> "three"; 4 -> "four"; else -> "$n" }

    private fun sideWord(s: Side) = when (s) { Side.LEFT -> "left"; Side.RIGHT -> "right"; Side.MIDDLE -> "middle" }

    val Maneuver.isSplit get() = branches >= 2 && index in 0 until branches

    /** "the right one", "the middle one", "the second one from the left". */
    fun which(m: Maneuver): String = when {
        !m.isSplit -> "the ${sideWord(m.side)} one"
        m.branches == 2 -> if (m.index == 0) "the left one" else "the right one"
        m.branches == 3 -> listOf("the left one", "the middle one", "the right one")[m.index]
        else -> when (m.index) {
            0 -> "the leftmost one"
            m.branches - 1 -> "the rightmost one"
            else -> "the ${ordinal(m.index + 1)} one from the left"
        }
    }

    /** Short extra cue about the road's shape, so the rider can confirm they picked right. */
    private fun cue(m: Maneuver): String? = when (m.kind) {
        Kind.FLYOVER_TAKE -> "going up"
        Kind.FLYOVER_SKIP -> "staying down"
        Kind.FLYOVER_STAY -> "staying up"
        Kind.FLYOVER_EXIT -> "going down"
        Kind.UNDERPASS_TAKE -> "going under"
        Kind.UNDERPASS_SKIP -> "staying on top"
        Kind.SERVICE_ROAD -> "the side lane"
        Kind.SLIP_ROAD -> "the slip road"
        else -> null
    }

    private fun turnWord(m: Maneuver): String {
        val a = abs(m.angle)
        val dir = if (m.side == Side.RIGHT) "right" else "left"
        return when {
            a < 60 -> "turn slight $dir"
            a > 125 -> "turn sharp $dir"
            else -> "turn $dir"
        }
    }

    private fun atSignal(m: Maneuver) = if (m.signal) "at the signal, " else ""

    /** The full instruction, without the distance. */
    fun action(m: Maneuver): String = when {
        m.kind == Kind.ARRIVE -> "you will reach your destination"
        m.kind == Kind.TURN -> atSignal(m) + turnWord(m)
        m.kind == Kind.UTURN -> atSignal(m) + "take the U-turn"
        m.kind == Kind.ROUNDABOUT && m.exit < 0 -> "join the circle and keep left"
        m.kind == Kind.ROUNDABOUT -> "at the roundabout, take the ${ordinal(m.exit)} exit"
        m.isSplit -> {
            val intro = if (m.branches == 2) "the road splits in two" else "${count(m.branches)} roads ahead"
            "$intro. Take ${which(m)}" + (cue(m)?.let { ", $it" } ?: "")
        }
        else -> {
            // Couldn't count the roads cleanly: fall back to a side.
            val keep = if (m.side == Side.MIDDLE) "stay in the middle" else "keep ${sideWord(m.side)}"
            keep + (cue(m)?.let { ", $it" } ?: "")
        }
    }

    /** Short form for "Now, …" and "…, then …". */
    fun short(m: Maneuver): String = when {
        m.kind == Kind.ARRIVE -> "you will arrive"
        m.kind == Kind.TURN -> turnWord(m)
        m.kind == Kind.UTURN -> "U-turn"
        m.kind == Kind.ROUNDABOUT && m.exit < 0 -> "join the circle"
        m.kind == Kind.ROUNDABOUT -> "${ordinal(m.exit)} exit"
        m.isSplit -> "take ${which(m)}"
        else -> if (m.side == Side.MIDDLE) "stay in the middle" else "keep ${sideWord(m.side)}"
    }

    /** Big text on the navigation banner. */
    fun banner(m: Maneuver): String = when {
        m.kind == Kind.ARRIVE -> "Arrive"
        m.kind == Kind.TURN -> turnWord(m).cap()
        m.kind == Kind.UTURN -> "U-turn"
        m.kind == Kind.ROUNDABOUT && m.exit < 0 -> "Join the circle"
        m.kind == Kind.ROUNDABOUT -> "${ordinal(m.exit).cap()} exit"
        m.isSplit -> when {
            m.branches <= 3 -> which(m).removePrefix("the ").replace(" one", " road").cap()   // "Right road", "Middle road"
            m.index == 0 -> "Leftmost road"
            m.index == m.branches - 1 -> "Rightmost road"
            else -> "${ordinal(m.index + 1).cap()} from left"
        }
        else -> if (m.side == Side.MIDDLE) "Stay in the middle" else "Keep ${sideWord(m.side)}"
    }

    /** Small line under the banner: what the rider will see. */
    fun detail(m: Maneuver): String {
        val bits = ArrayList<String>()
        if (m.isSplit) bits.add("${m.branches} roads ahead")
        cue(m)?.let { bits.add(it) }
        if (m.kind == Kind.ROUNDABOUT && m.exit > 0) bits.add("roundabout")
        if (m.signal) bits.add("at the signal")
        m.near?.let { bits.add("near $it") }
        return bits.joinToString(" · ").cap()
    }

    fun distance(meters: Double): String {
        if (meters >= 950) {
            val km = (meters / 100).roundToInt() / 10.0
            return if (km == 1.0) "1 kilometre" else "${trim(km)} kilometres"
        }
        val step = if (meters > 300) 100 else 50
        val m = maxOf(step, ((meters / step).roundToInt() * step))
        return "$m metres"
    }

    fun shortDistance(meters: Double): String =
        if (meters >= 1000) "${trim((meters / 100).roundToInt() / 10.0)} km" else "${maxOf(10, (meters / 10).roundToInt() * 10)} m"

    fun duration(seconds: Double): String {
        val min = (seconds / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    private fun trim(x: Double) = if (x == x.toLong().toDouble()) x.toLong().toString() else x.toString()

    /** Kept short: a rider can't take in a paragraph. */
    fun briefing(dest: String, length: Double, time: Double, ms: List<Maneuver>): String {
        val sb = StringBuilder("Route to $dest. ${distance(length)}, about ${spokenDuration(time)}.")
        val first = ms.firstOrNull { it.kind != Kind.ARRIVE }
        if (first != null) sb.append(" In ${distance(first.at)}, ${action(first)}.")
        return sb.toString()
    }

    private fun spokenDuration(s: Double): String {
        val min = (s / 60).roundToInt().coerceAtLeast(1)
        return if (min < 60) "$min minute${if (min > 1) "s" else ""}" else "${min / 60} hour ${min % 60} minutes"
    }

    fun String.cap() = replaceFirstChar { it.uppercase() }
}
