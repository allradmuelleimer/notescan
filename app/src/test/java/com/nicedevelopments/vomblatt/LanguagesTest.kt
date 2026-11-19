package com.nicedevelopments.vomblatt

import com.nicedevelopments.vomblatt.model.Pitch
import com.nicedevelopments.vomblatt.ui.MusicNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Alle 21 Sprachen: jede hat jeden Text der englischen Grundsprache, mit denselben Platzhaltern,
 * und die Sprachliste ist in build.gradle.kts, locales_config.xml und res/values-* deckungsgleich.
 */
class LanguagesTest {
    private val res = File("src/main/res")
    private val langs = listOf(
        "de", "en", "fr", "es", "it", "sv", "ru", "ar", "zh",
        "ja", "ko", "hi", "pl", "cs", "nb", "fi", "da", "id", "th", "pt", "nl",
    )

    private val entry = Regex("""<(string|plurals) name="([^"]+)"([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    /** name → Menge der Platzhalter (bei plurals über alle Formen, „%d“ zählt nicht – manche Sprachen schreiben die Zahl aus). */
    private fun read(dir: String): Map<String, Set<String>> {
        val f = File(res, "$dir/strings.xml")
        assertTrue("fehlt: $f", f.exists())
        return entry.findAll(f.readText()).filter { "translatable=\"false\"" !in it.groupValues[3] }
            .associate { m -> m.groupValues[2] to placeholder.findAll(m.groupValues[4]).map { it.value }.filter { it != "%d" }.toSet() }
    }

    @Test
    fun everyLanguageHasEveryText() {
        val base = read("values")
        val plurals = Regex("""<plurals name="([^"]+)"""").findAll(File(res, "values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        for (l in langs - "en") {
            val t = read("values-$l")
            assertEquals("$l: fehlende Texte", emptySet<String>(), base.keys - t.keys)
            assertEquals("$l: überzählige Texte", emptySet<String>(), t.keys - base.keys)
            for ((k, ph) in base) {
                // %1$d in Plural-Formen darf in Sprachen mit ausgeschriebenem „eins/zwei“ fehlen (z. B. Arabisch)
                val missing = ph - t.getValue(k)
                assertTrue("$l/$k: Platzhalter $missing fehlen", missing.all { k in plurals && it.endsWith("d") })
                assertTrue("$l/$k: unbekannte Platzhalter", (t.getValue(k) - ph).isEmpty())
            }
        }
    }

    @Test
    fun languageListsMatch() {
        val gradle = File("build.gradle.kts").readText()
        val filters = Regex(""""([a-z]{2})"""").findAll(gradle.substringAfter("localeFilters").substringBefore(")")).map { it.groupValues[1] }.toSet()
        assertEquals(langs.toSet(), filters)
        val config = Regex("""android:name="([a-z]{2})"""").findAll(File(res, "xml/locales_config.xml").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(langs.toSet(), config)
        val dirs = res.listFiles()!!.map { it.name }.filter { Regex("values-[a-z]{2}").matches(it) }.map { it.removePrefix("values-") }.toSet()
        assertEquals(langs.toSet() - "en", dirs)
    }

    @Test
    fun musicNames() {
        assertEquals("B-Dur", MusicNames.key(-2, "de"))
        assertEquals("Fis-Dur", MusicNames.key(6, "de"))
        assertEquals("B♭ major", MusicNames.key(-2, "en"))
        assertEquals("Si♭ majeur", MusicNames.key(-2, "fr"))
        assertEquals("си-бемоль мажор", MusicNames.key(-2, "ru"))
        assertEquals("変ロ長調", MusicNames.key(-2, "ja"))
        assertEquals("내림나장조", MusicNames.key(-2, "ko"))
        assertEquals("降B大调", MusicNames.key(-2, "zh"))
        assertEquals("Fiss-dur", MusicNames.key(6, "sv"))
        assertEquals("Es", MusicNames.pitch(Pitch(2, 4, -1), "de").dropLast(1))
        assertEquals("E♭4", MusicNames.pitch(Pitch(2, 4, -1), "en"))
        assertEquals("Do3", MusicNames.pitch(Pitch(0, 4, 0), "fr"))
        assertEquals(-2, MusicNames.fifthsOfStored("B-Dur"))
        assertEquals(6, MusicNames.fifthsOfStored("Fis-Dur"))
    }
}
