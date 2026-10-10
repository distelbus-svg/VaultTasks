package app.vaulttasks.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

private val Screen.isTab get() = this == Screen.Tasks || this == Screen.Calendar
private val Screen.tabIndex get() = if (this == Screen.Calendar) 1 else 0
private val Screen.depth get() = when (this) {
    Screen.Tasks, Screen.Calendar -> 0
    is Screen.SpaceEdit -> 2
    else -> 1
}

/**
 * Screen changes use the Material shared-axis X pattern that Pixel's Settings app uses: the outgoing screen fades out
 * quickly while drifting 30 dp away, then the incoming one fades in while drifting 30 dp into place. Forward (deeper, or
 * the next tab) moves right-to-left; back and the previous tab move the other way. Nothing scales or flashes.
 *
 * @param slidePx 30 dp in pixels
 */
fun AnimatedContentTransitionScope<Screen>.screenTransition(slidePx: Int): ContentTransform {
    val from = initialState
    val to = targetState
    val forward = if (from.isTab && to.isTab) to.tabIndex > from.tabIndex else to.depth > from.depth
    val dir = if (forward) 1 else -1
    val slide = tween<IntOffset>(durationMillis = 300, easing = FastOutSlowInEasing)
    val enter = slideInHorizontally(slide) { slidePx * dir } +
        fadeIn(tween(durationMillis = 210, delayMillis = 90, easing = LinearOutSlowInEasing))
    val exit = slideOutHorizontally(slide) { -slidePx * dir } +
        fadeOut(tween(durationMillis = 90, easing = FastOutLinearInEasing))
    return enter.togetherWith(exit)
}
