package com.shivansh.raasta.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import com.composables.icons.lucide.Bike
import com.composables.icons.lucide.Flag
import com.composables.icons.lucide.Lucide
import com.shivansh.raasta.R
import com.shivansh.raasta.core.AreaKind
import com.shivansh.raasta.core.AreaShape
import com.shivansh.raasta.core.CityMap
import com.shivansh.raasta.core.LatLon
import com.shivansh.raasta.core.MetroStation
import com.shivansh.raasta.core.PlaceKind
import com.shivansh.raasta.core.Poi
import com.shivansh.raasta.core.Polyline
import com.shivansh.raasta.core.RoadClass
import com.shivansh.raasta.core.RoadShape
import com.shivansh.raasta.core.RoutePack
import com.shivansh.raasta.core.TileShapes
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ====================================================================== world

/** One flat metre grid for all of Delhi, so every source (city map, tiles, saved rides) lines up. */
object World {
    private const val LAT0 = 28.64
    private const val LON0 = 77.10
    private const val M_PER_DEG = 111_194.93 // same sphere as Geo.dist, so drawn metres match ridden metres
    private val k = cos(Math.toRadians(LAT0)) * M_PER_DEG
    fun x(lon: Double) = ((lon - LON0) * k).toFloat()
    fun y(lat: Double) = (-(lat - LAT0) * M_PER_DEG).toFloat()
    fun of(p: LatLon) = Offset(x(p.lon), y(p.lat))
    fun latLon(x: Float, y: Float) = LatLon(LAT0 - y / M_PER_DEG, LON0 + x / k)
}

/** Road layers, bottom to top. Each has its own colour so road types stay readable at a glance. */
internal enum class Layer { SERVICE, MINOR, MAJOR, HIGHWAY, TUNNEL, BRIDGE }

/**
 * Map colours, chosen for readability: warm sand for main roads, greys for the rest, cream flyover decks, teal
 * dashed underpasses, deep blue water, dark green parks. Lime is reserved for the route.
 */
object MapC {
    val land = Color(0xFF101010)
    val water = Color(0xFF0E2433)
    val green = Color(0xFF14231A)
    val service = Color(0xFF313131)
    val minor = Color(0xFF4A494A)
    val major = Color(0xFF73716B)
    val highway = Color(0xFF8C7A55)
    val casing = Color(0xFF070807)
    val flyover = Color(0xFFE8D5A6)
    val tunnel = Color(0xFF3BB5A4)
    val route = Color(0xFFB0E454)
    val routeCasing = Color(0xFF224F10)
    val traveled = Color(0xFF575A52)
    val me = Color(0xFF4DA3FF)
    val area = Color(0xFFA3AB9B)
    val roadName = Color(0xFFC9CDC2)
}

// ====================================================================== geometry

class MapPoi(val poi: Poi) {
    val x = World.x(poi.lon)
    val y = World.y(poi.lat)
}

internal class NameTag(val text: String, val x: Float, val y: Float, val angle: Float, val lenM: Float, val layer: Layer)

internal class AreaItem(val kind: AreaKind, val path: Path, val x0: Float, val y0: Float, val x1: Float, val y1: Float)

internal class MapCell {
    val paths = arrayOfNulls<Path>(Layer.entries.size)
    val pois = ArrayList<MapPoi>()
    val tags = ArrayList<NameTag>()
}

