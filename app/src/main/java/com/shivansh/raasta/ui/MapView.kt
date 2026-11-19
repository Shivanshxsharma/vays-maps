package com.shivansh.raasta.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shivansh.raasta.LoadedPack
import com.shivansh.raasta.core.Kind
import com.shivansh.raasta.core.LatLon
import com.shivansh.raasta.core.Maneuver
import com.shivansh.raasta.core.NavState
import com.shivansh.raasta.core.Path as RoutePath
import com.shivansh.raasta.core.RoadClass
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Flat metre projection around a centre point. Fine for a city-sized area. */
class Projection(private val lat0: Double, private val lon0: Double) {
    private val k = cos(Math.toRadians(lat0)) * 111_320.0
    fun x(lon: Double) = ((lon - lon0) * k).toFloat()
    fun y(lat: Double) = (-(lat - lat0) * 111_320.0).toFloat()
    fun of(p: LatLon) = Offset(x(p.lon), y(p.lat))
}

/** Road layers, bottom to top. Each has its own colour so road types stay readable at a glance. */
internal enum class Layer { SERVICE, MINOR, MAJOR, HIGHWAY, TUNNEL, BRIDGE }

/**
 * Vector layers for one pack, built once on a background thread. Roads are also bucketed into ~300 m cells
 * so the riding view only draws what's near the camera (drawing the whole corridor every frame was the jank).
 */
class MapLayers(val lp: LoadedPack) {
    val proj: Projection
    private val all = Array(Layer.entries.size) { Path() }
    private val cells = HashMap<Long, Array<Path?>>()
    val minX: Float; val maxX: Float; val minY: Float; val maxY: Float

    init {
        val p = lp.pack
        proj = Projection((p.lat.min() + p.lat.max()) / 2, (p.lon.min() + p.lon.max()) / 2)
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (w in p.ways) {
            val layer = when {
                w.bridge -> Layer.BRIDGE
                w.isUnderground -> Layer.TUNNEL
                w.cls == RoadClass.SERVICE || w.cls == RoadClass.LIVING -> Layer.SERVICE
                w.cls.rank >= RoadClass.PRIMARY.rank -> Layer.HIGHWAY
                w.cls.rank >= RoadClass.TERTIARY.rank || w.link -> Layer.MAJOR
                else -> Layer.MINOR
            }.ordinal
            var px = 0f; var py = 0f
            var curCell = Long.MIN_VALUE
            var cellPath: Path? = null
            for ((k, n) in w.nodes.withIndex()) {
                val x = proj.x(p.lon[n]); val y = proj.y(p.lat[n])
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (k == 0) all[layer].moveTo(x, y) else {
                    all[layer].lineTo(x, y)
                    val c = cellOf((px + x) / 2, (py + y) / 2)
                    if (c != curCell) {
                        val arr = cells.getOrPut(c) { arrayOfNulls(Layer.entries.size) }
                        cellPath = arr[layer] ?: Path().also { arr[layer] = it }
                        cellPath.moveTo(px, py)
                        curCell = c
                    }
                    cellPath!!.lineTo(x, y)
                }
                px = x; py = y
            }
        }
        minX = x0; maxX = x1; minY = y0; maxY = y1
    }

    internal fun layer(l: Layer): Path = all[l.ordinal]

    /** Paths of one layer in the cells within [r] metres of (cx, cy). */
    internal fun near(l: Layer, cx: Float, cy: Float, r: Float, out: ArrayList<Path>) {
        out.clear()
        val c0 = floor((cx - r) / CELL).toInt(); val c1 = floor((cx + r) / CELL).toInt()
        val r0 = floor((cy - r) / CELL).toInt(); val r1 = floor((cy + r) / CELL).toInt()
        for (i in c0..c1) for (j in r0..r1) cells[key(i, j)]?.get(l.ordinal)?.let { out.add(it) }
    }

