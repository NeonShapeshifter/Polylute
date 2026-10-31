package com.lostf1sh.pixelplayeross.presentation.components.subcomps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.ui.theme.RoundedSans
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private val buttonHeight = 44.dp
private val segmentedOuterCornerRadius = 26.dp
private val segmentedInnerCornerRadius = 8.dp
private val segmentGap = 3.dp


/**
 * Action row displayed during multi-selection mode.
 * Shows segmented Select All/Deselect buttons and Options button.
 * The selection count is shown as a floating pill separately.
 */
@Composable
fun SelectionActionRow(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDeselect: () -> Unit,
    onOptionsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val leftSegmentShape = AbsoluteSmoothCornerShape(
        cornerRadiusTL = segmentedOuterCornerRadius,
        cornerRadiusBL = segmentedOuterCornerRadius,
        cornerRadiusTR = segmentedInnerCornerRadius,
        cornerRadiusBR = segmentedInnerCornerRadius,
        smoothnessAsPercentTL = 60,
        smoothnessAsPercentBL = 60,
        smoothnessAsPercentTR = 60,
        smoothnessAsPercentBR = 60
    )
    
    val rightSegmentShape = AbsoluteSmoothCornerShape(
        cornerRadiusTL = segmentedInnerCornerRadius,
        cornerRadiusBL = segmentedInnerCornerRadius,
        cornerRadiusTR = segmentedOuterCornerRadius,
        cornerRadiusBR = segmentedOuterCornerRadius,
        smoothnessAsPercentTL = 60,
        smoothnessAsPercentBL = 60,
        smoothnessAsPercentTR = 60,
        smoothnessAsPercentBR = 60
    )
    
    val optionsShape = AbsoluteSmoothCornerShape(
        cornerRadiusTL = segmentedOuterCornerRadius,
        cornerRadiusBL = segmentedOuterCornerRadius,
        cornerRadiusTR = segmentedOuterCornerRadius,
        cornerRadiusBR = segmentedOuterCornerRadius,
        smoothnessAsPercentTL = 60,
        smoothnessAsPercentBL = 60,
        smoothnessAsPercentTR = 60,
        smoothnessAsPercentBR = 60
    )
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Absolute.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(segmentGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onSelectAll,
                shape = leftSegmentShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(buttonHeight)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SelectAll,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.presentation_batch_g_selection_all),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    fontFamily = RoundedSans
                )
            }
            
            FilledTonalButton(
                onClick = onDeselect,
                shape = rightSegmentShape,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                ),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(buttonHeight)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Deselect,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.presentation_batch_g_selection_deselect),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    fontFamily = RoundedSans
                )
            }
        }
        
        FilledTonalButton(
            onClick = onOptionsClick,
            shape = optionsShape,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            contentPadding = PaddingValues(horizontal = 2.dp),
            modifier = Modifier.height(buttonHeight).width(36.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.presentation_batch_g_selection_cd_more),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
