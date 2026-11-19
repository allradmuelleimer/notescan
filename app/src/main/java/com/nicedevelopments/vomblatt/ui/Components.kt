package com.nicedevelopments.vomblatt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nicedevelopments.vomblatt.ui.theme.LocalInk
import com.nicedevelopments.vomblatt.ui.theme.Type

/** Die eine Hauptaktion eines Bildschirms – Kobalt, voll abgerundet. */
@Composable
fun PrimaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val ink = LocalInk.current
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) ink.accent else ink.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) ink.onAccent else ink.ink3, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Type.button, color = if (enabled) ink.onAccent else ink.ink3)
    }
}

/** Zweitrangige Aktion: Tinte auf leicht erhabener Fläche. */
@Composable
fun SecondaryButton(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(ink.raised)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.label, color = ink.ink)
    }
}

/** Runder Symbolknopf. */
@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, filled: Boolean = false) {
    val ink = LocalInk.current
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) ink.raised else ink.bg.copy(alpha = 0f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = ink.ink, modifier = Modifier.size(24.dp)) }
}

/** Umschalter mit Symbol und kurzer Beschriftung (Hände, Schleife, Metronom). */
@Composable
fun ToggleTile(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    androidx.compose.foundation.layout.Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) ink.accentSoft else ink.bg.copy(alpha = 0f))
            .clickable(role = Role.Switch, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (on) ink.accent else ink.ink2, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = Type.small, color = if (on) ink.accent else ink.ink2, maxLines = 1)
    }
}

/** Notensymbol-Knopf: Bravura-Glyph, optisch mittig über seine tatsächlichen Umrisse gesetzt. */
@Composable
fun SymbolPill(symbol: String, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val font = rememberMusicFont()
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { typeface = font.typeface } }
    val bounds = remember { android.graphics.Rect() }
    val color = if (selected) ink.bg else ink.ink
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) ink.ink else ink.raised)
            .clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            paint.textSize = 30.dp.toPx()
            paint.color = color.toArgb()
            paint.getTextBounds(symbol, 0, symbol.length, bounds)
            val x = size.width / 2 - bounds.exactCenterX()
            val y = size.height / 2 - bounds.exactCenterY()
            drawContext.canvas.nativeCanvas.drawText(symbol, x, y, paint)
        }
    }
}

/** Kleine Auswahlpille (Notenwerte, Vorzeichen). */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) ink.ink else ink.raised)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.label, color = if (selected) ink.bg else ink.ink) }
}

/** Segmentierter Umschalter (z. B. Noten | Foto). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, ink.line, RoundedCornerShape(20.dp))
            .padding(3.dp)
    ) {
        options.forEachIndexed { i, s ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (i == selected) ink.ink else ink.bg.copy(alpha = 0f))
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Text(s, style = Type.label, color = if (i == selected) ink.bg else ink.ink2) }
        }
    }
}
