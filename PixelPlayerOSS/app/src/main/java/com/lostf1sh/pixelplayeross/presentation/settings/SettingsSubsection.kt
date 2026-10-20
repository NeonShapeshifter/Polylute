package com.lostf1sh.pixelplayeross.presentation.settings

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapSizes
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType

@Composable
internal fun SettingsSubsectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
internal fun SettingsSubsection(
    title: String,
    addBottomSpace: Boolean = true,
    content: @Composable () -> Unit
) {
    SettingsSubsectionHeader(title)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Transparent),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        content()
    }
    if (addBottomSpace) {
        Spacer(modifier = Modifier.height(10.dp))
    }
}

/**
 * The accent picker: one row of swatches, plus a live preview of what the accent drives.
 *
 * Compact by design — a swatch is a colour, and a colour does not need a card. Each swatch carries
 * its name as a content description, so the choice is never conveyed by colour alone, and the
 * selection is a ring plus a state description rather than a second colour difference.
 *
 * The preview is honest about what the accent actually does: it fills a progress track and marks a
 * selected label, which are two of the states the accent owns. Everything on screen re-themes with
 * it anyway — this just shows the two states that are not on the Settings page itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccentSwatchRow(
    label: String,
    description: String?,
    selected: DapAccent,
    onAccentSelected: (DapAccent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter)) {
        Spacer(Modifier.height(DapSpacing.Md))
        Text(text = label, style = DapType.SettingLabel, color = Dap.primaryText)
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(text = description, style = DapType.Caption, color = Dap.secondaryText)
        }
        Spacer(Modifier.height(DapSpacing.Md))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
            verticalArrangement = Arrangement.spacedBy(DapSpacing.Xs),
        ) {
            DapAccent.entries.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .size(DapSizes.MinTouchTarget)
                        .clip(CircleShape)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onAccentSelected(option) },
                        )
                        .semantics {
                            contentDescription = option.label
                            stateDescription = if (isSelected) "Selected" else "Not selected"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(option.color)
                            .then(
                                if (isSelected) Modifier.border(2.dp, Dap.primaryText, CircleShape)
                                else Modifier
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(DapSpacing.Lg))
        AccentPreview(accent = selected.color)
        Spacer(Modifier.height(DapSpacing.Lg))
        DapDivider()
    }
}

@Composable
private fun AccentPreview(accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md),
    ) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Dap.interactiveTrack)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(3.dp)
                    .background(accent)
            )
        }
        Text(
            text = stringResource(R.string.appearance_preview),
            style = DapType.Caption,
            color = accent,
        )
    }
}
