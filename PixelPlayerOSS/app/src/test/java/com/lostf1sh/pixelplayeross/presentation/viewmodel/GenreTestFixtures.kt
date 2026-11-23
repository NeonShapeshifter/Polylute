package com.lostf1sh.pixelplayeross.presentation.viewmodel

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

internal fun emptyGenreImagery(): GenreImagery = mockk {
    every { pictures } returns MutableStateFlow(emptyMap())
}

internal fun offlineGenreInfo(): GenrePageInformation = mockk {
    every { description(any()) } returns flowOf(null)
    every { featuredPhoto(any()) } returns flowOf(null)
}
