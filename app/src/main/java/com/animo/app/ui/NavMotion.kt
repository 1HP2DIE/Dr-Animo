package com.animo.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.animo.app.ui.shell.Destinations

/**
 * Screen motion, one rule set for the whole graph:
 *
 *  - between two tabs: a quick crossfade with a hint of scale, because tabs are siblings, not depth;
 *  - pushing a page (detail, downloads…): it slides in a short way from the right while the page
 *    under it drifts left and dims, and back reverses that.
 *
 * Both slides are led by a spring rather than a fixed curve: the page arrives with a little
 * momentum and settles, which reads as weight instead of a video being fast-forwarded. A very
 * small scale on the incoming page (0.985 -> 1) supplies the depth the flat slide was missing, and
 * the page underneath fades and shrinks a touch so the two do not look pasted on top of each other.
 */
internal object NavMotion {

    private const val DURATION = 260
    private const val TAB_DURATION = 190
    private val easing = FastOutSlowInEasing

    /** Push and pop ride the same spring so forward and back feel like one system. */
    private val slide = spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 650f,
    )

    private fun isTab(route: String?) = Destinations.tabForRoute(route) != null

    private fun betweenTabs(from: String?, to: String?) = isTab(from) && isTab(to)

    fun enter(from: String?, to: String?): EnterTransition =
        if (betweenTabs(from, to)) {
            fadeIn(tween(TAB_DURATION, delayMillis = 40)) +
                scaleIn(tween(TAB_DURATION, delayMillis = 40), initialScale = 0.98f)
        } else {
            slideInHorizontally(slide) { it / 6 } +
                fadeIn(tween(DURATION)) +
                scaleIn(tween(DURATION, easing = easing), initialScale = 0.985f)
        }

    fun exit(from: String?, to: String?): ExitTransition =
        if (betweenTabs(from, to)) {
            fadeOut(tween(TAB_DURATION / 2))
        } else {
            slideOutHorizontally(tween(DURATION, easing = easing)) { -it / 12 } +
                fadeOut(tween(DURATION), targetAlpha = 0.55f) +
                scaleOut(tween(DURATION, easing = easing), targetScale = 0.99f)
        }

    fun popEnter(from: String?, to: String?): EnterTransition =
        if (betweenTabs(from, to)) {
            fadeIn(tween(TAB_DURATION))
        } else {
            slideInHorizontally(slide) { -it / 12 } +
                fadeIn(tween(DURATION), initialAlpha = 0.55f) +
                scaleIn(tween(DURATION, easing = easing), initialScale = 0.99f)
        }

    fun popExit(from: String?, to: String?): ExitTransition =
        if (betweenTabs(from, to)) {
            fadeOut(tween(TAB_DURATION / 2))
        } else {
            slideOutHorizontally(tween(DURATION, easing = easing)) { it / 6 } +
                fadeOut(tween(DURATION))
        }
}
