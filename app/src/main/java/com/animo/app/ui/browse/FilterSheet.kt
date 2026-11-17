package com.animo.app.ui.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.domain.model.FilterFacets
import com.animo.app.domain.model.Filters
import com.animo.app.domain.model.SortOrder
import com.animo.app.ui.theme.AnimoPalette
import com.animo.app.ui.theme.AnimoTheme

private const val MIN_YEAR = 1900
private const val MAX_YEAR = 2100

/**
 * Decade chips map onto the same two bounds the numeric inputs write:
 * `2020s → yearFrom=2020, yearTo=2029`, `90s → 1990..1999`, `Older → yearTo=1989`.
 * Selecting the already-active decade clears both bounds again.
 */
private data class DecadeSpec(@StringRes val labelRes: Int, val from: Int?, val to: Int?)

private val Decades = listOf(
    DecadeSpec(R.string.browse_decade_2020s, 2020, 2029),
    DecadeSpec(R.string.browse_decade_2010s, 2010, 2019),
    DecadeSpec(R.string.browse_decade_2000s, 2000, 2009),
    DecadeSpec(R.string.browse_decade_90s, 1990, 1999),
    DecadeSpec(R.string.browse_decade_older, null, 1989),
)

/**
 * Filter sheet. Every edit is applied live, so the footer count is the real result count and the
 * grid behind the sheet is already what "Show N results" will reveal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    filters: Filters,
    facets: FilterFacets,
    resultCount: Int,
    hasMore: Boolean,
    onFiltersChange: (Filters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AnimoPalette.surface,
    ) {
        FilterSheetBody(
            filters = filters,
            facets = facets,
            resultCount = resultCount,
            hasMore = hasMore,
            onFiltersChange = onFiltersChange,
            onReset = onReset,
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheetBody(
    filters: Filters,
    facets: FilterFacets,
    resultCount: Int,
    hasMore: Boolean,
    onFiltersChange: (Filters) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val letters = facets.letters.ifEmpty { ('A'..'Z').toList() + '#' }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(0.94f)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 0.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.browse_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.browse_sheet_close),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        HorizontalDivider(color = colors.outline.copy(alpha = 0.4f))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SheetSection(title = stringResource(R.string.browse_genre)) {
                if (facets.genres.isEmpty()) {
                    FacetHint()
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        facets.genres.forEach { genre ->
                            FacetChip(
                                label = genre,
                                selected = genre in filters.genres,
                                onClick = {
                                    val next = if (genre in filters.genres) {
                                        filters.genres - genre
                                    } else {
                                        filters.genres + genre
                                    }
                                    onFiltersChange(filters.copy(genres = next))
                                },
                            )
                        }
                    }
                }
            }

            SheetSection(title = stringResource(R.string.browse_year)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Decades.forEach { decade ->
                        val active = filters.yearFrom == decade.from && filters.yearTo == decade.to
                        FacetChip(
                            label = stringResource(decade.labelRes),
                            selected = active,
                            onClick = {
                                onFiltersChange(
                                    if (active) {
                                        filters.copy(yearFrom = null, yearTo = null)
                                    } else {
                                        filters.copy(yearFrom = decade.from, yearTo = decade.to)
                                    },
                                )
                            },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    YearInput(
                        label = stringResource(R.string.browse_year_from),
                        value = filters.yearFrom,
                        onChange = { onFiltersChange(filters.copy(yearFrom = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    YearInput(
                        label = stringResource(R.string.browse_year_to),
                        value = filters.yearTo,
                        onChange = { onFiltersChange(filters.copy(yearTo = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SheetSection(title = stringResource(R.string.browse_type)) {
                if (facets.types.isEmpty()) {
                    FacetHint()
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        facets.types.forEach { type ->
                            FacetChip(
                                label = type,
                                selected = filters.type == type,
                                onClick = {
                                    onFiltersChange(
                                        filters.copy(type = if (filters.type == type) null else type),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            SheetSection(title = stringResource(R.string.browse_status)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BROWSE_STATUS_VALUES.forEach { status ->
                        FacetChip(
                            label = statusLabel(status),
                            selected = filters.status == status,
                            onClick = {
                                onFiltersChange(
                                    filters.copy(status = if (filters.status == status) null else status),
                                )
                            },
                        )
                    }
                }
            }

            SheetSection(title = stringResource(R.string.browse_audio)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.browse_has_dub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = filters.hasDub,
                        onCheckedChange = { onFiltersChange(filters.copy(hasDub = it)) },
                    )
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.4f))

                // Source filter, not an audio-track filter: it narrows the whole catalogue to the
                // Hindi-dub provider (negative-id rows). Sits next to "Has dub" because that is where
                // a dub-shaped filter is looked for, but the body line says what it actually does.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.browse_hindi_only),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.browse_hindi_only_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Switch(
                        checked = filters.hindiOnly,
                        onCheckedChange = { onFiltersChange(filters.copy(hindiOnly = it)) },
                    )
                }
            }

            SheetSection(title = stringResource(R.string.browse_alphabet)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    letters.forEach { letter ->
                        LetterCell(
                            letter = letter,
                            selected = filters.letter == letter,
                            onClick = {
                                onFiltersChange(
                                    filters.copy(letter = if (filters.letter == letter) null else letter),
                                )
                            },
                        )
                    }
                }
            }

            SheetSection(title = stringResource(R.string.browse_sort)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SortOrder.entries.forEach { order ->
                        FacetChip(
                            label = stringResource(order.labelRes()),
                            selected = filters.sort == order,
                            onClick = { onFiltersChange(filters.copy(sort = order)) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
        }

        HorizontalDivider(color = colors.outline.copy(alpha = 0.4f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.browse_reset))
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1.4f),
            ) {
                Text(
                    text = when {
                        resultCount <= 0 -> stringResource(R.string.browse_show_results_none)
                        hasMore -> stringResource(R.string.browse_show_results_more, resultCount)
                        else -> stringResource(R.string.browse_show_results, resultCount)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SheetSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun FacetHint() {
    Text(
        text = stringResource(R.string.browse_facets_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

@Composable
private fun FacetChip(
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
            containerColor = colors.onSurface.copy(alpha = 0.05f),
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary.copy(alpha = 0.16f),
            selectedLabelColor = colors.primary,
        ),
    )
}

@Composable
private fun LetterCell(
    letter: Char,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.18f) else colors.onSurface.copy(alpha = 0.05f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
    }
}

/**
 * Numeric year input. Digits only, clamped to a plausible catalogue window; an empty field means
 * "no bound". The text field keeps its own draft so a half-typed year never rewrites the filter.
 */
