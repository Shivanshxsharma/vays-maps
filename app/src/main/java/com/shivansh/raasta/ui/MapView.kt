package com.shivansh.raasta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.LocateFixed
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.shivansh.raasta.LiveTiles
import com.shivansh.raasta.LoadedPack
import com.shivansh.raasta.core.Kind
import com.shivansh.raasta.core.Maneuver
import com.shivansh.raasta.core.NavState
import com.shivansh.raasta.core.Path as RoutePath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

fun routeXY(path: RoutePath): RouteXY {
    val n = path.nodes.size
    return RouteXY(FloatArray(n) { World.x(path.lon(it)) }, FloatArray(n) { World.y(path.lat(it)) }, FloatArray(n) { path.cum[it].toFloat() })
}

fun boundsOf(path: RoutePath): FloatArray {
    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
    for (i in path.nodes.indices) {
        val x = World.x(path.lon(i)); val y = World.y(path.lat(i))
        x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
    }
    return floatArrayOf(x0, y0, x1, y1)
}

/** Panels over the map swallow touches, so dragging on a card never moves the map under it. */
fun Modifier.blockMap(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** Asks for street tiles whenever the view settles somewhere zoomed in. */
@Composable
fun LoadTiles(cam: MapCamera, tiles: LiveTiles, network: Boolean) {
    val density = LocalDensity.current.density
    LaunchedEffect(cam, network) {
        snapshotFlow {
            val z = cam.scale / density
            if (z < TILES_FROM || cam.size.width <= 0f) emptyList() else {
                val rx = cam.size.width / cam.scale / 2; val ry = cam.size.height / cam.scale / 2
                val a = World.latLon(cam.cx - rx, cam.cy + ry); val b = World.latLon(cam.cx + rx, cam.cy - ry)
                LiveTiles.covering(a.lat, a.lon, b.lat, b.lon).take(60)
            }
        }.collectLatest { keys ->
            if (keys.isEmpty()) return@collectLatest
            delay(140)
            tiles.want(keys, network)
            // Keep retrying gently while we're looking at a gap (signal came back, for example).
            while (tiles.offline && network) { delay(9_000); tiles.want(keys, network) }
        }
    }
}

/** The interactive map used for planning and previewing. */
@Composable
fun LiveMap(
    state: MapState, city: CityScene?, tiles: LiveTiles?, modifier: Modifier = Modifier,
    extra: CellSet? = null, on: MapLayersOn = MapLayersOn(), network: Boolean = true,
    route: RoutePath? = null, maneuvers: List<Maneuver> = emptyList(),
    markers: List<MapMarker> = emptyList(), me: MeDot? = null, picked: Offset? = null, focus: List<MapPoi> = emptyList(),
    onTap: (MapHit?, Offset) -> Unit = { _, _ -> }, onLongPress: (Offset) -> Unit = {}, onUserMove: () -> Unit = {},
) {
    val cam = state.cam
    val ink = rememberMapInk()
    val hits = remember { ArrayList<MapHit>() }
    val density = LocalDensity.current.density
    val line = remember(route) { route?.let { routeXY(it) } }
    val raster = rememberRaster()
    val grow = remember(route) { Animatable(0f) }
    LaunchedEffect(route) { if (route != null) grow.animateTo(1f, tween(1300, 150, Motion.ease)) }
    val dots = remember(route, maneuvers) {
        if (route == null) emptyList() else maneuvers.filter { it.kind != Kind.ARRIVE }.map { Offset(World.x(route.lon(it.node)), World.y(route.lat(it.node))) }
    }
    // My dot glides instead of jumping once a second.
    val mx = remember { Animatable(me?.x ?: 0f) }
    val my = remember { Animatable(me?.y ?: 0f) }
    var hadMe by remember { mutableStateOf(me != null) }
    LaunchedEffect(me?.x, me?.y) {
        if (me == null) return@LaunchedEffect
        if (!hadMe) { mx.snapTo(me.x); my.snapTo(me.y); hadMe = true } else {
            launch { mx.animateTo(me.x, tween(700, easing = LinearEasing)) }
            my.animateTo(me.y, tween(700, easing = LinearEasing))
        }
    }
    if (tiles != null) LoadTiles(cam, tiles, network)
    val tileSets = tiles?.sets.orEmpty()
    val scene = remember(city, tileSets, extra) { Scene(city, extra, tileSets) }
    val style = GroundStyle(metro = on.metro, metroAlpha = if (route != null) 0.6f else 1f)
    Canvas(
        modifier.clipToBounds().background(MapC.land).onSizeChanged { cam.size = Size(it.width.toFloat(), it.height.toFloat()) }
            .mapGestures(
                state,
                onTap = { p ->
                    val hit = hits.filter { it.rect.contains(p.x, p.y) }
                        .minByOrNull { h -> (Offset(h.rect.centerX(), h.rect.centerY()) - p).getDistance() }
                    onTap(hit, p)
                },
                onLongPress = onLongPress,
                onTransform = { _, _, _, fingers -> if (fingers > 0) onUserMove(); false },
            ),
    ) {
        val t = grow.value
        raster.update(scene, style)
        raster.draw(this, cam, 1f)
        drawOverlay(
            scene, cam, ink, on, route = line, upTo = if (t >= 1f) Float.MAX_VALUE else (line?.length ?: 0f) * t, markers = markers,
            me = me?.let { MeDot(mx.value, my.value, it.accuracyM, it.bearing, it.stale) },
            picked = picked, hits = hits, routeDots = if (t >= 1f) dots else emptyList(), focus = focus,
        )
    }
}

/** Zoom buttons and the locate button, stacked at the side. */
@Composable
fun MapButtons(state: MapState, modifier: Modifier = Modifier, following: Boolean = false, searching: Boolean = false, onLocate: (() -> Unit)? = null, extra: @Composable () -> Unit = {}) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        extra()
        Column(Modifier.width(46.dp).panel(RoundedCornerShape(23.dp)).blockMap()) {
            SideButton(Lucide.Plus, "Zoom in") { state.zoomBy(2f) }
            Box(Modifier.padding(horizontal = 10.dp).fillMaxWidth().height(1.dp).background(C.separator))
            SideButton(Lucide.Minus, "Zoom out") { state.zoomBy(0.5f) }
        }
        if (onLocate != null) Box(Modifier.size(46.dp).panel(CircleShape).pressable(onLocate), contentAlignment = Alignment.Center) {
            val a by animateFloatAsState(if (searching) 0.4f else 1f, tween(500), label = "loc")
            Icon(Lucide.LocateFixed, "My location", tint = if (following) MapC.me else C.label, modifier = Modifier.size(21.dp).graphicsLayer { alpha = a })
        }
    }
}

