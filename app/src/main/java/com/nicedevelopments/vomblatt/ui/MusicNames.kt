package com.nicedevelopments.vomblatt.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.nicedevelopments.vomblatt.R
import com.nicedevelopments.vomblatt.model.NoteType
import com.nicedevelopments.vomblatt.model.Pitch

/**
 * Ton- und Tonartnamen in der Schreibweise des jeweiligen Landes: Deutsch und der Norden mit H und
 * „-is/-es“, romanische Sprachen mit Do-Re-Mi, Japanisch/Koreanisch mit eigenen Silben, sonst
 * Buchstaben mit ♯/♭. Die Daten hier statt in strings.xml, weil es Regeln sind, keine Sätze.
 */
object MusicNames {
    /** Dur-Tonarten von 7 ♭ bis 7 ♯ als (Stammton 0–6, Vorzeichen). */
    private val KEYS = arrayOf(0 to -1, 4 to -1, 1 to -1, 5 to -1, 2 to -1, 6 to -1, 3 to 0, 0 to 0, 4 to 0, 1 to 0, 5 to 0, 2 to 0, 6 to 0, 3 to 1, 0 to 1)

    /** Gespeicherte Tonart der Bibliothek (deutsch, z. B. „Fis-Dur“) → Zahl der Vorzeichen. */
    private val STORED = arrayOf("Ces", "Ges", "Des", "As", "Es", "B", "F", "C", "G", "D", "A", "E", "H", "Fis", "Cis")
    fun fifthsOfStored(key: String): Int? = STORED.indexOf(key.removeSuffix("-Dur")).takeIf { it >= 0 }?.minus(7)

    private val GERMANIC = setOf("de", "pl", "cs", "da", "nb", "no", "fi", "sv")
    private val SOLFEGE = mapOf(
        "fr" to arrayOf("Do", "Ré", "Mi", "Fa", "Sol", "La", "Si"),
        "es" to arrayOf("Do", "Re", "Mi", "Fa", "Sol", "La", "Si"),
        "it" to arrayOf("Do", "Re", "Mi", "Fa", "Sol", "La", "Si"),
        "pt" to arrayOf("Dó", "Ré", "Mi", "Fá", "Sol", "Lá", "Si"),
        "ru" to arrayOf("до", "ре", "ми", "фа", "соль", "ля", "си"),
        "ar" to arrayOf("دو", "ري", "مي", "فا", "صول", "لا", "سي"),
    )
    private const val LETTERS = "CDEFGAB"

    private fun accSymbol(alter: Int) = when (alter) { -2 -> "𝄫"; -1 -> "♭"; 1 -> "♯"; 2 -> "𝄪"; else -> "" }

    /** Deutsche Schreibweise (Fis, Es, As, B, H); Schwedisch verdoppelt das s (Fiss, Ess, Ass). */
    private fun germanic(step: Int, alter: Int, lang: String): String {
        val base = "CDEFGAH"[step].toString()
        if (step == 6 && alter == -1) return "B"
        val acc = when (alter) {
            -2 -> "eses"; -1 -> if (step == 2 || step == 5) "s" else "es"; 1 -> "is"; 2 -> "isis"; else -> ""
        }
        val sv = if (lang == "sv" && acc.endsWith("s")) acc + "s" else acc
        return base + sv
    }

    private fun dutch(step: Int, alter: Int): String {
        val base = LETTERS[step].toString()
        val acc = when (alter) { -2 -> "eses"; -1 -> if (step == 2 || step == 5) "s" else "es"; 1 -> "is"; 2 -> "isis"; else -> "" }
        return base + acc
    }

    fun pitch(p: Pitch, lang: String): String = when {
        lang in GERMANIC -> germanic(p.step, p.alter, lang) + p.octave
        lang == "nl" -> dutch(p.step, p.alter) + p.octave
        lang in SOLFEGE -> SOLFEGE.getValue(lang)[p.step] + accSymbol(p.alter) + (if (lang == "fr" || lang == "it") p.octave - 1 else p.octave)
        else -> LETTERS[p.step] + accSymbol(p.alter) + p.octave
    }

    fun key(fifths: Int, lang: String): String {
        val (step, alter) = KEYS.getOrNull(fifths + 7) ?: return "?"
        return when (lang) {
            "de" -> germanic(step, alter, lang) + "-Dur"
            "pl", "da", "nb", "no", "sv" -> germanic(step, alter, lang) + "-dur"
            "cs" -> germanic(step, alter, lang) + " dur"
            "fi" -> germanic(step, alter, lang) + "-duuri"
            "nl" -> dutch(step, alter) + " groot"
            "fr" -> SOLFEGE.getValue(lang)[step] + accSymbol(alter) + " majeur"
            "es" -> SOLFEGE.getValue(lang)[step] + accSymbol(alter) + " mayor"
            "it" -> SOLFEGE.getValue(lang)[step] + accSymbol(alter) + " maggiore"
            "pt" -> SOLFEGE.getValue(lang)[step] + accSymbol(alter) + " maior"
            "ru" -> SOLFEGE.getValue(lang)[step] + (when (alter) { -1 -> "-бемоль"; 1 -> "-диез"; else -> "" }) + " мажор"
            "ar" -> SOLFEGE.getValue(lang)[step] + accSymbol(alter) + " الكبير"
            "ja" -> (when (alter) { -1 -> "変"; 1 -> "嬰"; else -> "" }) + "ハニホヘトイロ"[step] + "長調"
            "ko" -> (when (alter) { -1 -> "내림"; 1 -> "올림"; else -> "" }) + "다라마바사가나"[step] + "장조"
            "zh" -> (when (alter) { -1 -> "降"; 1 -> "升"; else -> "" }) + LETTERS[step] + "大调"
            "hi" -> LETTERS[step] + accSymbol(alter) + " मेजर"
            "th" -> LETTERS[step] + accSymbol(alter) + " เมเจอร์"
            "id", "in" -> LETTERS[step] + accSymbol(alter) + " mayor"
            else -> LETTERS[step] + accSymbol(alter) + " major"
        }
    }
}

@Composable
private fun lang(): String = LocalConfiguration.current.locales[0].language

@Composable
fun keyLabel(fifths: Int?): String = if (fifths == null) "?" else MusicNames.key(fifths, lang())

@Composable
fun pitchLabel(p: Pitch): String = MusicNames.pitch(p, lang())

@Composable
fun noteTypeLabel(t: NoteType, dotted: Boolean): String {
    val name = stringResource(
        when (t) {
            NoteType.WHOLE -> R.string.note_whole
            NoteType.HALF -> R.string.note_half
            NoteType.QUARTER -> R.string.note_quarter
            NoteType.EIGHTH -> R.string.note_eighth
            NoteType.SIXTEENTH -> R.string.note_sixteenth
            NoteType.THIRTY_SECOND -> R.string.note_32nd
        }
    )
    return if (dotted) stringResource(R.string.dotted_fmt, name) else name
}
