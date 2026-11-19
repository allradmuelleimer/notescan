package com.nicedevelopments.vomblatt.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nicedevelopments.vomblatt.data.PieceInfo
import com.nicedevelopments.vomblatt.ui.theme.LocalInk
import com.nicedevelopments.vomblatt.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.nicedevelopments.vomblatt.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.outlined.WorkspacePremium

@Composable
fun LibraryScreen(
    pieces: List<PieceInfo>,
    onOpen: (PieceInfo) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onImportXml: () -> Unit,
    onImportPdf: () -> Unit,
    onLicenses: () -> Unit,
    onPro: () -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
) {
    val ink = LocalInk.current
    var menu by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(ink.bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 140.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(top = 28.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        // Logo-Ränder oben/links ausgleichen, damit die Schrift bündig mit den Karten steht
                        NoteScanLogo(62.dp, Modifier.offset(x = (-2).dp, y = (-8).dp))
                        Spacer(Modifier.height(0.dp))
                        Text(
                            if (pieces.isEmpty()) stringResource(R.string.library_empty_count) else pluralStringResource(R.plurals.pieces, pieces.size, pieces.size),
                            style = Type.label, color = ink.ink2,
                        )
                    }
                    Box {
                        IconAction(Icons.Outlined.MoreVert, stringResource(R.string.more_actions), { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_open_pdf), style = Type.body) },
                                leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                                onClick = { menu = false; onImportPdf() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_open_musicxml), style = Type.body) },
                                leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                onClick = { menu = false; onImportXml() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings), style = Type.body) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { menu = false; settings = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (dark) stringResource(R.string.menu_light) else stringResource(R.string.menu_dark), style = Type.body) },
                                leadingIcon = { Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null) },
                                onClick = { menu = false; onDark(!dark) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pro_title), style = Type.body) },
                                leadingIcon = { Icon(Icons.Outlined.WorkspacePremium, null) },
                                onClick = { menu = false; onPro() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.licenses), style = Type.body) },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = { menu = false; onLicenses() },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            if (pieces.isEmpty()) item { EmptyState() }
            items(pieces, key = { it.id }) { p -> PieceRow(p) { onOpen(p) } }
        }

        // Aktionen unten, mit dem Daumen erreichbar
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(ink.bg)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(stringResource(R.string.take_photo), Icons.Outlined.CameraAlt, onCamera, Modifier.weight(1f))
            IconAction(Icons.Outlined.Image, stringResource(R.string.pick_gallery), onGallery, size = 56.dp, filled = true)
        }
    }
    if (settings) SettingsDialog { settings = false }

}

@Composable
private fun EmptyState() {
    val ink = LocalInk.current
    Column(Modifier.fillMaxWidth().padding(top = 40.dp, end = 24.dp)) {
        Text(stringResource(R.string.empty_title), style = Type.title, color = ink.ink)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.empty_body),
            style = Type.body, color = ink.ink2,
        )
    }
}

@Composable
private fun PieceRow(p: PieceInfo, onClick: () -> Unit) {
    val ink = LocalInk.current
    val thumb by produceState<ImageBitmap?>(null, p.id) {
        value = withContext(Dispatchers.IO) {
            if (p.thumb.exists()) BitmapFactory.decodeFile(p.thumb.absolutePath)?.asImageBitmap() else null
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ink.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)).background(ink.raised),
            contentAlignment = Alignment.Center,
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Icon(Icons.Outlined.Description, null, tint = ink.ink3)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = Type.item, color = ink.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text((MusicNames.fifthsOfStored(p.key)?.let { keyLabel(it) } ?: p.key) + " · ${p.time} · " + pluralStringResource(R.plurals.measures, p.measures, p.measures), style = Type.label, color = ink.ink2)
        }
    }
}

/** Einstellungen: Takte je Zeile im Notenbild (gespeichert in den App-Einstellungen, „lineMode“ 0 = immer 2, 1 = 2 oder 3). */
@Composable
private fun SettingsDialog(onClose: () -> Unit) {
    val ink = LocalInk.current
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
    var mode by remember { mutableStateOf(prefs.getInt("lineMode", 0).coerceIn(0, 1)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.settings), style = Type.item) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.settings_bars_per_line), style = Type.label, color = ink.ink2)
                Spacer(Modifier.height(10.dp))
                Segmented(listOf(stringResource(R.string.settings_always_2), stringResource(R.string.settings_2_or_3)), mode, { mode = it; prefs.edit().putInt("lineMode", it).apply() })
                Spacer(Modifier.height(12.dp))
                Text(
                    if (mode == 0) stringResource(R.string.settings_always_2_desc)
                    else stringResource(R.string.settings_2_or_3_desc),
                    style = Type.body, color = ink.ink2,
                )
            }
        },
        confirmButton = { TextButton(onClose) { Text(stringResource(R.string.done)) } },
    )
}
