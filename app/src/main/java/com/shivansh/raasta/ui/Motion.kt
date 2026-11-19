package com.shivansh.raasta.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.shivansh.raasta.Screen
import kotlinx.coroutines.delay

/** One motion language for the whole app: soft springs, short fades, things arrive from where they belong. */
object Motion {
    val ease = FastOutSlowInEasing

    /** How "deep" a screen is, so we know whether a change is a push or a pop. */
    fun depth(s: Screen) = when (s) {
        Screen.Home -> 0
        Screen.NewRoute -> 1
        is Screen.Pack -> 1
        Screen.Navigate -> 2
    }

    /** iOS-style navigation: push slides in from the right over a parallaxed, dimmed page; modals rise up. */
    fun screens(): AnimatedContentTransitionScope<Screen>.() -> ContentTransform = {
        val from = initialState
        val to = targetState
        val forward = depth(to) > depth(from)
        when {
            to == Screen.NewRoute ->
                (slideInVertically(spring(dampingRatio = 0.86f, stiffness = 420f)) { it / 6 } + fadeIn(tween(220))) togetherWith
                    (fadeOut(tween(160)) + scaleOut(tween(260), targetScale = 0.96f))
            from == Screen.NewRoute ->
                (fadeIn(tween(220)) + scaleIn(tween(300, easing = ease), initialScale = 0.96f)) togetherWith
                    (slideOutVertically(tween(260, easing = ease)) { it / 6 } + fadeOut(tween(200)))
            to == Screen.Navigate ->
                (fadeIn(tween(320)) + scaleIn(tween(420, easing = ease), initialScale = 1.06f)) togetherWith fadeOut(tween(220))
            forward ->
                slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { it } togetherWith
                    (slideOutHorizontally(tween(320, easing = ease)) { -it / 4 } + fadeOut(tween(320), targetAlpha = 0.3f))
            else ->
                (slideInHorizontally(tween(320, easing = ease)) { -it / 4 } + fadeIn(tween(320), initialAlpha = 0.3f)) togetherWith
                    slideOutHorizontally(spring(dampingRatio = 0.9f, stiffness = 380f)) { it }
        }
    }
}

/**
 * Staggered entrance: the element fades in and rises a few dp, [index] steps after its siblings.
 * Runs once per composition of the element.
 */
@Composable
fun Modifier.appear(index: Int = 0, delayStep: Long = 55L, distance: Int = 18): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * delayStep)
        p.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
    }
    val dy = with(LocalDensity.current) { distance.dp.toPx() }
    return this.graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * dy
    }
}
