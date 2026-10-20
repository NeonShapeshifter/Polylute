package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight

/**
 * The thanks, one line per project, in the order the page shows them. The README carries the same English text under "Special thanks"
 * (`SpecialThanksTextTest` pins the two to each other).
 */
internal val SpecialThanksProjects: List<Int> = listOf(
    R.string.about_special_thanks_pixelplayer,
    R.string.about_special_thanks_decent_player,
    R.string.about_special_thanks_playback,
    R.string.about_special_thanks_tags,
    R.string.about_special_thanks_fiio,
    R.string.about_special_thanks_music_data,
    R.string.about_special_thanks_interface,
    R.string.about_special_thanks_licenses,
)

/** The personal line. It follows the projects after a larger gap, as a paragraph of its own. */
internal val SpecialThanksPerson: Int = R.string.about_special_thanks_roderick

/**
 * Settings, About, Special thanks: the projects Polylute is built on and the person who got its owner into audio, as text and nothing else.
 * The page has the layout of the Licenses page (back overlay, title, gutter and the player shelf's room at the bottom).
 */
@Composable
fun SpecialThanksScreen(navController: NavController) {
    DapBackScaffold(Modifier.fillMaxSize(), onBack = { navController.popBackStack() }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = DapSpacing.Sm,
                    bottom = LocalDapPlayerShelfHeight.current + DapSpacing.SectionGap,
                    start = DapSpacing.Gutter,
                    end = DapSpacing.Gutter,
                ),
            ) {
                item(key = "special_thanks_title") {
                    DapScreenTitle(text = stringResource(R.string.about_special_thanks_title))
                }

                SpecialThanksProjects.forEachIndexed { index, line ->
                    item(key = "special_thanks_project_$index") {
                        Text(
                            text = stringResource(line),
                            style = DapType.Body,
                            color = Dap.secondaryText,
                            modifier = Modifier.padding(top = if (index == 0) DapSpacing.SectionGap else DapSpacing.Md),
                        )
                    }
                }

                item(key = "special_thanks_person") {
                    Text(
                        text = stringResource(SpecialThanksPerson),
                        style = DapType.Body,
                        color = Dap.secondaryText,
                        modifier = Modifier.padding(top = DapSpacing.GroupGap),
                    )
                }
            }
        }
    }
}
