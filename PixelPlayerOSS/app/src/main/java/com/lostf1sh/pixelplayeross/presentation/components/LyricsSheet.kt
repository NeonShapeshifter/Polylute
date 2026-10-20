package com.lostf1sh.pixelplayeross.presentation.components

import com.lostf1sh.pixelplayeross.data.preferences.ImmersiveLyrics
import com.lostf1sh.pixelplayeross.data.model.Song
import com.lostf1sh.pixelplayeross.data.model.Lyrics
import com.lostf1sh.pixelplayeross.ui.ds.Dap
import com.lostf1sh.pixelplayeross.ui.ds.DapEmptyState
import com.lostf1sh.pixelplayeross.ui.ds.DapSecondaryButton
import com.lostf1sh.pixelplayeross.ui.ds.DapSpacing
import com.lostf1sh.pixelplayeross.ui.ds.DapTextAction
import com.lostf1sh.pixelplayeross.ui.ds.DapType
import androidx.compose.ui.res.stringResource
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.presentation.components.scoped.LyricsPredictiveBackHandler
import com.lostf1sh.pixelplayeross.presentation.components.scoped.respectSystemBackEdgesForHorizontalDrag
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.lostf1sh.pixelplayeross.data.model.SyncedLine
import com.lostf1sh.pixelplayeross.data.model.SyncedWord
import com.lostf1sh.pixelplayeross.data.repository.LyricsSearchResult
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.FetchLyricsDialog
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.PlayerSeekBar
import com.lostf1sh.pixelplayeross.presentation.viewmodel.DapDeviceControlPolicy
import com.lostf1sh.pixelplayeross.presentation.viewmodel.LyricsSearchUiState
import com.lostf1sh.pixelplayeross.presentation.viewmodel.StablePlayerState
import com.lostf1sh.pixelplayeross.utils.BubblesLine
import com.lostf1sh.pixelplayeross.utils.ProviderText
import com.lostf1sh.pixelplayeross.presentation.components.snapping.ExperimentalSnapperApi
import com.lostf1sh.pixelplayeross.presentation.components.snapping.SnapperLayoutInfo
import com.lostf1sh.pixelplayeross.presentation.components.snapping.rememberLazyListSnapperLayoutInfo
import com.lostf1sh.pixelplayeross.presentation.components.snapping.rememberSnapperFlingBehavior
import com.lostf1sh.pixelplayeross.utils.LyricsUtils
import com.lostf1sh.pixelplayeross.presentation.components.subcomps.LyricsOptionsSheet
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.lostf1sh.pixelplayeross.data.preferences.dataStore

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import com.lostf1sh.pixelplayeross.utils.MultiLangRomanizer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import com.lostf1sh.pixelplayeross.ui.ds.DapIcon
import com.lostf1sh.pixelplayeross.ui.ds.DapTonalRoundButton
import com.lostf1sh.pixelplayeross.ui.ds.LocalDapAccent
import com.lostf1sh.pixelplayeross.ui.ds.DapIconKind
import com.lostf1sh.pixelplayeross.ui.ds.DapRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal data class LyricsSheetColors(
    val container: Color,
    val content: Color,
    val controlContainer: Color,
    val controlContent: Color,
    val accent: Color,
    val accentContent: Color,
    val lyricHighlight: Color,
    val playPauseContainer: Color,
    val playPauseContent: Color,
    val syncButtonContainer: Color,
    val syncButtonContent: Color
)

internal fun lyricsSheetColors(colorScheme: ColorScheme): LyricsSheetColors {
    val container = colorScheme.primaryContainer
    val content = colorScheme.onPrimaryContainer
    val accent = colorScheme.primary
    val accentContent = colorScheme.onPrimary

    return LyricsSheetColors(
        container = container,
        content = content,
        controlContainer = colorScheme.surfaceContainerLowest,
        controlContent = colorScheme.onSurface,
        accent = accent,
        accentContent = accentContent,
        lyricHighlight = preferredContrastColor(
            background = container,
            preferred = accent,
            fallback = content
        ),
        playPauseContainer = colorScheme.tertiaryFixedDim,
        playPauseContent = colorScheme.onTertiaryFixed,
        syncButtonContainer = colorScheme.secondaryFixedDim,
        syncButtonContent = colorScheme.onSecondaryFixed
    )
}

