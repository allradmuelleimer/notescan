package com.nicedevelopments.vomblatt

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.nicedevelopments.vomblatt.data.PieceInfo
import com.nicedevelopments.vomblatt.recognize.Overlay
import com.nicedevelopments.vomblatt.recognize.Progress
import com.nicedevelopments.vomblatt.ui.LibraryScreen
import com.nicedevelopments.vomblatt.ui.LicensesScreen
import com.nicedevelopments.vomblatt.ui.PieceScreen
import com.nicedevelopments.vomblatt.ui.RecognizeScreen
import com.nicedevelopments.vomblatt.ui.CaptureScreen
import com.nicedevelopments.vomblatt.ui.theme.VomBlattTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource

sealed interface Screen {
    data object Library : Screen
    data class Piece(val info: PieceInfo) : Screen
    data class Recognize(val photo: File, val page: Int = 1, val pages: Int = 1) : Screen
    data object Capture : Screen
    data object Licenses : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as VomBlattApp
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        setContent {
            var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            VomBlattTheme(dark) {
                App(app, dark) { dark = it; prefs.edit().putBoolean("dark", it).apply() }
            }
        }
    }

    /** PDF-Seiten als JPEG in den Aufnahmeordner rendern (höchstens 20 Seiten). */
    private fun renderPdf(uri: Uri): List<File> {
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        val out = ArrayList<File>()
        contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { pdf ->
                val stamp = System.currentTimeMillis()
                for (i in 0 until minOf(pdf.pageCount, 20)) {
                    pdf.openPage(i).use { page ->
                        val longSide = 3300f
                        val s = longSide / maxOf(page.width, page.height)
                        val bmp = android.graphics.Bitmap.createBitmap((page.width * s).toInt(), (page.height * s).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val f = File(dir, "pdf_${stamp}_${i + 1}.jpg")
                        f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 94, it) }
                        bmp.recycle()
                        out.add(f)
                    }
                }
            }
        }
        return out
    }

    @Composable
    private fun App(app: VomBlattApp, dark: Boolean, onDark: (Boolean) -> Unit) {
        val scope = rememberCoroutineScope()
        var screen by remember { mutableStateOf<Screen>(Screen.Library) }
        var pieces by remember { mutableStateOf(app.library.list()) }
        var progress by remember { mutableStateOf<Progress?>(null) }
        var overlay by remember { mutableStateOf<Overlay?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }
        var pendingCapture by remember { mutableStateOf<File?>(null) }

        fun refresh() { pieces = app.library.list() }

        fun startRecognition(photos: List<File>) {
            if (photos.isEmpty()) return
            error = null; progress = null; overlay = null
            screen = Screen.Recognize(photos.first(), 1, photos.size)
            job?.cancel()
            job = scope.launch {
                try {
                    // Seite für Seite erkennen, dann zu einem Stück zusammenführen
                    // Seiten ohne Notenzeilen (Deckblatt, Text, leere Seite – v. a. in PDFs) werden übersprungen,
                    // solange mindestens eine Seite Noten hat
                    val results = ArrayList<Pair<File, com.nicedevelopments.vomblatt.recognize.Recognition>>()
                    val skipped = ArrayList<Int>()
                    var lastError: Throwable? = null
                    // Taktart am Ende der vorigen Seite: eine Seite ohne eigene Taktart am Anfang setzt sie fort
                    // (sonst schätzt homr sie aus den Taktlängen – Bohemian Seite 4/5: 2/4 und 6/4 statt 4/4 und 2/4)
                    var lastTime: String? = null
                    // Modelle zwischen den Seiten offen halten wurde gemessen und verworfen: +1,1 GB Grundlast (Arbeitsspeicher
                    // der Sitzungen), Spitze ~2,5 GB – dort hat Android die App schon einmal beendet
                    for ((i, photo) in photos.withIndex()) {
                        progress = null; overlay = null
                        screen = Screen.Recognize(photo, i + 1, photos.size)
                        val r = try {
                            withContext(Dispatchers.Default) {
                                app.recognizer.recognize(
                                    photo,
                                    onProgress = { p -> scope.launch(Dispatchers.Main) { progress = p } },
                                    onOverlay = { o -> scope.launch(Dispatchers.Main) { overlay = o } },
                                    inheritedTime = lastTime,
                                )
                            }
                        } catch (e: com.nicedevelopments.vomblatt.recognize.RecognitionException) {
                            if (photos.size == 1) throw e
                            android.util.Log.w("VomBlatt", "Seite ${i + 1} übersprungen", e)
                            skipped.add(i + 1); lastError = e
                            continue
                        }
                        results.add(photo to r)
                        Regex("""<beats>(\d+)</beats>\s*<beat-type>(\d+)</beat-type>""").findAll(r.musicXml).lastOrNull()
                            ?.let { lastTime = "${it.groupValues[1]}/${it.groupValues[2]}" }
                    }
                    app.recognizer.release()
                    if (results.isEmpty()) throw lastError ?: IllegalStateException("keine Seite")
                    if (skipped.isNotEmpty()) android.widget.Toast.makeText(
                        this@MainActivity,
                        resources.getQuantityString(R.plurals.pages_skipped, skipped.size, skipped.joinToString(", ")),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    val (photo, result) = withContext(Dispatchers.IO) {
                        com.nicedevelopments.vomblatt.data.PageMerge.merge(results, File(cacheDir, "capture").apply { mkdirs() })
                    }
                    val info = withContext(Dispatchers.IO) { app.library.create(
                        result.musicXml, photo,
                        result.title ?: getString(R.string.untitled_fmt, java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMM"), java.util.Locale.getDefault()).format(java.util.Date())),
                        tempo = result.tempo, overlayJson = result.overlayJson, pedalsJson = result.pedalsJson,
                        hairpinsJson = result.hairpinsJson, text = result.text,
                        notesPng = result.notesPng, notesScale = result.notesScale,
                    ) }
                    refresh()
                    screen = Screen.Piece(info)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable) { app.recognizer.release() }
                    throw e
                } catch (e: Throwable) {
                    runCatching { app.recognizer.release() }
                    android.util.Log.e("VomBlatt", "Erkennung", e)
                    error = if (e is com.nicedevelopments.vomblatt.recognize.RecognitionException) e.message
                    else getString(R.string.err_generic_short)
                }
            }
        }
        fun startRecognition(photo: File) = startRecognition(listOf(photo))
        var lastPhotos by remember { mutableStateOf<List<File>>(emptyList()) }

        fun newCaptureFile(): File = File(cacheDir, "capture").apply { mkdirs() }.let { File(it, "blatt_${System.currentTimeMillis()}.jpg") }

        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = pendingCapture
            if (ok && f != null && f.length() > 0) { lastPhotos = listOf(f); startRecognition(f) }
        }
        // Galerie: eine oder mehrere Seiten (in der gewählten Reihenfolge)
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { uris: List<Uri> ->
            val files = uris.mapNotNull { uri ->
                val f = File(cacheDir, "capture").apply { mkdirs() }.let { File(it, "blatt_${System.currentTimeMillis()}_${uris.indexOf(uri)}.jpg") }
                runCatching { contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }; f }.getOrNull()
            }
            // Mehrere Seiten auf einmal gehören zu Pro
            if (files.size > 1 && !app.pro.isPro.value) app.pro.require {}
            else if (files.isNotEmpty()) { lastPhotos = files; startRecognition(files) }
        }
        val openXml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) scope.launch {
                val info = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)!!.use { app.library.importStream(it, uri.lastPathSegment ?: "Import") }
                    }.getOrNull()
                }
                refresh()
                if (info != null) screen = Screen.Piece(info)
            }
        }

        // PDF: jede Seite als Bild rendern (weißer Grund, lange Seite 3300 px) und wie Fotos erkennen
        val openPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) scope.launch {
                val pages = withContext(Dispatchers.IO) {
                    runCatching { renderPdf(uri) }.onFailure { android.util.Log.w("VomBlatt", "PDF", it) }.getOrNull()
                }
                if (pages.isNullOrEmpty()) {
                    android.widget.Toast.makeText(this@MainActivity, getString(R.string.err_pdf), android.widget.Toast.LENGTH_LONG).show()
                } else { lastPhotos = pages; startRecognition(pages) }
            }
        }

        BackHandler(screen != Screen.Library) {
            if (screen is Screen.Recognize) job?.cancel()
            if (screen is Screen.Piece) app.engine.pause()
            refresh(); screen = Screen.Library
        }

        com.nicedevelopments.vomblatt.pro.ProSheet(app.pro, this@MainActivity)
        when (val sc = screen) {
            Screen.Library -> LibraryScreen(
                pieces = pieces,
                onOpen = { screen = Screen.Piece(it) },
                onCamera = { screen = Screen.Capture },
                onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onImportXml = { openXml.launch(arrayOf("application/vnd.recordare.musicxml+xml", "application/xml", "text/xml", "*/*")) },
                onImportPdf = { app.pro.require { openPdf.launch(arrayOf("application/pdf")) } },
                onPro = { app.pro.require {}; app.pro.sheet.value = true },
                onLicenses = { screen = Screen.Licenses },
                dark = dark,
                onDark = onDark,
            )
            // Notenbild, Klaviatur und Handknöpfe bleiben auch in Arabisch links → rechts (Musik läuft immer so).
            is Screen.Piece -> androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr,
            ) { PieceScreen(
                sc.info, app.library, app.engine,
                onBack = { refresh(); screen = Screen.Library },
                onDeleted = { refresh(); screen = Screen.Library },
            ) }
            is Screen.Recognize -> RecognizeScreen(
                sc.photo, progress, overlay, error,
                onBack = { job?.cancel(); screen = Screen.Library },
                onRetry = { startRecognition(lastPhotos.ifEmpty { listOf(sc.photo) }) },
                pageLabel = if (sc.pages > 1) stringResource(R.string.page_of, sc.page, sc.pages) else null,
            )
            Screen.Capture -> CaptureScreen(
                newFile = { newCaptureFile() },
                onDone = { files -> lastPhotos = files; startRecognition(files) },
                onCancel = { screen = Screen.Library },
            )
            Screen.Licenses -> LicensesScreen(onBack = { screen = Screen.Library })
        }
        // Nur Testfassung: Erkennung per adb starten (am start … --es pages "/pfad/a.jpg,/pfad/b.jpg")
        LaunchedEffect(Unit) {
            if (BuildConfig.DEBUG) intent?.getStringExtra("pages")?.split(",")?.map { File(it) }?.filter { it.exists() }
                ?.takeIf { it.isNotEmpty() }?.let { lastPhotos = it; startRecognition(it) }
        }
        val libVersion by app.libraryVersion.collectAsState()
        LaunchedEffect(libVersion) { refresh() }
    }
}
