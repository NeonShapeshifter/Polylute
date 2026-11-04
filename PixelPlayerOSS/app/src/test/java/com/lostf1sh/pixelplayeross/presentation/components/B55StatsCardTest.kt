package com.lostf1sh.pixelplayeross.presentation.components

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.stats.*
import com.lostf1sh.pixelplayeross.ui.ds.*
import java.time.ZoneId
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],application=Application::class,qualifiers="es-w360dp-h805dp-xhdpi")
class B55StatsCardTest {
    @get:Rule val compose=createComposeRule()
    @Test fun `Monday has seven weekday slots and one real listening bar`() {
        val now=Kit.at(2026,11,2,20)
        val ledger=Kit.ledger(listOf(Kit.play(1,now-Kit.HOUR)),Kit.catalog(listOf(Kit.song(1))))
        val report=StatsEngine.report(ledger,StatsPeriod.Week,now,Kit.zone,StatsEngine.Detail.OVERVIEW)
        val cls=Class.forName("com.lostf1sh.pixelplayeross.presentation.components.StatsOverviewCardKt")
        val method=cls.declaredMethods.first { it.name=="miniBars" }.apply { isAccessible=true }
        val labels:(Int)->String={"Semana $it"}
        val bars=method.invoke(null,report,Kit.zone,Locale.forLanguageTag("es"),labels) as List<*>
        assertThat(bars).hasSize(7)
        fun value(bar:Any)=bar.javaClass.getDeclaredMethod("getValue").apply { isAccessible=true }.invoke(bar) as Long
        assertThat(bars.count { value(it!!) > 0 }).isEqualTo(1)
        assertThat(value(bars.first()!!)).isEqualTo(3*Kit.MIN)
    }
    @Test fun `month week labels are complete and the last row is displayed`() {
        val now=Kit.at(2026,10,31,20)
        val ledger=Kit.ledger((1..30).map { Kit.play(1,now-it*Kit.DAY) },Kit.catalog(listOf(Kit.song(1))))
        val report=StatsEngine.report(ledger,StatsPeriod.Preset(StatsTimeRange.MONTH),now,Kit.zone,StatsEngine.Detail.OVERVIEW)
        compose.setContent { DapTheme { StatsOverviewCard(summary=report,onClick={}) } }
        compose.onNodeWithText("Semana 5",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithText("Semana 1",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val layouts=mutableListOf<TextLayoutResult>();action(layouts)
            assertThat(layouts.single().isLineEllipsized(0)).isFalse()
            assertThat(layouts.single().hasVisualOverflow).isFalse()
        }
    }
    @Test fun `two line labels never lift a number above its baseline peers`() {
        val now=Kit.at(2026,11,2,20)
        val ledger=Kit.ledger((0..19).map { Kit.play(1,now-it*Kit.HOUR) },Kit.catalog(listOf(Kit.song(1))))
        val report=StatsEngine.report(ledger,StatsPeriod.Week,now,Kit.zone,StatsEngine.Detail.OVERVIEW)
        var total="";var average=""
        compose.setContent { DapTheme {
            total=com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact(report.totals.listenedMs, androidx.compose.ui.platform.LocalResources.current)
            average=com.lostf1sh.pixelplayeross.utils.formatListeningDurationCompact(report.totals.avgPerDayMs, androidx.compose.ui.platform.LocalResources.current)
            StatsOverviewCard(summary=report,onClick={})
        } }
        val labels=listOf(total,report.totals.plays.toString(),average)
        val nodes=labels.mapIndexed { index,label ->
            val all=compose.onAllNodesWithText(label,useUnmergedTree=true)
            if(index==2 && average==total) all[1] else all[0]
        }
        val baselines=nodes.map { node ->
            var baseline=0f
            val top=node.fetchSemanticsNode().boundsInRoot.top
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
                val layouts=mutableListOf<TextLayoutResult>();action(layouts);baseline=top+layouts.single().firstBaseline
            }
            baseline
        }
        println("B55 Stats numeric baselines=$baselines")
        baselines.forEach { assertThat(it).isWithin(1f).of(baselines.first()) }
    }

}