private fun preferredContrastColor(
    background: Color,
    preferred: Color,
    fallback: Color,
    minContrastRatio: Double = 4.5
): Color {
    if (contrastRatio(preferred, background) >= minContrastRatio) return preferred
    if (contrastRatio(fallback, background) >= minContrastRatio) return fallback

    val blackContrast = contrastRatio(Color.Black, background)
    val whiteContrast = contrastRatio(Color.White, background)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun contrastRatio(foreground: Color, background: Color): Double {
    val foregroundLuminance = foreground.relativeLuminance()
    val backgroundLuminance = background.relativeLuminance()
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun Color.relativeLuminance(): Double {
    val argb = encodedSrgbArgb()
    val red = linearizedChannel((argb shr 16) and 0xFF)
    val green = linearizedChannel((argb shr 8) and 0xFF)
    val blue = linearizedChannel(argb and 0xFF)
    return (0.2126 * red) + (0.7152 * green) + (0.0722 * blue)
}

private fun Color.encodedSrgbArgb(): Int = (value shr 32).toInt()

private fun linearizedChannel(channel: Int): Double {
    val value = channel / 255.0
    return if (value <= 0.03928) {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).pow(2.4)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LyricsSheet(
    stablePlayerStateFlow: StateFlow<StablePlayerState>,
    playbackPositionFlow: StateFlow<Long>,
    lyricsSearchUiState: LyricsSearchUiState,
    resetLyricsForCurrentSong: () -> Unit,
    onSearchLyrics: (Boolean) -> Unit,
    onPickResult: (LyricsSearchResult) -> Unit,
    onManualSearch: (String, String?) -> Unit,
    onImportLyrics: () -> Unit,
    onDismissLyricsSearch: () -> Unit,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    lyricsTextStyle: TextStyle,
    colorScheme: ColorScheme,
    onLyricsAlignmentChange: (String) -> Unit,
    onShowLyricsTranslationChange: (Boolean) -> Unit,
    onShowLyricsRomanizationChange: (Boolean) -> Unit,
    onBackClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    immersiveLyricsEnabled: Boolean,
    immersiveLyricsTimeout: Long,
    isImmersiveTemporarilyDisabled: Boolean,
    onSaveLyricsToFile: (Song, Lyrics, Boolean) -> Unit,
    /**
     * Whether online lyrics are enabled in settings. When they are not, the lyrics surface must
     * not offer a search the repository will refuse — being sent into an error by the product's
     * own configuration is a dead end, not a state.
     */
    externalLyricsEnabled: Boolean,
    modifier: Modifier = Modifier,
    swipeThreshold: Dp = 100.dp,
    highlightZoneFraction: Float = 0.08f,
    highlightOffsetDp: Dp = 32.dp,
    autoscrollAnimationSpec: AnimationSpec<Float>? = null
) {
    var backProgress by remember { mutableFloatStateOf(1f) }

    val backProgressProvider = rememberUpdatedState(backProgress)

    LaunchedEffect(Unit) {
        val anim = Animatable(1f)
        anim.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                stiffness = Spring.StiffnessMediumLow,
                dampingRatio = Spring.DampingRatioLowBouncy
            )
        ) { backProgress = value }
    }

    LyricsPredictiveBackHandler(
        enabled = true,
        onProgressChanged = { backProgress = it },
        onBack = onBackClick
    )

    val stablePlayerState by stablePlayerStateFlow.collectAsStateWithLifecycle()
    val sheetColors = remember(colorScheme) { lyricsSheetColors(colorScheme) }
    val backgroundColor = sheetColors.controlContainer
    val onBackgroundColor = sheetColors.controlContent
    val containerColor = sheetColors.container
    val contentColor = sheetColors.content
    val accentColor = sheetColors.accent
    val onAccentColor = sheetColors.accentContent
    val lyricHighlightColor = sheetColors.lyricHighlight
    val playPauseColor = sheetColors.playPauseContainer
    val onPlayPauseColor = sheetColors.playPauseContent

    val isLoadingLyrics by remember(stablePlayerState) { derivedStateOf { stablePlayerState.isLoadingLyrics } }
    val lyrics by remember(stablePlayerState) { derivedStateOf { stablePlayerState.lyrics } }
    val lyricsResolveCompleted by remember(stablePlayerState) {
        derivedStateOf { stablePlayerState.lyricsResolveCompleted }
    }
    val lyricsError by remember(stablePlayerState) { derivedStateOf { stablePlayerState.lyricsError } }
    val isPlaying by remember(stablePlayerState) { derivedStateOf { stablePlayerState.isPlaying } }
    val currentSong by remember(stablePlayerState) { derivedStateOf { stablePlayerState.currentSong } }

    val hasTranslatedLyrics = remember(lyrics) {
        lyrics?.synced?.any { !it.translation.isNullOrBlank() } == true
    }

    val hasRomanizedLyrics = remember(lyrics) {
        val hasSynced = lyrics?.synced?.any { !it.romanization.isNullOrBlank() } == true
        val hasPlain = lyrics?.plain?.any { line ->
            MultiLangRomanizer.isScriptThatNeedsRomanization(line)
        } == true
        hasSynced || hasPlain
    }

    val context = LocalContext.current
    val lyricsAlignmentFlow = remember(context) {
        context.dataStore.data.map { it[stringPreferencesKey("lyrics_alignment")] ?: "left" }
    }
    val lyricsAlignment by lyricsAlignmentFlow.collectAsStateWithLifecycle(initialValue = "left")

    val showLyricsTranslationFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("show_lyrics_translation")] ?: true }
    }
    val showLyricsTranslation by showLyricsTranslationFlow.collectAsStateWithLifecycle(initialValue = true)

    val showLyricsRomanizationFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("show_lyrics_romanization")] ?: true }
    }
    val showLyricsRomanization by showLyricsRomanizationFlow.collectAsStateWithLifecycle(initialValue = true)

    val useAnimatedLyricsFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("use_animated_lyrics")] ?: false }
    }
    val useAnimatedLyrics by useAnimatedLyricsFlow.collectAsStateWithLifecycle(initialValue = false)

    val animatedLyricsBlurEnabledFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("animated_lyrics_blur_enabled")] ?: true }
    }
    val animatedLyricsBlurEnabled by animatedLyricsBlurEnabledFlow.collectAsStateWithLifecycle(initialValue = true)

    val animatedLyricsBlurStrengthFlow = remember(context) {
        context.dataStore.data.map { it[androidx.datastore.preferences.core.floatPreferencesKey("animated_lyrics_blur_strength")] ?: 2.5f }
    }
    val animatedLyricsBlurStrength by animatedLyricsBlurStrengthFlow.collectAsStateWithLifecycle(initialValue = 2.5f)

    val keepScreenOnFlow = remember(context) {
        context.dataStore.data.map { it[booleanPreferencesKey("keep_screen_on_lyrics")] ?: false }
    }
    var keepScreenOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        keepScreenOnFlow.collect { keepScreenOn = it }
    }
    val coroutineScope = rememberCoroutineScope()

    val view = LocalView.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // ONE lifecycle owner for keep-screen-on.
    //
    // This block was written twice, identically. Both copies keyed on the same
    // (keepScreenOn, lifecycleOwner) pair, so they were created and disposed together, and both
    // added a LifecycleObserver to the same owner — two observers firing on every ON_STOP, two
    // `keepScreenOn = false` writes, and two DataStore edits per screen-off, plus two competing
    // `view.keepScreenOn` owners whose onDispose order decided the final value.
    //
    // The ACTION_SCREEN_OFF receiver below is a *different* mechanism and is not part of this
    // duplication: a lifecycle stop and a screen-off broadcast are different events, and the
    // receiver covers the case where the screen turns off while the activity stays resumed.
    //
    // F-05: the toggle holds the screen only while a song is playing. Paused with the sheet open it
    // used to hold the screen on until the sheet was closed (and so kept the phone out of Doze);
    // `isPlaying` is a key so the single owner re-evaluates, and releases, on every play/pause.
    DisposableEffect(keepScreenOn, isPlaying, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && keepScreenOn) {
                keepScreenOn = false
                coroutineScope.launch {
                    context.dataStore.edit { prefs ->
                        prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = false
                    }
                }
            }
        }

        if (DapDeviceControlPolicy.shouldKeepScreenOnForLyrics(keepScreenOn, isPlaying)) {
            view.keepScreenOn = true
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            view.keepScreenOn = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val resolvedAutoscrollSpec = autoscrollAnimationSpec ?: if (useAnimatedLyrics) {
        spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = Spring.DampingRatioLowBouncy
        )
    } else {
        tween(durationMillis = 450, easing = FastOutSlowInEasing)
    }

    var showFetchLyricsDialog by remember { mutableStateOf(false) }
    var showSaveLyricsDialog by remember { mutableStateOf(false) }
    var previewSeekPositionMs by remember(currentSong?.id) { mutableStateOf<Long?>(null) }

    var showSyncedLyrics by remember(lyrics) {
        mutableStateOf(
            when {
                !lyrics?.synced.isNullOrEmpty() -> true
                !lyrics?.plain.isNullOrEmpty() -> false
                else -> null
            }
        )
    }

    val hasSyncedLyrics = remember(lyrics) {
        !lyrics?.synced.isNullOrEmpty()
    }

    var immersiveMode by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showMoreSheet by remember { mutableStateOf(false) }
    val moreSheetState = rememberModalSheetState(
        skipPartiallyExpanded = true
    )

    val hapticFeedback = LocalHapticFeedback.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var isSwipeActive by remember { mutableStateOf(false) }
    var hasTriggeredAction by remember { mutableStateOf(false) }
    val swipeThresholdPx = with(LocalDensity.current) { swipeThreshold.toPx() }
    val overlayTranslation = remember { Animatable(0f) }
    val swipeProgress = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    keepScreenOn = false
                    coroutineScope.launch {
                        context.dataStore.edit { prefs ->
                            prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = false
                        }
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(immersiveLyricsEnabled, lastInteractionTime, showSyncedLyrics, isImmersiveTemporarilyDisabled) {
        // B32-Q7: asked again here, so a state built with "on" (a stored preference read before the gate, a test) cannot start the mode while it is hidden.
        if (ImmersiveLyrics.effective(immersiveLyricsEnabled) && showSyncedLyrics == true && !isImmersiveTemporarilyDisabled) {
            delay(immersiveLyricsTimeout)
            immersiveMode = true
        } else {
            immersiveMode = false
        }
    }

    val fontScale by animateFloatAsState(
        targetValue = if (immersiveMode) 1.4f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "fontScale"
    )
    
    val scaledTextStyle = lyricsTextStyle.copy(
        fontSize = lyricsTextStyle.fontSize * fontScale,
        lineHeight = lyricsTextStyle.lineHeight * fontScale
    )

    fun resetImmersiveTimer() {
        lastInteractionTime = System.currentTimeMillis()
        immersiveMode = false
    }

    // The surface used to force this dialog open the moment a resolve came back empty, which
    // turned "no lyrics for this track" into a modal the user had to dismiss before they could
    // see anything at all — and dismissing it left the surface entirely. Lyrics is a destination:
    // an empty resolve now renders an in-page state, and this dialog is only ever opened by an
    // explicit action from it. Closing the dialog never navigates away on its own.
    LaunchedEffect(lyrics) {
        if (lyrics != null || isLoadingLyrics) {
            showFetchLyricsDialog = false
        }
    }

    if (showFetchLyricsDialog) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes
        ) {
            FetchLyricsDialog(
                uiState = lyricsSearchUiState,
                currentSong = currentSong,
                onConfirm = onSearchLyrics,
                onPickResult = onPickResult,
                onManualSearch = onManualSearch,
                onDismiss = {
                    showFetchLyricsDialog = false
                    onDismissLyricsSearch()
                },
                onImport = onImportLyrics
            )
        }
    }

    if (showSaveLyricsDialog && lyrics != null && currentSong != null) {
        val hasSynced = !lyrics?.synced.isNullOrEmpty()
        val hasPlain = !lyrics?.plain.isNullOrEmpty()
        
        AlertDialog(
            onDismissRequest = { showSaveLyricsDialog = false },
            // Behaviour unchanged: the same two conditional save actions and the same cancel.
            // Only the presentation moves to the graphite palette and the DAP button roles.
            containerColor = Dap.surfaceRaised,
            titleContentColor = Dap.primaryText,
            textContentColor = Dap.secondaryText,
            title = {
                // An alert is a window of its own, so the activity's volume-key path does not reach
                // it. The bridge routes Volume Up/Down to the same authority the activity uses.
                ModalInputBridge()
                Text(
                    text = stringResource(R.string.save_lyrics_dialog_title),
                    style = DapType.Row,
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.save_lyrics_dialog_message),
                        style = DapType.Body,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (hasSynced) {
                        DapSecondaryButton(
                            label = stringResource(R.string.save_synced_lyrics),
                            onClick = {
                                showSaveLyricsDialog = false
                                onSaveLyricsToFile(
                                    currentSong!!,
                                    lyrics!!,
                                    true
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (hasPlain) {
                        DapSecondaryButton(
                            label = stringResource(R.string.save_plain_lyrics),
                            onClick = {
                                showSaveLyricsDialog = false
                                onSaveLyricsToFile(
                                    currentSong!!,
                                    lyrics!!,
                                    false
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                DapTextAction(
                    label = stringResource(R.string.cancel),
                    onClick = { showSaveLyricsDialog = false },
                )
            }
        )
    }

    

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = backProgressProvider.value
                val scale = lerp(1f, 0.92f, p)
                scaleX = scale
                scaleY = scale
                translationY = lerp(0f, size.height * 0.08f, p)
            }
            // An inward drag from the reserved Back edge is the system Back gesture, not a track
            // change. Claiming it here stops `onPrev`/`onNext` firing for the same motion, while
            // centre swipes and vertical lyric scrolling are untouched.
            .respectSystemBackEdgesForHorizontalDrag()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isSwipeActive = true
                        hasTriggeredAction = false
                        dragOffset = 0f
                        resetImmersiveTimer()
                        coroutineScope.launch {
                            swipeProgress.snapTo(0f)
                        }
                    },
                    onDragEnd = {
                        isSwipeActive = false
                        val committed = abs(dragOffset) > swipeThresholdPx && !hasTriggeredAction 
                        
                        if (committed) {
                            if (dragOffset > 0) onPrev() else onNext()
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }

                        coroutineScope.launch {
                             swipeProgress.animateTo(0f, tween(200))
                             dragOffset = 0f
                        }
                    },
                    onDragCancel = {
                        isSwipeActive = false
                        dragOffset = 0f
                        coroutineScope.launch {
                            swipeProgress.animateTo(0f, tween(200))
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        resetImmersiveTimer()
                        
                        if (!hasTriggeredAction) {
                            dragOffset += dragAmount.x
                            val progress = (abs(dragOffset) / swipeThresholdPx).coerceIn(0f, 1f)
                            
                            coroutineScope.launch {
                                swipeProgress.snapTo(progress)
                            }
                        }
                    }
                )
            },
        containerColor = containerColor,
        contentColor = contentColor,
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    resetImmersiveTimer()
                }
        ) {
            val initialSyncedLineIndex = remember(lyrics, playbackPositionFlow, lyricsSyncOffset) {
                resolveCurrentLineIndex(
                    lines = lyrics?.synced.orEmpty(),
                    position = (playbackPositionFlow.value + lyricsSyncOffset).coerceAtLeast(0L)
                ).coerceAtLeast(0)
            }
            val syncedListState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialSyncedLineIndex
            )
            val staticListState = rememberLazyListState()

            Box(
                modifier = Modifier
                    .align(Alignment.Start)
                    .weight(1f)
                    .fillMaxWidth()
            ) {

                when (showSyncedLyrics) {
                    null -> {
                        LyricsStatusPane(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            isLoading = isLoadingLyrics,
                            hasResolved = lyricsResolveCompleted,
                            errorMessage = lyricsError,
                            externalLyricsEnabled = externalLyricsEnabled,
                            currentSong = currentSong,
                            onSearch = {
                                showFetchLyricsDialog = true
                                onSearchLyrics(false)
                            },
                            onImport = onImportLyrics,
                        )
                    }

                    true -> {
                        lyrics?.synced?.let { synced ->
                            val syncedLines = remember(synced) { synced.toImmutableList() }
                            SyncedLyricsList(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 24.dp),
                                contentPadding = PaddingValues(top = DapSpacing.Sm, bottom = 24.dp),
                                lines = syncedLines,
                                listState = syncedListState,
                                playbackPositionFlow = playbackPositionFlow,
                                lyricsSyncOffset = lyricsSyncOffset,
                                positionOverrideMs = previewSeekPositionMs,
                                accentColor = lyricHighlightColor,
                                textStyle = scaledTextStyle,
                                onLineClick = { syncedLine -> 
                                    onSeekTo(
                                        resolveSeekPositionMs(
                                            lineTimeMs = syncedLine.time.toLong(),
                                            lyricsSyncOffsetMs = lyricsSyncOffset
                                        )
                                    )
                                    resetImmersiveTimer()
                                },
                                highlightZoneFraction = highlightZoneFraction,
                                highlightOffsetDp = highlightOffsetDp,
                                autoscrollAnimationSpec = resolvedAutoscrollSpec,
                                useAnimatedLyrics = useAnimatedLyrics,
                                animatedLyricsBlurEnabled = animatedLyricsBlurEnabled,
                                animatedLyricsBlurStrength = animatedLyricsBlurStrength,
                                immersiveMode = immersiveMode,
                                lyricsAlignment = lyricsAlignment,
                                showTranslation = showLyricsTranslation,
                                showRomanization = showLyricsRomanization,
                                footer = {
                                    if (lyrics?.areFromRemote == true) {
                                        item(key = "provider_text") {
                                            ProviderText(
                                                providerText = context.resources.getString(R.string.lyrics_provided_by),
                                                uri = context.resources.getString(R.string.lrclib_uri),
                                                textAlign = TextAlign.Center,
                                                accentColor = lyricHighlightColor,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 16.dp)
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    false -> {
                        lyrics?.plain?.let { plain ->
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = staticListState,
                                contentPadding = PaddingValues(
                                    start = 24.dp,
                                    end = 24.dp,
                                    top = DapSpacing.Sm,
                                    bottom = 24.dp
                                )
                            ) {
                                itemsIndexed(
                                    items = plain,
                                    key = { index, line -> "$index-$line" }
                                ) { _, line ->
                                    PlainLyricsLine(
                                        line = line,
                                        style = lyricsTextStyle,
                                        lyricsAlignment = lyricsAlignment,
                                        showTranslation = if (hasTranslatedLyrics) showLyricsTranslation else true,
                                        showRomanization = if (hasRomanizedLyrics) showLyricsRomanization else true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            }
                        }
                    }
                }
                
            }

            AnimatedVisibility(
                visible = !immersiveMode,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(containerColor)
                        .padding(
                            top = DapSpacing.Sm,
                            bottom = paddingValues.calculateBottomPadding() + DapSpacing.Sm,
                            start = DapSpacing.Gutter,
                            end = DapSpacing.Gutter,
                        )
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.pressed }) {
                                        resetImmersiveTimer()
                                    }
                                }
                            }
                        }
                ) {
                    // One row (B22): play/pause as a tonal round button, the timeline in a pill, and the options as a
                    // tonal round button. The same tonal disc the queue and the library use.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)
                    ) {
                        val pauseLabel = stringResource(R.string.cd_pause)
                        val playLabel = stringResource(R.string.cd_play)
                        DapTonalRoundButton(
                            contentDescription = if (isPlaying) pauseLabel else playLabel,
                            onClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPlayPause()
                            },
                        ) { tint ->
                            DapIcon(
                                kind = if (isPlaying) DapIconKind.Pause else DapIconKind.Play,
                                tint = tint,
                                size = 20.dp,
                            )
                        }

                        LyricsPlaybackSeekBar(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            playbackPositionFlow = playbackPositionFlow,
                            accentColor = LocalDapAccent.current,
                            totalDuration = stablePlayerState.totalDuration,
                            onSeekTo = onSeekTo,
                            onSeekPreviewChange = { previewSeekPositionMs = it },
                            isPlaying = isPlaying
                        )

                        DapTonalRoundButton(
                            contentDescription = stringResource(R.string.lyrics_options),
                            onClick = { showMoreSheet = true },
                        ) { tint ->
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }

        if (showMoreSheet) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes
            ) {
                LyricsOptionsSheet(
                    onDismissRequest = { showMoreSheet = false },
                    sheetState = moreSheetState,
                    // The sync row is for synced lyrics only.
                    syncOffsetMs = if (showSyncedLyrics == true && lyrics?.synced != null) lyricsSyncOffset else null,
                    onSyncOffsetChange = {
                        resetImmersiveTimer()
                        onLyricsSyncOffsetChange(it)
                    },
                    lyricsAlignment = lyricsAlignment,
                    onLyricsAlignmentChange = onLyricsAlignmentChange,
                    hasTranslatedLyrics = hasTranslatedLyrics,
                    showTranslation = showLyricsTranslation,
                    onShowTranslationChange = { enabled ->
                        resetImmersiveTimer()
                        onShowLyricsTranslationChange(enabled)
                    },
                    hasRomanizedLyrics = hasRomanizedLyrics,
                    showRomanization = showLyricsRomanization,
                    onShowRomanizationChange = { enabled ->
                        resetImmersiveTimer()
                        onShowLyricsRomanizationChange(enabled)
                    },
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOnChange = { enabled ->
                        keepScreenOn = enabled
                        coroutineScope.launch {
                            context.dataStore.edit { prefs ->
                                prefs[booleanPreferencesKey("keep_screen_on_lyrics")] = enabled
                            }
                        }
                    },
                    canSearch = externalLyricsEnabled,
                    onSearchLyrics = {
                        showMoreSheet = false
                        showFetchLyricsDialog = true
                        onSearchLyrics(false)
                    },
                    onImportLyrics = {
                        showMoreSheet = false
                        onImportLyrics()
                    },
                    canSave = lyrics != null && currentSong != null,
                    onSaveLyrics = {
                        showMoreSheet = false
                        showSaveLyricsDialog = true
                    },
                    canReset = lyrics != null,
                    onResetLyrics = resetLyricsForCurrentSong,
                )
            }
        }

       AnimatedVisibility(
            visible = immersiveMode,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            // A squared accent control rather than a filled circular Material button; same action,
            // same size, same content description.
            val showControlsLabel = stringResource(R.string.cd_show_controls)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(DapRadius.Surface))
                    .background(accentColor)
                    .clickable(onClick = { resetImmersiveTimer() })
                    .semantics {
                        contentDescription = showControlsLabel
                    },
                contentAlignment = Alignment.Center
            ) {
                DapIcon(
                    kind = DapIconKind.ChevronUp,
                    tint = onAccentColor,
                    size = 18.dp,
                )
            }
        }
       
       if (isSwipeActive || swipeProgress.value > 0f) {
           val isNext = dragOffset < 0
           val overlayAlignment = if (isNext) Alignment.CenterEnd else Alignment.CenterStart
           val icon = if (isNext) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious
           
           Box(
               modifier = Modifier
                   .align(overlayAlignment)
                   .size(100.dp)
                   .padding(
                       start = if(isNext) 0.dp else 6.dp,
                       end = if(isNext) 6.dp else 0.dp
                   )
                   .graphicsLayer {
                        val widthPx = size.width
                        val initialOffset = if(isNext) widthPx else -widthPx
                        translationX = initialOffset * (1f - swipeProgress.value)

                        scaleX = 0.8f + (swipeProgress.value * 0.2f)
                        scaleY = 0.8f + (swipeProgress.value * 0.2f)
                   }
                   .background(
                        color = accentColor,
                        shape = RoundedCornerShape(
                            topStart = if(isNext) 360.dp else 8.dp,
                            bottomStart = if(isNext) 360.dp else 8.dp,
                            topEnd = if(isNext) 8.dp else 360.dp,
                            bottomEnd = if(isNext) 8.dp else 360.dp
                        )
                   ),
               contentAlignment = Alignment.Center
           ) {
               Icon(
                   imageVector = icon,
                   contentDescription = null,
                   modifier = Modifier.size(48.dp),
                   tint = onAccentColor
               )
           }
       }

      }
    }
}

