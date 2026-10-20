package com.lostf1sh.pixelplayeross.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The locale this composition is rendered in.
 *
 * `Locale.getDefault()` is a global that composition cannot observe, so text derived from it (an upper-cased
 * label) would keep the old locale's casing rules until something else forced a recomposition (lint
 * NonObservableLocale, B14-4). `LocalConfiguration` is observed: a configuration change recomposes the readers.
 */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales.get(0)
