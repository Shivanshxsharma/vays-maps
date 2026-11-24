package com.shivansh.raasta.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The ground (land, water, parks, roads, metro) as cached image tiles, painted on background threads.
 * Moving, zooming, rotating and tilting the map then only moves pictures around, which every phone does smoothly;
 * stroking thousands of road segments per frame is not something every phone can do.
 */
class RasterMap(private val scope: CoroutineScope, private val density: Float) {
    private data class Key(val level: Int, val i: Int, val j: Int)
    private class Entry(val bitmap: Bitmap, var dirty: Boolean)
    private class Change(val gen: Int, val b: FloatArray?)

    private val cache = LinkedHashMap<Key, Entry>(64, 0.75f, true)
    private val inFlight = HashSet<Key>()
    private var wanted = ArrayList<Key>()
    private var wantedSet: Set<Key> = emptySet()
    private var scene: Scene? = null
    private var style = GroundStyle()
    private var gen = 0
    private val changes = ArrayList<Change>()
    private var workers = 0
    private var ready by mutableIntStateOf(0)
    private var alive = true
    private val paint = Paint().apply { isFilterBitmap = true; isAntiAlias = false }
    private val dst = RectF()

    fun close() { alive = false; cache.clear(); wanted.clear() }

    /** New content: only the tiles it touches are repainted, and the old picture stays until the new one is ready. */
    fun update(s: Scene, st: GroundStyle) {
        val old = scene
        if (s === old && st == style) return
        scene = s
        gen++
        if (old == null || st != style || s.city !== old.city || s.pack !== old.pack) {
            changes.add(Change(gen, null))
            for (e in cache.values) e.dirty = true
        } else {
            val had = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CellSet, Boolean>()).apply { addAll(old.live) }
            for (c in s.live) if (c !in had) {
                changes.add(Change(gen, c.bounds))
                for ((k, e) in cache) if (!e.dirty && hits(k, c.bounds)) e.dirty = true
            }
        }
        while (changes.size > 400) changes.removeAt(0)
        style = st
    }

    private fun tileM(level: Int) = TILE / scaleOf(level)

    private fun hits(k: Key, b: FloatArray): Boolean {
        val t = tileM(k.level)
        return b[0] < (k.i + 1) * t + 20 && b[2] > k.i * t - 20 && b[1] < (k.j + 1) * t + 20 && b[3] > k.j * t - 20
    }

    /** Draws what we have and asks for what's missing. [stretch] > 1 asks for more ground (tilted view sees further). */
    fun draw(ds: DrawScope, cam: MapCamera, stretch: Float) {
        ready // redraw when a tile arrives
        val s = cam.scale
        if (scene == null || !(s > 0.001f) || !cam.cx.isFinite() || !cam.cy.isFinite()) return
        val level = ceil(2 * ln(s) / LN2 - 0.02f).toInt()
        val t = tileM(level)
        val a = cam.anchor
        val w = ds.size.width; val h = ds.size.height
        val r = Math.toRadians(cam.rot.toDouble()); val cosR = cos(r).toFloat(); val sinR = sin(r).toFloat()
        // Screen box (stretched upwards when tilted) turned into world corners.
        val xs = floatArrayOf(-(a.x) * stretch, (w - a.x) * stretch)
        val ys = floatArrayOf(-a.y * stretch, h - a.y)
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (px in xs) for (py in ys) {
            val wx = cam.cx + (px * cosR + py * sinR) / s; val wy = cam.cy + (-px * sinR + py * cosR) / s
            x0 = minOf(x0, wx); x1 = maxOf(x1, wx); y0 = minOf(y0, wy); y1 = maxOf(y1, wy)
        }
        val halfW = (w * stretch) / s; val up = a.y * stretch / s; val down = (h - a.y) / s
        val rad = t * 0.71f
        val need = ArrayList<Key>()
        for (i in floor(x0 / t).toInt()..floor(x1 / t).toInt()) for (j in floor(y0 / t).toInt()..floor(y1 / t).toInt()) {
            // Skip corners of the bounding box a rotated view never sees.
            val dx = (i + 0.5f) * t - cam.cx; val dy = (j + 0.5f) * t - cam.cy
            val vx = dx * cosR - dy * sinR; val vy = dx * sinR + dy * cosR
            if (abs(vx) > halfW / 2 + abs(w / 2 - a.x) / s + rad || vy < -up - rad || vy > down + rad) continue
            need.add(Key(level, i, j))
            if (need.size > 160) break
        }
        val missing = ArrayList<Key>()
        for (k in need) { val e = cache[k]; if (e == null || e.dirty) missing.add(k) }
        val nc = ds.drawContext.canvas.nativeCanvas
        nc.save()
        nc.translate(a.x, a.y)
        nc.rotate(cam.rot)
        nc.scale(s, s)
        nc.translate(-cam.cx, -cam.cy)
        if (need.any { cache[it] == null }) {
            // Something to look at while the sharp tiles are painted: whatever other zoom levels we have, nearest on top.
            val others = cache.entries.filter { it.key.level != level && abs(it.key.level - level) <= 7 }
                .filter { (k, _) -> val m = tileM(k.level); k.i * m < x1 && (k.i + 1) * m > x0 && k.j * m < y1 && (k.j + 1) * m > y0 }
                .sortedByDescending { abs(it.key.level - level) }
            for ((k, e) in others) blit(nc, k, e)
        }
        for (k in need) cache[k]?.let { blit(nc, k, it) }
        nc.restore()
        // Nearest to the middle first.
        missing.sortBy { k -> hypot((k.i + 0.5f) * t - cam.cx, (k.j + 0.5f) * t - cam.cy) }
        wanted = missing
        wantedSet = need.toHashSet()
        pump()
    }

    private fun blit(nc: android.graphics.Canvas, k: Key, e: Entry) {
        val t = tileM(k.level); val b = EDGE / scaleOf(k.level)
        dst.set(k.i * t - b, k.j * t - b, (k.i + 1) * t + b, (k.j + 1) * t + b)
        nc.drawBitmap(e.bitmap, null, dst, paint)
    }

    private fun pump() {
        while (workers < WORKERS && alive) {
            val k = wanted.firstOrNull { it !in inFlight } ?: return
            val s = scene ?: return
            val st = style
            val g = gen
            inFlight.add(k)
            workers++
            scope.launch {
                try {
                    val bmp = withContext(Dispatchers.Default) { render(k, s, st) }
                    if (alive) {
                        val stale = changes.any { it.gen > g && (it.b == null || hits(k, it.b)) }
                        cache[k] = Entry(bmp, stale)
                        trim()
                        ready++
                    }
                } finally {
                    inFlight.remove(k)
                    wanted.remove(k)
                    workers--
                    pump()
                }
            }
        }
    }

    private fun trim() {
        val it = cache.entries.iterator()
        while (cache.size > max(70, wantedSet.size + 16) && it.hasNext()) if (it.next().key !in wantedSet) it.remove()
    }

    private fun render(k: Key, s: Scene, st: GroundStyle): Bitmap {
        val t = tileM(k.level)
        val px = TILE.toInt() + 2 * EDGE
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.RGB_565)
        bmp.eraseColor(MapC.land.toArgb())
        paintGround(bmp, s, st, (k.i + 0.5f) * t, (k.j + 0.5f) * t, scaleOf(k.level), density)
        bmp.prepareToDraw()
        return bmp
    }

    companion object {
        private const val TILE = 512f
        /** Painted past the edge, so neighbours overlap and no seams show when rotated. */
        private const val EDGE = 1
        private const val WORKERS = 3
        private val LN2 = ln(2f)
        private fun scaleOf(level: Int) = 2f.pow(level / 2f)

        fun paintGround(bmp: Bitmap, s: Scene, st: GroundStyle, cx: Float, cy: Float, scale: Float, density: Float) {
            val size = Size(bmp.width.toFloat(), bmp.height.toFloat())
            CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(bmp.asImageBitmap()), size) {
                drawGround(s, cx, cy, scale, density, st)
            }
        }
    }
}

@Composable
fun rememberRaster(): RasterMap {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val r = remember { RasterMap(scope, density) }
    DisposableEffect(r) { onDispose { r.close() } }
    return r
}
