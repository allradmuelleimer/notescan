package com.nicedevelopments.vomblatt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.nicedevelopments.vomblatt.R

/**
 * Gestaltung „NoteScan“: kühles Porzellangrau, Lacktiefschwarz, genau eine Signalfarbe (Kobalt).
 * Kobalt bedeutet immer „das klingt gerade“ oder „das ist die Hauptaktion“ – sonst nirgends.
 */
@Immutable
data class Ink(
    val bg: Color,
    val surface: Color,
    val raised: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val leftHand: Color,
    val warn: Color,
    val paper: Color,
    val dark: Boolean,
)

val LightInk = Ink(
    bg = Color(0xFFF2F3F5), surface = Color(0xFFFFFFFF), raised = Color(0xFFE8EAEE),
    ink = Color(0xFF16181D), ink2 = Color(0xFF5D6370), ink3 = Color(0xFF9399A4), line = Color(0xFFDADDE2),
    accent = Color(0xFF2B4BDB), onAccent = Color(0xFFFFFFFF), accentSoft = Color(0xFFE2E7FB),
    leftHand = Color(0xFF7086E8), warn = Color(0xFFC2410C), paper = Color(0xFFFFFFFF), dark = false,
)

val DarkInk = Ink(
    bg = Color(0xFF0E1014), surface = Color(0xFF171A20), raised = Color(0xFF21252D),
    ink = Color(0xFFECEEF2), ink2 = Color(0xFF9AA0AC), ink3 = Color(0xFF656B77), line = Color(0xFF2A2E36),
    accent = Color(0xFF8297FF), onAccent = Color(0xFF0E1014), accentSoft = Color(0xFF1E2540),
    leftHand = Color(0xFF4A5AA8), warn = Color(0xFFFB923C), paper = Color(0xFF171A20), dark = true,
)

val LocalInk = staticCompositionLocalOf { LightInk }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun instrument(weight: Int, width: Float) = Font(
    R.font.instrument_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

val Instrument = FontFamily(
    instrument(400, 100f), instrument(500, 100f), instrument(600, 100f),
)
val InstrumentCondensed = FontFamily(instrument(620, 78f))

object Type {
    /** Große, schmale Überschrift – die einzige laute Typostufe. */
    val display = TextStyle(fontFamily = InstrumentCondensed, fontWeight = FontWeight(620), fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontFamily = InstrumentCondensed, fontWeight = FontWeight(620), fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp)
    val item = TextStyle(fontFamily = Instrument, fontWeight = FontWeight(600), fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = Instrument, fontWeight = FontWeight(400), fontSize = 15.sp, lineHeight = 21.sp)
    val label = TextStyle(fontFamily = Instrument, fontWeight = FontWeight(500), fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp)
    val small = TextStyle(fontFamily = Instrument, fontWeight = FontWeight(500), fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp)
    val button = TextStyle(fontFamily = Instrument, fontWeight = FontWeight(600), fontSize = 16.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
    /** Tempo und Taktzahlen: Ziffern gleich breit, damit nichts springt. */
    val number = TextStyle(fontFamily = InstrumentCondensed, fontWeight = FontWeight(620), fontSize = 22.sp, fontFeatureSettings = "tnum")
}

/** Standard ist schwarz auf weiß; die dunkle Fassung nur, wenn man sie im Menü wählt. */
@Composable
fun VomBlattTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val ink = if (dark) DarkInk else LightInk
    val scheme = if (dark) darkColorScheme(
        primary = ink.accent, onPrimary = ink.onAccent, background = ink.bg, surface = ink.surface,
        onBackground = ink.ink, onSurface = ink.ink, surfaceVariant = ink.raised, outline = ink.line,
    ) else lightColorScheme(
        primary = ink.accent, onPrimary = ink.onAccent, background = ink.bg, surface = ink.surface,
        onBackground = ink.ink, onSurface = ink.ink, surfaceVariant = ink.raised, outline = ink.line,
    )
    CompositionLocalProvider(LocalInk provides ink) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