/** A set of drawable cells (one tile, or one saved ride), bucketed on a 500 m grid so only what's visible is drawn. */
class CellSet internal constructor(internal val cells: HashMap<Long, MapCell>, internal val areas: List<AreaItem>, val bounds: FloatArray) {
    companion object {
        internal const val G = 500f
        internal fun key(i: Int, j: Int) = (i + 100_000).toLong() * 1_000_000L + (j + 100_000)

        fun ofTile(t: TileShapes) = build(t.roads, t.pois, t.areas)

        fun ofPack(p: RoutePack): CellSet = build(
            p.ways.map { w ->
                RoadShape(w.cls, w.link, w.bridge, w.isUnderground, w.name,
                    Polyline(DoubleArray(w.nodes.size) { p.lat[w.nodes[it]] }, DoubleArray(w.nodes.size) { p.lon[w.nodes[it]] }))
            },
            p.pois, p.areas,
        )

        private fun build(roads: List<RoadShape>, pois: List<Poi>, areas: List<AreaShape>): CellSet {
            val cells = HashMap<Long, MapCell>()
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            fun grow(x: Float, y: Float) { b[0] = min(b[0], x); b[1] = min(b[1], y); b[2] = max(b[2], x); b[3] = max(b[3], y) }
            fun cell(x: Float, y: Float) = cells.getOrPut(key(floor(x / G).toInt(), floor(y / G).toInt())) { MapCell() }
            for (r in roads) {
                val layer = when {
                    r.bridge -> Layer.BRIDGE
                    r.tunnel -> Layer.TUNNEL
                    r.cls == RoadClass.SERVICE || r.cls == RoadClass.LIVING -> Layer.SERVICE
                    r.cls.rank >= RoadClass.PRIMARY.rank -> Layer.HIGHWAY
                    r.cls.rank >= RoadClass.TERTIARY.rank || r.link -> Layer.MAJOR
                    else -> Layer.MINOR
                }
                val n = r.line.size
                var cur: MapCell? = null
                var px = World.x(r.line.lon[0])
                var py = World.y(r.line.lat[0])
                grow(px, py)
                for (i in 1 until n) {
                    val x = World.x(r.line.lon[i])
                    val y = World.y(r.line.lat[i])
                    grow(x, y)
                    val c = cell((px + x) / 2, (py + y) / 2)
                    val path = c.paths[layer.ordinal] ?: Path().also { c.paths[layer.ordinal] = it }
                    if (c !== cur) { path.moveTo(px, py); cur = c }
                    path.lineTo(x, y)
                    px = x; py = y
                }
                if (r.name != null && !r.link && n > 1) tagFor(r, if (layer == Layer.BRIDGE || layer == Layer.TUNNEL) Layer.HIGHWAY else layer)?.let { cell(it.x, it.y).tags.add(it) }
            }
            for (p in pois) MapPoi(p).let { cell(it.x, it.y).pois.add(it) }
            val items = areas.map { areaItem(it) }
            for (a in items) { grow(a.x0, a.y0); grow(a.x1, a.y1) }
            return CellSet(cells, items, b)
        }

        internal fun areaItem(a: AreaShape): AreaItem {
            val p = Path()
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in a.lat.indices) {
                val x = World.x(a.lon[i]); val y = World.y(a.lat[i])
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
            p.close()
            return AreaItem(a.kind, p, x0, y0, x1, y1)
        }

        /** Where a road's name goes: the middle of its longest fairly straight stretch. */
        private fun tagFor(r: RoadShape, layer: Layer): NameTag? {
            val n = r.line.size
            val xs = FloatArray(n) { World.x(r.line.lon[it]) }
            val ys = FloatArray(n) { World.y(r.line.lat[it]) }
            var bestLen = 0f; var bs = 0; var be = 0
            var s = 0
            for (i in 1 until n) {
                if (i + 1 < n) {
                    val a1 = atan2(ys[i] - ys[s], xs[i] - xs[s])
                    val a2 = atan2(ys[i + 1] - ys[i], xs[i + 1] - xs[i])
                    var d = abs(a2 - a1)
                    if (d > Math.PI) d = (2 * Math.PI - d).toFloat()
                    if (d < 0.22f) continue
                }
                val len = hypot(xs[i] - xs[s], ys[i] - ys[s])
                if (len > bestLen) { bestLen = len; bs = s; be = i }
                s = i
            }
            if (bestLen < 25f) return null
            val ang = Math.toDegrees(atan2((ys[be] - ys[bs]).toDouble(), (xs[be] - xs[bs]).toDouble())).toFloat()
            return NameTag(r.name!!, (xs[bs] + xs[be]) / 2, (ys[bs] + ys[be]) / 2, ang, bestLen, layer)
        }
    }
}

class StationMark(val station: MetroStation, colors: List<Int>) {
    val x = World.x(station.lon)
    val y = World.y(station.lat)
    val colors = colors.map { Color(it) }
}

/** The bundled whole-Delhi map, ready to draw. */
class CityScene(val map: CityMap) {
    internal class RoadCell { val paths = arrayOfNulls<Path>(3) }
    internal val roads = HashMap<Long, RoadCell>()
    internal val areas = map.areas.map { CellSet.areaItem(it) }
    internal class Track(val color: Color, val path: Path, val x0: Float, val y0: Float, val x1: Float, val y1: Float)
    internal val tracks = ArrayList<Track>()
    val stations = map.stations.sortedByDescending { it.lines.size }.map { s -> StationMark(s, s.lines.map { map.metro[it].color }) }
    internal class Label(val text: String, val rank: Int, val x: Float, val y: Float)
    internal val labels = map.labels.map { Label(it.name, it.rank, World.x(it.lon), World.y(it.lat)) }.sortedBy { it.rank }
    val places = map.places.map { MapPoi(it) }

