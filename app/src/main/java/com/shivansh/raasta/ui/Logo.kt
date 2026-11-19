package com.shivansh.raasta.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Vays mark: a "V" made of two roads splitting. The road you skip is an outline, the road you take is
 * solid lavender with an arrow, which is exactly what the voice does at every split.
 * On an ink squircle. [animate] draws the roads in, once.
 */
@Composable
fun VaysMark(size: Dp = 44.dp, animate: Boolean = false, tile: Boolean = true, modifier: Modifier = Modifier) {
    val draw = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) { if (animate) draw.animateTo(1f, tween(1100, delayMillis = 150, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 100f
        if (tile) {
            drawRoundRect(
                Brush.linearGradient(listOf(Ink.i500, Ink.i700, Ink.i900), start = Offset(0f, 0f), end = Offset(this.size.width, this.size.height)),
                cornerRadius = CornerRadius(26f * u),
            )
            // Soft top light, like a glass tile.
            drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), startY = 0f, endY = 50f * u),
                cornerRadius = CornerRadius(26f * u),
            )
            drawRoundRect(Color.White.copy(alpha = 0.10f), cornerRadius = CornerRadius(26f * u), style = Stroke(1.2f * u))
        }
        fun road(x0: Float, y0: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, x1: Float, y1: Float) =
            Path().apply { moveTo(x0 * u, y0 * u); cubicTo(c1x * u, c1y * u, c2x * u, c2y * u, x1 * u, y1 * u) }
        val skip = road(50f, 78f, 50f, 62f, 34f, 52f, 31f, 26f)
        val take = road(50f, 78f, 50f, 62f, 66f, 52f, 69f, 30f)
        val w = 11f * u
        fun part(p: Path, t: Float): Path {
            if (t >= 1f) return p
            val m = PathMeasure().apply { setPath(p, false) }
            return Path().also { m.getSegment(0f, m.length * t, it, true) }
        }
        val t = draw.value
        // Skipped road: hollow outline.
        drawPath(part(skip, t), Color.White.copy(alpha = 0.55f), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(part(skip, t), if (tile) Ink.i700 else C.bg, style = Stroke(w - 4.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Taken road: solid, with a soft glow.
        drawPath(part(take, t), Ink.i300.copy(alpha = 0.40f), style = Stroke(w + 8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(part(take, t), Brush.verticalGradient(listOf(Ink.i100, Ink.i300), startY = 26f * u, endY = 80f * u),
            style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (t > 0.85f) {
            val a = ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)
            val tip = Offset(70.5f * u, 18f * u)
            val head = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x + 10f * u, tip.y + 15f * u)
                lineTo(tip.x - 9.5f * u, tip.y + 13f * u)
                close()
            }
            drawPath(head, Color.White.copy(alpha = a))
        }
    }
}

/** "Vays Maps": weight contrast, like Apple's product wordmarks. */
@Composable
fun Wordmark(fontSize: TextUnit = 22.sp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Vays", style = T.title2.copy(fontSize = fontSize, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp))
        Spacer(Modifier.width(5.dp))
        Text("Maps", style = T.title2.copy(fontSize = fontSize, fontWeight = FontWeight.Normal, color = C.secondary, letterSpacing = (-0.4).sp))
    }
}
