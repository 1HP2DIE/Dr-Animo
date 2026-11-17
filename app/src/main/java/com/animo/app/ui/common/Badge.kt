package com.animo.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class BadgeTone { Accent, Muted, Outlined, Danger, Amber }

/**
 * Small uppercase pill used for meta (year / type / score), audio (SUB / DUB) and status.
 * Named `PillBadge` on purpose: `Badge` is already a Material3 composable.
 */
@Composable
fun PillBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.Muted,
) {
    val colors = MaterialTheme.colorScheme
    val background = when (tone) {
        BadgeTone.Accent -> colors.primary.copy(alpha = 0.16f)
        BadgeTone.Muted -> colors.onSurface.copy(alpha = 0.07f)
        BadgeTone.Outlined -> Color.Transparent
        BadgeTone.Danger -> colors.error.copy(alpha = 0.16f)
        BadgeTone.Amber -> colors.tertiary.copy(alpha = 0.16f)
    }
    val content = when (tone) {
        BadgeTone.Accent -> colors.primary
        BadgeTone.Muted -> colors.onSurfaceVariant
        BadgeTone.Outlined -> colors.onSurfaceVariant
        BadgeTone.Danger -> colors.error
        BadgeTone.Amber -> colors.tertiary
    }
    val shape = RoundedCornerShape(6.dp)
    var box = modifier
        .clip(shape)
        .background(background)
    if (tone == BadgeTone.Outlined) {
        box = box.border(1.dp, colors.outline, shape)
    }
    Text(
        text = text,
        modifier = box.padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
    )
}