@Composable
private fun SideButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = C.label, modifier = Modifier.size(20.dp))
    }
}

/** A small, static, real map of a ride (actual roads around the route), for cards and lists. Painted once, off the UI thread. */
@Composable
fun MiniMap(lp: LoadedPack, city: CityScene?, path: RoutePath, modifier: Modifier = Modifier, drawIn: Boolean = false, padding: Float = 0.16f, reserveBottom: Float = 0f, markers: Boolean = false) {
    val ink = rememberMapInk()
    val line = remember(path) { routeXY(path) }
    val t = remember(path) { Animatable(if (drawIn) 0f else 1f) }
    LaunchedEffect(path, drawIn) { if (drawIn) t.animateTo(1f, tween(1400, 250, Motion.ease)) }
    val b = remember(path) { boundsOf(path) }
    val scene = remember(lp, city) { Scene(city, lp.cells, emptyList()) }
    val ends = remember(path) {
        val n = path.nodes.size - 1
        listOf(MapMarker(World.x(path.lon(0)), World.y(path.lat(0)), false, ""), MapMarker(World.x(path.lon(n)), World.y(path.lat(n)), true, ""))
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val small = box != IntSize.Zero && min(box.width, box.height) < 100 * ink.density
    val cam = remember(box, path) {
        if (box == IntSize.Zero) null else {
            // Fit the route into the area above [reserveBottom] (where a card's text sits).
            val areaH = box.height * (1 - reserveBottom)
            val headroom = if (markers) 34f * ink.density else 0f // the destination pin stands above its point
            val s = min(box.width * (1 - 2 * padding) / max(b[2] - b[0], 60f), (areaH * (1 - 2 * padding) - headroom).coerceAtLeast(40f) / max(b[3] - b[1], 60f))
            MapCamera((b[0] + b[2]) / 2, (b[1] + b[3]) / 2 - headroom / 2 / s, s).also {
                it.size = Size(box.width.toFloat(), box.height.toFloat())
                it.anchorY = (1 - reserveBottom) / 2
            }
        }
    }
    val ground by produceState<ImageBitmap?>(null, cam, scene) {
        val c = cam ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val bmp = android.graphics.Bitmap.createBitmap(box.width, box.height, android.graphics.Bitmap.Config.RGB_565)
            bmp.eraseColor(MapC.land.toArgb())
            val mid = c.toWorld(Offset(box.width / 2f, box.height / 2f))
            RasterMap.paintGround(bmp, scene, GroundStyle(metro = false, roadScale = if (small) 0.55f else 0.9f), mid.x, mid.y, c.scale, ink.density)
            bmp.asImageBitmap()
        }
    }
    Canvas(modifier.clipToBounds().background(MapC.land).onSizeChanged { box = it }) {
        val c = cam ?: return@Canvas
        ground?.let { drawImage(it) }
        drawOverlay(scene, c, ink, MapLayersOn(), route = line, upTo = line.length * t.value, labels = false,
            markers = if (markers) (if (t.value > 0.95f) ends else ends.take(1)) else emptyList(), routeScale = if (small) 0.6f else 0.9f)
        if (!markers) for (m in ends) c.toScreen(m.x, m.y).let { p ->
            drawCircle(Color.Black.copy(alpha = 0.5f), 4.2f * ink.density, p)
            drawCircle(if (m.destination) Ink.i100 else MapC.route, 3.2f * ink.density, p)
            if (m.destination) drawCircle(C.bg, 1.4f * ink.density, p)
        }
    }
}

