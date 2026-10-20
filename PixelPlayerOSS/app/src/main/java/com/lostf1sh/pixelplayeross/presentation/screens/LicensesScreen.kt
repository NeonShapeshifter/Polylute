package com.lostf1sh.pixelplayeross.presentation.screens

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import com.lostf1sh.pixelplayeross.presentation.model.LicenseAssets
import com.lostf1sh.pixelplayeross.presentation.model.LicenseBlock
import com.lostf1sh.pixelplayeross.presentation.model.licenseBlocks
import com.lostf1sh.pixelplayeross.presentation.model.licenseInlineText
import com.lostf1sh.pixelplayeross.presentation.model.licenseUrlPattern
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.DapGroup
import com.lostf1sh.pixelplayeross.ui.ds.DapScreenTitle
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapPlayerShelfHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lostf1sh.pixelplayeross.presentation.components.DapBackScaffold

/**
 * The open-source notices that ship inside the app.
 *
 * The documents were already bundled under `assets/licenses/` but nothing opened them, so the
 * product carried licence text no user could read. This reads them straight from the APK's assets
 * rather than embedding a second copy that could drift.
 *
 * Polylute's own licence, the GPL, comes first, as an entry of its own; the third-party notices and
 * the font licence follow. The GPL asset is a checked-in copy of the repository's `LICENSE`, pinned
 * equal to it by `LicenseDocumentTest`.
 */
@Composable
fun LicensesScreen(navController: NavController) {
    val context = LocalContext.current

    val gpl by produceState<String?>(initialValue = null, context) {
        value = readAsset(context, LicenseAssets.GPL)
    }
    val thirdParty by produceState<String?>(initialValue = null, context) {
        value = readAsset(context, LicenseAssets.THIRD_PARTY)
    }
    val fonts by produceState<String?>(initialValue = null, context) {
        value = readAsset(context, LicenseAssets.FONTS)
    }

    // Resolved here rather than inside a remember block: stringResource is a composable call.
    val gplTitle = stringResource(R.string.about_licenses_gpl)
    val thirdPartyTitle = stringResource(R.string.about_licenses_third_party)
    val fontsTitle = stringResource(R.string.about_licenses_fonts)
    val sections = remember(gpl, thirdParty, fonts, gplTitle, thirdPartyTitle, fontsTitle) {
        listOfNotNull(
            gpl?.let { gplTitle to licenseBlocks(it, markdown = false) },
            thirdParty?.let { thirdPartyTitle to licenseBlocks(it, markdown = true) },
            fonts?.let { fontsTitle to licenseBlocks(it, markdown = false) },
        )
    }

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
                item(key = "licenses_title") {
                    DapScreenTitle(
                        text = stringResource(R.string.about_licenses_title),
                    )
                }

                if (sections.isEmpty()) {
                    item(key = "licenses_empty") {
                        Text(
                            text = stringResource(R.string.about_licenses_unavailable),
                            style = DapType.Body,
                            color = Dap.tertiaryText,
                            modifier = Modifier.padding(top = DapSpacing.SectionGap),
                        )
                    }
                }

                sections.forEachIndexed { sectionIndex, (title, blocks) ->
                    item(key = "licenses_section_$sectionIndex") {
                        DapGroup(title = title) {}
                    }
                    blocks.forEachIndexed { blockIndex, block ->
                        item(key = "licenses_${sectionIndex}_$blockIndex") {
                            LicenseContent(block)
                        }
                    }
                }
            }
        }
    }
}

/** Assets are small here (tens of KB), so they are read whole rather than streamed. */
private suspend fun readAsset(context: android.content.Context, path: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(path).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

@Composable
private fun LicenseContent(block: LicenseBlock) {
    SelectionContainer {
        when (block) {
            is LicenseBlock.Heading -> Text(
                block.text,
                style = DapType.Heading,
                color = Dap.primaryText,
                modifier = Modifier.padding(vertical = DapSpacing.Md).semantics { heading() },
            )
            is LicenseBlock.Paragraph -> LicenseText(block.text)
            is LicenseBlock.Table -> Column(Modifier.fillMaxWidth()) {
                // Stacked records keep every cell readable at 320 dp and with enlarged text.
                block.rows.forEach { row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = DapSpacing.Md)) {
                        row.forEachIndexed { index, cell ->
                            Text(
                                block.headers[index],
                                style = DapType.CaptionMedium,
                                color = Dap.primaryText,
                                modifier = Modifier.padding(top = DapSpacing.Xs),
                            )
                            LicenseText(cell)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseText(source: String) {
    val text = licenseInlineText(source)
    val linkStyle = TextLinkStyles(style = SpanStyle(
        color = Dap.secondaryText,
        textDecoration = TextDecoration.Underline,
    ))
    val annotated = buildAnnotatedString {
        var offset = 0
        licenseUrlPattern.findAll(text).forEach { match ->
            // Parentheses and sentence punctuation following a URL are prose, not its target.
            val url = match.value.trimEnd('.', ',', ';', ')')
            append(text.substring(offset, match.range.first))
            withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
            offset = match.range.first + url.length
        }
        append(text.substring(offset))
    }
    Text(
        annotated,
        style = DapType.Caption,
        color = Dap.secondaryText,
        modifier = Modifier.fillMaxWidth().padding(vertical = DapSpacing.Sm),
    )
}
