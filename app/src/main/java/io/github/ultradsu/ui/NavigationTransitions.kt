package io.github.ultradsu.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** The header and bottom navigation stay fixed; each tab has its own moving content. */
internal fun AnimatedContentTransitionScope<Int>.tabContentTransition(): ContentTransform {
    val direction = if (targetState > initialState) AnimatedContentTransitionScope.SlideDirection.Start
        else AnimatedContentTransitionScope.SlideDirection.End
    return ((slideIntoContainer(direction, tween(320, easing = FastOutSlowInEasing)) + fadeIn(tween(200, 40)))
        togetherWith (slideOutOfContainer(direction, tween(320, easing = FastOutSlowInEasing)) + fadeOut(tween(160))))
        .using(SizeTransform(clip = false))
}

/** Push the editor over a parallax list; pop keeps the departing editor on top. */
internal fun AnimatedContentTransitionScope<String?>.slotScreenTransition(): ContentTransform {
    val spec = tween<androidx.compose.ui.unit.IntOffset>(360, easing = FastOutSlowInEasing)
    val transform = if (targetState != null) {
        slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it / 3 }
    } else {
        slideInHorizontally(spec) { -it / 3 } togetherWith slideOutHorizontally(spec) { it }
    }
    transform.targetContentZIndex = if (targetState != null) 1f else 0f
    return transform.using(SizeTransform(clip = false))
}

/** The outgoing page remains drawable during animation, but cannot activate stale actions. */
internal fun Modifier.transitionInput(active: Boolean): Modifier = if (active) this else
    clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
