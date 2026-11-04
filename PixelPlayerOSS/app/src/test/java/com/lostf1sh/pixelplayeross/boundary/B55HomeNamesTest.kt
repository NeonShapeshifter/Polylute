package com.lostf1sh.pixelplayeross.boundary

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Test

class B55HomeNamesTest {
    private fun strings(locale:String):Map<String,String> {
        val root=listOf(File("src/main/res"),File("app/src/main/res")).first { it.isDirectory }
        val result=mutableMapOf<String,String>()
        File(root,locale).listFiles()!!.filter { it.extension=="xml" }.forEach { file ->
            val nodes=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            for(i in 0 until nodes.length) { val node=nodes.item(i) as org.w3c.dom.Element;result[node.getAttribute("name")]=node.textContent }
        }
        return result
    }
    @Test fun `H06 Daily Mix has one exact name on Home queue page Playlists and Maintenance`():Unit {
        for((locale,name) in listOf("values" to "Daily Mix","values-es" to "Mix diario","values-sv" to "Daglig mix")) {
            val map=strings(locale)
            fun value(key:String):String { val value=map.getValue(key);return if(value.startsWith("@string/")) value(value.removePrefix("@string/")) else value }
            for(key in listOf("collection_name_daily_mix","home_daily_mix_title","presentation_batch_g_daily_mix_queue_name")) assertThat(value(key)).isEqualTo(name)
            for(key in listOf("setcat_force_daily_mix_title","setcat_force_daily_mix_subtitle","setcat_regenerate_daily_mix_action","dialog_regenerate_daily_mix_title","toast_daily_mix_regeneration_started")) {
                assertThat(String.format(java.util.Locale.ROOT,value(key),name)).contains(name)
            }
        }
    }
    @Test fun `Suggestions and Recently played have one localized name across entry points`():Unit {
        for((locale,name) in listOf("values" to "Suggestions","values-es" to "Sugerencias","values-sv" to "Förslag")) {
            val map=strings(locale)
            fun value(key:String):String { val value=map.getValue(key);return if(value.startsWith("@string/")) value(value.removePrefix("@string/")) else value }
            assertThat(value("home_your_mix_heading")).isEqualTo(name)
            assertThat(value("home_your_mix_queue_name")).isEqualTo(name)
            val recent=value("presentation_batch_b_recently_played_title")
            for(key in listOf("presentation_batch_g_recently_played_title","presentation_batch_b_queue_recently_played")) assertThat(value(key)).isEqualTo(recent)
        }
    }
}
