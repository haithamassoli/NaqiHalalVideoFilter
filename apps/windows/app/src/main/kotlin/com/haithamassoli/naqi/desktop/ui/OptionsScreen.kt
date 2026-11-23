package com.haithamassoli.naqi.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.model.FilterOps
import java.io.File
import kotlin.math.roundToInt

/** Black: the censor bar everyone already recognizes. */
private val DEFAULT_SOLID = FilterOps.SOLID_COLORS[1]

/**
 * Step 2, Android's `OptionsScreen`: tune the selected operations, choose where the result is saved, start.
 * A control shows only while the operation it tunes is on. Body modes are not on Windows yet, so Coverage is
 * Face or Frame.
 */
@Composable
internal fun OptionsScreen(
    input: File,
    output: File,
    ops: FilterOps,
    onOpsChange: (FilterOps) -> Unit,
    onChangeOutput: () -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val s = LocalStrings.current
    val outputIsInput = sameFile(input, output)
    NaqiScreen(
        title = s.optTitle,
        onBack = onBack,
        bottom = { NaqiBottomAction(label = s.actionStart, enabled = !outputIsInput, onClick = onStart) },
    ) {
        if (ops.censorFaces) {
            SectionHeader(s.sectionCensorFaces)
            NaqiCard(contentPadding = 0.dp) {
                Segments(
                    title = s.whoTitle,
                    desc = s.whoDesc,
                    labels = listOf(s.whoEveryone, s.whoWomen, s.whoMen),
                    selected = listOf(FilterOps.EVERYONE, FilterOps.WOMEN, FilterOps.MEN).indexOf(ops.censorWho),
                ) { onOpsChange(ops.copy(censorWho = listOf(FilterOps.EVERYONE, FilterOps.WOMEN, FilterOps.MEN)[it])) }
                NaqiRowDivider()
                Segments(
                    title = s.coverageTitle,
                    desc = if (ops.wholeFrameBlur) s.wholeFrameDesc else null,
                    labels = listOf(s.coverFace, s.coverFrame),
                    selected = if (ops.wholeFrameBlur) 1 else 0,
                ) { onOpsChange(ops.copy(wholeFrameBlur = it == 1)) }
                NaqiRowDivider()
                ToggleTile(
                    title = s.nsfwTitle,
                    desc = s.nsfwDesc,
                    checked = ops.censorNsfw,
                    onCheckedChange = { onOpsChange(ops.copy(censorNsfw = it)) },
                )
                if (ops.censorNsfw) {
                    NaqiRowDivider()
                    SliderRow(s.strictnessTitle, s.strictnessDesc, ops.strictness) { onOpsChange(ops.copy(strictness = it)) }
                }
                NaqiRowDivider()
                CensorStyleRow(ops.solidColor) { onOpsChange(ops.copy(solidColor = it)) }
                // Both only style the blur, and a solid fill has no blur to style.
                if (ops.solidColor == FilterOps.BLUR) {
                    NaqiRowDivider()
                    SliderRow(s.blurAmountTitle, s.blurAmountDesc, ops.blurAmount) { onOpsChange(ops.copy(blurAmount = it)) }
                    NaqiRowDivider()
                    ToggleTile(
                        title = s.grayscaleTitle,
                        desc = s.grayscaleDesc,
                        checked = ops.grayscale,
                        onCheckedChange = { onOpsChange(ops.copy(grayscale = it)) },
                    )
                }
            }
            Spacer(Modifier.height(NaqiTokens.space5))
        }

        if (ops.removeMusic) {
            SectionHeader(s.sectionRemoveMusic)
            NaqiCard(contentPadding = 0.dp) {
                // "vocals" / "vocals_other" are wire values the pipeline reads; never localize them.
                KeepStemsOption(s.keepVocalsTitle, s.keepVocalsDesc, ops.keepStems == "vocals") {
                    onOpsChange(ops.copy(keepStems = "vocals"))
                }
                NaqiRowDivider()
                KeepStemsOption(s.keepVocalsOtherTitle, s.keepVocalsOtherDesc, ops.keepStems == "vocals_other") {
                    onOpsChange(ops.copy(keepStems = "vocals_other"))
                }
            }
            Spacer(Modifier.height(NaqiTokens.space5))
        }

        SectionHeader(s.sectionOutput)
        NaqiCard(contentPadding = 0.dp) {
            Row(
                Modifier.fillMaxWidth().padding(start = NaqiTokens.space4, end = NaqiTokens.space2, top = NaqiTokens.space2, bottom = NaqiTokens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(output.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        output.absoluteFile.parent.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onChangeOutput) { Text(s.outputChange) }
            }
            if (outputIsInput) {
                Text(
                    s.outputIsInput,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = NaqiTokens.space4, end = NaqiTokens.space4, bottom = NaqiTokens.space3),
                )
            }
        }
    }
}

/** A titled single-choice segmented row: who, and coverage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segments(title: String, desc: String?, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Spacer(Modifier.height(NaqiTokens.space3))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = selected == i,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        if (desc != null) {
            Spacer(Modifier.height(NaqiTokens.space3))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/**
 * Blur or a flat fill, and which fill: one decision, so picking a swatch IS choosing Solid.
 * Five fixed swatches (FilterOps.SOLID_COLORS), as on Android.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CensorStyleRow(color: Int, onChange: (Int) -> Unit) {
    val s = LocalStrings.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3)) {
        Text(s.censorStyleTitle, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Text(s.censorStyleDesc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(NaqiTokens.space3))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = color == FilterOps.BLUR,
                onClick = { onChange(FilterOps.BLUR) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(s.styleBlur) }
            SegmentedButton(
                // Keeps the colour last picked; only a first visit needs a default.
                selected = color != FilterOps.BLUR,
                onClick = { if (color == FilterOps.BLUR) onChange(DEFAULT_SOLID) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(s.styleSolid) }
        }
        if (color != FilterOps.BLUR) {
            Spacer(Modifier.height(NaqiTokens.space3))
            Row(horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space3)) {
                FilterOps.SOLID_COLORS.forEachIndexed { i, c ->
                    Swatch(c, selected = c == color, label = s.solidNames[i]) { onChange(c) }
                }
            }
        }
    }
}

/** The ring sits outside the swatch with a gap, so it reads on black and on white alike. */
@Composable
private fun Swatch(color: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) cs.primary else Color.Transparent, CircleShape)
            .selectable(selected = selected, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(4.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(1.dp, cs.outlineVariant, CircleShape),
    )
}

@Composable
private fun SliderRow(title: String, desc: String, value: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, modifier = Modifier.weight(1f))
            Text(
                String.format(LocalStrings.current.locale, "%d", value),
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
                modifier = Modifier
                    .clip(NaqiTokens.shapePill)
                    .background(cs.primary.copy(alpha = 0.12f))
                    .padding(horizontal = NaqiTokens.space3, vertical = 2.dp),
            )
        }
        Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) }, valueRange = 0f..100f)
    }
}

@Composable
private fun KeepStemsOption(title: String, desc: String, selected: Boolean, onSelect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.05f) else cs.surfaceContainer, NaqiTokens.expressiveSpring())
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        SelectDot(selected)
    }
}
