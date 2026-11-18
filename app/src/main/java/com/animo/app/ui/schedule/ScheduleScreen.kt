package com.animo.app.ui.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.animo.app.R
import com.animo.app.di.ServiceLocator
import com.animo.app.domain.model.AiringDay
import com.animo.app.domain.model.AiringSlot
import com.animo.app.ui.common.EmptyState
import com.animo.app.ui.shell.AnimoTopBar
import com.animo.app.ui.theme.AnimoPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The coming week of releases: a day strip (today first) over that day's episodes in air order.
 * Built from each airing title's recorded next air time, so it works offline; the silent sync keeps
 * those times current.
 */
@Composable
fun ScheduleScreen(
    onOpenDrawer: () -> Unit,
    onOpenAnime: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val flow = remember { ServiceLocator.favorites.observeSchedule() }
    val days: List<AiringDay>? by flow.collectAsState(initial = null)
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        AnimoTopBar(
            title = stringResource(R.string.ui_schedule_title),
            subtitle = stringResource(R.string.ui_schedule_subtitle),
            onOpenDrawer = onOpenDrawer,
        )
        val week = days ?: return@Column
        DayStrip(days = week, selected = selected, onSelect = { selected = it })
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(260)) { w -> if (forward) w / 6 else -w / 6 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(200)) { w -> if (forward) -w / 6 else w / 6 } + fadeOut(tween(160)))
            },
            label = "schedule-day",
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val day = week.getOrNull(index) ?: return@AnimatedContent
            if (day.slots.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.ui_schedule_empty_title),
                    body = stringResource(R.string.ui_schedule_empty_body),
                    icon = Icons.Rounded.CalendarMonth,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(day.slots, key = { "${it.anime.id}-${it.airAtSec}" }) { slot ->
                        AiringRow(slot = slot, onClick = { onOpenAnime(slot.anime.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DayStrip(days: List<AiringDay>, selected: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { state.animateScrollToItem((selected - 1).coerceAtLeast(0)) }
    val locale = Locale.getDefault()
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(days, key = { _, day -> day.date.toString() }) { index, day ->
            val active = index == selected
            val colors = MaterialTheme.colorScheme
            val container by animateColorAsState(if (active) colors.primary else colors.surfaceContainerHigh, tween(220), label = "day-bg")
            val content by animateColorAsState(if (active) colors.onPrimary else colors.onSurface, tween(220), label = "day-fg")
            Column(
                modifier = Modifier
                    .width(58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(container)
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (index == 0) stringResource(R.string.ui_schedule_today) else day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = content.copy(alpha = 0.8f),
                    maxLines = 1,
                )
                Text(
                    text = day.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                )
                Text(
                    text = day.slots.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = if (day.slots.isEmpty()) 0.4f else 0.7f),
                )
            }
        }
    }
}

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

@Composable
private fun AiringRow(slot: AiringSlot, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val time = Instant.ofEpochSecond(slot.airAtSec).atZone(ZoneId.systemDefault()).format(TimeFormat)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = if (slot.aired) colors.onSurfaceVariant else colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            if (slot.aired) {
                Text(
                    text = stringResource(R.string.ui_schedule_aired),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                )
            }
        }
        Box(
            modifier = Modifier
                .width(44.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(AnimoPalette.posterPlaceholder),
        ) {
            if (slot.anime.poster.isNotBlank()) {
                AsyncImage(
                    model = slot.anime.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = slot.anime.title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            slot.episode?.let {
                Text(
                    text = stringResource(R.string.ui_schedule_episode, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (slot.favorite) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = stringResource(R.string.ui_favorites_title),
                tint = colors.primary,
                modifier = Modifier.padding(start = 8.dp).size(16.dp),
            )
        }
    }
}
