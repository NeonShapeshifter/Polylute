package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

/** Design boundaries approved for B55. Native renders exercise the actual controls and geometry. */
class B55HomeVisualContractTest {
    private val root=File("src/main/java/com/lostf1sh/pixelplayeross")
    private fun source(path:String)=File(root,path).readText()
    @Test fun `L01 L02 Home spacing uses named design tokens with one inter section gap`():Unit {
        val home=source("presentation/screens/HomeScreen.kt")
        assertThat(home).doesNotContain("38.dp")
        assertThat(home).doesNotContain("7.dp")
        assertThat(home).contains("DapSpacing.SectionGap")
        assertThat(source("presentation/components/DailyMixSection.kt")).doesNotContain("Spacer(Modifier.height(16.dp))")
    }
    @Test fun `L03 unloaded Home has no player fade`():Unit {
        assertThat(source("presentation/screens/HomeScreen.kt")).contains("if(player.first == null) 0.dp else resolveMainScreenBottomGradientHeight")
    }
    @Test fun `L04 L05 Daily card shares the surface shape gutter and design system action`():Unit {
        val daily=source("presentation/components/DailyMixSection.kt")
        assertThat(daily).doesNotContain("AbsoluteSmoothCornerShape")
        assertThat(daily).doesNotContain("FilledTonalButton")
        assertThat(daily).contains("shape = RoundedCornerShape(DapRadius.Surface)")
        assertThat(daily).contains("DapNavRow(")
    }
    @Test fun `L06 L07 every Home section delegates its heading to the same clickable design system row`():Unit {
        for(path in listOf("DailyMixSection.kt","StatsOverviewCard.kt","RecentlyPlayedSection.kt","HomeDiscoverySections.kt")) {
            assertThat(source("presentation/components/$path")).contains("DapFeatureHeader(")
        }
    }
    @Test fun `H15 Daily has no unused title font or shape selector`():Unit {
        val daily=source("presentation/components/DailyMixSection.kt")
        assertThat(daily).doesNotContain("rememberDailyMixTitleStyle")
        assertThat(daily).doesNotContain("shapeConditionalModifier")
    }
}
