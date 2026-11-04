package com.lostf1sh.pixelplayeross.presentation

import androidx.compose.runtime.staticCompositionLocalOf

/** The product's effective level, supplied by the root; Device Owner alone is not DAP mode. */
val LocalDapMode = staticCompositionLocalOf { false }