    fun routePath(path: RoutePath): Path {
        val out = Path()
        for (i in path.nodes.indices) {
            val x = proj.x(path.lon(i)); val y = proj.y(path.lat(i))
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        return out
    }

    private fun cellOf(x: Float, y: Float) = key(floor(x / CELL).toInt(), floor(y / CELL).toInt())
    private fun key(i: Int, j: Int) = (i + 100_000).toLong() * 1_000_000L + (j + 100_000)

    companion object { const val CELL = 300f }
}

/**
 * Map colours, chosen for readability rather than brand: warm sand for main roads and cool greys for the
 * rest (the trick Google's night style uses), cream flyover decks, teal dashed underpasses. The violet
 * accent is reserved for the route, the one thing that must never be confused with a road.
 */
object MapC {
    val land = Color(0xFF101110)
    val service = Color(0xFF1C1D1B)
    val minor = Color(0xFF2D2F2B)
    val major = Color(0xFF52554E)
    val highway = Color(0xFF8C7A55)
    val casing = Color(0xFF050505)
    val flyover = Color(0xFFE8D5A6)
    val tunnel = Color(0xFF3BB5A4)
    val route = Color(0xFFB0E454)
    val routeCasing = Color(0xFF224F10)
    val traveled = Color(0xFF575A52)
}

private class Strokes(private val s: Float, private val widen: Float) {
    private fun w(px: Float) = px * widen / s
    private fun round(px: Float) = Stroke(w(px), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val service = round(3f)
    val minor = round(5f)
    val majorCase = round(10f); val major = round(7f)
    val highwayCase = round(13f); val highway = round(9f)
    val tunnel = Stroke(w(6f), cap = StrokeCap.Butt, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w(9f), w(7f))))
    val bridgeCase = round(18f); val bridge = round(12f)
    val routeGlow = round(30f); val routeCase = round(12f); val route = round(7.5f)
    val traveled = round(7f)
}

/** Draws [paths] for each layer: either everything (preview) or just the nearby cells (riding). */
private fun DrawScope.drawRoads(
    get: (Layer) -> List<Path>, st: Strokes, traveled: Path?, route: Path?,
) {
    get(Layer.SERVICE).forEach { drawPath(it, MapC.service, style = st.service) }
    get(Layer.MINOR).forEach { drawPath(it, MapC.minor, style = st.minor) }
    get(Layer.TUNNEL).forEach { drawPath(it, MapC.tunnel, style = st.tunnel) }
    val major = get(Layer.MAJOR); val hw = get(Layer.HIGHWAY)
    major.forEach { drawPath(it, MapC.casing, style = st.majorCase) }
    hw.forEach { drawPath(it, MapC.casing, style = st.highwayCase) }
    major.forEach { drawPath(it, MapC.major, style = st.major) }
    hw.forEach { drawPath(it, MapC.highway, style = st.highway) }
    // Flyover decks are wider than the route, so a route going up one shows cream edges either side.
    val br = get(Layer.BRIDGE)
    br.forEach { drawPath(it, MapC.casing, style = st.bridgeCase) }
    br.forEach { drawPath(it, MapC.flyover, style = st.bridge) }
    traveled?.let { drawPath(it, MapC.traveled, style = st.traveled) }
    route?.let {
        drawPath(it, MapC.route.copy(alpha = 0.16f), style = st.routeGlow)
        drawPath(it, MapC.routeCasing, style = st.routeCase)
        drawPath(it, MapC.route, style = st.route)
    }
}

/** Start: small lime dot in a white ring. */
private fun DrawScope.startMarker(at: Offset, r: Float) {
    drawCircle(Color.Black.copy(alpha = 0.35f), r * 1.55f, at)
    drawCircle(Color.White, r * 1.3f, at)
    drawCircle(MapC.route, r * 0.85f, at)
}

/** Destination: white disc with a jet-black centre and a soft shadow. Calm, not alarming red. */
private fun DrawScope.endMarker(at: Offset, r: Float) {
    drawCircle(Color.Black.copy(alpha = 0.35f), r * 2.1f, at)
    drawCircle(Color.White, r * 1.6f, at)
    drawCircle(C.bg, r * 0.7f, at)
}

