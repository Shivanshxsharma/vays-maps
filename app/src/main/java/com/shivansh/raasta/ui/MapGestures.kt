package com.shivansh.raasta.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** A camera you can move: animated moves, flings, zoom steps. Touching the map stops whatever is running. */
class MapState(val cam: MapCamera, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun stop() { job?.cancel(); job = null }

    fun launch(block: suspend CoroutineScope.() -> Unit) { stop(); job = scope.launch(block = block) }

    /** Glides there. Long trips lift off a little first so you can see where you're going. */
    fun flyTo(x: Float, y: Float, scale: Float = cam.scale, ms: Int = 620) = launch {
        val x0 = cam.cx; val y0 = cam.cy; val s0 = cam.scale
        val s1 = scale.coerceIn(cam.minScale, cam.maxScale)
        val screens = hypot(x - x0, y - y0) * min(s0, s1) / cam.size.minDimension.coerceAtLeast(1f)
        val lift = if (screens > 1.5f) (1.5f / screens).coerceAtLeast(0.15f) else 1f
        Animatable(0f).animateTo(1f, tween(if (lift < 1f) ms + 350 else ms, easing = FastOutSlowInEasing)) {
            val t = value
            cam.scale = (s0 * (s1 / s0).pow(t) * lift.pow(4 * t * (1 - t))).coerceIn(cam.minScale, cam.maxScale)
            cam.cx = x0 + (x - x0) * t
            cam.cy = y0 + (y - y0) * t
        }
    }

    fun zoomBy(f: Float, at: Offset = cam.anchor, ms: Int = 280) = launch {
        val s0 = cam.scale
        Animatable(0f).animateTo(1f, tween(ms, easing = FastOutSlowInEasing)) {
            cam.transform(at, at, s0 * f.pow(value) / cam.scale)
        }
    }

    fun fling(v: Velocity) {
        if (hypot(v.x, v.y) < 250f) return
        launch {
            var last = Offset.Zero
            AnimationState(Offset.VectorConverter, Offset.Zero, Offset(v.x, v.y)).animateDecay(exponentialDecay(frictionMultiplier = 1.4f)) {
                cam.panBy(value - last)
                last = value
            }
        }
    }

    /** Fits [b] = (x0, y0, x1, y1) into the screen minus the given insets (px). */
    fun fit(b: FloatArray, left: Float, top: Float, right: Float, bottom: Float, animate: Boolean, maxScale: Float = cam.maxScale) {
        if (cam.size.width <= 0f) return
        val w = cam.size.width - left - right; val h = cam.size.height - top - bottom
        if (w <= 0 || h <= 0) return
        val s = min(w / (b[2] - b[0]).coerceAtLeast(60f), h / (b[3] - b[1]).coerceAtLeast(60f)).coerceIn(cam.minScale, min(maxScale, cam.maxScale))
        val x = (b[0] + b[2]) / 2 - (left + w / 2 - cam.anchor.x) / s
        val y = (b[1] + b[3]) / 2 - (top + h / 2 - cam.anchor.y) / s
        if (animate) flyTo(x, y, s) else { stop(); cam.cx = x; cam.cy = y; cam.scale = s }
    }

    /** Puts a point in the middle of the part of the screen not covered by panels. */
    fun show(x: Float, y: Float, scale: Float, top: Float, bottom: Float) =
        flyTo(x, y + (cam.anchor.y - (top + (cam.size.height - top - bottom) / 2)) / scale, scale)
}

/**
 * Every gesture people expect from a map: pinch around your fingers, drag with a fling, double-tap to zoom in,
 * double-tap-and-drag to zoom with one thumb, two-finger tap to zoom out, tap, and press-and-hold.
 */
fun Modifier.mapGestures(
    state: MapState,
    onTap: (Offset) -> Unit = {},
    onLongPress: (Offset) -> Unit = {},
    /** Return true if handled (the riding map follows you while pinching). */
    onTransform: (from: Offset, to: Offset, zoom: Float, fingers: Int) -> Boolean = { _, _, _, _ -> false },
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val tap by rememberUpdatedState(onTap)
    val hold by rememberUpdatedState(onLongPress)
    val move by rememberUpdatedState(onTransform)
    pointerInput(state) {
        var lastTapAt = 0L
        var lastTapPos = Offset.Zero
        var tapJob: Job? = null
        val scope = CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            state.stop()
            val second = down.uptimeMillis - lastTapAt < 300 && (down.position - lastTapPos).getDistance() < 56.dp.toPx()
            if (second) tapJob?.cancel()
            val slop = viewConfiguration.touchSlop
            val tracker = VelocityTracker()
            var moved = false
            var multi = false
            var held = false
            var travel = Offset.Zero
            var spread = 0f
            var upAt = down.uptimeMillis
            tracker.addPosition(down.uptimeMillis, down.position)
            fun apply(from: Offset, to: Offset, zoom: Float, fingers: Int) {
                if (!move(from, to, zoom, fingers)) state.cam.transform(from, to, zoom)
            }
            while (true) {
                val ev = if (!moved && !multi && !held) {
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { awaitPointerEvent() }
                } else awaitPointerEvent()
                if (ev == null) {
                    if (!second) {
                        held = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hold(down.position)
                    } else moved = true
                    continue
                }
                val pressed = ev.changes.filter { it.pressed }
                upAt = ev.changes.maxOf { it.uptimeMillis }
                if (pressed.isEmpty()) break
                if (held) { ev.changes.forEach { it.consume() }; continue }
                if (pressed.size >= 2) {
                    multi = true
                    val zoom = ev.calculateZoom()
                    val from = ev.calculateCentroid(useCurrent = false)
                    val to = ev.calculateCentroid(useCurrent = true)
                    spread += abs(ln(zoom))
                    if (from.isSpecified && to.isSpecified) {
                        travel += to - from
                        if (zoom != 1f || from != to) apply(from, to, zoom, pressed.size)
                    }
                    ev.changes.forEach { it.consume() }
                } else {
                    val c = pressed[0]
                    val d = c.positionChange()
                    if (multi) {
                        if (d != Offset.Zero) apply(c.previousPosition, c.position, 1f, 1)
                        c.consume()
                        continue
                    }
                    tracker.addPosition(c.uptimeMillis, c.position)
                    travel += d
                    if (!moved && travel.getDistance() > slop) moved = true
                    if (moved && d != Offset.Zero) {
                        // Second tap held and dragged: zoom with one thumb, down = in.
                        if (second) apply(down.position, down.position, exp(d.y / 130.dp.toPx()), 2)
                        else apply(c.previousPosition, c.position, 1f, 1)
                        c.consume()
                    }
                }
            }
            when {
                held -> {}
                multi -> if (upAt - down.uptimeMillis < 280 && spread < 0.06f && travel.getDistance() < slop * 2) state.zoomBy(0.5f, down.position)
                moved -> if (!second) tracker.calculateVelocity().let { v -> if (!move(Offset.Unspecified, Offset.Unspecified, 0f, 0)) state.fling(v) }
                second -> { lastTapAt = 0L; if (!move(down.position, down.position, 2f, 2)) state.zoomBy(2f, down.position) }
                else -> {
                    lastTapAt = upAt
                    lastTapPos = down.position
                    tapJob = scope.launch { delay(260); tap(down.position) }
                }
            }
        }
    }
}
