package com.nicedevelopments.vomblatt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.nicedevelopments.vomblatt.ui.theme.LocalInk

private val BLACK = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)

/**
 * Klaviatur über den Tonumfang des Stücks. Klingende Tasten leuchten:
 * rechte Hand in Kobalt, linke Hand im helleren Kobalt.
 */
@Composable
fun KeyboardStrip(low: Int, high: Int, right: Set<Int>, left: Set<Int>, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    // Auf weiße Tasten erweitern
    var lo = low; while (BLACK[lo % 12]) lo--
    var hi = high; while (BLACK[hi % 12]) hi++
    val whites = (lo..hi).filter { !BLACK[it % 12] }
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val w = size.width / whites.size
        val h = size.height
        val gap = 1.5.dp.toPx()
        val r = CornerRadius(3.dp.toPx())
        whites.forEachIndexed { i, m ->
            val col = when (m) { in right -> ink.accent; in left -> ink.leftHand; else -> if (ink.dark) ink.ink2 else ink.raised }
            drawRoundRect(col, Offset(i * w + gap / 2, 0f), Size(w - gap, h), r)
        }
        val bw = w * 0.58f
        val bh = h * 0.6f
        for (m in lo..hi) if (BLACK[m % 12]) {
            val idx = whites.indexOfFirst { it > m }
            if (idx <= 0) continue
            val x = idx * w - bw / 2
            val col = when (m) { in right -> ink.accent; in left -> ink.leftHand; else -> if (ink.dark) ink.raised else ink.ink }
            drawRoundRect(col, Offset(x, 0f), Size(bw, bh), CornerRadius(2.dp.toPx()))
        }
    }
}
