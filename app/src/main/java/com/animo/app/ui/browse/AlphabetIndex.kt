package com.animo.app.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.animo.app.R

/** A–Z plus `#` for digit-led titles — the same set the repository derives for `FilterFacets.letters`. */
private val RailLetters: List<Char> = ('A'..'Z').toList() + '#'

/**
 * Right-edge alphabet rail. Deliberately a fixed 20dp column *next to* the grid rather than an
 * overlay, so a tap can never land on a poster.
 *
 * Tap toggles (tap the active letter to clear); dragging scrubs without toggling, so sliding past the
 * current letter does not deselect it.
 */
@Composable
fun AlphabetIndex(
    selected: Char?,
    onSelect: (Char?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.browse_alphabet_desc)
    val colors = MaterialTheme.colorScheme
    var railHeight by remember { mutableIntStateOf(0) }

    fun pick(y: Float) {
        if (railHeight <= 0) return
        val index = ((y / railHeight) * RailLetters.size)
            .toInt()
            .coerceIn(0, RailLetters.lastIndex)
        val letter = RailLetters[index]
        if (letter != selected) onSelect(letter)
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .semantics { contentDescription = description }
            .onSizeChanged { railHeight = it.height }
            .pointerInput(railHeight) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> pick(offset.y) },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        pick(change.position.y)
                    },
                )
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RailLetters.forEach { letter ->
            val active = letter == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onSelect(if (active) null else letter)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (active) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .padding(vertical = 2.dp)
                            .fillMaxWidth(0.55f)
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = 0.18f)),
                    )
                }
                Text(
                    text = letter.toString(),
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        }
    }
}