@Composable
private fun YearInput(
    label: String,
    value: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }

    // Only re-seeds when the model value changes from outside (decade chip, reset). Typing never
    // changes `value` to a *different* number unless the draft is a complete, valid year.
    LaunchedEffect(value) {
        val external = value?.toString()
        when {
            external != null -> if (external != text) text = external
            text.isNotEmpty() -> text = ""
        }
    }

    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.onSurface.copy(alpha = 0.06f))
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.browse_year_any),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { raw ->
                        val digits = raw.filter { it.isDigit() }.take(4)
                        text = digits
                        onChange(digits.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
            }
            if (text.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable {
                            text = ""
                            onChange(null)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.browse_year_clear),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------- preview

private val PreviewFacets = FilterFacets(
    genres = listOf("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Sci-Fi"),
    years = (2025 downTo 1990).toList(),
    types = listOf("Movie", "ONA", "OVA", "Special", "TV"),
    statuses = listOf("airing", "finished", "upcoming"),
    letters = ('A'..'Z').toList() + '#',
)

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Filter sheet", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 390, heightDp = 900)
@Composable
private fun FilterSheetPreview() {
    AnimoTheme {
        Box(Modifier.fillMaxHeight().background(AnimoPalette.surface)) {
            FilterSheetBody(
                filters = Filters(
                    genres = setOf("Action", "Fantasy"),
                    yearFrom = 2010,
                    yearTo = 2019,
                    type = "TV",
                    letter = 'S',
                    hasDub = true,
                    hindiOnly = true,
                    sort = SortOrder.SCORE_DESC,
                ),
                facets = PreviewFacets,
                resultCount = 42,
                hasMore = true,
                onFiltersChange = {},
                onReset = {},
                onDismiss = {},
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(name = "Filter sheet empty facets", showBackground = true, backgroundColor = 0xFF0D1113, widthDp = 390, heightDp = 900)
@Composable
private fun FilterSheetEmptyPreview() {
    AnimoTheme {
        Box(Modifier.fillMaxHeight().background(AnimoPalette.surface)) {
            FilterSheetBody(
                filters = Filters(),
                facets = FilterFacets(),
                resultCount = 0,
                hasMore = false,
                onFiltersChange = {},
                onReset = {},
                onDismiss = {},
            )
        }
    }
}
