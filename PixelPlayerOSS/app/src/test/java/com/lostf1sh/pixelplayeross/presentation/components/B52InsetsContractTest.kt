package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.R
import com.lostf1sh.pixelplayeross.data.offline.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w360dp-h805dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class B52InsetsContractTest {
    @get:Rule val compose=createComposeRule()
    private var host:View?=null
    @Test fun `a real status bar replaces the mockups top reservation instead of adding it twice`() {
        val pages=flowOf(PagingData.from(emptyList<InfoArtist>()))
        compose.setContent {
            host=LocalView.current
            DapTheme(darkTheme=true) { Surface(Modifier.fillMaxSize(),color=Dap.background) {
                MusicInfoDownloadsContent(pages.collectAsLazyPagingItems(),"",{},InfoQueue(),InfoProgress(0,0,0),InfoProgress(0,0,0),InfoSelection(0,0,null,0),false,false,{flowOf(emptyList())},{},{},{},{},{},{_,_->},{_,_->},{})
            } }
        }
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val title=context.getString(R.string.info_internet_title)
        fun inset(px:Int) {
            compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(host!!,WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,px,0,0)).build()) }
            compose.waitForIdle()
        }
        inset(0)
        val before=compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        inset(74)
        val after=compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        assertThat((after-before)/2f).isWithin(1f).of(9f)
    }
}
