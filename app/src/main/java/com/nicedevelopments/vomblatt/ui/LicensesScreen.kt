package com.nicedevelopments.vomblatt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nicedevelopments.vomblatt.ui.theme.LocalInk
import com.nicedevelopments.vomblatt.ui.theme.Type
import com.nicedevelopments.vomblatt.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

private val CREDITS = listOf(
    R.string.lic_piano_title to R.string.lic_piano,
    R.string.lic_omr_title to R.string.lic_omr,
    R.string.lic_music_font_title to R.string.lic_music_font,
    R.string.lic_font_title to R.string.lic_font,
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ink = LocalInk.current
    Column(
        Modifier.fillMaxSize().background(ink.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        IconAction(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), onBack, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.licenses), style = Type.display, color = ink.ink)
        Spacer(Modifier.height(20.dp))
        for ((title, text) in CREDITS) {
            Text(stringResource(title), style = Type.item, color = ink.ink)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(text), style = Type.body, color = ink.ink2)
            Spacer(Modifier.height(18.dp))
        }
    }
}
