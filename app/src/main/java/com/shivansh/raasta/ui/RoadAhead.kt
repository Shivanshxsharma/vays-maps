package com.shivansh.raasta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.shivansh.raasta.core.Kind
import com.shivansh.raasta.core.Maneuver
import com.shivansh.raasta.core.Path as RoutePath
import kotlin.math.max
import kotlin.math.min

/** The next few km as a thin bar: cream where you'll be up on a flyover, teal in an underpass, dots at each prompt. */
@Composable
fun RoadAhead(path: RoutePath, progress: Double, maneuvers: List<Maneuver>, windowM: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val h = size.height
        val barH = h * 0.36f
        val y = (h - barH) / 2
        val end = min(path.length, progress + windowM)
        val span = max(1.0, end - progress)
        fun x(d: Double) = ((d - progress) / span * size.width).toFloat().coerceIn(0f, size.width)
        drawRoundRect(C.fill, Offset(0f, y), Size(size.width, barH), CornerRadius(barH / 2))
        drawRoundRect(MapC.route.copy(alpha = 0.55f), Offset(0f, y), Size(x(end), barH), CornerRadius(barH / 2))
        var i = path.segmentAt(progress)
        while (i < path.edges.size && path.cum[i] < end) {
            val w = path.way(i)
            val c = when { w.bridge -> MapC.flyover; w.isUnderground -> MapC.tunnel; else -> null }
            if (c != null) {
                val a = x(max(progress, path.cum[i])); val b = x(min(end, path.cum[i + 1]))
                if (b > a) drawRect(c, Offset(a, y), Size(max(2f, b - a), barH))
            }
            i++
        }
        for (m in maneuvers) {
            if (m.at <= progress || m.at > end || m.kind == Kind.ARRIVE) continue
            drawCircle(C.bg, h * 0.32f, Offset(x(m.at), h / 2))
            drawCircle(Color.White, h * 0.2f, Offset(x(m.at), h / 2))
        }
    }
}