/** A still map of where you are, for the home screen. */
@Composable
fun HereMap(city: CityScene?, tiles: LiveTiles, me: MeDot, modifier: Modifier = Modifier) {
    val ink = rememberMapInk()
    val density = LocalDensity.current.density
    val cam = remember { MapCamera(me.x, me.y, 0.16f * density).also { it.anchorY = 0.32f } }
    val x by animateFloatAsState(me.x, tween(700), label = "hx")
    val y by animateFloatAsState(me.y, tween(700), label = "hy")
    val scene = remember(city, tiles.sets) { Scene(city, null, tiles.sets) }
    val raster = rememberRaster()
    LoadTiles(cam, tiles, true)
    LaunchedEffect(cam) { snapshotFlow { x to y }.collectLatest { (a, b) -> cam.cx = a; cam.cy = b } }
    Canvas(modifier.clipToBounds().background(MapC.land).onSizeChanged { cam.size = Size(it.width.toFloat(), it.height.toFloat()) }) {
        raster.update(scene, GroundStyle())
        raster.draw(this, cam, 1f)
        drawOverlay(scene, cam, ink, MapLayersOn(), me = MeDot(x, y, me.accuracyM, me.bearing, me.stale))
    }
}

/**
 * Riding view: heading-up, tilted, you low on the screen. Position, heading and progress glide between GPS fixes
 * at the display's frame rate. Pinch to zoom while it keeps following you; drag to look around, and it comes back
 * by itself after a few seconds (or tap Re-centre).
 */