    init {
        for (r in map.roads) {
            val l = when {
                r.cls.rank >= RoadClass.PRIMARY.rank -> 0
                r.cls.rank >= RoadClass.SECONDARY.rank -> 1
                else -> 2
            }
            var cur: RoadCell? = null
            var px = World.x(r.line.lon[0]); var py = World.y(r.line.lat[0])
            for (i in 1 until r.line.size) {
                val x = World.x(r.line.lon[i]); val y = World.y(r.line.lat[i])
                val c = roads.getOrPut(CellSet.key(floor((px + x) / 2 / CG).toInt(), floor((py + y) / 2 / CG).toInt())) { RoadCell() }
                val p = c.paths[l] ?: Path().also { c.paths[l] = it }
                if (c !== cur) { p.moveTo(px, py); cur = c }
                p.lineTo(x, y)
                px = x; py = y
            }
        }
        for (m in map.metro) for (t in m.tracks) {
            val p = Path()
            var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (i in 0 until t.size) {
                val x = World.x(t.lon[i]); val y = World.y(t.lat[i])
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
            tracks.add(Track(Color(m.color), p, x0, y0, x1, y1))
        }
    }

    internal companion object { const val CG = 2500f }
}

/** What's on the map right now. Immutable: replace it to redraw. */
class Scene(val city: CityScene?, val pack: CellSet?, val live: List<CellSet>) {
    /** Street tiles are only worth drawing zoomed in; a saved ride's own roads always are. */
    internal fun sets(z: Float): List<CellSet> = if (z >= TILES_SHOW) listOfNotNull(pack) + live else listOfNotNull(pack)
}

/** dp per metre at which street tiles start loading, and below which they're not drawn. */
const val TILES_FROM = 0.115f
const val TILES_SHOW = 0.06f

/** How the ground is painted. */
data class GroundStyle(val metro: Boolean = true, val metroAlpha: Float = 1f, val metroMaxDp: Float = 6f, val roadScale: Float = 1f)

/** What the user has switched on. */
data class MapLayersOn(val metro: Boolean = true, val places: Boolean = true)

// ====================================================================== camera

class MapCamera(cx: Float = 0f, cy: Float = 0f, scale: Float = 0.1f) {
    /** World metres at the anchor point of the screen. */
    var cx by mutableFloatStateOf(cx)
    var cy by mutableFloatStateOf(cy)
    /** Pixels per metre. */
    var scale by mutableFloatStateOf(scale)
    var rot by mutableFloatStateOf(0f)
    var anchorX by mutableFloatStateOf(0.5f)
    var anchorY by mutableFloatStateOf(0.5f)
    var size by mutableStateOf(Size.Zero)
    var minScale = 0.012f
    var maxScale = 22f

    val anchor get() = Offset(size.width * anchorX, size.height * anchorY)