@Composable
private fun LyricsPlaybackSeekBar(
    playbackPositionFlow: StateFlow<Long>,
    accentColor: Color,
    totalDuration: Long,
    onSeekTo: (Long) -> Unit,
    onSeekPreviewChange: (Long?) -> Unit,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val playbackPosition by playbackPositionFlow.collectAsStateWithLifecycle()

    PlayerSeekBar(
        backgroundColor = Dap.surface,
        onBackgroundColor = Dap.secondaryText,
        primaryColor = accentColor,
        currentPosition = playbackPosition,
        totalDuration = totalDuration,
        onSeek = onSeekTo,
        onSeekPreview = onSeekPreviewChange,
        isPlaying = isPlaying,
        modifier = modifier
    )
}

@OptIn(ExperimentalSnapperApi::class)
@Composable
fun SyncedLyricsList(
    lines: ImmutableList<SyncedLine>,
    listState: LazyListState,
    playbackPositionFlow: StateFlow<Long>,
    lyricsSyncOffset: Int,
    positionOverrideMs: Long? = null,
    accentColor: Color,
    textStyle: TextStyle,
    onLineClick: (SyncedLine) -> Unit,
    highlightZoneFraction: Float,
    highlightOffsetDp: Dp,
    autoscrollAnimationSpec: AnimationSpec<Float>,
    useAnimatedLyrics: Boolean = false,
    animatedLyricsBlurEnabled: Boolean = true,
    animatedLyricsBlurStrength: Float = 2.5f,
    immersiveMode: Boolean = false,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    footer: LazyListScope.() -> Unit = {}
) {
    val density = LocalDensity.current
    val playbackPosition by playbackPositionFlow.collectAsStateWithLifecycle()
    val position = remember(playbackPosition, lyricsSyncOffset, positionOverrideMs) {
        positionOverrideMs ?: (playbackPosition + lyricsSyncOffset).coerceAtLeast(0L)
    }
    val isPreviewSeeking = positionOverrideMs != null
    // The lines read the position through this, inside their derivedStateOf (AUD-113).
    val positionState = rememberUpdatedState(position)
    val positionProvider = remember { { positionState.value } }
    val currentLineIndex by remember(position, lines) {
        derivedStateOf {
            resolveCurrentLineIndex(lines = lines, position = position)
        }
    }
    var hasAlignedInitialLine by remember(lines) { mutableStateOf(false) }
    var lastAutoScrolledLineIndex by remember(lines) { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier = modifier) {
        val metrics = remember(maxHeight, highlightZoneFraction, highlightOffsetDp) {
            calculateHighlightMetrics(maxHeight, highlightZoneFraction, highlightOffsetDp)
        }
        val highlightOffsetPx = remember(highlightOffsetDp, density) { with(density) { highlightOffsetDp.toPx() } }

        val snapperLayoutInfo = rememberLazyListSnapperLayoutInfo(
            lazyListState = listState,
            snapOffsetForItem = { layoutInfo, item ->
                val viewportHeight = layoutInfo.endScrollOffset - layoutInfo.startScrollOffset
                highlightSnapOffsetPx(viewportHeight, item.size, highlightOffsetPx)
            }
        )
        val flingBehavior = rememberSnapperFlingBehavior(layoutInfo = snapperLayoutInfo)

        LaunchedEffect(currentLineIndex, lines.size, metrics, isPreviewSeeking) {
            if (lines.isEmpty()) return@LaunchedEffect
            if (currentLineIndex !in lines.indices) return@LaunchedEffect
            if (listState.layoutInfo.totalItemsCount == 0) return@LaunchedEffect

            if (!hasAlignedInitialLine) {
                listState.scrollToItem(currentLineIndex)
                snapToSnapIndex(
                    listState = listState,
                    layoutInfo = snapperLayoutInfo,
                    targetIndex = currentLineIndex
                )
                hasAlignedInitialLine = true
                lastAutoScrolledLineIndex = currentLineIndex
                return@LaunchedEffect
            }

            if (listState.isScrollInProgress && !isPreviewSeeking) return@LaunchedEffect

            val lineJumpDistance = if (lastAutoScrolledLineIndex >= 0) {
                abs(currentLineIndex - lastAutoScrolledLineIndex)
            } else {
                0
            }

            if (isPreviewSeeking) {
                if (lineJumpDistance > 2) {
                    listState.scrollToItem(currentLineIndex)
                    snapToSnapIndex(
                        listState = listState,
                        layoutInfo = snapperLayoutInfo,
                        targetIndex = currentLineIndex
                    )
                } else {
                    animateToSnapIndex(
                        listState = listState,
                        layoutInfo = snapperLayoutInfo,
                        targetIndex = currentLineIndex,
                        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing)
                    )
                }
                lastAutoScrolledLineIndex = currentLineIndex
                return@LaunchedEffect
            }

            val dynamicAnimationSpec = if (useAnimatedLyrics) {
                val currentLineTime = lines.getOrNull(currentLineIndex)?.time ?: 0
                val nextLineTime = lines.getOrNull(currentLineIndex + 1)?.time ?: (currentLineTime + 1000)
                val timeDiff = (nextLineTime - currentLineTime).coerceIn(250, 2000)
                
                tween<Float>(
                    durationMillis = timeDiff,
                    easing = FastOutSlowInEasing
                )
            } else {
                autoscrollAnimationSpec
            }

            animateToSnapIndex(
                listState = listState,
                layoutInfo = snapperLayoutInfo,
                targetIndex = currentLineIndex,
                animationSpec = dynamicAnimationSpec
            )
            lastAutoScrolledLineIndex = currentLineIndex
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                flingBehavior = flingBehavior,
                contentPadding = contentPadding
            ) {
                itemsIndexed(
                    items = lines,
                    key = { index, item -> "${item.time}_$index" }
                ) { index, line ->
                    val nextTime = lines.getOrNull(index + 1)?.time ?: Int.MAX_VALUE
                    val distanceFromCurrent = if (currentLineIndex != -1) abs(currentLineIndex - index) else 100
                    
                    val parallaxModifier = if (useAnimatedLyrics) {
                        Modifier.graphicsLayer {
                            val currentLayoutInfo = listState.layoutInfo
                            val lineItemInfo = currentLayoutInfo.visibleItemsInfo.find { it.index == index }
                            val itemCenter = lineItemInfo?.let { it.offset + (it.size / 2f) }
                            val viewportCenter = currentLayoutInfo.viewportEndOffset / 2f
                            
                            val distanceFromCenter = itemCenter?.let { it - viewportCenter } ?: 0f
                            
                            val maxTranslation = 40f 
                            val distanceRatio = (distanceFromCenter / viewportCenter).coerceIn(-1f, 1f)
                            translationY = distanceRatio * distanceRatio * distanceRatio * maxTranslation 
                        }
                    } else Modifier

                    if (line.line.isNotBlank()) {
                        LyricLineRow(
                            line = line,
                            nextTime = nextTime,
                            positionProvider = positionProvider,
                            distanceFromCurrent = distanceFromCurrent,
                            useAnimatedLyrics = useAnimatedLyrics,
                            animatedLyricsBlurEnabled = animatedLyricsBlurEnabled,
                            animatedLyricsBlurStrength = animatedLyricsBlurStrength,
                            immersiveMode = immersiveMode,
                            lyricsAlignment = lyricsAlignment,
                            showTranslation = showTranslation,
                            showRomanization = showRomanization,
                            accentColor = accentColor,
                            style = textStyle,
                            modifier = parallaxModifier
                                .fillMaxWidth()
                                .testTag("synced_line_${line.time}"),
                            onClick = { onLineClick(line) }
                        )
                    } else {
                        BubblesLine(
                            positionFlow = playbackPositionFlow,
                            time = line.time,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                            nextTime = nextTime,
                            modifier = parallaxModifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                }
                footer()
            }

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LyricLineRow(
    line: SyncedLine,
    nextTime: Int,
    /** Read inside derivedStateOf so a progress tick does not rebuild the line's state (AUD-113). */
    positionProvider: () -> Long,
    distanceFromCurrent: Int = 100,
    useAnimatedLyrics: Boolean = false,
    animatedLyricsBlurEnabled: Boolean = true,
    animatedLyricsBlurStrength: Float = 2.5f,
    immersiveMode: Boolean = false,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    accentColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val sanitizedLine = remember(line.line) { sanitizeLyricLineText(line.line) }
    val sanitizedWords = remember(line.words) {
        line.words?.let(::sanitizeSyncedWords)
    }
    val sanitizedWordClusters = remember(sanitizedWords) {
        sanitizedWords?.takeIf { it.isNotEmpty() }?.let(::clusterSyncedWords)
    }
    val lineEndTime = remember(line, nextTime) {
        resolveLineEndTimeMs(line, nextTime)
    }
    val isCurrentLine by remember(line.time, lineEndTime) {
        derivedStateOf { positionProvider() in line.time.toLong()..<lineEndTime }
    }
    val unhighlightedColor = LocalContentColor.current.copy(alpha = 0.45f)
    val lineColor by animateColorAsState(
        targetValue = if (isCurrentLine) accentColor else unhighlightedColor,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessVeryLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ) else tween(durationMillis = 250),
        label = "lineColor"
    )

    val targetScale = if (useAnimatedLyrics) when (distanceFromCurrent) {
        0 -> if (immersiveMode) 1.02f else 1.1f; 1 -> 0.95f; else -> 0.85f
    } else 1f
    val targetPadding = if (useAnimatedLyrics) when (distanceFromCurrent) {
        0 -> 32.dp; 1 -> 16.dp; else -> 8.dp
    } else 12.dp
    val targetAlpha = if (useAnimatedLyrics) when (distanceFromCurrent) {
        0 -> 1.0f; 1 -> 0.6f; else -> 0.3f
    } else 1f

    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessVeryLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ) else tween(durationMillis = 200),
        label = "lineScale"
    )
    val verticalPadding by animateDpAsState(
        targetValue = targetPadding,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessVeryLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ) else tween(durationMillis = 200),
        label = "linePadding"
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioNoBouncy
        ) else tween(durationMillis = 200),
        label = "lineAlpha"
    )

    val targetBlur = if (useAnimatedLyrics && animatedLyricsBlurEnabled && distanceFromCurrent > 0) {
        (distanceFromCurrent * animatedLyricsBlurStrength).coerceAtMost(10f).dp
    } else 0.dp

    val blurRadius by animateDpAsState(
        targetValue = targetBlur,
        animationSpec = if (useAnimatedLyrics) tween(durationMillis = 400) else tween(durationMillis = 200),
        label = "lineBlur"
    )

    val baseModifier = if (useAnimatedLyrics && !immersiveMode) {
        when (lyricsAlignment) {
            "center" -> modifier.padding(horizontal = 36.dp)
            "right" -> modifier.padding(start = 36.dp)
            else -> modifier.padding(end = 36.dp)
        }
    } else {
        modifier
    }
    val animatedModifier = if (useAnimatedLyrics) {
        baseModifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                transformOrigin = TransformOrigin(
                    pivotFractionX = when (lyricsAlignment) {
                        "center" -> 0.5f
                        "right" -> 1f
                        else -> 0f
                    },
                    pivotFractionY = 0.5f
                )
            }
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
    } else baseModifier

    val translationText = line.translation
    val romanizationText = line.romanization

    val secondaryStyle = remember(style) {
        style.copy(
            fontSize = (style.fontSize.value * 0.75f).sp,
            fontWeight = FontWeight.Normal
        )
    }

    val romanizationColor = lineColor.copy(alpha = lineColor.alpha * 0.85f)
    val translationColor = lineColor.copy(alpha = lineColor.alpha * 0.55f)

    val horizontalAlignment = when (lyricsAlignment) {
        "center" -> Alignment.CenterHorizontally
        "right" -> Alignment.End
        else -> Alignment.Start
    }

    val textAlign = when (lyricsAlignment) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.Right
        else -> TextAlign.Left
    }

    val boxAlignment = when (lyricsAlignment) {
        "center" -> Alignment.TopCenter
        "right" -> Alignment.TopEnd
        else -> Alignment.TopStart
    }

    if (sanitizedWordClusters.isNullOrEmpty()) {
        Column(
            modifier = animatedModifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(vertical = verticalPadding, horizontal = 2.dp),
            horizontalAlignment = horizontalAlignment
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = boxAlignment) {
                Text(
                    text = sanitizedLine,
                    style = style,
                    color = Color.Transparent,
                    fontWeight = FontWeight.Bold,
                    textAlign = textAlign
                )
                Text(
                    text = sanitizedLine,
                    style = style,
                    color = lineColor,
                    fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                    textAlign = textAlign
                )
            }

            if (showRomanization && !romanizationText.isNullOrBlank()) {
                Text(
                    text = romanizationText,
                    style = secondaryStyle,
                    color = romanizationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && !translationText.isNullOrBlank()) {
                Text(
                    text = translationText,
                    style = secondaryStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    } else {
        val highlightedWordIndex by remember(sanitizedWords, line.time, lineEndTime) {
            derivedStateOf {
                resolveHighlightedWordIndex(
                    words = requireNotNull(sanitizedWords),
                    positionMs = positionProvider(),
                    lineStartTimeMs = line.time.toLong(),
                    lineEndTimeMs = lineEndTime
                )
            }
        }

        Column(
            modifier = animatedModifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(vertical = verticalPadding, horizontal = 2.dp),
            horizontalAlignment = horizontalAlignment
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = when (lyricsAlignment) {
                    "center" -> Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
                    "right" -> Arrangement.spacedBy(3.dp, Alignment.End)
                    else -> Arrangement.spacedBy(3.dp, Alignment.Start)
                },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                sanitizedWordClusters.forEach { cluster ->
                    cluster.words.forEachIndexed { clusterOffset, word ->
                        val wordIndex = cluster.startIndex + clusterOffset
                        key("${line.time}_${word.time}_${word.word}_$wordIndex") {
                            LyricWordSpan(
                                word = word,
                                isHighlighted = isCurrentLine && wordIndex == highlightedWordIndex,
                                useAnimatedLyrics = useAnimatedLyrics,
                                style = style,
                                highlightedColor = accentColor,
                                unhighlightedColor = unhighlightedColor
                            )
                        }
                    }
                }
            }

            if (showRomanization && !romanizationText.isNullOrBlank()) {
                Text(
                    text = romanizationText,
                    style = secondaryStyle,
                    color = romanizationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && !translationText.isNullOrBlank()) {
                Text(
                    text = translationText,
                    style = secondaryStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
fun LyricWordSpan(
    word: SyncedWord,
    isHighlighted: Boolean,
    useAnimatedLyrics: Boolean = false,
    style: TextStyle,
    highlightedColor: Color,
    unhighlightedColor: Color,
    modifier: Modifier = Modifier
) {
    val wordAnimSpec = if (useAnimatedLyrics) spring<Float>(
        stiffness = Spring.StiffnessVeryLow,
        dampingRatio = Spring.DampingRatioMediumBouncy
    ) else tween(durationMillis = 200)

    val color by animateColorAsState(
        targetValue = if (isHighlighted) highlightedColor else unhighlightedColor,
        animationSpec = if (useAnimatedLyrics) spring(
            stiffness = Spring.StiffnessVeryLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ) else tween(durationMillis = 200),
        label = "wordColor"
    )

    val scale by animateFloatAsState(
        targetValue = if (useAnimatedLyrics && isHighlighted) 1.10f else 1f,
        animationSpec = wordAnimSpec,
        label = "wordScale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (useAnimatedLyrics && !isHighlighted) 0.55f else 1f,
        animationSpec = wordAnimSpec,
        label = "wordAlpha"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = word.word,
            style = style,
            color = Color.Transparent,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = word.word,
            style = style,
            color = color,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
        )
    }
}

@Composable
fun PlainLyricsLine(
    line: String,
    style: TextStyle,
    lyricsAlignment: String = "left",
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    modifier: Modifier = Modifier
) {
    val sanitizedLines = remember(line) { line.split("\n") }
    val primaryText = remember(sanitizedLines) { if (sanitizedLines.isNotEmpty()) sanitizeLyricLineText(sanitizedLines[0]) else "" }

    val isRomanizedScript = remember(primaryText) {
        MultiLangRomanizer.isScriptThatNeedsRomanization(primaryText)
    }

    val translationText = remember(sanitizedLines, primaryText, isRomanizedScript) {
        if (sanitizedLines.size > 1) {
            val firstExtra = sanitizedLines[1]
            val rest = if (sanitizedLines.size > 2) sanitizedLines.drop(2).joinToString("\n") { sanitizeLyricLineText(it) } else ""
            
            val isLatin = firstExtra.any { it.code in 32..126 } 
            val isFirstRomanization = isRomanizedScript && isLatin

            if (isFirstRomanization) rest else sanitizedLines.drop(1).joinToString("\n") { sanitizeLyricLineText(it) }
        } else ""
    }

    val romanizationText = remember(sanitizedLines, primaryText, isRomanizedScript) {
         if (sanitizedLines.size > 1) {
            val firstExtra = sanitizedLines[1]
            val isLatin = firstExtra.any { it.code in 32..126 } 
            val isFirstRomanization = isRomanizedScript && isLatin
            
            if (isFirstRomanization) sanitizeLyricLineText(firstExtra) else ""
        } else ""
    }
    val textAlign = when (lyricsAlignment) {
        "center" -> TextAlign.Center
        "right" -> TextAlign.Right
        else -> TextAlign.Left
    }

    val horizontalAlignment = when (lyricsAlignment) {
        "center" -> Alignment.CenterHorizontally
        "right" -> Alignment.End
        else -> Alignment.Start
    }

    val translationStyle = remember(style) {
        style.copy(
            fontSize = (style.fontSize.value * 0.75f).sp,
            fontWeight = FontWeight.Normal
        )
    }
    val translationColor = LocalContentColor.current.copy(alpha = 0.45f)

    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        if (primaryText.isNotBlank()) {
            Text(text = primaryText, style = style, color = LocalContentColor.current.copy(alpha = 0.7f), textAlign = textAlign)

            if (showRomanization && romanizationText.isNotBlank()) {
                Text(
                    text = romanizationText,
                    style = translationStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (showTranslation && translationText.isNotBlank()) {
                Text(
                    text = translationText,
                    style = translationStyle,
                    color = translationColor,
                    textAlign = textAlign,
                    modifier = Modifier.padding(top = if (showRomanization && romanizationText.isNotBlank()) 2.dp else 4.dp)
                )
            }
        }
    }
}

private val LeadingTagRegex = Regex("^v\\d+:\\s*", RegexOption.IGNORE_CASE)

internal fun sanitizeLyricLineText(raw: String): String =
    LyricsUtils.stripLrcTimestamps(raw).replace(LeadingTagRegex, "").trimStart()

internal fun sanitizeSyncedWords(words: List<SyncedWord>): List<SyncedWord> =
    buildList {
        words.forEachIndexed { index, word ->
            val sanitized = if (index == 0) LeadingTagRegex.replace(word.word, "") else word.word
            val normalized = sanitized.trim()
            if (normalized.isEmpty()) return@forEachIndexed

            add(
                word.copy(
                    word = normalized,
                    startsNewWord = if (isEmpty()) true else word.startsNewWord
                )
            )
        }
    }

internal data class SyncedWordCluster(
    val startIndex: Int,
    val words: List<SyncedWord>
)

internal fun clusterSyncedWords(words: List<SyncedWord>): List<SyncedWordCluster> {
    if (words.isEmpty()) return emptyList()

    val clusters = mutableListOf<SyncedWordCluster>()
    var currentWords = mutableListOf<SyncedWord>()
    var currentStartIndex = 0

    words.forEachIndexed { index, word ->
        if (word.startsNewWord && currentWords.isNotEmpty()) {
            clusters += SyncedWordCluster(startIndex = currentStartIndex, words = currentWords.toList())
            currentWords = mutableListOf()
            currentStartIndex = index
        } else if (currentWords.isEmpty()) {
            currentStartIndex = index
        }

        currentWords += word
    }

    if (currentWords.isNotEmpty()) {
        clusters += SyncedWordCluster(startIndex = currentStartIndex, words = currentWords.toList())
    }

    return clusters
}

internal fun normalizeWordEndTime(
    currentWordTimeMs: Long,
    nextWordTimeMs: Long,
    lineEndTimeMs: Long
): Long {
    val minEnd = currentWordTimeMs + 1L
    val boundedLineEnd = lineEndTimeMs.coerceAtLeast(minEnd)
    return nextWordTimeMs.coerceIn(minEnd, boundedLineEnd)
}

internal fun resolveLineEndTimeMs(line: SyncedLine, nextLineStartMs: Int): Long {
    val baseEnd = nextLineStartMs.toLong()
    val lastWordStart = line.words?.maxOfOrNull { it.time.toLong() } ?: line.time.toLong()
    return maxOf(baseEnd, lastWordStart + 1L)
}

internal fun resolveHighlightedWordIndex(
    words: List<SyncedWord>,
    positionMs: Long,
    lineStartTimeMs: Long,
    lineEndTimeMs: Long
): Int {
    if (positionMs < lineStartTimeMs || positionMs >= lineEndTimeMs) return -1
    return words.indexOfLast { it.time.toLong() <= positionMs }
}

/**
 * Everything the lyrics surface shows when there is no lyric text to show.
 *
 * Exhaustive by construction: it branches on [isLoading] first and then on what the resolve
 * actually returned, so there is no combination of state that renders nothing. That was the
 * defect this replaces — `lyrics == null && !isLoading && !hasResolved` matched no branch and
 * produced a silent blank pane.
 *
 * It also owns the honesty rule for online search: when online lyrics are disabled in Settings
 * the action is not offered at all, because offering an action the repository will refuse sends
 * the user into an error the product created for itself.
 */
@Composable
private fun LyricsStatusPane(
    isLoading: Boolean,
    hasResolved: Boolean,
    errorMessage: String?,
    externalLyricsEnabled: Boolean,
    currentSong: Song?,
    onSearch: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            isLoading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.loading_lyrics),
                        style = DapType.Row,
                        color = Dap.secondaryText,
                    )
                    Spacer(Modifier.height(DapSpacing.Md))
                    DapIndeterminateBar(modifier = Modifier.width(120.dp))
                }
            }

            // A failure is its own state. It says something different from "there are no lyrics",
            // and collapsing the two would tell the user their file has no lyrics when in fact
            // the lookup broke.
            errorMessage != null -> {
                DapEmptyState(
                    title = stringResource(R.string.dap_lyrics_error_title),
                    message = errorMessage.ifBlank { stringResource(R.string.failed_to_fetch_lyrics_from_remote) },
                    action = {
                        LyricsEmptyActions(
                            externalLyricsEnabled = externalLyricsEnabled,
                            onSearch = onSearch,
                            onImport = onImport,
                        )
                    },
                )
            }

            // Not loading, nothing found. Covers both "resolved and empty" and the belt-and-braces
            // case of a state where the resolve has not been marked complete — neither may be blank.
            else -> {
                DapEmptyState(
                    title = stringResource(R.string.dap_lyrics_empty_title),
                    message = buildString {
                        append(stringResource(R.string.dap_lyrics_empty_message))
                        if (!externalLyricsEnabled) {
                            append("\n\n")
                            append(stringResource(R.string.dap_lyrics_online_disabled))
                        }
                    },
                    action = {
                        LyricsEmptyActions(
                            externalLyricsEnabled = externalLyricsEnabled,
                            onSearch = onSearch,
                            onImport = onImport,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LyricsEmptyActions(
    externalLyricsEnabled: Boolean,
    onSearch: () -> Unit,
    onImport: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(DapSpacing.Md)) {
        if (externalLyricsEnabled) {
            DapSecondaryButton(
                label = stringResource(R.string.dap_lyrics_search_online),
                onClick = onSearch,
            )
        }
        DapTextAction(
            label = stringResource(R.string.import_file),
            onClick = onImport,
        )
    }
}

/**
 * An indeterminate progress bar in the design system's own vocabulary: a hairline track with a
 * travelling segment. The Material wavy indicator it replaces belonged to a different product.
 */
@Composable
private fun DapIndeterminateBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "lyricsLoading")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lyricsLoadingProgress",
    )

    BoxWithConstraints(
        modifier = modifier
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Dap.surfaceRaised),
    ) {
        val trackWidth = maxWidth
        val segmentWidth = trackWidth * 0.35f
        Box(
            modifier = Modifier
                .offset(x = (trackWidth - segmentWidth) * progress)
                .width(segmentWidth)
                .fillMaxHeight()
                .background(Dap.tertiaryText),
        )
    }
}

internal fun resolveSeekPositionMs(
    lineTimeMs: Long,
    lyricsSyncOffsetMs: Int
): Long = (lineTimeMs - lyricsSyncOffsetMs.toLong()).coerceAtLeast(0L)

internal data class HighlightZoneMetrics(
    val topPadding: Dp,
    val bottomPadding: Dp,
    val zoneHeight: Dp,
    val centerFromTop: Dp
)

internal fun calculateHighlightMetrics(
    containerHeight: Dp,
    highlightZoneFraction: Float,
    highlightOffset: Dp
): HighlightZoneMetrics {
    val container = containerHeight.value
    val zoneHeight = (containerHeight * highlightZoneFraction).value.coerceAtLeast(0f)
    val offset = highlightOffset.value
    val minCenter = zoneHeight / 2f
    val maxCenter = (container - zoneHeight / 2f).coerceAtLeast(minCenter)
    val unclampedCenter = container / 2f - offset
    val center = unclampedCenter.coerceIn(minCenter, maxCenter)
    val topPadding = (center - zoneHeight / 2f).coerceAtLeast(0f)
    val bottomPadding = (container - center - zoneHeight / 2f).coerceAtLeast(0f)

    return HighlightZoneMetrics(
        topPadding = topPadding.dp,
        bottomPadding = bottomPadding.dp,
        zoneHeight = zoneHeight.dp,
        centerFromTop = center.dp
    )
}

internal fun highlightSnapOffsetPx(
    viewportHeight: Int,
    itemSize: Int,
    highlightOffsetPx: Float
): Int {
    if (viewportHeight <= 0 || itemSize <= 0) return 0
    if (itemSize >= viewportHeight) return 0
    val viewport = viewportHeight.toFloat()
    val halfItem = itemSize / 2f
    val targetCenter = (viewport / 2f) - highlightOffsetPx
    val clampedCenter = targetCenter.coerceIn(halfItem, viewport - halfItem)
    return (clampedCenter - halfItem).roundToInt()
}

internal suspend fun animateToSnapIndex(
    listState: LazyListState,
    layoutInfo: SnapperLayoutInfo,
    targetIndex: Int,
    animationSpec: AnimationSpec<Float>
) {
    val distance = layoutInfo.distanceToIndexSnap(targetIndex)
    if (distance == 0) return

    listState.scroll {
        var previous = 0f
        AnimationState(initialValue = 0f).animateTo(
            targetValue = distance.toFloat(),
            animationSpec = animationSpec
        ) {
            val delta = value - previous
            val consumed = scrollBy(delta)
            previous = value
            if (abs(delta - consumed) > 0.5f) cancelAnimation()
        }
    }
}

internal suspend fun snapToSnapIndex(
    listState: LazyListState,
    layoutInfo: SnapperLayoutInfo,
    targetIndex: Int
) {
    val distance = layoutInfo.distanceToIndexSnap(targetIndex)
    if (distance == 0) return

    listState.scroll {
        scrollBy(distance.toFloat())
    }
}

internal fun resolveCurrentLineIndex(
    lines: List<SyncedLine>,
    position: Long
): Int {
    if (lines.isEmpty()) return -1

    return lines.withIndex().lastOrNull { (index, line) ->
        val nextTime = lines.getOrNull(index + 1)?.time ?: Int.MAX_VALUE
        val lineEndTime = resolveLineEndTimeMs(line, nextTime)
        position in line.time.toLong()..<lineEndTime
    }?.index ?: -1
}
