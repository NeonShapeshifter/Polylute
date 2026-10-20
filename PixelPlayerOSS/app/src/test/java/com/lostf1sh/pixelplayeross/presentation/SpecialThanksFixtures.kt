package com.lostf1sh.pixelplayeross.presentation

import java.io.File

/**
 * B38 (E1): the "Special thanks" text as the owner wrote it, in the three languages, typed out here so that the screen and the strings are
 * each compared against the same words and not against one another. The last entry of each list is the personal line: it follows the
 * projects after a larger gap on the screen. (The README carried it too until the owner kept it to the app.)
 */
internal object SpecialThanksFixtures {

    val englishTitle = "Special thanks"

    val english = listOf(
        "To PixelPlayer, for a beautiful app worth falling in love with.",
        "To decent-player, for the USB driver that lets the music reach the DAC untouched.",
        "To Media3, FFmpeg (via Jellyfin) and libFLAC, for playing every file we throw at them.",
        "To TagLib and JAudiotagger, for reading our libraries right.",
        "To WolfEQ and Pragmatic Audio's DevicePEQ, for mapping the FiiO EQ protocol.",
        "To LRCLIB and MusicBrainz, for keeping music data open.",
        "To Wavy Slider, Reorderable and Coil, for the little things that feel right.",
        "And to every project in Licenses: Polylute stands on your work.",
        "And to Roderick, who got me into the audio world, helped me understand every concept along the way, and was brave enough to try it, " +
            "beer in hand, long before it was anywhere near finished.",
    )

    val spanishTitle = "Agradecimientos especiales"

    val spanish = listOf(
        "A PixelPlayer, por una app tan bonita que daba gusto enamorarse de ella.",
        "A decent-player, por el driver USB que deja llegar la música al DAC sin tocarla.",
        "A Media3, FFmpeg (gracias a Jellyfin) y libFLAC, por reproducir cada archivo que les damos.",
        "A TagLib y JAudiotagger, por leer bien nuestras bibliotecas.",
        "A WolfEQ y DevicePEQ de Pragmatic Audio, por mapear el protocolo de ecualizador de FiiO.",
        "A LRCLIB y MusicBrainz, por mantener abiertos los datos de la música.",
        "A Wavy Slider, Reorderable y Coil, por los pequeños detalles que se sienten bien.",
        "Y a cada proyecto de la sección Licencias: Polylute se apoya en su trabajo.",
        "Y a Roderick, que me metió en el mundo del audio, me ayudó a entender cada concepto por el camino y se atrevió a probarlo, " +
            "cerveza en mano, mucho antes de que estuviera ni cerca de terminado.",
    )

    val swedishTitle = "Särskilt tack"

    val swedish = listOf(
        "Till PixelPlayer, för en så vacker app att man bara måste bli förälskad i den.",
        "Till decent-player, för USB-drivrutinen som låter musiken nå DAC:en orörd.",
        "Till Media3, FFmpeg (via Jellyfin) och libFLAC, för att de spelar upp varje fil vi kastar på dem.",
        "Till TagLib och JAudiotagger, för att de läser våra bibliotek rätt.",
        "Till WolfEQ och Pragmatic Audios DevicePEQ, för att ha kartlagt FiiO:s EQ-protokoll.",
        "Till LRCLIB och MusicBrainz, för att de håller musikdata öppen.",
        "Till Wavy Slider, Reorderable och Coil, för de små detaljerna som känns rätt.",
        "Och till varje projekt under Licenser: Polylute vilar på ert arbete.",
        "Och till Roderick, som förde in mig i ljudvärlden, hjälpte mig förstå varje begrepp på vägen och var modig nog att prova det, " +
            "med en öl i handen, långt innan det var i närheten av färdigt.",
    )

    /** The nine string resources, in the order of the lists above. */
    val resourceNames = listOf(
        "about_special_thanks_pixelplayer",
        "about_special_thanks_decent_player",
        "about_special_thanks_playback",
        "about_special_thanks_tags",
        "about_special_thanks_fiio",
        "about_special_thanks_music_data",
        "about_special_thanks_interface",
        "about_special_thanks_licenses",
        "about_special_thanks_roderick",
    )

    /** `<string name="x">value</string>` of every `values<suffix>/str*.xml` under `src/main/res`, with Android's string escapes undone. */
    fun stringValues(valuesDir: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        File("src/main/res/$valuesDir").listFiles { f -> f.name.startsWith("str") && f.name.endsWith(".xml") }!!.sortedBy { it.name }.forEach { file ->
            Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(file.readText()).forEach { m ->
                result[m.groupValues[1]] = unescape(m.groupValues[2])
            }
        }
        return result
    }

    private fun unescape(raw: String): String = raw
        .replace("\\'", "'")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
