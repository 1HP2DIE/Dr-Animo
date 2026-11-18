package com.animo.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import com.animo.app.R
import com.animo.app.di.ServiceLocator
import kotlinx.coroutines.launch

/**
 * Heart toggle backed by the favourites table. Filling the heart pops it with a short spring and a
 * light haptic, so the save is felt rather than announced with a snackbar.
 */
@Composable
fun FavoriteButton(
    animeId: Int,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val repository = ServiceLocator.favorites
    val stored by remember(animeId) { repository.observeIsFavorite(animeId) }.collectAsState(initial = false)
    // The write is asynchronous, so the heart follows the tap and the database confirms it a moment
    // later. Without this a fast double tap read the same stale value twice and lost one of them.
    var pending by remember(animeId) { mutableStateOf<Boolean?>(null) }
    val favorite = pending ?: stored
    LaunchedEffect(stored, pending) { if (pending == stored) pending = null }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val pop = remember { Animatable(1f) }
    var armed by remember { mutableStateOf(false) }

    LaunchedEffect(favorite) {
        if (favorite && armed) {
            pop.snapTo(0.6f)
            pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
    }

    IconButton(
        onClick = {
            armed = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val next = !favorite
            pending = next
            scope.launch {
                // A Room failure must not take the composer down; the heart simply falls back to
                // whatever is really stored.
                runCatching { repository.setFavorite(animeId, next) }
                    .onFailure { if (pending == next) pending = null }
            }
        },
        modifier = modifier,
    ) {
        AnimatedContent(
            targetState = favorite,
            transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
            label = "favorite",
        ) { on ->
            Icon(
                imageVector = if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(if (on) R.string.ui_favorite_remove else R.string.ui_favorite_add),
                tint = if (on) MaterialTheme.colorScheme.primary else tint,
                modifier = Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
            )
        }
    }
}
