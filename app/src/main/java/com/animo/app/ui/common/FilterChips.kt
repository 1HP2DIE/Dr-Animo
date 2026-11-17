package com.animo.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.animo.app.R

/** A single filter facet value. [key] is what the repository sees; [label] is what the user reads. */
data class FilterOption(val key: String, val label: String)

/**
 * One horizontally scrollable chip row for one facet. Options are always derived from the catalog
 * (never hardcoded) — see `SearchViewModel.facets`.
 *
 * Selecting the already-selected chip clears the facet, which is why `onSelect` accepts null.
 */
@Composable
fun FilterChipRow(
    label: String,
    options: List<FilterOption>,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    if (options.isEmpty()) return
    val layoutDirection = LocalLayoutDirection.current
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = contentPadding.calculateStartPadding(layoutDirection),
                bottom = 6.dp,
            ),
        )
        LazyRow(
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "all", contentType = "chip") {
                FilterChipItem(
                    label = stringResource(R.string.ui_filter_all),
                    selected = selectedKey == null,
                    onClick = { onSelect(null) },
                )
            }
            items(
                items = options,
                key = { option -> option.key },
                contentType = { "chip" },
            ) { option ->
                FilterChipItem(
                    label = option.label,
                    selected = option.key == selectedKey,
                    onClick = { onSelect(if (option.key == selectedKey) null else option.key) },
                )
            }
        }
    }
}

@Composable
private fun FilterChipItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(50),
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary.copy(alpha = 0.16f),
            selectedLabelColor = colors.primary,
        ),
    )
}

/** Compact summary of what is currently filtered, shown when the filter panel is collapsed. */
@Composable
fun ActiveFilterSummary(
    labels: List<String>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    if (labels.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items = labels, key = { it }, contentType = { "summary" }) { label ->
            PillBadge(text = label, tone = BadgeTone.Accent)
        }
    }
}