    fun toScreen(x: Float, y: Float): Offset {
        val dx = (x - cx) * scale; val dy = (y - cy) * scale
        if (rot == 0f) return Offset(anchor.x + dx, anchor.y + dy)
        val r = Math.toRadians(rot.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Offset(anchor.x + dx * c - dy * s, anchor.y + dx * s + dy * c)
    }

    fun toWorld(p: Offset): Offset {
        val dx = p.x - anchor.x; val dy = p.y - anchor.y
        if (rot == 0f) return Offset(cx + dx / scale, cy + dy / scale)
        val r = Math.toRadians(-rot.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Offset(cx + (dx * c - dy * s) / scale, cy + (dx * s + dy * c) / scale)
    }

    /** Move and zoom so the spot under the fingers stays under the fingers. */
    fun transform(from: Offset, to: Offset, zoom: Float) {
        if (!zoom.isFinite() || zoom <= 0f || size.width <= 0f) return
        val w = toWorld(from)
        scale = (scale * zoom).coerceIn(minScale, maxScale)
        val now = toWorld(to)
        // Stay around Delhi: flinging off into nothing only gets people lost.
        cx = (cx + w.x - now.x).coerceIn(-60_000f, 60_000f)
        cy = (cy + w.y - now.y).coerceIn(-60_000f, 60_000f)
    }

    fun panBy(d: Offset) = transform(Offset.Zero, d, 1f)

    fun centreLatLon() = World.latLon(cx, cy)
}

// ====================================================================== drawing tools

/** Fonts and icons the renderer needs, made once. */
class MapInk(ctx: Context, val density: Float, internal val icons: Map<PlaceKind, Painter>, internal val bike: Painter, internal val flag: Painter) {
    private val face = runCatching { ResourcesCompat.getFont(ctx, R.font.inter) }.getOrNull()
    private val widths = HashMap<String, Float>()

    internal fun paint(sp: Float, weight: Int, color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = sp * density
        runCatching { fontVariationSettings = "'wght' $weight" }
        this.color = color.toArgb()
        textAlign = Paint.Align.CENTER
    }

    internal fun halo(p: Paint) = Paint(p).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * density
        strokeJoin = Paint.Join.ROUND
        color = Color(0xE6090A09).toArgb()
    }

    internal class Pair2(val fill: Paint, val halo: Paint, val id: Int)
    private var nextId = 0
    private fun pair(sp: Float, w: Int, c: Color) = paint(sp, w, c).let { Pair2(it, halo(it), nextId++) }
    internal val area0 = pair(14f, 700, MapC.area)
    internal val area1 = pair(12f, 600, MapC.area)
    internal val area2 = pair(10.5f, 500, Color(0xFF8B9384))
    internal val road = pair(10f, 500, MapC.roadName)
    internal val station = pair(11f, 650, Color(0xFFF3FCE6))
    internal val marker = pair(12.5f, 700, Color(0xFFF3FCE6))
    private val poi = HashMap<PlaceKind, Pair2>()
    internal fun poi(k: PlaceKind) = poi.getOrPut(k) { pair(10.5f, 550, kindColor(k)) }

    internal fun width(p: Pair2, s: String) = widths.getOrPut("${p.id}|$s") { p.fill.measureText(s) }
}

@Composable
fun rememberMapInk(): MapInk {
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val painters = PlaceKind.entries.associateWith { rememberVectorPainter(iconFor(it)) }
    val bike = rememberVectorPainter(Lucide.Bike)
    val flag = rememberVectorPainter(Lucide.Flag)
    return remember(density) { MapInk(ctx, density, painters, bike, flag) }
}

/** Calm category colours, so food, fuel, health and transport are told apart at a glance. */
fun kindColor(k: PlaceKind): Color = when (k) {
    PlaceKind.FOOD, PlaceKind.CAFE -> Color(0xFFF0A868)
    PlaceKind.FUEL -> Color(0xFF7FB2FF)
    PlaceKind.HOSPITAL, PlaceKind.PHARMACY -> Color(0xFFFF8FA0)
    PlaceKind.SHOP, PlaceKind.MALL, PlaceKind.MARKET -> Color(0xFF9DB8F5)
    PlaceKind.PARK -> Color(0xFF79B97E)
    PlaceKind.LANDMARK -> Color(0xFF66CFC4)
    PlaceKind.HOTEL -> Color(0xFFDC9BD2)
    PlaceKind.WORSHIP -> Color(0xFFD2BA7C)
    PlaceKind.EDUCATION -> Color(0xFFC7A97B)
    PlaceKind.RAIL, PlaceKind.METRO, PlaceKind.METRO_GATE, PlaceKind.BUS, PlaceKind.AIRPORT -> Color(0xFFA9C7E8)
    else -> Color(0xFFA7AFA0)
}

/** Something the finger can tap on the map. */
class MapHit(val rect: RectF, val poi: Poi?, val station: MetroStation?)

class MapMarker(val x: Float, val y: Float, val destination: Boolean, val label: String)

class MeDot(val x: Float, val y: Float, val accuracyM: Float, val bearing: Float?, val stale: Boolean)

// ====================================================================== renderer

/**
 * Land, water, parks, roads and metro lines, north up, centred on (cx, cy). What appears depends on how far in you
 * are: whole city with metro lines, then streets, then lanes. Painted into image tiles by [RasterMap].
 */
fun DrawScope.drawGround(scene: Scene, cx: Float, cy: Float, s: Float, d: Float, st: GroundStyle) {
    val z = s / d // dp per metre
    val x0 = cx - size.width / 2 / s; val x1 = cx + size.width / 2 / s
    val y0 = cy - size.height / 2 / s; val y1 = cy + size.height / 2 / s
    fun w(metres: Float, minDp: Float, maxDp: Float = 60f) = (metres * z).coerceIn(minDp * st.roadScale, maxDp) * d / s
    fun round(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val sets = scene.sets(z)
    // Roads are bucketed by the middle of each segment, so look a little past the edge.
    val cells = cellsIn(sets, x0 - 250, y0 - 250, x1 + 250, y1 + 250)

    withTransform({
        translate(size.width / 2, size.height / 2)
        scale(s, s, Offset.Zero)
        translate(-cx, -cy)
    }) {
        fun area(it: AreaItem) {
            if (it.x1 < x0 || it.x0 > x1 || it.y1 < y0 || it.y0 > y1) return
            if ((it.x1 - it.x0) * s < 3f) return
            drawPath(it.path, if (it.kind == AreaKind.WATER) MapC.water else MapC.green)
        }
        scene.city?.areas?.forEach { area(it) }
        for (set in sets) set.areas.forEach { area(it) }

        val hwW = w(9f, if (z < 0.03f) 1.1f else 2f); val majW = w(7f, 1.1f); val minW = w(5.5f, 0.85f); val srvW = w(3.8f, 0.65f)
        val caseAdd = 1.6f * d / s

        // Whole-city roads (always there, under any detail).
        scene.city?.let { c ->
            val g = CityScene.CG
            val shown = ArrayList<CityScene.RoadCell>()
            for (i in floor((x0 - 600) / g).toInt()..floor((x1 + 600) / g).toInt()) for (j in floor((y0 - 600) / g).toInt()..floor((y1 + 600) / g).toInt()) {
                c.roads[CellSet.key(i, j)]?.let { shown.add(it) }
            }
            if (z >= 0.05f) shown.forEach { r -> r.paths[2]?.let { drawPath(it, if (z < 0.1f) MapC.minor else MapC.major, style = round(majW * 0.8f)) } }
            if (z >= 0.018f) shown.forEach { r -> r.paths[1]?.let { drawPath(it, MapC.major, style = round(majW)) } }
            if (z >= 0.08f) shown.forEach { r -> r.paths[0]?.let { drawPath(it, MapC.casing, style = round(hwW + caseAdd)) } }
            shown.forEach { r -> r.paths[0]?.let { drawPath(it, MapC.highway, style = round(hwW)) } }
        }
        fun layer(l: Layer, c: Color, st: Stroke) { for (cell in cells) cell.paths[l.ordinal]?.let { drawPath(it, c, style = st) } }
        if (z >= 0.11f) layer(Layer.SERVICE, MapC.service, round(srvW))
        if (z >= 0.05f) layer(Layer.MINOR, MapC.minor, round(minW))
        val dash = max(6f * d / s, 5f)
        layer(Layer.TUNNEL, MapC.tunnel, Stroke(w(6f, 1.6f), join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.8f))))
        if (z >= 0.08f) {
            layer(Layer.MAJOR, MapC.casing, round(majW + caseAdd))
            layer(Layer.HIGHWAY, MapC.casing, round(hwW + caseAdd))
        }
        layer(Layer.MAJOR, MapC.major, round(majW))
        layer(Layer.HIGHWAY, MapC.highway, round(hwW))
        if (z >= 0.05f) {
            layer(Layer.BRIDGE, MapC.casing, round(w(11f, 2.4f) + caseAdd * 1.3f))
            layer(Layer.BRIDGE, MapC.flyover, round(w(10f, 2.2f)))
        }

        // Metro lines in their own colours. All outlines first, or a line's outline would cut the next one.
        if (st.metro) scene.city?.let { c ->
            val seen = c.tracks.filter { t -> t.x1 > x0 && t.x0 < x1 && t.y1 > y0 && t.y0 < y1 }
            val tw = w(4f, if (z < 0.03f) 1.6f else 2.4f, st.metroMaxDp)
            for (t in seen) drawPath(t.path, MapC.casing, style = round(tw + 1.6f * d / s))
            for (t in seen) drawPath(t.path, if (st.metroAlpha < 1f) t.color.copy(alpha = st.metroAlpha) else t.color, style = round(tw))
        }
    }
}

internal fun cellsIn(sets: List<CellSet>, x0: Float, y0: Float, x1: Float, y1: Float): List<MapCell> {
    val cells = ArrayList<MapCell>()
    val i0 = floor(x0 / CellSet.G).toInt(); val i1 = floor(x1 / CellSet.G).toInt()
    val j0 = floor(y0 / CellSet.G).toInt(); val j1 = floor(y1 / CellSet.G).toInt()
    val count = (i1 - i0 + 1L) * (j1 - j0 + 1L)
    for (set in sets) {
        val b = set.bounds
        if (b[2] < x0 || b[0] > x1 || b[3] < y0 || b[1] > y1) continue
        if (count > set.cells.size) {
            for ((k, c) in set.cells) {
                val i = (k / 1_000_000L).toInt() - 100_000; val j = (k % 1_000_000L).toInt() - 100_000
                if (i in i0..i1 && j in j0..j1) cells.add(c)
            }
        } else for (i in i0..i1) for (j in j0..j1) set.cells[CellSet.key(i, j)]?.let { cells.add(it) }
    }
    return cells
}

/** Everything that stays sharp and upright over the ground: labels, places, stations, the route, markers, you. */
fun DrawScope.drawOverlay(
    scene: Scene, cam: MapCamera, ink: MapInk, on: MapLayersOn,
    route: RouteXY? = null, done: Float = 0f, upTo: Float = Float.MAX_VALUE,
    markers: List<MapMarker> = emptyList(), me: MeDot? = null,
    labels: Boolean = true, riding: Boolean = false, picked: Offset? = null, hits: ArrayList<MapHit>? = null,
    routeDots: List<Offset> = emptyList(), routeScale: Float = 1f, focus: List<MapPoi> = emptyList(),
) {
    val s = cam.scale
    // A nonsense camera must draw nothing rather than walk an endless grid.
    if (!(s > 0.001f) || !cam.cx.isFinite() || !cam.cy.isFinite() || abs(cam.cx) > 500_000f || abs(cam.cy) > 500_000f) return
    val d = ink.density
    val z = s / d
    val a = cam.anchor
    val reach = (if (riding) 1.25f else 1f) * hypot(max(a.x, size.width - a.x), max(a.y, size.height - a.y)) / s
    val turned = cam.rot != 0f || riding
    val rx = if (turned) reach else max(a.x, size.width - a.x) / s
    val ry = if (turned) reach else max(a.y, size.height - a.y) / s
    val x0 = cam.cx - rx; val x1 = cam.cx + rx; val y0 = cam.cy - ry; val y1 = cam.cy + ry
    fun inBox(x: Float, y: Float, pad: Float = 0f) = x > x0 - pad && x < x1 + pad && y > y0 - pad && y < y1 + pad

    route?.let { drawRoute(it, cam, d, done, upTo, riding, routeScale) }
    for (o in routeDots) if (inBox(o.x, o.y)) cam.toScreen(o.x, o.y).let { p ->
        drawCircle(MapC.casing, 4.2f * d, p)
        drawCircle(Color.White, 2.8f * d, p)
    }
    hits?.clear()
    if (labels) {
        val cells = if (z >= 0.03f) cellsIn(scene.sets(z), x0, y0, x1, y1) else emptyList()
        drawLabels(scene, cam, ink, on, cells, z, riding, route != null, markers, hits, focus, ::inBox)
    }
    me?.let { drawMe(cam.toScreen(it.x, it.y), it, s, d, cam.rot) }
    picked?.let { p ->
        val c = cam.toScreen(p.x, p.y)
        drawCircle(Color.Black.copy(alpha = 0.4f), 11f * d, c)
        drawCircle(Color.White, 8f * d, c)
        drawCircle(C.bg, 3.5f * d, c)
    }
    for (m in markers) drawMarker(cam.toScreen(m.x, m.y), m, ink)
}

/** A ride as plain points with distances along it. */
class RouteXY(val x: FloatArray, val y: FloatArray, val cum: FloatArray) {
    val length get() = cum.lastOrNull() ?: 0f
    internal val sx = FloatArray(x.size + 2)
    internal val sy = FloatArray(x.size + 2)
}

/**
 * The route as plain line segments with round joints, only where it's on screen. Segments are the cheapest thing a
 * GPU can draw, unlike one long stroked path, which many phones rasterise on the CPU every frame.
 */
private fun DrawScope.drawRoute(r: RouteXY, cam: MapCamera, d: Float, done: Float, upTo: Float, riding: Boolean, k: Float) {
    val n = r.x.size
    if (n < 2) return
    val z = cam.scale / d
    val wide = (7f * z).coerceIn(4.6f * k, if (riding) 13f else 11f) * d
    val pad = wide * 2 + (if (riding) size.height else 0f)
    fun seg(from: Float, to: Float, w: Float, c: Color) {
        if (to <= from) return
        var m = 0
        fun put(x: Float, y: Float, force: Boolean) {
            val p = cam.toScreen(x, y)
            if (!force && m > 0 && abs(p.x - r.sx[m - 1]) + abs(p.y - r.sy[m - 1]) < 1.6f) return
            r.sx[m] = p.x; r.sy[m] = p.y; m++
        }
        var lo = java.util.Arrays.binarySearch(r.cum, from).let { if (it < 0) -it - 2 else it }.coerceIn(0, n - 2)
        fun at(dist: Float, i: Int, force: Boolean) {
            val len = r.cum[i + 1] - r.cum[i]
            val t = if (len <= 0f) 0f else ((dist - r.cum[i]) / len).coerceIn(0f, 1f)
            put(r.x[i] + (r.x[i + 1] - r.x[i]) * t, r.y[i] + (r.y[i + 1] - r.y[i]) * t, force)
        }
        at(from, lo, true)
        while (lo < n - 1 && r.cum[lo + 1] < to) { put(r.x[lo + 1], r.y[lo + 1], lo + 1 == n - 1); lo++ }
        if (lo < n - 1) at(to, lo, true)
        for (i in 0 until m) {
            val ax = r.sx[i]; val ay = r.sy[i]
            val onA = ax > -pad && ax < size.width + pad && ay > -pad && ay < size.height + pad
            if (onA) drawCircle(c, w / 2, Offset(ax, ay))
            if (i + 1 < m) {
                val bx = r.sx[i + 1]; val by = r.sy[i + 1]
                if (max(ax, bx) < -pad || min(ax, bx) > size.width + pad || max(ay, by) < -pad || min(ay, by) > size.height + pad) continue
                drawLine(c, Offset(ax, ay), Offset(bx, by), w)
            }
        }
    }
    val end = min(upTo, r.length)
    if (done > 1f) seg(max(0f, done - 1500f), done, wide * 0.8f, MapC.traveled)
    seg(done, end, wide * 1.6f, MapC.routeCasing)
    seg(done, end, wide, MapC.route)
}

private fun DrawScope.drawLabels(
    scene: Scene, cam: MapCamera, ink: MapInk, on: MapLayersOn, cells: List<MapCell>, z: Float, riding: Boolean, trip: Boolean,
    markers: List<MapMarker>, hits: ArrayList<MapHit>?, focus: List<MapPoi>, inBox: (Float, Float, Float) -> Boolean,
) {
    val d = ink.density
    val nc = drawContext.canvas.nativeCanvas
    val taken = ArrayList<RectF>(128)
    fun free(r: RectF): Boolean { for (t in taken) if (RectF.intersects(t, r)) return false; return true }
    fun onScreen(p: Offset) = p.x > -60 * d && p.x < size.width + 60 * d && p.y > -40 * d && p.y < size.height + 40 * d
    for (m in markers) cam.toScreen(m.x, m.y).let { p ->
        taken.add(if (m.destination) RectF(p.x - 19 * d, p.y - 50 * d, p.x + 19 * d, p.y + 20 * d) else RectF(p.x - 19 * d, p.y - 19 * d, p.x + 19 * d, p.y + 36 * d))
    }
    fun text(p: MapInk.Pair2, t: String, x: Float, y: Float) { nc.drawText(t, x, y, p.halo); nc.drawText(t, x, y, p.fill) }

    // Search results ("petrol nearby") win over everything else and stand out.
    for (q in focus) {
        val p = cam.toScreen(q.x, q.y)
        if (!onScreen(p)) continue
        val r = 13f * d
        drawCircle(Color.Black.copy(alpha = 0.55f), r + 2.5f * d, p)
        drawCircle(kindColor(q.poi.kind), r, p)
        ink.icons[q.poi.kind]?.let { icon ->
            val sz = 15f * d
            translate(p.x - sz / 2, p.y - sz / 2) { with(icon) { draw(Size(sz, sz), colorFilter = ColorFilter.tint(C.bg)) } }
        }
        taken.add(RectF(p.x - r, p.y - r, p.x + r, p.y + r))
        hits?.add(MapHit(RectF(p.x - 20 * d, p.y - 20 * d, p.x + 20 * d, p.y + 20 * d), q.poi, null))
        val tw = ink.width(ink.poi(q.poi.kind), q.poi.name)
        val box = RectF(p.x - tw / 2, p.y + r, p.x + tw / 2, p.y + r + 15 * d)
        if (free(box)) { text(ink.poi(q.poi.kind), q.poi.name, p.x, p.y + r + 12.5f * d); taken.add(box) }
    }

    // Metro stations first: the thing people steer by.
    // With a ride on the map, far-out station dots would look like its turn dots, so they wait a little longer.
    if (on.metro && z >= (if (trip) 0.03f else 0.011f)) scene.city?.stations?.forEach { st ->
        if (!inBox(st.x, st.y, 0f)) return@forEach
        val p = cam.toScreen(st.x, st.y)
        if (!onScreen(p)) return@forEach
        val inter = st.station.interchange
        val r = (if (z < 0.03f) (if (inter) 2.6f else 1.8f) else if (z < 0.12f) (if (inter) 4.6f else 3.4f) else (if (inter) 7.5f else 6.2f)) * d
        val dot = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
        if (z >= 0.03f && !free(dot)) return@forEach
        drawCircle(MapC.casing, r + 1.2f * d, p)
        if (z < 0.03f) drawCircle(Color.White, r, p) else {
            // One ring per line, so an interchange shows all its colours.
            val n = st.colors.size
            val inner = r * (if (n > 1) 0.4f else 0.56f)
            for (i in 0 until n) drawCircle(st.colors[i], r - (r - inner) * i / n, p)
            drawCircle(Color.White, inner, p)
        }
        if (z >= 0.03f) {
            taken.add(dot)
            hits?.add(MapHit(RectF(p.x - 18 * d, p.y - 18 * d, p.x + 18 * d, p.y + 18 * d), null, st.station))
        }
        if (z >= (if (inter) 0.032f else 0.06f)) {
            val name = st.station.name
            val tw = ink.width(ink.station, name)
            val box = RectF(p.x - tw / 2 - 3 * d, p.y + r + 2 * d, p.x + tw / 2 + 3 * d, p.y + r + 16 * d)
            if (free(box)) { text(ink.station, name, p.x, p.y + r + 13 * d); taken.add(box) }
        }
    }

    // Area names.
    scene.city?.labels?.forEach { l ->
        val ok = when (l.rank) { 0 -> z in 0.005f..0.06f; 1 -> z in 0.014f..0.16f; else -> z in 0.06f..0.5f }
        if (!ok || riding || !inBox(l.x, l.y, 0f)) return@forEach
        val p = cam.toScreen(l.x, l.y)
        if (!onScreen(p)) return@forEach
        val paint = when (l.rank) { 0 -> ink.area0; 1 -> ink.area1; else -> ink.area2 }
        val tw = ink.width(paint, l.text)
        val box = RectF(p.x - tw / 2 - 8 * d, p.y - 13 * d, p.x + tw / 2 + 8 * d, p.y + 7 * d)
        if (free(box)) { text(paint, l.text, p.x, p.y + 4 * d); taken.add(box) }
    }

    // Places, most important first.
    if (on.places) {
        val cand = ArrayList<MapPoi>()
        scene.city?.places?.forEach { if (z in 0.035f..0.3f && inBox(it.x, it.y, 0f)) cand.add(it) }
        for (c in cells) for (q in c.pois) {
            val need = when (q.poi.kind) {
                PlaceKind.METRO_GATE -> 1.0f
                else -> when (q.poi.rank) { 0 -> 0.035f; 1 -> 0.14f; 2 -> 0.32f; else -> 0.7f }
            }
            if (z < need) continue
            if (riding && (q.poi.rank > 2 || q.poi.kind !in RIDE_KINDS)) continue
            if (inBox(q.x, q.y, 0f)) cand.add(q)
        }
        cand.sortWith(compareBy({ it.poi.rank }, { it.poi.kind == PlaceKind.PARK }, { it.poi.name.length }, { it.poi.name }))
        var placed = 0
        val maxPlaced = if (riding) 14 else 70
        for (q in cand) {
            if (placed >= maxPlaced) break
            val p = cam.toScreen(q.x, q.y)
            if (!onScreen(p)) continue
            val r = (if (z < 0.12f) 7f else 9f) * d
            val dot = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
            if (!free(dot)) continue
            taken.add(dot); placed++
            val color = kindColor(q.poi.kind)
            drawCircle(MapC.casing, r + 1f * d, p)
            drawCircle(Color(0xFF1E211C), r, p)
            ink.icons[q.poi.kind]?.let { icon ->
                val sz = r * 1.25f
                translate(p.x - sz / 2, p.y - sz / 2) { with(icon) { draw(Size(sz, sz), colorFilter = ColorFilter.tint(color)) } }
            }
            hits?.add(MapHit(RectF(p.x - 17 * d, p.y - 17 * d, p.x + 17 * d, p.y + 17 * d), q.poi, null))
            val name = if (q.poi.name.length > 26) q.poi.name.take(25).trimEnd() + "…" else q.poi.name
            val paint = ink.poi(q.poi.kind)
            val tw = ink.width(paint, name)
            val box = RectF(p.x - tw / 2 - 2 * d, p.y + r + 1 * d, p.x + tw / 2 + 2 * d, p.y + r + 14 * d)
            if (free(box)) { text(paint, name, p.x, p.y + r + 11.5f * d); taken.add(box) }
        }
    }

    // Street names along the streets.
    if (z >= 0.1f) {
        val seen = HashMap<String, ArrayList<Offset>>()
        val tags = ArrayList<NameTag>()
        for (c in cells) for (t in c.tags) {
            val need = when (t.layer) { Layer.HIGHWAY -> 0.1f; Layer.MAJOR -> 0.18f; Layer.MINOR -> 0.45f; else -> 0.9f }
            if (z >= need && inBox(t.x, t.y, 0f)) tags.add(t)
        }
        tags.sortWith(compareBy({ -it.layer.ordinal }, { -it.lenM }))
        var n = 0
        for (t in tags) {
            if (n >= (if (riding) 10 else 34)) break
            val tw = ink.width(ink.road, t.text)
            if (tw > t.lenM * cam.scale * 1.25f) continue
            val p = cam.toScreen(t.x, t.y)
            if (!onScreen(p)) continue
            val near = seen.getOrPut(t.text) { ArrayList() }
            if (near.any { (it - p).getDistance() < 230 * d }) continue
            var ang = (t.angle + cam.rot) % 360f
            if (ang > 180) ang -= 360f
            if (ang > 90) ang -= 180f
            if (ang < -90) ang += 180f
            val rad = Math.toRadians(ang.toDouble())
            val hw = (abs(cos(rad)) * tw / 2 + abs(sin(rad)) * 6 * d).toFloat()
            val hh = (abs(sin(rad)) * tw / 2 + abs(cos(rad)) * 6 * d).toFloat()
            val box = RectF(p.x - hw, p.y - hh, p.x + hw, p.y + hh)
            if (!free(box)) continue
            rotate(ang, p) { text(ink.road, t.text, p.x, p.y + 3.5f * d) }
            taken.add(box); near.add(p); n++
        }
    }
}

/** While riding, only landmarks that help you place yourself. */
private val RIDE_KINDS = setOf(
    PlaceKind.METRO, PlaceKind.RAIL, PlaceKind.BUS, PlaceKind.FUEL, PlaceKind.HOSPITAL, PlaceKind.MALL,
    PlaceKind.MARKET, PlaceKind.LANDMARK, PlaceKind.CINEMA, PlaceKind.POLICE, PlaceKind.EDUCATION, PlaceKind.WORSHIP,
)

/** Where you really are: blue dot, accuracy circle, and which way you're facing when moving. */
private fun DrawScope.drawMe(p: Offset, me: MeDot, s: Float, d: Float, rot: Float) {
    val acc = me.accuracyM * s
    val col = if (me.stale) Color(0xFF8C948A) else MapC.me
    if (acc > 10 * d) {
        drawCircle(col.copy(alpha = 0.13f), acc, p)
        drawCircle(col.copy(alpha = 0.35f), acc, p, style = Stroke(1f * d))
    }
    me.bearing?.let { b ->
        rotate(b + rot, p) {
            val cone = Path().apply { moveTo(p.x, p.y - 22 * d); lineTo(p.x + 9 * d, p.y - 9 * d); lineTo(p.x - 9 * d, p.y - 9 * d); close() }
            drawPath(cone, col.copy(alpha = 0.85f))
        }
    }
    drawCircle(Color.Black.copy(alpha = 0.35f), 11f * d, p.copy(y = p.y + 1.5f * d))
    drawCircle(Color.White, 9.5f * d, p)
    drawCircle(col, 7f * d, p)
}

/**
 * Pickup and destination look different in shape, colour and icon: the pickup is a round lime badge with a bike,
 * the destination is an ivory pin with a flag whose tip marks the spot.
 */
fun DrawScope.drawMarker(p: Offset, m: MapMarker, ink: MapInk) {
    val d = ink.density
    val nc = drawContext.canvas.nativeCanvas
    fun icon(painter: Painter, c: Offset, sz: Float) =
        translate(c.x - sz / 2, c.y - sz / 2) { with(painter) { draw(Size(sz, sz), colorFilter = ColorFilter.tint(C.bg)) } }
    if (m.destination) {
        val c = Offset(p.x, p.y - 30f * d)
        val r = 15f * d
        drawOval(Color.Black.copy(alpha = 0.45f), Offset(p.x - 7 * d, p.y - 2.5f * d), Size(14 * d, 5 * d))
        val pin = Path().apply {
            moveTo(p.x, p.y)
            cubicTo(p.x - 3 * d, p.y - 10 * d, c.x - r, c.y + 11 * d, c.x - r, c.y)
            cubicTo(c.x - r, c.y - r * 1.33f, c.x + r, c.y - r * 1.33f, c.x + r, c.y)
            cubicTo(c.x + r, c.y + 11 * d, p.x + 3 * d, p.y - 10 * d, p.x, p.y)
            close()
        }
        drawPath(pin, Color.Black.copy(alpha = 0.55f), style = Stroke(4f * d, join = StrokeJoin.Round))
        drawPath(pin, Ink.i100)
        icon(ink.flag, c.copy(y = c.y - 1 * d), 16 * d)
    } else {
        drawCircle(Color.Black.copy(alpha = 0.45f), 16f * d, p.copy(y = p.y + 2 * d))
        drawCircle(Color.Black.copy(alpha = 0.6f), 16f * d, p)
        drawCircle(MapC.route, 14.5f * d, p)
        icon(ink.bike, p, 16 * d)
    }
    if (m.label.isNotEmpty()) {
        val t = if (m.label.length > 24) m.label.take(23).trimEnd() + "…" else m.label
        val y = p.y + (if (m.destination) 16f else 31f) * d
        nc.drawText(t, p.x, y, ink.marker.halo)
        nc.drawText(t, p.x, y, ink.marker.fill)
    }
}
