package com.animo.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
 * Distances are a fraction of the width and durations stay under 300 ms, so it reads as smooth
 * rather than slow.
 */
internal object NavMotion {

    private const val DURATION = 280
    private const val TAB_DURATION = 200
    private val easing = FastOutSlowInEasing

    private fun isTab(route: String?) = Destinations.tabForRoute(route) != null

    private fun betweenTabs(from: String?, to: String?) = isTab(from) && isTab(to)

    fun enter(from: String?, to: String?): EnterTransition =
        if (betweenTabs(from, to)) {
            fadeIn(tween(TAB_DURATION, delayMillis = 40)) + scaleIn(tween(TAB_DURATION, delayMillis = 40), initialScale = 0.98f)
        } else {
            slideInHorizontally(tween(DURATION, easing = easing)) { it / 5 } + fadeIn(tween(DURATION))
        }

    fun exit(from: String?, to: String?): ExitTransition =
        if (betweenTabs(from, to)) {
            fadeOut(tween(TAB_DURATION / 2))
        } else {
            slideOutHorizontally(tween(DURATION, easing = easing)) { -it / 10 } + fadeOut(tween(DURATION), targetAlpha = 0.4f)
        }

    fun popEnter(from: String?, to: String?): EnterTransition =
        if (betweenTabs(from, to)) {
            fadeIn(tween(TAB_DURATION))
        } else {
            slideInHorizontally(tween(DURATION, easing = easing)) { -it / 10 } + fadeIn(tween(DURATION), initialAlpha = 0.4f)
        }

    fun popExit(from: String?, to: String?): ExitTransition =
        if (betweenTabs(from, to)) {
            fadeOut(tween(TAB_DURATION / 2))
        } else {
            slideOutHorizontally(tween(DURATION, easing = easing)) { it / 5 } + fadeOut(tween(DURATION))
        }
}
