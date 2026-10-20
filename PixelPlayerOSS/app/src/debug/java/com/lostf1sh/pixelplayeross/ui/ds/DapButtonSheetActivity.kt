package com.lostf1sh.pixelplayeross.ui.ds

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Debug-only reference sheet for the button set: every variant and state side by side, rendered
 * on the device with the real theme. Not shipped (src/debug), and guarded by DUMP in the manifest
 * so only the shell can open it:
 *
 *     adb shell am start -n com.lostf1sh.pixelplayeross.debug/com.lostf1sh.pixelplayeross.ui.ds.DapButtonSheetActivity --es page proposal
 *     adb shell am start -n …/…DapButtonSheetActivity --es page today
 *
 * The accent is read from `--es accent <storageKey>` so the sheet matches the device's own.
 */
class DapButtonSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark bars regardless of the system setting: the sheet is always drawn on graphite.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val page = intent.getStringExtra("page") ?: "proposal"
        val accent = DapAccent.fromStorageKey(intent.getStringExtra("accent"))
        setContent {
            DapTheme(darkTheme = true, accent = accent) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Dap.background)
                        .safeDrawingPadding()
                ) {
                    when (page) {
                        "today" -> TodaySheet()
                        else -> ProposalSheet()
                    }
                }
            }
        }
    }
}

// --- Proposal ------------------------------------------------------------------------------------

@Composable
private fun ProposalSheet() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DapSpacing.Gutter, vertical = DapSpacing.Md),
    ) {
        Text("Botones · propuesta", style = DapType.Heading, color = Dap.primaryText)
        Spacer(Modifier.height(DapSpacing.Xs))
        Text(
            "Contorno fino = área táctil (mín. 48 dp). Presión: capa tonal, DapMotion.micro 100/90 ms, sin ripple.",
            style = DapType.Note,
            color = Dap.tertiaryText,
        )
        Spacer(Modifier.height(DapSpacing.Md))
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(0.dp))
            listOf("Normal", "Presionado", "Deshabilitado").forEach {
                Text(
                    it,
                    style = DapType.StateWord,
                    color = Dap.tertiaryText,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        VariantBlock(
            name = "Primario",
            spec = "44 en 48 · r 4 · primaryText / onFill · uno por pantalla",
        ) { pressed, enabled ->
            DapButtonImpl("Guardar", {}, Modifier, DapButtonVariant.Primary, enabled, null, pressed)
        }
        VariantBlock(
            name = "Secundario",
            spec = "44 en 48 · r 4 · surfaceRaised / primaryText",
        ) { pressed, enabled ->
            DapButtonImpl("Cancelar", {}, Modifier, DapButtonVariant.Secondary, enabled, null, pressed)
        }
        VariantBlock(
            name = "Texto",
            spec = "48 · sin fondo · secondaryText · Action 14/500",
        ) { pressed, enabled ->
            DapButtonImpl("Editar", {}, Modifier, DapButtonVariant.Text, enabled, null, pressed)
        }
        VariantBlock(
            name = "Destructivo",
            spec = "44 en 48 · r 4 · surfaceRaised / warning",
        ) { pressed, enabled ->
            DapButtonImpl("Borrar", {}, Modifier, DapButtonVariant.Destructive, enabled, null, pressed)
        }
        VariantBlock(
            name = "Icono",
            spec = "48 × 48 · glifo 20 · secondaryText",
        ) { pressed, enabled ->
            DapIconButtonImpl(DapIconKind.More, "Más", {}, Modifier, enabled, false, pressed)
        }
        VariantBlock(
            name = "Icono activado",
            spec = "igual · acento = aplicado (aleatorio, repetir, favorito)",
        ) { pressed, enabled ->
            DapIconButtonImpl(DapIconKind.Shuffle, "Aleatorio", {}, Modifier, enabled, true, pressed)
        }
        VariantBlock(
            name = "Disco de play (solo reproductor)",
            spec = "72 · círculo · primaryText / onFill · presión = escala 0,96",
        ) { pressed, enabled ->
            DapPlayDiscImpl("Reproducir", {}, Modifier, enabled, pressed) { tint ->
                DapIcon(DapIconKind.Play, tint = tint, size = 18.dp)
            }
        }
        Spacer(Modifier.height(DapSpacing.Md))
        Text("Con icono delante (primario y secundario):", style = DapType.Note, color = Dap.tertiaryText)
        Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Sm)) {
            TouchOutline { DapButtonImpl("Aleatorio", {}, Modifier, DapButtonVariant.Primary, true, DapIconKind.Shuffle, false) }
            TouchOutline { DapButtonImpl("Cola", {}, Modifier, DapButtonVariant.Secondary, true, DapIconKind.List, false) }
        }
    }
}

