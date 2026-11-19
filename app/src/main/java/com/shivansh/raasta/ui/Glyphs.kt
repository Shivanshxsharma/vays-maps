package com.shivansh.raasta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shivansh.raasta.core.Kind
import com.shivansh.raasta.core.Maneuver
import com.shivansh.raasta.core.Side
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Highlight colour for a maneuver's text. */
fun accentOf(k: Kind): Color = C.label

/** Roads you should not take: quiet, same weight family, no outline tricks. */
private val Other = Color(0xFF3B3E38)

/**
 * Maneuver icons in the Apple Maps manner: the road to take is one solid stroke ending in a soft, rounded
 * arrowhead; every other road at the split is a quiet grey line. Nothing else, so it reads in a glance.
 * [hollow] is kept for call-site compatibility (the old outline style needed the background colour).
 */
@Composable
fun ManeuverGlyph(m: Maneuver, size: Dp = 56.dp, @Suppress("UNUSED_PARAMETER") hollow: Color = Color.Unspecified, tint: Color = C.blue) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 100f
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        val w = 13f * u
        val take = tint

        fun line(p: Path, c: Color, width: Float = w) = drawPath(p, c, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

        when (m.kind) {
            Kind.TURN -> {
                val a = Math.toRadians(m.angle.coerceIn(-150.0, 150.0))
                val corner = o(50f, 54f)
                val d = Offset(sin(a).toFloat(), -cos(a).toFloat())
                val tip = corner + d * (36f * u)
                line(Path().apply { moveTo(corner.x, corner.y); lineTo(50f * u, 8f * u) }, Other, w * 0.8f)  // road straight on
                val base = tip - d * (17f * u)
                line(Path().apply { moveTo(50f * u, 95f * u); lineTo(corner.x, corner.y); lineTo(base.x, base.y) }, take)
                arrowHead(tip, d, take, u)
            }
            Kind.UTURN -> {
                val dir = if (m.side == Side.RIGHT) 1f else -1f
                val x2 = 50f + 34f * dir
                val tip = o(x2, 76f)
                line(Path().apply {
                    moveTo(50f * u, 95f * u); lineTo(50f * u, 42f * u)
                    cubicTo(50f * u, 17f * u, x2 * u, 17f * u, x2 * u, 42f * u)
                    lineTo(x2 * u, 59f * u)
                }, take)
                arrowHead(tip, Offset(0f, 1f), take, u)
            }
            Kind.ROUNDABOUT -> {
                val c = o(50f, 44f)
                val r = 19f * u
                drawCircle(Other, r, c, style = Stroke(w * 0.75f))
                line(Path().apply { moveTo(50f * u, 95f * u); lineTo(50f * u, 44f * u + r) }, take)
                val ang = if (m.exit < 0) Math.toRadians(45.0) else Math.toRadians(0.0) // 0 = straight up
                val d = Offset(sin(ang).toFloat(), -cos(ang).toFloat())
                val start = c + d * r
                val tip = c + d * (r + 30f * u)
                line(Path().apply { moveTo(start.x, start.y); lineTo((tip - d * (17f * u)).x, (tip - d * (17f * u)).y) }, take)
                arrowHead(tip, d, take, u)
            }
            Kind.ARRIVE -> {
                // A calm target, not a pin: ring and dot.
                drawCircle(take, 26f * u, o(50f, 50f), style = Stroke(9f * u))
                drawCircle(take, 10f * u, o(50f, 50f))
            }
            else -> {
                // A split: n roads fanned left to right, yours solid.
                val n = if (m.branches >= 2) m.branches.coerceAtMost(4) else 2
                val pick = when {
                    m.branches >= 2 && m.index >= 0 -> m.index
                    m.side == Side.LEFT -> 0
                    m.side == Side.RIGHT -> n - 1
                    else -> n / 2
                }
                val spread = when (n) { 2 -> 30f; 3 -> 42f; else -> 50f }
                val split = o(50f, 60f)
                fun endOf(i: Int): Offset {
                    val a = Math.toRadians((-spread + 2 * spread * i / (n - 1)).toDouble())
                    return o(50f + 40f * sin(a).toFloat(), 60f - 52f * cos(a).toFloat())
                }
                fun branch(i: Int, stopShort: Float = 0f): Pair<Path, Offset> {
                    val e = endOf(i)
                    val c1 = Offset(split.x, split.y - 20f * u)
                    val c2 = Offset(e.x - (e.x - split.x) * 0.15f, e.y + 22f * u)
                    val dir = (e - c2).let { it / it.getDistance() }
                    val end = e - dir * stopShort
                    return Path().apply { moveTo(split.x, split.y); cubicTo(c1.x, c1.y, c2.x, c2.y, end.x, end.y) } to dir
                }
                for (i in 0 until n) if (i != pick) line(branch(i).first, Other, w * 0.8f)
                line(Path().apply { moveTo(50f * u, 95f * u); lineTo(split.x, split.y) }, take)
                val (p, dir) = branch(pick, stopShort = 16f * u)
                line(p, take)
                arrowHead(endOf(pick) + dir * (2f * u), dir, take, u)
            }
        }
    }
}

/** Soft, rounded arrowhead (filled triangle with round-joined edges), pointing along [dir] with its tip at [tip]. */
private fun DrawScope.arrowHead(tip: Offset, dir: Offset, color: Color, u: Float) {
    val len = hypot(dir.x, dir.y).takeIf { it > 0 } ?: return
    val d = Offset(dir.x / len, dir.y / len)
    val perp = Offset(-d.y, d.x)
    val base = tip - d * (20f * u)
    val p = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo((base + perp * (15f * u)).x, (base + perp * (15f * u)).y)
        lineTo((base - perp * (15f * u)).x, (base - perp * (15f * u)).y)
        close()
    }
    drawPath(p, color)
    drawPath(p, color, style = Stroke(5f * u, join = StrokeJoin.Round))
}
