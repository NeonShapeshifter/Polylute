package com.lostf1sh.pixelplayeross.ui.ds

import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lostf1sh.pixelplayeross.R

/**
 * The pinned overlay actions.
 *
 * These are the controls that must stay reachable while the page moves under them: Home's Settings
 * gear, and the Back affordance on a screen whose title is ordinary scroll content. They float over
 * the page rather than reserving a row of it, which is the whole reason they exist — a full app bar
 * for one glyph was costing each of those surfaces a permanent 56 dp row.
 *
 * The glyph stands alone (B33-U3): no box, no tonal container, no hairline. It keeps a full 48 dp
 * touch target and the description on that target. Press is the one state that gets louder (the
 * glyph takes the accent). Over a header image ([onImage]) the glyph is drawn with a thin halo in the
 * page's own ground colour, so it stays visible over a bright picture with only the short top scrim
 * the image already has under it, and still no container.
 */
@Composable
fun DapOverlayAction(
    kind: DapIconKind,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    enabled: Boolean = true,
    onImage: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val accent = LocalDapAccent.current

    val tint = when {
        !enabled -> Dap.disabledText
        pressed -> accent
        onImage -> Dap.primaryText
        else -> Dap.secondaryText
    }

    Box(
        modifier = modifier
            .size(DapSizes.MinTouchTarget)
            .clip(RoundedCornerShape(DapRadius.Surface))
            // The description goes on the touch target, not on the glyph: the target is the node a
            // screen reader focuses, and the glyph itself declares no description of its own.
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (onImage) {
            // Four copies of the glyph one dp away in the page's ground colour: an outline, not a container.
            val halo = Dap.background.copy(alpha = ON_IMAGE_HALO_ALPHA)
            listOf(-ON_IMAGE_HALO_OFFSET to 0.dp, ON_IMAGE_HALO_OFFSET to 0.dp, 0.dp to -ON_IMAGE_HALO_OFFSET, 0.dp to ON_IMAGE_HALO_OFFSET)
                .forEach { (dx, dy) -> DapIcon(kind = kind, tint = halo, size = iconSize, modifier = Modifier.offset(x = dx, y = dy)) }
        }
        DapIcon(kind = kind, tint = tint, size = iconSize)
    }
}

private const val ON_IMAGE_HALO_ALPHA = 0.55f
private val ON_IMAGE_HALO_OFFSET = 1.dp

/**
 * The short scrim under the status bar on a page whose header image runs to the top edge (B33-U3): the one the album and artist headers
 * already carry (dark at 60 % in the dark theme, light at 40 % in the light one, fading out over 80 dp), so the status icons and the Back
 * glyph stay legible without a band over the picture. Place it with `Modifier.align(Alignment.TopCenter)`.
 */
@Composable
fun DapHeroTopScrim(modifier: Modifier = Modifier) {
    val scrim = if (com.lostf1sh.pixelplayeross.ui.theme.LocalPixelPlayerDarkTheme.current) Color.Black.copy(alpha = 0.6f)
    else Color.White.copy(alpha = 0.4f)
    val brush = remember(scrim) { androidx.compose.ui.graphics.Brush.verticalGradient(colors = listOf(scrim, Color.Transparent)) }
    Box(modifier.fillMaxWidth().height(HERO_TOP_SCRIM_HEIGHT).background(brush))
}

private val HERO_TOP_SCRIM_HEIGHT = 80.dp

/**
 * The readout the shell shows when the hardware volume keys are used.
 *
 * A slim vertical pill at the screen edge, beside the physical keys, filling from the bottom — so it
 * never sits over a title. When the signal carries the app's digital level as well (USB Direct,
 * PROCESSED), a thinner accent pill stands beside it: the keys move the wide one, and the thin one
 * shows what is applied in front of it.
 *
 * It renders the volume the *system* is actually at, never a number of its own: the caller passes
 * the authoritative level and range, and the fill, the numerals and the state word are all derived
 * from those. [notice] is the one thing not derivable from the level — "the press you just made was
 * refused" is an event, not a state — so it arrives already decided and only chooses the cue.
 */
