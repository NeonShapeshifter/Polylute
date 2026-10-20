package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.presentation.model.SettingsCategory
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.settings.SettingsSurfacePolicy
import kotlinx.coroutines.delay
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DeviceControlViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapContentWidth
import com.lostf1sh.pixelplayeross.ui.ds.DapDivider
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.ui.ds.DapScreen
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSettingRow
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing

/**
 * Settings: a page of labelled groups of rows.
 *
 * The rows are the page. There are no cards, no coloured icon containers and no filled pill;
 * the hairlines carry the structure, and every row that navigates says where it goes with a
 * trailing value and a chevron. The categories and their destinations are exactly what they were.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SettingsScreen(
        navController: NavController,
        onNavigationIconClick: () -> Unit,
        deviceControlViewModel: DeviceControlViewModel = hiltViewModel(),
) {
    val deviceControlState by deviceControlViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        deviceControlViewModel.refresh()
    }

    val context = LocalContext.current

    DapBackScaffold(Modifier.fillMaxSize()) {
        DapScreen(Modifier.fillMaxSize()) {
            DapContentWidth(Modifier.fillMaxSize()) {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            top = DapSpacing.Md,
                            start = DapSpacing.Gutter,
                            end = DapSpacing.Gutter,
                            bottom = LocalDapPlayerShelfHeight.current + DapSpacing.SectionGap,
                        ),
                        verticalArrangement = Arrangement.spacedBy(DapSpacing.SectionGap),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item {
                            // The title scrolls in the viewport below the fixed navigation row.
                            DapScreenTitle(
                                text = stringResource(R.string.settings_top_bar_title),
                                modifier = Modifier.padding(
                                    start = 0.dp,
                                    end = 0.dp,
                                ),
                            )
                        }

                        item {
                            // No DapGroup title here: DapQuickDeviceControls already opens with
                            // its own "Controles del dispositivo" group header, so wrapping it in
                            // another one printed the heading twice, stacked.
                            Column {
                                // TODO(dap): DapQuickDeviceControls lives in another file and is
                                // still a Material card; its label helpers are private there, so
                                // it is called as-is until that file is converted.
                                DapQuickDeviceControls(
                                    state = deviceControlState,
                                    onBrightnessChanged = { percent ->
                                        applyDapWindowBrightness(context, percent)
                                        deviceControlViewModel.setBrightnessPercent(percent)
                                    },
                                    onVolumeChanged = deviceControlViewModel::setMediaVolume,
                                    onDigitalGainChanged = deviceControlViewModel::setDigitalGainPercent,
                                    // F-05: the "never" notice leads to the page that owns the timeout.
                                    onReviewScreenTimeout = {
                                        navController.navigateSafely(
                                            Screen.SettingsCategory.createRoute(SettingsCategory.DEVICE.id)
                                        )
                                    },
                                )
                            }
                        }

                        // The root is live controls followed by the categories, and nothing else.
                        //
                        // The DAC and headphone groups used to sit here as well, which made the
                        // root a second entrance to Audio's own subject: the same two profiles
                        // were reachable from here and from the category that owns them, and
                        // "which of these is the real one" had no answer. Profile management is
                        // Audio's, so it is in Audio.
                        item {
                            DapGroup(title = stringResource(R.string.settings_top_bar_title)) {
                                SettingsSurfacePolicy.visibleCategories()
                                    .filter { it != SettingsCategory.ABOUT }
                                    .forEach { category ->
                                        SettingsRow(
                                            label = stringResource(category.titleRes),
                                            description = stringResource(category.subtitleRes),
                                            onClick = {
                                                navController.navigateSafely(
                                                    Screen.SettingsCategory.createRoute(
                                                        category.id
                                                    )
                                                )
                                            },
                                        )
                                    }
                                SettingsRow(
                                    label = stringResource(SettingsCategory.ABOUT.titleRes),
                                    description = stringResource(SettingsCategory.ABOUT.subtitleRes),
                                    onClick = {
                                        navController.navigateSafely(Screen.About.route)
                                    },
                                )
                            }
                        }
                    }
            }

        }

        var isTransitioning by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            delay(com.lostf1sh.pixelplayeross.presentation.navigation.TRANSITION_DURATION.toLong())
            isTransitioning = false
        }

        if (isTransitioning) {
            Box(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                     awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                        }
                    }
                }
            )
        }
    }
}

/**
 * One settings row, on its hairline.
 *
 * [description] and [value] are different slots and are used for different things: a category's
 * subtitle is prose and goes in [description] (Manrope), while a measured or observed state — a
 * profile's status word, a percentage, a route — goes in [value] (mono). Passing prose as [value]
 * is what put the category subtitles in JetBrains Mono.
 */
@Composable
private fun SettingsRow(
    label: String,
    value: String? = null,
    description: String? = null,
    valueColor: Color = Dap.secondaryText,
    dim: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    DapSettingRow(
        label = label,
        value = value,
        description = description,
        valueColor = valueColor,
        dim = dim,
        onClick = onClick,
        showChevron = onClick != null,
    )
    DapDivider()
}
