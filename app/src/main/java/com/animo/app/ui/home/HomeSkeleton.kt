package com.animo.app.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * First-paint skeleton with the same geometry as [HomeScreen]: flush hero, three shelf headers and
 * a poster rail under each. Nothing is tappable, so the shimmer is the only motion on screen.
 */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "home-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.24f,
        targetValue = 0.58f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "home-skeleton-alpha",
    )
    val tileColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        userScrollEnabled = false,
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(208.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(tileColor),
            )
        }
        items(count = 3) {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .width(150.dp)
                            .height(16.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(tileColor),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .width(116.dp)
                                .aspectRatio(2f / 3f)
                                .clip(MaterialTheme.shapes.medium)
                                .background(tileColor),
                        )
                    }
                }
            }
        }
    }
}
