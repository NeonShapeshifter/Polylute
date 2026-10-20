package com.lostf1sh.pixelplayeross.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lostf1sh.pixelplayeross.presentation.viewmodel.ProductModeViewModel
import kotlinx.coroutines.delay

/** How often a page that shows the product mode asks Android again while it is on screen (B34). One binder round trip each. */
const val PRODUCT_MODE_POLL_MS = 1_000L

/**
 * Keeps [viewModel] current while the page is started: a read as it starts (which covers returning from the home picker or the security
 * settings) and one every [PRODUCT_MODE_POLL_MS], so a pin confirmed or dropped in Android's own UI, or another launcher chosen, shows up
 * without the listener leaving the page.
 */
@Composable
fun ProductModeLiveEffect(viewModel: ProductModeViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                viewModel.refresh()
                delay(PRODUCT_MODE_POLL_MS)
            }
        }
    }
}
