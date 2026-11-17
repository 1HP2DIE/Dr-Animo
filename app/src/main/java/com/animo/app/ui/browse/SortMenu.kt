package com.animo.app.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.animo.app.R
import com.animo.app.domain.model.SortOrder

/**
 * Compact sort entry point: a chip that reads `Sort: Recent` and opens a single-select menu.
 * The same six orders are also offered as chips inside the filter sheet.
 */
@Composable
fun SortMenu(
    selected: SortOrder,
    onSelect: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Box(modifier) {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    text = stringResource(R.string.browse_sort_label, stringResource(selected.labelRes())),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                labelColor = colors.onSurfaceVariant,
                trailingIconContentColor = colors.onSurfaceVariant,
            ),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(order.labelRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (order == selected) colors.primary else colors.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(order)
                    },
                    leadingIcon = if (order == selected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
