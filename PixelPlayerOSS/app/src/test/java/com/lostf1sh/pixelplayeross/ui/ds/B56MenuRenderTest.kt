package com.lostf1sh.pixelplayeross.ui.ds

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.InfoGlyph
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B56 phase 2 (#42): renders of the overflow menu, today's and the new one side by side, dark and light, on the Library header and on a detail page.
 *
 * A popup lives in a window of its own, which a JVM render does not composite, so both panes draw the menu's container in the page, at the place the popup would take
 * ([DapMenuPlacement] for the new one; flush to the right edge, the Material menu's place, for today's). The new pane draws [DapMenuSurface] and [DapMenuItem], the very
 * composables the popup shows. Today's pane reconstructs the Material `DropdownMenu` content from its public parts (a `Surface` with the extra-small shape, the raised colour
 * and `DropdownMenuItem`), which is what the owner's screenshot (`B52-fix1-device/shot-2.png`) shows: square corners, flat grey, flush to the edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w720dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B56MenuRenderTest {
    @get:Rule val compose = createComposeRule()

    private enum class Scene { LIBRARY, DETAIL }

    private val out: File
        get() = File(System.getenv("B56_RENDERS") ?: "../../../analysis/audit-2026-09-27/batches/B56-gates/renders").apply { mkdirs() }

    @Composable
    private fun Items(scene: Scene, old: Boolean) {
        @Composable fun item(text: String, icon: (@Composable () -> Unit)? = null) {
            if (old) DropdownMenuItem(text = { Text(text) }, leadingIcon = icon, onClick = {})
            else DapMenuItem(text = text, leadingIcon = icon, onClick = {})
        }
        when (scene) {
            Scene.LIBRARY -> {
                item(stringResource(R.string.audio_bookmarks_cd_open))
                item(stringResource(R.string.presentation_batch_d_reorder_tabs_label))
                item(stringResource(R.string.presentation_batch_d_open_settings_cd))
            }
            Scene.DETAIL -> {
                item(stringResource(R.string.music_info_prepare)) { InfoGlyph("download", modifier = Modifier.size(20.dp), tint = Dap.secondaryText) }
                item(stringResource(R.string.music_info_refresh)) { InfoGlyph("retry", modifier = Modifier.size(20.dp), tint = Dap.secondaryText) }
                item(stringResource(R.string.music_info_delete)) { InfoGlyph("delete", modifier = Modifier.size(20.dp), tint = Dap.secondaryText) }
                item(stringResource(R.string.presentation_batch_d_change_photo)) { Icon(Icons.Rounded.AddAPhoto, contentDescription = null) }
                item(stringResource(R.string.presentation_batch_d_reset_to_default)) { Icon(Icons.Rounded.Delete, contentDescription = null) }
            }
        }
    }

    @Composable
    private fun Pane(scene: Scene, old: Boolean, modifier: Modifier) {
        var pane by remember { mutableStateOf(IntSize.Zero) }
        var paneOrigin by remember { mutableStateOf(IntOffset.Zero) }
        var anchor by remember { mutableStateOf<IntRect?>(null) }
        var menu by remember { mutableStateOf(IntSize.Zero) }
        Box(
            modifier
                .background(Dap.background)
                .onSizeChanged { pane = it }
                .onGloballyPositioned { paneOrigin = it.positionInRoot().let { p -> IntOffset(p.x.toInt(), p.y.toInt()) } },
        ) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(28.dp))
                Row(Modifier.padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Xs)) {
                    DapScreenTitle(
                        text = if (scene == Scene.LIBRARY) stringResource(R.string.presentation_batch_d_library_title) else "Mägo de Oz",
                        modifier = Modifier.weight(1f),
                        trailing = {
                            Box(Modifier.onGloballyPositioned { c ->
                                val r = c.boundsInRoot()
                                anchor = IntRect(r.left.toInt() - paneOrigin.x, r.top.toInt() - paneOrigin.y, r.right.toInt() - paneOrigin.x, r.bottom.toInt() - paneOrigin.y)
                            }) { DapOverflowButton(onClick = {}) }
                        },
                    )
                }
                DapDivider()
                for (n in 1..8) {
                    DapMediaRow(title = "Song $n", subtitle = "Mägo de Oz · Gaia", duration = "4:${10 + n}")
                }
            }
            val a = anchor
            if (a != null && pane != IntSize.Zero) {
                val at = if (old) {
                    // the Material menu's place: starts at the button, pushed back to the window's edge: flush right, under the button
                    IntOffset((pane.width - menu.width).coerceAtLeast(0), a.bottom)
                } else {
                    DapMenuPlacement.place(a, pane, menu, with(androidx.compose.ui.platform.LocalDensity.current) { DapMenuDefaults.EdgeMargin.roundToPx() })
                }
                Box(Modifier.offset { at }.onSizeChanged { menu = it }) {
                    if (old) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = Dap.surfaceRaised,
                            tonalElevation = 3.dp,
                            shadowElevation = 3.dp,
                        ) {
                            Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 112.dp).padding(vertical = 8.dp)) { Items(scene, old = true) }
                        }
                    } else {
                        DapMenuSurface { Items(scene, old = false) }
                    }
                }
            }
        }
    }

    private fun render(scene: Scene, dark: Boolean) {
        compose.setContent {
            DapTheme(darkTheme = dark, baseTheme = if (dark) DapBaseTheme.Graphite else DapBaseTheme.Light, accent = DapAccent.Sage) {
                Row(Modifier.fillMaxSize()) {
                    Pane(scene, old = true, modifier = Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Dap.divider))
                    Pane(scene, old = false, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        compose.waitForIdle()
        compose.waitForIdle()
        val bitmap: Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val name = "menu-${scene.name.lowercase()}-${if (dark) "dark" else "light"}-before-after.png"
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `render the Library menu in the dark theme`() = render(Scene.LIBRARY, dark = true)

    @Test fun `render the Library menu in the light theme`() = render(Scene.LIBRARY, dark = false)

    @Test fun `render a detail page menu in the dark theme`() = render(Scene.DETAIL, dark = true)

    @Test fun `render a detail page menu in the light theme`() = render(Scene.DETAIL, dark = false)
}