@Composable
fun DapVolumeHud(
    label: String,
    level: Int,
    maxLevel: Int,
    notice: DapVolumeNotice,
    modifier: Modifier = Modifier,
    outputName: String? = null,
    /** One short line under the state word: for an override, when it ends. Null shows nothing. */
    noticeDetail: String? = null,
    digitalFraction: Float? = null,
    digitalLabel: String? = null,
    digitalName: String? = null,
    onLevelChange: ((Float) -> Unit)? = null,
    onDigitalChange: ((Float) -> Unit)? = null,
    onTouchActive: (Boolean) -> Unit = {},
) {
    val palette = LocalDapPalette.current
    val accent = LocalDapAccent.current
    val cue = if (notice == DapVolumeNotice.NONE) palette.primaryText
    else noticeCueColor(notice, accent, palette)
    val fraction = if (maxLevel > 0) (level.toFloat() / maxLevel).coerceIn(0f, 1f) else 0f
    val shownFraction by animateFloatAsState(fraction, tween(120), label = "hudLevel")
    val shownDigital by animateFloatAsState(digitalFraction ?: 0f, tween(120), label = "hudDigital")
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(160)) }
    val kind = when {
        level <= 0 -> DapIconKind.VolumeMute
        fraction < 0.5f -> DapIconKind.VolumeLow
        else -> DapIconKind.VolumeHigh
    }
    val split = digitalFraction != null

    Row(
        modifier = modifier.graphicsLayer {
            alpha = appear.value
            translationX = (1f - appear.value) * HUD_SLIDE.toPx()
        },
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .shadow(HUD_ELEVATION, RoundedCornerShape(HUD_LABEL_RADIUS))
                .clip(RoundedCornerShape(HUD_LABEL_RADIUS))
                .background(palette.surfaceRaised)
                .padding(horizontal = DapSpacing.Sm + 2.dp, vertical = DapSpacing.Sm - 2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            HudValue(name = outputName.takeIf { split }, value = label, color = if (notice == DapVolumeNotice.NONE) palette.primaryText else cue)
            if (split && digitalLabel != null) {
                HudValue(name = digitalName, value = digitalLabel, color = accent)
            }
            noticeLabel(notice)?.let {
                Text(it, style = DapType.StateWord, color = cue, maxLines = 1)
            }
            noticeDetail?.takeIf { notice != DapVolumeNotice.NONE }?.let {
                Text(
                    it,
                    style = DapType.Caption,
                    color = palette.secondaryText,
                    textAlign = TextAlign.End,
                    maxLines = 3,
                    modifier = Modifier.widthIn(max = HUD_DETAIL_MAX_WIDTH),
                )
            }
        }
        Spacer(Modifier.width(DapSpacing.Sm))
        if (split) {
            HudPill(
                fraction = shownDigital,
                gestureBase = digitalFraction ?: 0f,
                fill = accent,
                width = HUD_PILL_THIN,
                // A 14 dp pill is too narrow for a finger; the touch area extends away from the DAC
                // pill so the two never compete for the same touch.
                touchWidth = HUD_PILL_THIN_TOUCH,
                onChange = onDigitalChange,
                onTouchActive = onTouchActive,
            )
            Spacer(Modifier.width(6.dp))
        }
        HudPill(
            fraction = shownFraction,
            gestureBase = fraction,
            fill = cue,
            width = HUD_PILL_WIDTH,
            onChange = onLevelChange,
            onTouchActive = onTouchActive,
        ) {
            DapIcon(
                kind = kind,
                // Over the fill the icon takes the fill's contrast colour; above it, the quiet one.
                tint = if (shownFraction > HUD_ICON_COVER) palette.onFill else palette.secondaryText,
                size = 16.dp,
            )
        }
    }
}

@Composable
private fun HudValue(name: String?, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (name != null) {
            Text(name, style = DapType.Caption, color = Dap.secondaryText, maxLines = 1)
            Spacer(Modifier.width(DapSpacing.Sm))
        }
        Text(value, style = DapType.ValueSmall, color = color, maxLines = 1)
    }
}

/**
 * One vertical level. Draggable when [onChange] is set, and relative: touching changes nothing, and
 * only the finger's travel moves the level from where it was ([gestureBase]). Tap-to-set would let a
 * stray touch near the top of a floating readout jump a DAC to full level in one frame.
 * [onTouchActive] brackets the gesture so the host can keep the readout up while a finger is on it.
 */
