package com.animo.app.ui.browse

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.animo.app.R
import com.animo.app.domain.model.SortOrder

/** Label for the sort dropdown and the sort chip row. */
@StringRes
fun SortOrder.labelRes(): Int = when (this) {
    SortOrder.RECENT -> R.string.browse_sort_recent
    SortOrder.TITLE_ASC -> R.string.browse_sort_title_asc
    SortOrder.TITLE_DESC -> R.string.browse_sort_title_desc
    SortOrder.YEAR_DESC -> R.string.browse_sort_year_desc
    SortOrder.YEAR_ASC -> R.string.browse_sort_year_asc
    SortOrder.SCORE_DESC -> R.string.browse_sort_score
}

/** The three status values the repository normalises to. Unknown values fall back to the raw tag. */
@Composable
fun statusLabel(status: String): String = when (status) {
    "airing" -> stringResource(R.string.browse_status_airing)
    "finished" -> stringResource(R.string.browse_status_finished)
    "upcoming" -> stringResource(R.string.browse_status_upcoming)
    else -> status.replaceFirstChar { it.uppercaseChar() }
}

/** Order is the UI order, not alphabetical — it matches how the chips read top-left to bottom-right. */
val BROWSE_STATUS_VALUES: List<String> = listOf("airing", "finished", "upcoming")
