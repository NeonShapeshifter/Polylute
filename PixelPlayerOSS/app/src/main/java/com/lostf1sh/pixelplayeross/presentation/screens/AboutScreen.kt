package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.navigation.Screen
import com.lostf1sh.pixelplayeross.presentation.navigation.navigateSafely
import com.lostf1sh.pixelplayeross.presentation.viewmodel.PlayerViewModel
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapNavRow
import com.lostf1sh.pixelplayeross.ui.ds.DapScreen
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTopBar
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold

@androidx.annotation.OptIn(UnstableApi::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun AboutScreen(
    navController: NavController,
    viewModel: PlayerViewModel,
    onNavigationIconClick: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "N/A"
        }.getOrDefault("N/A")
    }

    DapBackScaffold(Modifier.fillMaxSize(), onBack = onNavigationIconClick) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = LocalDapPlayerShelfHeight.current + 24.dp,
                    start = DapSpacing.Gutter,
                    end = DapSpacing.Gutter,
                ),
            ) {
                item(key = "about_title") {
                    DapScreenTitle(
                        text = stringResource(R.string.screen_about),
                    )
                }

                // Identity and version in one group. (B32-Q6: the version used to carry a hidden seven-tap entry to the PIN screen of
                // Maintenance. There is no PIN now, and Maintenance is a row under Settings, System.)
                item(key = "about_product") {
                    Column(Modifier.fillMaxWidth().padding(top = DapSpacing.SectionGap)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.about_app_name),
                                style = DapType.HeadingSmall,
                                color = Dap.primaryText,
                            )
                            Spacer(Modifier.width(DapSpacing.Md))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                Text(
                                    text = stringResource(R.string.about_version_format, versionName),
                                    style = DapType.Value,
                                    color = Dap.tertiaryText,
                                )
                            }
                        }
                        Spacer(Modifier.height(DapSpacing.Sm))
                        Text(
                            text = stringResource(R.string.about_tagline),
                            style = DapType.Body,
                            color = Dap.secondaryText,
                        )
                    }
                }

                item(key = "about_attribution") {
                    DapGroup(title = stringResource(R.string.about_acknowledgements)) {
                        DapNavRow(
                            label = stringResource(R.string.about_special_thanks_title),
                            description = stringResource(R.string.about_special_thanks_subtitle),
                            divider = true,
                            onClick = { navController.navigateSafely(Screen.SpecialThanks.route) },
                        )
                        DapNavRow(
                            label = stringResource(R.string.about_licenses_title),
                            description = stringResource(R.string.about_licenses_subtitle),
                            divider = false,
                            onClick = { navController.navigateSafely(Screen.Licenses.route) },
                        )
                    }
                }

                item { Spacer(Modifier.height(DapSpacing.SectionGap)) }
            }
        }
        // The bar held one Back chevron and nothing else. The overlay is that chevron, floating
        // over the page instead of reserving a row for it — and unlike the bar it is the only
        // affordance that honours the Back mode, so Gestures mode stops drawing one at all.
    }
}