@Composable
private fun VariantBlock(
    name: String,
    spec: String,
    content: @Composable (pressed: Boolean, enabled: Boolean) -> Unit,
) {
    Spacer(Modifier.height(DapSpacing.Md))
    Text(name, style = DapType.RowSmall, color = Dap.primaryText)
    Text(spec, style = DapType.ValueSmall, color = Dap.tertiaryText)
    Spacer(Modifier.height(DapSpacing.Xs))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { TouchOutline { content(false, true) } }
        Box(Modifier.weight(1f)) { TouchOutline { content(true, true) } }
        Box(Modifier.weight(1f)) { TouchOutline { content(false, false) } }
    }
}

/** Draws the touch target's bounds, so the 48 dp minimum is visible rather than claimed. */
@Composable
private fun TouchOutline(content: @Composable () -> Unit) {
    Box(Modifier.border(BorderStroke(1.dp, Dap.dividerStrong))) { content() }
}

// --- Today ---------------------------------------------------------------------------------------

@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
private fun TodaySheet() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DapSpacing.Md, vertical = DapSpacing.Md),
    ) {
        Text("Botones · hoy", style = DapType.Heading, color = Dap.primaryText)
        Text(
            "Cada estilo tal como se dibuja con el tema real. Número = llamadas en el código.",
            style = DapType.Note,
            color = Dap.tertiaryText,
        )
        Group("Material 3") {
            Cell("Button · 28") { Button(onClick = {}) { Text("Guardar") } }
            Cell("FilledTonalButton · 36") { FilledTonalButton(onClick = {}) { Text("Guardar") } }
            Cell("FilledTonal redondo · 11") {
                FilledTonalButton(onClick = {}, shape = CircleShape) { Text("OK") }
            }
            Cell("OutlinedButton · 1") { OutlinedButton(onClick = {}) { Text("Guardar") } }
            Cell("TextButton · 69") { TextButton(onClick = {}) { Text("Guardar") } }
            Cell("IconButton · 23") {
                IconButton(onClick = {}) { Icon(Icons.Rounded.MoreVert, contentDescription = null) }
            }
            Cell("FilledIconButton · 19") {
                FilledIconButton(onClick = {}) { Icon(Icons.Rounded.Shuffle, contentDescription = null) }
            }
            Cell("FilledTonalIconButton · 11") {
                FilledTonalIconButton(onClick = {}) { Icon(Icons.Rounded.Shuffle, contentDescription = null) }
            }
            Cell("MediumFAB · 1") {
                MediumFloatingActionButton(onClick = {}) { Icon(Icons.Rounded.Add, contentDescription = null) }
            }
            Cell("ToggleButton · 2") {
                ToggleButton(checked = true, onCheckedChange = {}) { Text("Activo") }
            }
            Cell("FilterChip · 3") {
                FilterChip(selected = true, onClick = {}, label = { Text("Álbum") })
            }
            Cell("InputChip · 2") {
                InputChip(selected = false, onClick = {}, label = { Text(";") })
            }
            Cell("SegmentedButton · 2") {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = true,
                        onClick = {},
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("A") }
                    SegmentedButton(
                        selected = false,
                        onClick = {},
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("B") }
                }
            }
        }
        Group("Design system hoy") {
            Cell("DapPrimaryButton · 15") { DapPrimaryButton("Guardar", {}) }
            Cell("DapSecondaryButton · 20") { DapSecondaryButton("Cancelar", {}) }
            Cell("DapTextAction · 37") { DapTextAction("Editar", {}) }
            Cell("DapDestructiveAction · 5") { DapDestructiveAction("Borrar", {}) }
            Cell("DapOverflow/Back · 12") {
                Row {
                    DapBackButton({})
                    DapOverflowButton({})
                }
            }
            Cell("DapOverlayAction/Back · 27") {
                DapOverlayAction(DapIconKind.Chevron, "Atrás", {})
            }
            Cell("DapRoundPlayAction · 6") { DapRoundPlayAction({}) }
            Cell("DapCollectionActions · 3") { DapCollectionActions({}, {}, "Reproducir", "Aleatorio") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(DapSpacing.Md))
    Text(title.uppercase(), style = DapType.SectionHeader, color = Dap.tertiaryText)
    Spacer(Modifier.height(DapSpacing.Sm))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(DapSpacing.Sm),
    ) { content() }
}

@Composable
private fun Cell(label: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(162.dp)
            .border(BorderStroke(1.dp, Dap.divider))
            .padding(DapSpacing.Sm),
    ) {
        Box(Modifier.height(64.dp).fillMaxWidth(), contentAlignment = Alignment.CenterStart) { content() }
        Text(label, style = DapType.ValueSmall, color = Dap.secondaryText, maxLines = 2)
    }
}
