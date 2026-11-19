package com.nicedevelopments.vomblatt.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nicedevelopments.vomblatt.data.Library
import com.nicedevelopments.vomblatt.ui.theme.LocalInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.nicedevelopments.vomblatt.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Eigene Kamera für ein- und mehrseitige Stücke. Nach dem Auslösen: Foto prüfen – „Nochmal“, ✓ (fertig) oder
 * + (weitere Seite). Das + ist ausgegraut, solange noch kein Foto gemacht ist.
 * [newFile] liefert die Zieldatei je Foto, [onDone] bekommt alle Seiten in Reihenfolge.
 */
@Composable
fun CaptureScreen(newFile: () -> File, onDone: (List<File>) -> Unit, onCancel: () -> Unit) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    val pro = com.nicedevelopments.vomblatt.pro.rememberPro()
    val owner = LocalLifecycleOwner.current
    val pages = remember { mutableStateListOf<File>() }
    var review by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(granted) {
        var provider: ProcessCameraProvider? = null
        if (granted) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                runCatching {
                    val p = future.get(); provider = p
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    p.unbindAll()
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }.onFailure { error = ctx.getString(R.string.err_camera); android.util.Log.w("VomBlatt", "Kamera", it) }
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose { runCatching { provider?.unbindAll() } }
    }

    fun shoot() {
        if (busy) return
        busy = true
        val f = newFile()
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { busy = false; review = f }
                override fun onError(e: ImageCaptureException) { busy = false; error = ctx.getString(R.string.err_photo_save); android.util.Log.w("VomBlatt", "Foto", e) }
            })
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val shown = review
        if (shown != null) {
            val bmp by produceState<android.graphics.Bitmap?>(null, shown) {
                value = withContext(Dispatchers.IO) { runCatching { Library.decodeUpright(shown, 1800) }.getOrNull() }
            }
            bmp?.let { Image(it.asImageBitmap(), stringResource(R.string.photo_of_page), Modifier.fillMaxSize().padding(bottom = 210.dp, top = 56.dp), contentScale = ContentScale.Fit) }
        } else if (granted) {
            AndroidView({ previewView }, Modifier.fillMaxSize().padding(bottom = 210.dp, top = 56.dp))
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.camera_needed), color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(R.string.camera_allow), null, { ask.launch(Manifest.permission.CAMERA) })
            }
        }

        // Kopfzeile: Seitenzahl
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pages.isNotEmpty()) stringResource(R.string.capture_page_count, pages.size + 1, pages.size) else stringResource(R.string.capture_page, pages.size + 1),
                color = Color.White, fontSize = 16.sp,
            )
        }
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }

        // Bedienleiste: Auslöser mittig, links Abbrechen/Nochmal, rechts + (gleich groß, gleiche Höhe);
        // ✓ direkt unter dem Auslöser, sobald es etwas zu übernehmen gibt (Platz ist immer reserviert)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (shown == null) RoundButton(Icons.Outlined.Close, stringResource(R.string.cancel), { pages.forEach { it.delete() }; onCancel() })
                else RoundButton(Icons.Outlined.Refresh, stringResource(R.string.retake), { shown.delete(); review = null })
                // Auslöser (beim Prüfen des Fotos ausgegraut)
                val canShoot = granted && !busy && shown == null
                Box(
                    Modifier.size(78.dp).alpha(if (shown == null) 1f else 0.35f).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                        .padding(7.dp).clip(CircleShape)
                        .background(if (busy) Color.Gray else Color.White).clickable(enabled = canShoot) { shoot() },
                )
                // + : diese Seite übernehmen, nächste fotografieren (vor dem Foto ausgegraut)
                RoundButton(Icons.Outlined.Add, stringResource(R.string.add_page), { pro.require { shown?.let { pages.add(it); review = null } } }, enabled = shown != null)
            }
            Spacer(Modifier.height(16.dp))
            val canFinish = shown != null || pages.isNotEmpty()
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                if (canFinish) RoundButton(
                    Icons.Outlined.Check, stringResource(R.string.done), { onDone(if (shown != null) pages.toList() + shown else pages.toList()) },
                    filled = true, color = ink.accent, size = 64.dp,
                )
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, filled: Boolean = false,
    color: Color = Color.White, size: Dp = 52.dp,
) {
    Box(
        Modifier.size(size).alpha(if (enabled) 1f else 0.35f).clip(CircleShape)
            .background(if (filled) color else Color.White.copy(alpha = 0.16f))
            .border(if (filled) 0.dp else 1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.5f)) }
}