@Composable
private fun HudPill(
    fraction: Float,
    gestureBase: Float,
    fill: Color,
    width: Dp,
    touchWidth: Dp = width,
    onChange: ((Float) -> Unit)? = null,
    onTouchActive: (Boolean) -> Unit = {},
    icon: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(percent = 50)
    val latestOnChange by rememberUpdatedState(onChange)
    val latestOnTouch by rememberUpdatedState(onTouchActive)
    val latestBase by rememberUpdatedState(gestureBase)
    Box(
        modifier = Modifier
            .width(touchWidth)
            .height(HUD_PILL_HEIGHT)
            .then(
                if (onChange == null) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        latestOnTouch(true)
                        down.consume()
                        val startY = down.position.y
                        val startFraction = latestBase
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val travelled = (startY - change.position.y) / size.height
                            latestOnChange?.invoke((startFraction + travelled).coerceIn(0f, 1f))
                            change.consume()
                        }
                        latestOnTouch(false)
                    }
                }
            ),
        contentAlignment = Alignment.CenterEnd,
    ) {
        PillBody(fraction, fill, width, shape, icon)
    }
}

@Composable
private fun PillBody(
    fraction: Float,
    fill: Color,
    width: Dp,
    shape: RoundedCornerShape,
    icon: (@Composable () -> Unit)?,
) {
    Box(
        modifier = Modifier
            .shadow(HUD_ELEVATION, shape)
            .clip(shape)
            .background(Dap.surfaceRaised)
            .width(width)
            .height(HUD_PILL_HEIGHT),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceIn(0f, 1f))
                .background(fill)
        )
        if (icon != null) {
            Box(Modifier.padding(bottom = DapSpacing.Md)) { icon() }
        }
    }
}

private val HUD_PILL_WIDTH = 40.dp
private val HUD_PILL_THIN = 14.dp
private val HUD_PILL_THIN_TOUCH = 34.dp
private val HUD_PILL_HEIGHT = 168.dp
private val HUD_LABEL_RADIUS = 12.dp
private val HUD_ELEVATION = 6.dp
private val HUD_SLIDE = 16.dp

/** The detail line wraps at this width, so a long sentence grows the label downwards, not across the screen. */
private val HUD_DETAIL_MAX_WIDTH = 150.dp

/** Fill height above which the icon at the pill's foot sits on the fill rather than the track. */
private const val HUD_ICON_COVER = 0.14f

/**
 * How loud the readout is allowed to be.
 *
 * The non-neutral cases are the truthful states of Safety Max: a press that was refused, a volume
 * sitting on the ceiling, an override that has taken the level past it, and the plain top of the
 * output's own range. None of them is an error and none of them interrupts: they change the colour
 * of a bar that was going to be on screen anyway.
 */
enum class DapVolumeNotice {
    /** Ordinary volume change. */
    NONE,

    /** The press was refused by the configured Safety Max. */
    BLOCKED,

    /** Volume is sitting at the configured Safety Max. */
    AT_LIMIT,

    /** A temporary override is active and the level is above the configured Safety Max. */
    OVERRIDE,

    /** The change was refused because the level is already at the top of the output's range. */
    AT_MAXIMUM,
}

@Composable
private fun noticeLabel(notice: DapVolumeNotice): String? = when (notice) {
    DapVolumeNotice.NONE -> null
    DapVolumeNotice.AT_MAXIMUM -> stringResource(R.string.volume_hud_state_maximum)
    DapVolumeNotice.AT_LIMIT -> stringResource(R.string.volume_hud_state_limit)
    DapVolumeNotice.BLOCKED -> stringResource(R.string.volume_hud_state_blocked)
    DapVolumeNotice.OVERRIDE -> stringResource(R.string.volume_hud_state_override)
}

private fun noticeCueColor(
    notice: DapVolumeNotice,
    accent: Color,
    palette: DapPalette,
): Color = when (notice) {
    DapVolumeNotice.NONE -> accent
    DapVolumeNotice.AT_MAXIMUM -> palette.secondaryText
    // Amber already means "over the ceiling" in this palette, so the Safety Max states borrow it
    // rather than inventing a fourth colour for the same idea.
    DapVolumeNotice.AT_LIMIT,
    DapVolumeNotice.BLOCKED,
    DapVolumeNotice.OVERRIDE -> palette.warning
}