/** Whole-route overview: north up, fitted above [bottomInset], pinch to zoom, drag to pan. */
@Composable
fun PreviewMap(layers: MapLayers, path: RoutePath, maneuvers: List<Maneuver>, modifier: Modifier = Modifier, topInset: Dp = 0.dp, bottomInset: Dp = 0.dp) {
    val route = remember(layers, path) { layers.routePath(path) }
    val routeMeasure = remember(route) { PathMeasure().apply { setPath(route, false) } }
    val partial = remember { Path() }
    val draw = remember(path) { Animatable(0f) }
    LaunchedEffect(path) { draw.animateTo(1f, tween(1300, delayMillis = 120, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val marks = remember(layers, path, maneuvers) {
        maneuvers.filter { it.kind != Kind.ARRIVE }.map { Offset(layers.proj.x(path.lon(it.node)), layers.proj.y(path.lat(it.node))) }
    }
    val bounds = remember(path) {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in path.nodes.indices) {
            val x = layers.proj.x(path.lon(i)); val y = layers.proj.y(path.lat(i))
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        floatArrayOf(x0, y0, x1, y1)
    }
    val allLayers = remember(layers) { Layer.entries.associateWith { listOf(layers.layer(it)) } }
    var zoom by remember(path) { mutableFloatStateOf(1f) }
    var pan by remember(path) { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val topPx = with(density) { topInset.toPx() }
    val bottomPx = with(density) { bottomInset.toPx() }
    Canvas(modifier.background(MapC.land).pointerInput(path) {
        detectTransformGestures { _, p, z, _ -> zoom = (zoom * z).coerceIn(0.4f, 30f); pan += p }
    }) {
        val availH = size.height - topPx - bottomPx
        val w = bounds[2] - bounds[0]; val h = bounds[3] - bounds[1]
        val s = min((size.width - 160f) / max(w, 1f), (availH - 160f) / max(h, 1f)) * zoom
        val cx = (bounds[0] + bounds[2]) / 2; val cy = (bounds[1] + bounds[3]) / 2
        val ox = size.width / 2 + pan.x; val oy = topPx + availH / 2 + pan.y
        val st = Strokes(s, 0.8f)
        translate(ox, oy) {
            scale(s, s, pivot = Offset.Zero) {
                translate(-cx, -cy) {
                    val t = draw.value
                    val r = if (t >= 1f) route else partial.also { it.reset(); routeMeasure.getSegment(0f, routeMeasure.length * t, it, true) }
                    drawRoads({ allLayers.getValue(it) }, st, null, r)
                    val ma = ((t - 0.85f) / 0.15f).coerceIn(0f, 1f)
                    if (ma > 0f) for (o in marks) {
                        drawCircle(MapC.casing.copy(alpha = ma), 7f / s, o)
                        drawCircle(Color.White.copy(alpha = ma), 4.5f / s, o)
                    }
                }
            }
        }
        fun screen(lat: Double, lon: Double) = Offset(ox + (layers.proj.x(lon) - cx) * s, oy + (layers.proj.y(lat) - cy) * s)
        val start = screen(path.lat(0), path.lon(0))
        val end = screen(path.lat(path.nodes.size - 1), path.lon(path.nodes.size - 1))
        startMarker(start, 8f)
        if (draw.value > 0.95f) endMarker(end, 9f)
    }
}

/**
 * A small, static, real map of a ride (actual roads around the route), for cards and lists.
 * Fitted to the route; [drawIn] animates the route being drawn on.
 */
@Composable
fun MiniMap(layers: MapLayers, path: RoutePath, modifier: Modifier = Modifier, drawIn: Boolean = false, padding: Float = 0.16f, reserveBottom: Float = 0f) {
    val route = remember(layers, path) { layers.routePath(path) }
    val measure = remember(route) { PathMeasure().apply { setPath(route, false) } }
    val partial = remember { Path() }
    val t = remember(path) { Animatable(if (drawIn) 0f else 1f) }
    LaunchedEffect(path, drawIn) { if (drawIn) t.animateTo(1f, tween(1400, delayMillis = 250, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val bounds = remember(path) {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in path.nodes.indices) {
            val x = layers.proj.x(path.lon(i)); val y = layers.proj.y(path.lat(i))
            x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
        }
        floatArrayOf(x0, y0, x1, y1)
    }
    val allLayers = remember(layers) { Layer.entries.associateWith { listOf(layers.layer(it)) } }
    Canvas(modifier.background(MapC.land)) {
        val w = bounds[2] - bounds[0]; val h = bounds[3] - bounds[1]
        // Fit the route into the area above [reserveBottom] (where a card's text sits).
        val areaH = size.height * (1 - reserveBottom)
        val s = min(size.width * (1 - 2 * padding) / max(w, 1f), areaH * (1 - 2 * padding) / max(h, 1f))
        val cx = (bounds[0] + bounds[2]) / 2; val cy = (bounds[1] + bounds[3]) / 2
        val oy = areaH / 2
        val st = Strokes(s, if (size.minDimension < 300f) 0.45f else 0.7f)
        translate(size.width / 2, oy) {
            scale(s, s, pivot = Offset.Zero) {
                translate(-cx, -cy) {
                    val r = if (t.value >= 1f) route else partial.also { it.reset(); measure.getSegment(0f, measure.length * t.value, it, true) }
                    drawRoads({ allLayers.getValue(it) }, st, null, r)
                }
            }
        }
        fun screen(i: Int) = Offset(size.width / 2 + (layers.proj.x(path.lon(i)) - cx) * s, oy + (layers.proj.y(path.lat(i)) - cy) * s)
        val r0 = (size.minDimension / 40f).coerceAtMost(6.5f)
        startMarker(screen(0), r0)
        if (t.value > 0.95f) endMarker(screen(path.nodes.size - 1), r0)
    }
}

/**
 * Riding view: heading-up, tilted, car low on screen. Position, heading and progress glide between GPS
 * fixes (1 per second) at the display's frame rate, and only roads near the camera are drawn.
 */
@Composable
fun DriveMap(layers: MapLayers, state: NavState, modifier: Modifier = Modifier) {
    val path = state.path
    val full = remember(layers, state.pathVersion) { layers.routePath(path) }
    val measure = remember(full) { PathMeasure().apply { setPath(full, false) } }
    val ahead = remember { Path() }
    val target = layers.proj.of(state.position)
    val ax = remember { Animatable(target.x) }
    val ay = remember { Animatable(target.y) }
    val ah = remember { Animatable(state.heading.toFloat()) }
    val ap = remember { Animatable(state.progress.toFloat()) }
    val lastVersion = remember { intArrayOf(state.pathVersion) }
    LaunchedEffect(state) {
        // New route (re-route) or a GPS jump: move there at once instead of gliding across the map.
        val far = kotlin.math.hypot(target.x - ax.value, target.y - ay.value) > 120f
        val jump = lastVersion[0] != state.pathVersion || far
        lastVersion[0] = state.pathVersion
        val spec = tween<Float>(1000, easing = LinearEasing)
        // Shortest way round for the heading.
        var h = state.heading.toFloat()
        while (h - ah.value > 180f) h -= 360f
        while (h - ah.value < -180f) h += 360f
        if (jump) { ax.snapTo(target.x); ay.snapTo(target.y); ap.snapTo(state.progress.toFloat()) }
        launch { ax.animateTo(target.x, spec) }
        launch { ay.animateTo(target.y, spec) }
        launch { ah.animateTo(h, spec) }
        launch { ap.animateTo(state.progress.toFloat(), spec) }
    }
    val buckets = remember { Layer.entries.associateWith { ArrayList<Path>() } }
    val strokeCache = remember { arrayOfNulls<Strokes>(1) }
    val strokeScale = remember { floatArrayOf(-1f) }
    val density = LocalDensity.current.density
    Box(modifier.background(MapC.land)) {
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                rotationX = 34f
                cameraDistance = 22f * density
                transformOrigin = TransformOrigin(0.5f, 0.74f)
            },
        ) {
            val s = size.minDimension / 300f
            val cx = ax.value; val cy = ay.value
            for ((l, list) in buckets) layers.near(l, cx, cy, 750f, list)
            ahead.reset()
            measure.getSegment(ap.value, measure.length, ahead, true)
            if (strokeScale[0] != s) { strokeCache[0] = Strokes(s, 1.9f); strokeScale[0] = s }
            val st = strokeCache[0]!!
            translate(size.width / 2, size.height * 0.74f) {
                rotate(-ah.value, pivot = Offset.Zero) {
                    scale(s, s, pivot = Offset.Zero) {
                        translate(-cx, -cy) { drawRoads({ buckets.getValue(it) }, st, full, ahead) }
                    }
                }
            }
        }
        // The puck sits on the tilt pivot, so it stays put while the map moves under it.
        // Navigation arrow on a white disc, with a soft accent halo.
        Canvas(Modifier.fillMaxSize()) {
            val a = Offset(size.width / 2, size.height * 0.74f)
            val k = size.minDimension / 1080f
            drawCircle(MapC.route.copy(alpha = 0.12f), 78f * k, a)
            drawCircle(Color.Black.copy(alpha = 0.45f), 40f * k, a.copy(y = a.y + 5f * k))
            drawCircle(Color.White, 38f * k, a)
            val p = Path().apply {
                moveTo(a.x, a.y - 22f * k)
                lineTo(a.x + 15f * k, a.y + 16f * k)
                lineTo(a.x, a.y + 8f * k)
                lineTo(a.x - 15f * k, a.y + 16f * k)
                close()
            }
            drawPath(p, C.bg)
            drawPath(p, C.bg, style = Stroke(5f * k, join = StrokeJoin.Round))
        }
    }
}

/** Tiny route silhouette for lists (points normalised 0..1, segment flags for flyovers). */
class RouteThumbData(val xy: FloatArray, val bridge: BooleanArray)

@Composable
fun RouteThumb(d: RouteThumbData, modifier: Modifier = Modifier) {
    Canvas(modifier.background(MapC.land)) {
        val pad = size.minDimension * 0.2f
        val w = size.width - 2 * pad; val h = size.height - 2 * pad
        fun pt(i: Int) = Offset(pad + d.xy[2 * i] * w, pad + d.xy[2 * i + 1] * h)
        val n = d.xy.size / 2
        if (n < 2) return@Canvas
        val p = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until n) lineTo(pt(i).x, pt(i).y) }
        drawPath(p, MapC.route, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (i in 0 until n - 1) if (d.bridge[i]) drawLine(MapC.flyover, pt(i), pt(i + 1), 4f, StrokeCap.Round)
        drawCircle(Color.White, 4.5f, pt(0))
        drawCircle(Color.White, 5f, pt(n - 1)); drawCircle(C.bg, 2.2f, pt(n - 1))
    }
}

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
