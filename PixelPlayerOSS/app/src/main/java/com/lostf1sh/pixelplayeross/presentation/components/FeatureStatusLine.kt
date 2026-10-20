package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatus
import com.lostf1sh.pixelplayeross.presentation.viewmodel.FeatureStatusKind
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapStatusLine
import com.lostf1sh.pixelplayeross.ui.ds.DapStatusTone
import com.lostf1sh.pixelplayeross.ui.ds.DapType

/**
 * Draws a [FeatureStatus]: the one status line of the Audio page and the equalizer.
 *
 * The lock's own name is a parameter of the "frozen" word (`Congelado · %1$s`), taken from `pristine_lock_name`, so the
 * rebrand still edits one string.
 */
@Composable
fun FeatureStatusLine(status: FeatureStatus, modifier: Modifier = Modifier) {
    DapStatusLine(
        text = stringResource(status.textRes, stringResource(R.string.pristine_lock_name)),
        tone = when (status.kind) {
            FeatureStatusKind.APPLIED -> DapStatusTone.Applied
            FeatureStatusKind.OFF -> DapStatusTone.Plain
            FeatureStatusKind.FROZEN,
            FeatureStatusKind.BYPASSED,
            FeatureStatusKind.OTHER_PROFILE -> DapStatusTone.Muted
        },
        routeTag = if (status.androidRoute) stringResource(R.string.audio_status_android_route) else null,
        modifier = modifier,
    )
}

/** A sentence about the Android route, with the route glyph in front: "Distinta de 1,0× sale por la ruta Android". */
@Composable
fun AndroidRouteHintLine(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Rounded.AltRoute,
            contentDescription = null,
            tint = Dap.tertiaryText,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(DapSpacing.Sm - 2.dp))
        Text(
            text = text,
            style = DapType.Caption,
            color = Dap.tertiaryText,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
