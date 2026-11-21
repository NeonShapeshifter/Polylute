package com.lostf1sh.pixelplayeross.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.ui.ds.*

/**
 * The Library's per-tab search. Closed: the search glyph. Open: the same flat field as the Search tab (44 dp, Surface radius, panel fill,
 * one hairline), so the two searches read as one control. The cross closes the field (and so clears the query).
 */
@Composable
internal fun LibraryMiniSearch(open: Boolean, query: String, onOpen: () -> Unit, onQuery: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.library_search_tab)
    if (!open) {
        DapIconButton(DapIconKind.Search, label, onOpen, modifier.testTag("library-search-open"))
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(DapRadius.Surface)
    Row(
        modifier
            .dapTextField()
            .height(DapHeights.Control)
            .clip(shape)
            .background(Dap.panel)
            .border(1.dp, Dap.border, shape)
            .padding(start = DapSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DapIcon(kind = DapIconKind.Search, tint = Dap.tertiaryText, size = 16.dp)
        Spacer(Modifier.width(DapSpacing.Sm))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(label, style = DapType.Body, color = Dap.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = DapType.Body.copy(color = Dap.primaryText),
                cursorBrush = SolidColor(LocalDapAccent.current),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = dapDismissKeyboardActions(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label }.testTag("library-search-field"),
            )
        }
        val closeLabel = stringResource(R.string.library_search_close)
        Box(
            Modifier
                .size(DapHeights.Control)
                .clip(shape)
                .clickable(onClick = onClose)
                .semantics { contentDescription = closeLabel }
                .testTag("library-search-close"),
            contentAlignment = Alignment.Center,
        ) {
            // The set has no cross glyph; the Search tab types it the same way.
            Text("×", style = DapType.Body, color = Dap.secondaryText, maxLines = 1)
        }
    }
}

/** The applied per-tab queries, for the tabs inside the Library pager (each reads its own). */
internal val LocalLibraryTabQueries = staticCompositionLocalOf<Map<com.lostf1sh.pixelplayeross.data.model.LibraryTabId, String>> { emptyMap() }

/**
 * B63: a tab searched with nothing to show says so. Before, an empty filtered list fell into the first-load skeleton and stayed there.
 * A paged list may still deliver a match from a later page; it then replaces this.
 */
@Composable
internal fun LibrarySearchNoResults(query: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.SectionGap).testTag("library-search-empty"), contentAlignment = Alignment.TopCenter) {
        Text(stringResource(R.string.library_search_no_results, query.trim()), style = DapType.Body, color = Dap.secondaryText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
