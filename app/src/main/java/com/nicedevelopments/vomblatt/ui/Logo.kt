package com.nicedevelopments.vomblatt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.nicedevelopments.vomblatt.ui.theme.LocalInk

/** Kobalt des Logos (Gideons Entwurf, etwas kräftiger als die Signalfarbe der App). */
private val LogoBlue = Color(0xFF0C3BE6)

/**
 * Langes Logo „NoteScan“ (Optik/Logo lang.svg): Schrift in der Textfarbe, Notenhals, Notenkopf und Sucherecken in Kobalt.
 * Im dunklen Modus hell auf dunkel; die Innenflächen der Buchstaben sind Löcher, kein Weiß.
 */
@Composable
fun NoteScanLogo(height: Dp, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val inkPaths = remember { LogoPaths.ink.map { PathParser().parsePathString(it).toPath() } }
    val accentPaths = remember { LogoPaths.accent.map { PathParser().parsePathString(it).toPath() } }
    val accent = if (ink.dark) ink.accent else LogoBlue
    Canvas(
        modifier.height(height).aspectRatio(LogoPaths.WIDTH / LogoPaths.HEIGHT).semantics { contentDescription = "NoteScan" },
    ) {
        val s = size.height / LogoPaths.HEIGHT
        scale(s, s, pivot = Offset.Zero) {
            for (p in inkPaths) drawPath(p, ink.ink)
            for (p in accentPaths) drawPath(p, accent)
        }
    }
}