@Composable
fun DriveMap(lp: LoadedPack, city: CityScene?, tiles: LiveTiles, on: MapLayersOn, state: NavState, modifier: Modifier = Modifier, markers: List<MapMarker>, recentreBottom: Dp) {
    val path = state.path
    val line = remember(state.pathVersion) { routeXY(path) }
    val raster = rememberRaster()
    val target = World.of(state.position)
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
    val ink = rememberMapInk()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val cam = remember { MapCamera(target.x, target.y, 1.3f * density).also { it.anchorY = 0.72f } }
    val ms = remember { MapState(cam, scope) }
    var zoom by remember { mutableFloatStateOf(1.25f * density) }
    var free by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    val tilt by animateFloatAsState(if (free) 0f else 1f, tween(420, easing = Motion.ease), label = "tilt")
    // 0 = looking around, 1 = locked on you. Gliding back is this going 0 → 1.
    val lock = remember { Animatable(1f) }
    val from = remember { FloatArray(3) }
    // Remember where the glide starts *before* following resumes, or the first frame would glide from nowhere.
    fun recentre() {
        ms.stop()
        from[0] = cam.cx; from[1] = cam.cy; from[2] = cam.scale
        free = false
    }
    LaunchedEffect(free, touches) { if (free) { delay(14_000); recentre() } }
    LaunchedEffect(free) {
        if (free) lock.snapTo(0f) else if (lock.value < 1f) {
            lock.animateTo(1f, tween(650, easing = Motion.ease))
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(ax.value, ay.value, ah.value, zoom, lock.value) }.collect { (x, y, h, z, k) ->
            if (free) return@collect
            cam.rot = -h
            if (k >= 1f) { cam.cx = x; cam.cy = y; cam.scale = z } else {
                cam.cx = from[0] + (x - from[0]) * k; cam.cy = from[1] + (y - from[1]) * k
                cam.scale = from[2] + (z - from[2]) * k
            }
        }
    }
    LoadTiles(cam, tiles, network = false)
    val scene = remember(lp, city, tiles.sets) { Scene(city, lp.cells, tiles.sets) }
    Box(
        modifier.clipToBounds().background(MapC.land)
            .onSizeChanged { cam.size = Size(it.width.toFloat(), it.height.toFloat()) }
            .mapGestures(ms, onTransform = { a, b, z, fingers ->
                when {
                    fingers == 0 -> !free // a fling only makes sense while looking around
                    free -> { touches++; false }
                    fingers >= 2 -> { zoom = (zoom * z).coerceIn(0.12f * density, 6f * density); true }
                    a.isSpecified && (b - a).getDistance() > 0f -> {
                        // Dragged again while gliding back: stay exactly where the glide got to.
                        free = true; touches++; false
                    }
                    else -> true
                }
            }),
    ) {
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                rotationX = 34f * tilt
                cameraDistance = 22f * density
                transformOrigin = TransformOrigin(0.5f, cam.anchorY)
            },
        ) {
            raster.update(scene, GroundStyle(metro = on.metro, metroAlpha = 0.5f, metroMaxDp = 2.6f))
            raster.draw(this, cam, 1f + 0.7f * tilt)
            drawOverlay(scene, cam, ink, on, route = line, done = ap.value, markers = markers, riding = tilt > 0.02f)
        }
        // You: navigation arrow on a white disc, at the tilt pivot, so it stays put while the map moves under it.
        Canvas(Modifier.fillMaxSize()) {
            val a = if (lock.value >= 1f && !free) cam.anchor else cam.toScreen(ax.value, ay.value)
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
            rotate(ah.value + cam.rot, a) {
                drawPath(p, C.bg)
                drawPath(p, C.bg, style = Stroke(5f * k, join = StrokeJoin.Round))
            }
        }
        AnimatedVisibility(
            free, Modifier.align(Alignment.BottomCenter).padding(bottom = recentreBottom),
            enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut() + scaleOut(targetScale = 0.85f),
        ) {
            Row(
                Modifier.height(46.dp).clip(CircleShape).background(C.label).pressable({ recentre() }).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.LocateFixed, null, tint = C.bg, modifier = Modifier.size(18.dp))
                Text("  Re-centre", style = T.headline.copy(color = C.bg))
            }
        }
    }
}
