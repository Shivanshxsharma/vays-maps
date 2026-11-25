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

private val MarkTile = Color(0xFF14392A)
private val MarkLime = Color(0xFFB9E769)

/**
 * The Vays mark: a "V" made of two roads splitting. The road you skip is dotted, the road you take is solid with an
 * arrow, which is exactly what the voice does at every split. On a deep green squircle. [animate] plays it once:
 * the dots come down, the road goes up, the arrow lands.
 */
@Composable
fun VaysMark(size: Dp = 44.dp, animate: Boolean = false, tile: Boolean = true, modifier: Modifier = Modifier) {
    val draw = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) { if (animate) draw.animateTo(1f, tween(1100, delayMillis = 150, easing = FastOutSlowInEasing)) }
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 100f
        if (tile) drawRoundRect(MarkTile, cornerRadius = CornerRadius(23f * u))
        // The artwork sits in the tile at 72%, as in docs/art/logo.svg.
        fun x(v: Float) = (13f + 0.72f * v) * u
        fun y(v: Float) = (15f + 0.72f * v) * u
        val k = 0.72f * u
        val t = draw.value
        fun phase(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)
        for (i in 0..3) {
            val a = phase(0.09f * i, 0.09f * i + 0.16f)
            val d = i * 11.57f / 34.731f
            if (a > 0f) drawCircle(MarkLime.copy(alpha = 0.6f * a), 4f * k * (0.5f + 0.5f * a), Offset(x(24f + 13.5f * d), y(22f + 32f * d)))
        }
        val road = Path().apply {
            moveTo(x(41.5f), y(63f))
            lineTo(x(44.5f), y(70.5f))
            quadraticTo(x(48f), y(78.5f), x(51.5f), y(70.5f))
            lineTo(x(70f), y(34f))
        }
        val r = phase(0.42f, 0.88f)
        if (r > 0f) {
            val shown = if (r >= 1f) road else {
                val m = PathMeasure().apply { setPath(road, false) }
                Path().also { m.getSegment(0f, m.length * r, it, true) }
            }
            drawPath(shown, MarkLime, style = Stroke(10f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val h = phase(0.84f, 1f)
        if (h > 0f) {
            val head = Path().apply { moveTo(x(79f), y(16f)); lineTo(x(80.8f), y(37f)); lineTo(x(61.1f), y(27.2f)); close() }
            drawPath(head, MarkLime.copy(alpha = h))
            drawPath(head, MarkLime.copy(alpha = h), style = Stroke(3f * k, join = StrokeJoin.Round))
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
