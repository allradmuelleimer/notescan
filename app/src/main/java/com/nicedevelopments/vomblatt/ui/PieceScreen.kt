package com.nicedevelopments.vomblatt.ui

import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.BackHand
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nicedevelopments.vomblatt.audio.PianoEngine
import com.nicedevelopments.vomblatt.data.Library
import com.nicedevelopments.vomblatt.data.PieceInfo
import com.nicedevelopments.vomblatt.model.Chord
import com.nicedevelopments.vomblatt.model.NoteType
import com.nicedevelopments.vomblatt.model.Performance
import com.nicedevelopments.vomblatt.model.Score
import com.nicedevelopments.vomblatt.model.ScoreEdit
import com.nicedevelopments.vomblatt.model.TPQ
import com.nicedevelopments.vomblatt.ui.theme.LocalInk
import com.nicedevelopments.vomblatt.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nicedevelopments.vomblatt.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

@Composable
fun PieceScreen(
    initial: PieceInfo,
    library: Library,
    engine: PianoEngine,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ink = LocalInk.current
    val pro = com.nicedevelopments.vomblatt.pro.rememberPro()
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(initial) }
    var score by remember { mutableStateOf<Score?>(null) }
    LaunchedEffect(initial.id) { score = withContext(Dispatchers.IO) { library.loadScore(initial) } }
    val s = score ?: run { Box(Modifier.fillMaxSize().background(ink.bg)); return }

    val perf = remember(s) { Performance.build(s) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(perf) {
        engine.setPerformance(perf)
        // Erst die Töne der ersten Takte, dann sofort spielbereit; der Rest lädt weiter.
        val firstBars = perf.notes.takeWhile { it.tick < perf.tickOfMeasure(minOf(4, s.measures.size - 1)) + 1 }.map { it.midi }.toSet()
        withContext(Dispatchers.IO) { engine.bank.preload(firstBars) }
        ready = true
        withContext(Dispatchers.IO) { engine.bank.preload(perf.notes.map { it.midi }.toSet()) }
    }
    val playing by engine.playing.collectAsState()
    // Beim Mitlesen soll das Display nicht ausgehen
    val hostView = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(playing) {
        hostView.keepScreenOn = playing
        onDispose { hostView.keepScreenOn = false }
    }
    var pos by remember { mutableDoubleStateOf(0.0) }
    // Flüssige Anzeige: pro Bild mit Tempo-Geschwindigkeit fortschreiben, sanft an die Audio-Position
    // angleichen. Der Audio-Thread meldet seine Position nur schubweise – direkt angezeigt ruckelt es.
    LaunchedEffect(playing) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val target = engine.positionTick
            if (playing && last != 0L) {
                val predicted = pos + (now - last) / 1e9 * engine.bpm * TPQ / 60.0
                pos = if (kotlin.math.abs(target - predicted) > TPQ * 1.5) target // Sprung (Spulen, Schleife)
                // nur ganz sanft nachführen und nie rückwärts – sonst wackelt der Balken; nie dem Gehörten vorauslaufen
                // (beim Start ist der erste Ton erst nach der Ausgabeverzögerung zu hören)
                else maxOf(pos, minOf(predicted + (target - predicted) * 0.02, target + TPQ / 12.0))
            } else pos = target
            last = now
            if (!playing) break
        }
    }

    var tempo by remember(initial.id) { mutableIntStateOf(s.tempo) }
    LaunchedEffect(tempo) { engine.bpm = tempo.toDouble() }
    var right by remember { mutableStateOf(true) }
    var left by remember { mutableStateOf(true) }
    var metro by remember { mutableStateOf(false) }
    LaunchedEffect(right, left, metro) { engine.playRight = right; engine.playLeft = left; engine.metronome = metro }
    var loop by remember { mutableStateOf<IntRange?>(null) }
    var loopPick by remember { mutableStateOf<Int?>(null) } // -1: Anfang wählen, sonst gewählter Anfang
    LaunchedEffect(loop, perf) {
        engine.loop = loop?.let { r -> perf.tickOfMeasure(r.first)..perf.endOfMeasure(r.last) }
    }

    var view by remember { mutableIntStateOf(0) }
    // Bleistift-Notizen vom Foto: einmal laden, Ein/Aus wird gemerkt
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("vomblatt", android.content.Context.MODE_PRIVATE) }
    var showNotes by remember { mutableStateOf(prefs.getBoolean("notes", true)) }
    var showClean by remember { mutableStateOf(prefs.getBoolean("cleanPhoto", true)) }
    val notesLayer by produceState<Pair<android.graphics.Bitmap, List<com.nicedevelopments.vomblatt.model.SystemGeom>>?>(null, info.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!info.notes.exists() || !info.layout.exists()) return@runCatching null
                val b = android.graphics.BitmapFactory.decodeFile(info.notes.absolutePath) ?: return@runCatching null
                b to com.nicedevelopments.vomblatt.recognize.Overlay.parse(info.layout.readText()).systems()
            }.getOrNull()
        }
    }
    // Notenzeilen des Fotos (auch ohne Notiz-Ebene): Notenbild im Original-Layout
    val photoSystems by produceState<List<com.nicedevelopments.vomblatt.model.SystemGeom>?>(null, info.id) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (info.layout.exists()) com.nicedevelopments.vomblatt.recognize.Overlay.parse(info.layout.readText()).systems() else null }.getOrNull()
        }
    }
    var selected by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // Was klingt gerade?
    val sounding = remember(pos, perf) {
        val t = pos.toInt()
        perf.notes.filter { it.tick <= t && t < it.tick + it.length }
    }
    val activeChords = remember(sounding) { sounding.map { it.chordId }.toSet() }
    val located = perf.locate(pos.toInt())
    val suspicious = remember(s) { s.measures.indices.filter { s.measures[it].suspicious() }.toSet() }

    // Korrektur: Verlauf zum Rückgängigmachen; beim Ziehen mit gedrücktem Finger die Ausgangsfassung
    val history = remember { mutableStateListOf<Score>() }
    val future = remember { mutableStateListOf<Score>() } // zum Wiederholen nach Rückgängig
    var holdBase by remember { mutableStateOf<Score?>(null) }
    var holdChanged by remember { mutableStateOf(false) }
    var holdNote by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    // Notenwert für neu gesetzte Noten: zuletzt gewählter, sonst Viertel
    var insertTicks by remember { mutableStateOf(com.nicedevelopments.vomblatt.model.TPQ) }
    fun pushHistory(old: Score) { history.add(old); if (history.size > 80) history.removeAt(0); future.clear() }
    fun save(ns: Score) { scope.launch(Dispatchers.IO) { info = library.saveScore(info, ns) } }
    // Jede Änderung setzt auf dem neuesten Stand auf (nicht auf dem beim Zeichnen gemerkten – sonst gingen
    // Korrekturen verloren, Fable-Prüfung 19.11.)
    fun edit(f: (Score) -> Score) {
        val cur = score ?: return
        val ns = f(cur)
        if (ns == cur) return
        pushHistory(cur)
        score = ns
        save(ns)
    }
    fun preview(chordId: Long, noteIndex: Int, sc: Score = s) {
        sc.chordById(chordId)?.second?.notes?.getOrNull(noteIndex)?.let { engine.preview(it.pitch.midi) }
    }
    /** Änderung, nach der die Auswahl dem Ton folgen soll (Tonhöhe, Oktave, andere Hand …). */
    fun editFollow(f: (Score) -> Pair<Score, Pair<Long, Int>?>) {
        val cur = score ?: return
        val (ns, sel) = f(cur)
        if (sel == null || ns == cur) return
        pushHistory(cur); score = ns; save(ns)
        selected = sel; preview(sel.first, sel.second, ns)
    }
    fun followSelection(sc: Score) {
        val sel = selected ?: return
        val c = sc.chordById(sel.first)?.second
        selected = when {
            c == null -> null
            sel.second >= c.notes.size -> sel.first to (c.notes.size - 1)
            else -> sel
        }
    }
    // Nach Rückgängig/Wiederholen die geänderte Stelle wählen – das Notenbild rollt dorthin (Fable-Prüfung 2:
    // „Rückgängig ist blind“, wenn die Änderung außerhalb des Bildes lag)
    fun showChange(from: Score, to: Score) {
        val id = ScoreEdit.changedChord(from, to) ?: ScoreEdit.changedChord(to, from)?.takeIf { to.chordById(it) != null }
        // Die gewählte Note selbst geändert (Notenwert, Tonhöhe …)? Dann bleibt sie gewählt.
        selected?.let { sel ->
            val c = to.chordById(sel.first)?.second
            if (c != null && from.chordById(sel.first)?.second != c) { selected = sel.first to sel.second.coerceAtMost(c.notes.size - 1).coerceAtLeast(0); return }
        }
        if (id == null) { followSelection(to); return }
        val c = to.chordById(id)?.second ?: run { followSelection(to); return }
        val keep = selected?.takeIf { it.first == id }?.second
        selected = id to (keep?.coerceAtMost(c.notes.size - 1) ?: 0).coerceAtLeast(0)
    }
    fun redo() {
        val cur = score ?: return
        val next = future.removeLastOrNull() ?: return
        history.add(cur); score = next; save(next); showChange(cur, next)
    }
    fun undo() {
        val cur = score ?: return
        val prev = history.removeLastOrNull() ?: return
        future.add(cur)
        score = prev; save(prev); showChange(cur, prev)
    }


    // Querformat: Noten bekommen die Höhe, Bedienung in einer kompakten Zeile, ohne Klaviatur
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().background(ink.bg)) {
        // Kopf
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), { engine.pause(); onBack() })
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(s.title, style = Type.item, color = ink.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(keyLabel(s.measures.firstOrNull()?.fifths ?: 0) + " · " + pluralStringResource(R.plurals.measures, s.measures.size, s.measures.size), style = Type.small, color = ink.ink2)
            }
            if (info.hasPhoto) Segmented(listOf(stringResource(R.string.view_score), stringResource(R.string.view_photo)), view, { view = it })
            Box {
                IconAction(Icons.Outlined.MoreVert, stringResource(R.string.more_actions), { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename), style = Type.body) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.discard_edits), style = Type.body) }, onClick = {
                        menu = false; confirmDiscard = true
                    })
                    if (info.clean.exists()) DropdownMenuItem(
                        text = { Text(if (showClean) stringResource(R.string.show_original_photo) else stringResource(R.string.brighten_photo), style = Type.body) },
                        onClick = { menu = false; showClean = !showClean; prefs.edit().putBoolean("cleanPhoto", showClean).apply() },
                    )
                    if (notesLayer != null) DropdownMenuItem(
                        text = { Text(if (showNotes) stringResource(R.string.hide_annotations) else stringResource(R.string.show_annotations), style = Type.body) },
                        onClick = { menu = false; showNotes = !showNotes; prefs.edit().putBoolean("notes", showNotes).apply() },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_piece), style = Type.body, color = ink.warn) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        // Inhalt
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (view == 0) ScoreArea(s, activeChords, selected?.first, located, loop, suspicious, frac = (pos - kotlin.math.floor(pos)).toFloat(),
                notes = notesLayer?.let { (b, g) -> Triple(b, info.notesScale, g) }, showNotes = showNotes,
                photoSystems = photoSystems, playing = playing, selectedNote = selected?.second ?: -1,
                // Gedrückt halten: auf einer Note auswählen, an leerer Stelle eine neue Note setzen;
                // weiter nach oben/unten ziehen ändert die Tonhöhe (gespeichert beim Loslassen)
                onHold = { h ->
                    if (loopPick != null) return@ScoreArea
                    when (h) {
                        is Hold.Head -> {
                            selected = h.chordId to h.noteIndex
                            holdBase = score; holdNote = h.chordId to h.noteIndex; holdChanged = false
                            if (h.noteIndex >= 0) preview(h.chordId, h.noteIndex)
                        }
                        is Hold.Spot -> {
                            // neue Noten nur, wenn korrigiert wird (Panel offen) – sonst entstand beim zögerlichen
                            // Scrollen versehentlich eine Note (Fable-Prüfung 19.11.)
                            val sel0 = selected ?: return@ScoreArea
                            val cur = score ?: return@ScoreArea
                            val m = cur.measures.getOrNull(h.measure) ?: return@ScoreArea
                            val bottomLine = if (m.clefAt(h.staff, h.tick) == com.nicedevelopments.vomblatt.model.Clef.TREBLE) 30 else 18
                            // Notenwert wie die gerade bearbeitete Note (dort lässt er sich vorher wählen)
                            val want = cur.chordById(sel0.first)?.second?.duration ?: insertTicks
                            val (ns, sel) = ScoreEdit.insertAt(cur, h.measure, h.staff, h.tick, h.pos + bottomLine, want)
                            if (sel == null) return@ScoreArea
                            pushHistory(cur); score = ns
                            selected = sel; holdBase = ns; holdNote = sel; holdChanged = true
                            preview(sel.first, sel.second, ns)
                        }
                    }
                },
                onHoldMove = { steps ->
                    val base = holdBase ?: return@ScoreArea
                    val (id, ni) = holdNote ?: return@ScoreArea
                    if (ni < 0) return@ScoreArea
                    val (mi, c) = base.chordById(id) ?: return@ScoreArea
                    val from = c.notes.getOrNull(ni)?.pitch ?: return@ScoreArea
                    val to = from.shiftDiatonic(steps).let { p ->
                        if (steps == 0) from else p.copy(alter = com.nicedevelopments.vomblatt.model.keyAlter(base.measures[mi].fifths, p.step))
                    }
                    if (!holdChanged && steps != 0) { pushHistory(base); holdChanged = true }
                    val ns = if (steps == 0) base else ScoreEdit.step(base, id, ni, steps)
                    score = ns
                    val idx = ScoreEdit.indexOf(ns, id, to.midi)
                    if (idx >= 0) { selected = id to idx; preview(id, idx, ns) }
                },
                onHoldEnd = {
                    if (holdChanged) score?.let { save(it) }
                    holdBase = null; holdNote = null; holdChanged = false
                },
                onTapMeasure = { mi, tick ->
                    val lp = loopPick
                    when {
                        lp == -1 -> loopPick = mi
                        lp != null -> { loop = minOf(lp, mi)..maxOf(lp, mi); loopPick = null; engine.seek(perf.tickOfMeasure(minOf(lp, mi))) }
                        // Tippen: Abspielmarker genau an die getippte Stelle (bei Wiederholungen der nächstgelegene Durchgang)
                        else -> { selected = null; val t = perf.tickAt(mi, tick, pos.toInt()); engine.seek(t); pos = t.toDouble() }
                    }
                })
            else PhotoArea(info, s, activeChords, located, clean = showClean && info.clean.exists())
        }

        // Steuerung bzw. Korrektur
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(ink.surface)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = if (landscape) 4.dp else 14.dp)
        ) {
            val sel = selected
            AnimatedContent(sel != null, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "panel") { editing ->
                if (editing && sel != null) {
                    val chord = s.chordById(sel.first)?.second
                    if (chord == null) { selected = null; return@AnimatedContent }
                    EditPanel(
                        chord, sel.second,
                        onClose = { selected = null },
                        canUndo = history.isNotEmpty(), onUndo = { undo() },
                        canRedo = future.isNotEmpty(), onRedo = { redo() },
                        balance = ScoreEdit.balance(s.measures[s.chordById(sel.first)?.first ?: 0]),
                        // Tonhöhe: die Auswahl folgt dem Ton (im Akkord ändert sich sonst die Reihenfolge)
                        onStep = { d ->
                            editFollow { st ->
                                val (mi, c) = st.chordById(sel.first) ?: return@editFollow st to null
                                val p = c.notes.getOrNull(sel.second)?.pitch?.shiftDiatonic(d) ?: return@editFollow st to null
                                val to = p.copy(alter = com.nicedevelopments.vomblatt.model.keyAlter(st.measures[mi].fifths, p.step))
                                val ns = ScoreEdit.step(st, sel.first, sel.second, d)
                                ns to (sel.first to ScoreEdit.indexOf(ns, sel.first, to.midi))
                            }
                        },
                        onOctave = { d ->
                            editFollow { st ->
                                val to = st.chordById(sel.first)?.second?.notes?.getOrNull(sel.second)?.pitch?.shiftDiatonic(7 * d)
                                    ?: return@editFollow st to null
                                val ns = ScoreEdit.octave(st, sel.first, sel.second, d)
                                ns to (sel.first to ScoreEdit.indexOf(ns, sel.first, to.midi))
                            }
                        },
                        onAlter = { a -> edit { ScoreEdit.setAlter(it, sel.first, sel.second, a) }; preview(sel.first, sel.second, score!!) },
                        onDuration = { t, d ->
                            insertTicks = com.nicedevelopments.vomblatt.model.dotted(t.ticks, d)
                            edit { ScoreEdit.setDuration(it, sel.first, t, d) }
                        },
                        onMove = { dir -> edit { ScoreEdit.swapWithNeighbor(it, sel.first, dir) } },
                        onOtherHand = { editFollow { ScoreEdit.moveToStaff(it, sel.first, sel.second) } },
                        onDelete = {
                            edit { ScoreEdit.deleteNote(it, sel.first, sel.second) }
                            // Panel bleibt offen (auch wenn eine Pause daraus wird) – sonst wäre Rückgängig weg
                            selected = sel.first to 0
                        },
                        onAdd = {
                            edit { ScoreEdit.addNote(it, sel.first) }
                            val c2 = score!!.chordById(sel.first)?.second
                            if (c2 != null) { val ni = c2.notes.indices.maxBy { c2.notes[it].pitch.diatonic }; selected = sel.first to ni; preview(sel.first, ni, score!!) }
                        },
                        onAccent = { edit { ScoreEdit.toggleAccent(it, sel.first) } },
                        pedalHere = s.chordById(sel.first)?.let { (mi, c) -> s.pedals.firstOrNull { it.measure == mi && it.tick == c.start }?.type },
                        onPedal = { press ->
                            edit { st -> st.chordById(sel.first)?.let { (mi, c) -> ScoreEdit.togglePedal(st, mi, c.start, press) } ?: st }
                        },
                        onTie = { edit { ScoreEdit.toggleTie(it, sel.first, sel.second) } },
                    )
                } else if (landscape) {
                    Column {
                        if (loopPick != null) Text(
                            if (loopPick == -1) stringResource(R.string.loop_pick_first) else stringResource(R.string.loop_pick_last),
                            style = Type.label, color = ink.accent,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HandToggle(left = true, on = left, color = ink.leftHand) { pro.require { if (right || !left) left = !left } }
                            IconAction(Icons.Outlined.SkipPrevious, stringResource(R.string.to_start), { engine.seek(loop?.let { perf.tickOfMeasure(it.first) } ?: 0); pos = 0.0 }, size = 40.dp)
                            Timeline(pos, perf.totalTicks, Modifier.weight(1f)) { t -> engine.seek(t); pos = t.toDouble() }
                            TempoStepper(tempo, sheetTempo = s.tempo) { t -> if (t != tempo) pro.require { tempo = t } }
                            ToggleTile(Icons.Outlined.Repeat, stringResource(R.string.loop), loop != null || loopPick != null, {
                                if (loop != null || loopPick != null) { loop = null; loopPick = null } else pro.require { loopPick = -1 }
                            })
                            PlayButton(playing, ready) { if (playing) engine.pause() else engine.play() }
                            ToggleTile(Icons.Outlined.Timer, stringResource(R.string.metronome), metro, { pro.require { metro = !metro } })
                            HandToggle(left = false, on = right, color = ink.accent) { pro.require { if (left || !right) right = !right } }
                        }
                    }
                } else {
                    Column {
                        KeyboardStrip(
                            low = (perf.notes.minOfOrNull { it.midi } ?: 48) - 2,
                            high = (perf.notes.maxOfOrNull { it.midi } ?: 72) + 2,
                            right = sounding.filter { it.staff == 0 }.map { it.midi }.toSet(),
                            left = sounding.filter { it.staff == 1 }.map { it.midi }.toSet(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconAction(Icons.Outlined.SkipPrevious, stringResource(R.string.to_start), { engine.seek(loop?.let { perf.tickOfMeasure(it.first) } ?: 0); pos = 0.0 }, size = 40.dp)
                            Timeline(pos, perf.totalTicks, Modifier.weight(1f)) { t -> engine.seek(t); pos = t.toDouble() }
                            TempoStepper(tempo, sheetTempo = s.tempo) { t -> if (t != tempo) pro.require { tempo = t } }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (loopPick != null) {
                            Text(
                                if (loopPick == -1) stringResource(R.string.loop_pick_first) else stringResource(R.string.loop_pick_last),
                                style = Type.label, color = ink.accent, modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            // Wie am Klavier: linke Hand links, rechte Hand rechts – Farben wie auf der Klaviatur.
                            HandToggle(left = true, on = left, color = ink.leftHand) { pro.require { if (right || !left) left = !left } }
                            ToggleTile(Icons.Outlined.Repeat, stringResource(R.string.loop), loop != null || loopPick != null, {
                                if (loop != null || loopPick != null) { loop = null; loopPick = null } else pro.require { loopPick = -1 }
                            })
                            PlayButton(playing, ready) { if (playing) engine.pause() else engine.play() }
                            ToggleTile(Icons.Outlined.Timer, stringResource(R.string.metronome), metro, { pro.require { metro = !metro } })
                            HandToggle(left = false, on = right, color = ink.accent) { pro.require { if (left || !right) right = !right } }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename_title), style = Type.item) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, textStyle = Type.body) },
            confirmButton = { TextButton({ renaming = false; edit { it.copy(title = text.trim().ifEmpty { it.title }) } }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton({ renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_edits) + "?", style = Type.item) },
            text = { Text(stringResource(R.string.discard_body), style = Type.body) },
            confirmButton = { TextButton({
                confirmDiscard = false; selected = null; history.clear(); future.clear()
                scope.launch(Dispatchers.IO) { library.resetEdits(info); score = library.loadScore(info) }
            }) { Text(stringResource(R.string.discard), color = ink.warn) } },
            dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(R.string.keep)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_title, s.title), style = Type.item) },
            text = { Text(stringResource(R.string.delete_body), style = Type.body) },
            confirmButton = { TextButton({ confirmDelete = false; engine.pause(); library.delete(info); onDeleted() }) { Text(stringResource(R.string.delete), color = ink.warn) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.keep)) } },
        )
    }
}

@Composable
private fun PlayButton(playing: Boolean, ready: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Box(
        Modifier.size(64.dp).clip(CircleShape).background(if (ready) ink.accent else ink.raised).clickable(enabled = ready, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            if (playing) stringResource(R.string.pause) else stringResource(R.string.play),
            tint = if (ready) ink.onAccent else ink.ink3, modifier = Modifier.size(34.dp),
        )
    }
}

/**
 * Hand an/aus. Zeigt den Handrücken so, wie man ihn beim Spielen von oben sieht:
 * linke Hand mit dem Daumen nach rechts, rechte Hand mit dem Daumen nach links.
 */
@Composable
private fun HandToggle(left: Boolean, on: Boolean, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val ink = LocalInk.current
    val name = if (left) stringResource(R.string.left_hand) else stringResource(R.string.right_hand)
    val muteLabel = stringResource(R.string.hand_mute, name)
    val unmuteLabel = stringResource(R.string.hand_unmute, name)
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) color.copy(alpha = 0.16f) else ink.bg.copy(alpha = 0f))
            .clickable(onClickLabel = if (on) muteLabel else unmuteLabel, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.BackHand, contentDescription = name,
                tint = if (on) color else ink.ink3,
                modifier = Modifier.size(24.dp).graphicsLayer { if (left) scaleX = -1f },
            )
            if (!on) Canvas(Modifier.size(24.dp)) {
                drawLine(ink.ink3, Offset(size.width * 0.12f, size.height * 0.12f), Offset(size.width * 0.88f, size.height * 0.88f), 2.dp.toPx())
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(if (on) (if (left) stringResource(R.string.hand_left_short) else stringResource(R.string.hand_right_short)) else stringResource(R.string.hand_muted), style = Type.small, color = if (on) color else ink.ink3, maxLines = 1)
    }
}

/** Zeitleiste: zeigt, wo man im Stück ist; Ziehen springt an die Stelle. */
@Composable
private fun Timeline(pos: Double, total: Int, modifier: Modifier = Modifier, onSeek: (Int) -> Unit) {
    val ink = LocalInk.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val value = dragging ?: if (total > 0) (pos / total).toFloat().coerceIn(0f, 1f) else 0f
    Slider(
        value = value,
        onValueChange = { dragging = it },
        onValueChangeFinished = { dragging?.let { onSeek((it * total).toInt()) }; dragging = null },
        modifier = modifier,
        colors = SliderDefaults.colors(thumbColor = ink.ink, activeTrackColor = ink.ink, inactiveTrackColor = ink.line),
    )
}

/** Tempo als Zahl: − / +, lang drücken in Zehnerschritten, Tippen auf die Zahl = Tempo vom Blatt. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TempoStepper(tempo: Int, sheetTempo: Int, onChange: (Int) -> Unit) {
    val ink = LocalInk.current
    fun set(v: Int) = onChange(v.coerceIn(20, 240))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(ink.raised)
                .combinedClickable(onClickLabel = stringResource(R.string.slower), onLongClick = { set(tempo - 10) }) { set(tempo - 1) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Remove, stringResource(R.string.slower), tint = ink.ink, modifier = Modifier.size(18.dp)) }
        Column(
            Modifier.width(58.dp).clickable(onClickLabel = stringResource(R.string.tempo_from_sheet)) { set(sheetTempo) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$tempo", style = Type.number, color = if (tempo == sheetTempo) ink.ink else ink.accent)
            Text("♩ / min", style = Type.small, color = ink.ink3)
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(ink.raised)
                .combinedClickable(onClickLabel = stringResource(R.string.faster), onLongClick = { set(tempo + 10) }) { set(tempo + 1) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, stringResource(R.string.faster), tint = ink.ink, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun ScoreArea(
    s: Score, active: Set<Long>, selected: Long?, located: Pair<Int, Int>?, loop: IntRange?, suspicious: Set<Int>,
    /** Bruchteil des laufenden Ticks: der Zeitbalken läuft stufenlos statt in Tick-Schritten. */
    frac: Float = 0f,
    onHold: (Hold) -> Unit, onTapMeasure: (Int, Int) -> Unit,
    onHoldMove: (Int) -> Unit = {}, onHoldEnd: () -> Unit = {},
    selectedNote: Int = -1,
    notes: Triple<android.graphics.Bitmap, Float, List<com.nicedevelopments.vomblatt.model.SystemGeom>>? = null,
    showNotes: Boolean = true,
    /** Notenzeilen des Fotos: Notenbild im Original-Layout. */
    photoSystems: List<com.nicedevelopments.vomblatt.model.SystemGeom>? = null,
    playing: Boolean = false,
) {
    val font = rememberMusicFont()
    val density = LocalDensity.current.density
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val uiFont = remember { androidx.core.content.res.ResourcesCompat.getFont(ctx, com.nicedevelopments.vomblatt.R.font.instrument_sans) }
        // Notizen als Ganzes; der Notensatz bricht nie mitten durch eine Notiz um (auch wenn sie ausgeblendet ist –
        // sonst springen die Zeilen beim Ein-/Ausblenden)
        val marks = remember(notes, s) {
            notes?.let { (b, sc, g) -> runCatching { noteMarks(b, sc, g, s) }.onFailure { android.util.Log.w("VomBlatt", "Notizen", it) }.getOrDefault(emptyList()) } ?: emptyList()
        }
        val keep = remember(marks) { keepTogether(marks) }
        val dynamicLines = remember { ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).getInt("lineMode", 0) == 1 }
        val layout = remember(s, w, keep, photoSystems) { engrave(s, w, density, font, uiFont, keep, photoSystems, dynamicLines) }
        val cursor = located?.let { (mi, t) -> cursorAt(layout, mi, t + frac) }
        val sysIndex = cursor?.let { c -> layout.measures.firstOrNull { it.index == c.measure }?.system }
        // Mitscrollen beim Abspielen, gleichmäßig nach der Zeit: Fortschritt p = Zeile + Anteil der gespielten Zeit dieser
        // Zeile. Die vorige Zeile verlässt das Bild erst, wenn die laufende zu Ende gespielt ist: bei p = k + 1 steht
        // die Unterkante von Zeile k−1 am oberen Rand; dazwischen linear.
        val vp = constraints.maxHeight
        val progress = located?.let { (mi, t) ->
            val line = layout.measures.firstOrNull { it.index == mi }?.system ?: return@let null
            val inLine = layout.measures.filter { it.system == line }.map { it.index }.sorted()
            val lens = inLine.map { s.measures.getOrNull(it)?.length?.coerceAtLeast(1) ?: 1 }
            val before = inLine.takeWhile { it < mi }.indices.sumOf { lens[it] }
            val total = lens.sum().coerceAtLeast(1)
            line + ((before + t + frac) / total).coerceIn(0f, 1f)
        }
        // beim Start der Wiedergabe von der aktuellen Ansicht aus weich zur Zielposition überblenden (0,8 s)
        var playStart by remember { mutableStateOf(0L to 0) }
        LaunchedEffect(playing) { if (playing) playStart = System.nanoTime() to scroll.value }
        LaunchedEffect(progress, playing) {
            if (!playing || progress == null || layout.systems.isEmpty()) return@LaunchedEffect
            val maxS = scroll.maxValue.toFloat()
            fun anchor(j: Int): Float = (if (j <= 0) 0f else layout.systems[minOf(j - 1, layout.systems.size - 1)].bottom).coerceAtMost(maxS)
            val q = progress - 1f
            val j = kotlin.math.floor(q).toInt()
            val f = q - j
            // linear zwischen den Zeilenpunkten; an jedem Punkt ein sanfter Übergang von der alten zur neuen Geschwindigkeit
            // (±0,3 Zeilen). Die Geschwindigkeit wechselt darin gleichmäßig, liegt also immer zwischen alter und neuer –
            // aus dem Stand: früh und ganz langsam anlaufen, nie schneller als danach; am Ende ebenso auslaufen
            val r = 0.12f
            val qq = q - r // ganzer Übergang nach dem Zeilenende: nichts bewegt sich, bevor die erste Zeile durch ist
            val k = kotlin.math.round(qq).toInt()
            val jj = kotlin.math.floor(qq).toInt(); val ff = qq - jj
            var y = if (kotlin.math.abs(qq - k) < r) {
                val va = anchor(k) - anchor(k - 1); val vb = anchor(k + 1) - anchor(k)
                val u = qq - (k - r)
                anchor(k) + va * (qq - k) + (vb - va) * u * u / (4 * r)
            } else anchor(jj) + (anchor(jj + 1) - anchor(jj)) * ff
            // die laufende Zeile muss ganz zu sehen sein (Querformat, kleine Bildschirme)
            val cur = layout.systems.getOrNull(kotlin.math.floor(progress).toInt().coerceIn(0, layout.systems.size - 1))
            if (cur != null) y = maxOf(y, cur.bottom - vp * 0.95f)
            val blendT = ((System.nanoTime() - playStart.first) / 8e8).toFloat().coerceIn(0f, 1f)
            val e = blendT * blendT * (3 - 2 * blendT)
            if (e < 1f) y = playStart.second + (y - playStart.second) * e
            val target = y.toInt().coerceIn(0, scroll.maxValue)
            if (target != scroll.value) scroll.scrollTo(target)
        }
        // Korrektur: die gewählte Note muss über dem Panel zu sehen sein
        LaunchedEffect(selected, selectedNote, layout, vp) {
            if (playing || selected == null) return@LaunchedEffect
            val h = layout.heads.firstOrNull { it.chordId == selected && (selectedNote < 0 || it.noteIndex == selectedNote) } ?: return@LaunchedEffect
            val top = scroll.value; val margin = vp * 0.12f
            if (h.y < top + margin || h.y > top + vp - margin)
                scroll.animateScrollTo((h.y - vp * 0.4f).toInt().coerceIn(0, scroll.maxValue))
        }
        // angehalten: nur nachziehen, wenn der Balken aus dem Bild geraten ist (z. B. nach Spulen)
        LaunchedEffect(sysIndex, playing) {
            if (playing || sysIndex == null) return@LaunchedEffect
            val sb = layout.systems[sysIndex]
            if (sb.top < scroll.value || sb.bottom > scroll.value + vp * 0.85f)
                scroll.animateScrollTo((sb.top - vp * 0.12f).toInt().coerceAtLeast(0))
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            val pieces = remember(layout, marks, showNotes) {
                if (!showNotes) emptyList() else notes?.let { (_, _, g) -> runCatching { notesPieces(marks, g, s, layout) }.onFailure { android.util.Log.w("VomBlatt", "Notizen", it) }.getOrDefault(emptyList()) } ?: emptyList()
            }
            ScoreCanvas(layout, font, active, selected, cursor, loop, suspicious, onHold, onTapMeasure, notes = pieces, onHoldMove = onHoldMove, onHoldEnd = onHoldEnd, selectedNote = selectedNote)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PhotoArea(info: PieceInfo, s: Score, active: Set<Long>, located: Pair<Int, Int>?, clean: Boolean = false) {
    val ink = LocalInk.current
    val bmp by produceState<ImageBitmap?>(null, info.id, clean) {
        value = withContext(Dispatchers.IO) { runCatching { Library.decodePhoto(if (clean) info.clean else info.photo, 2400).asImageBitmap() }.getOrNull() }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { z, pan, _ ->
        zoom = (zoom * z).coerceIn(1f, 5f)
        offset = if (zoom == 1f) Offset.Zero else offset + pan
    }
    val boxes = remember(info.id) {
        runCatching { com.nicedevelopments.vomblatt.recognize.Overlay.parse(info.layout.readText()).staffBoxes }.getOrDefault(emptyList())
    }
    val photoCursor = located?.let { (mi, t) -> photoCursor(s, mi, t, boxes, info.sourceW) }
    val points = remember(s) {
        s.measures.flatMap { m -> m.chords.flatMap { c -> c.notes.mapNotNull { n -> if (n.imgX != null && n.imgY != null) Triple(c.id, n.imgX, n.imgY) else null } } }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp)).transformable(state)) {
        val b = bmp ?: return@BoxWithConstraints
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y }) {
            Image(b, stringResource(R.string.original_photo), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                // Bildposition innerhalb der Fläche (ContentScale.Fit)
                val scale = minOf(size.width / b.width, size.height / b.height)
                val dx = (size.width - b.width * scale) / 2; val dy = (size.height - b.height * scale) / 2
                val k = if (info.sourceW > 0) b.width.toFloat() / info.sourceW else 1f
                photoCursor?.let { c ->
                    val top = Offset(dx + c.x * k * scale, dy + c.top * k * scale)
                    val bottom = Offset(dx + c.x * k * scale, dy + c.bottom * k * scale)
                    drawLine(ink.accent.copy(alpha = 0.18f), top, bottom, 10.dp.toPx() / zoom)
                    drawLine(ink.accent, top, bottom, 2.5.dp.toPx() / zoom)
                }
                for ((id, x, y) in points) if (id in active) {
                    val c = Offset(dx + x * k * scale, dy + y * k * scale)
                    drawCircle(ink.accent.copy(alpha = 0.28f), 16.dp.toPx() / zoom, c)
                    drawCircle(ink.accent, 6.dp.toPx() / zoom, c)
                }
            }
        }
    }
}

private class PhotoCursor(val x: Float, val top: Float, val bottom: Float)

/**
 * Zeitbalken im Foto: zwischen den (ausgerichteten) Notenköpfen des Takts nach Zeit interpoliert,
 * senkrecht über das ganze Klaviersystem (Rahmen aus der Erkennung, sonst aus den Noten geschätzt).
 */
private fun photoCursor(s: Score, mi: Int, tick: Int, boxes: List<android.graphics.RectF>, width: Int): PhotoCursor? {
    if (width <= 0) return null
    fun anchors(m: Int): List<Triple<Int, Float, Float>> = s.measures.getOrNull(m)?.chords
        ?.filter { c -> c.notes.any { it.imgX != null } }
        ?.groupBy { it.start }
        ?.map { (start, cs) ->
            val ns = cs.flatMap { it.notes }.filter { it.imgX != null && it.imgY != null }
            Triple(start, ns.minOf { it.imgX!! }, ns.map { it.imgY!! }.average().toFloat())
        }?.sortedBy { it.first } ?: emptyList()
    val a = anchors(mi)
    if (a.isEmpty()) return null
    val m = s.measures[mi]
    val next = anchors(mi + 1).firstOrNull()
    // Taktende: erste Note des nächsten Takts, wenn sie in derselben Zeile steht, sonst geschätzt
    val sameRow = next != null && kotlin.math.abs(next.third - a.first().third) < width * 0.04f && next.second > a.last().second
    val step = if (a.size > 1) (a.last().second - a.first().second) / (a.size - 1) else width * 0.05f
    val endX = if (sameRow) next!!.second else a.last().second + step
    val pts = a.map { it.first to it.second } + (m.length to endX)
    var x = pts.first().second
    if (tick > pts.first().first) {
        for (i in 0 until pts.size - 1) {
            val (t0, x0) = pts[i]
            val (t1, x1) = pts[i + 1]
            if (tick in t0..t1) { x = if (t1 == t0) x0 else x0 + (x1 - x0) * (tick - t0) / (t1 - t0).toFloat(); break }
            x = x1
        }
    }
    val y = a.map { it.third }.average().toFloat()
    val box = boxes.firstOrNull { y >= it.top - width * 0.02f && y <= it.bottom + width * 0.02f }
    val ys = m.chords.flatMap { c -> c.notes.mapNotNull { it.imgY } }
    val top = box?.top ?: ((ys.minOrNull() ?: y) - width * 0.03f)
    val bottom = box?.bottom ?: ((ys.maxOrNull() ?: y) + width * 0.03f)
    return PhotoCursor(x - width * 0.006f, top - width * 0.01f, bottom + width * 0.01f)
}

@Composable
private fun EditPanel(
    chord: Chord, noteIndex: Int,
    onClose: () -> Unit, onStep: (Int) -> Unit, onAlter: (Int) -> Unit, onDuration: (NoteType, Int) -> Unit,
    onDelete: () -> Unit, onAdd: () -> Unit, onAccent: () -> Unit, onTie: () -> Unit,
    pedalHere: com.nicedevelopments.vomblatt.model.PedalType? = null, onPedal: (Boolean) -> Unit = {},
    canUndo: Boolean = false, onUndo: () -> Unit = {},
    canRedo: Boolean = false, onRedo: () -> Unit = {},
    /** Was im Takt nicht aufgeht: (System, Ticks), positiv = fehlt. */
    balance: List<Pair<Int, Int>> = emptyList(),
    onOctave: (Int) -> Unit = {}, onMove: (Int) -> Unit = {}, onOtherHand: () -> Unit = {},
) {
    val ink = LocalInk.current
    val note = chord.notes.getOrNull(noteIndex)
    // Panel wächst, schrumpft aber nicht: sonst springen Rückgängig & Co. auf dem Bildschirm, sobald Bilanzzeile oder
    // Umbruch wechseln, und der zweite Tipp trifft einen anderen Knopf (Fable-Prüfung 3)
    var tallest by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    Column(
        Modifier
            .heightIn(min = with(density) { tallest.toDp() })
            .onSizeChanged { if (it.height > tallest) tallest = it.height },
    ) {
        // Knöpfe oben fest – bricht der Text um, wandert Rückgängig nicht mit
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (note == null) stringResource(R.string.rest) else pitchLabel(note.pitch),
                    style = Type.title, color = ink.ink,
                )
                Text(
                    noteTypeLabel(chord.type, chord.dots > 0) +
                        (if (chord.notes.size > 1) " · " + pluralStringResource(R.plurals.chord_notes, chord.notes.size, chord.notes.size) else "") +
                        " · " + (if (chord.staff == 0) stringResource(R.string.right_hand_lower) else stringResource(R.string.left_hand_lower)),
                    style = Type.label, color = ink.ink2,
                )
            }
            IconAction(
                Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.undo), { if (canUndo) onUndo() },
                Modifier.alpha(if (canUndo) 1f else 0.35f),
            )
            IconAction(
                Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.redo), { if (canRedo) onRedo() },
                Modifier.alpha(if (canRedo) 1f else 0.35f),
            )
            IconAction(Icons.Outlined.Close, stringResource(R.string.done), onClose, filled = true)
        }
        Spacer(Modifier.height(10.dp))
        // Tonhöhe: Stammtonschritt, Oktave, Vorzeichen (auch durch Ziehen mit gedrücktem Finger im Notenbild)
        if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconAction(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.step_up), { onStep(1) }, size = 44.dp, filled = true)
                IconAction(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.step_down), { onStep(-1) }, size = 44.dp, filled = true)
                IconAction(Icons.Outlined.KeyboardDoubleArrowUp, stringResource(R.string.octave_up), { onOctave(1) }, size = 44.dp, filled = true)
                IconAction(Icons.Outlined.KeyboardDoubleArrowDown, stringResource(R.string.octave_down), { onOctave(-1) }, size = 44.dp, filled = true)
                Spacer(Modifier.weight(1f))
                SymbolPill("", stringResource(R.string.acc_flat), note.pitch.alter == -1, { onAlter(-1) }, Modifier.width(44.dp))
                SymbolPill("", stringResource(R.string.acc_natural), note.pitch.alter == 0, { onAlter(0) }, Modifier.width(44.dp))
                SymbolPill("", stringResource(R.string.acc_sharp), note.pitch.alter == 1, { onAlter(1) }, Modifier.width(44.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(NoteType.WHOLE to "", NoteType.HALF to "", NoteType.QUARTER to "", NoteType.EIGHTH to "", NoteType.SIXTEENTH to "").forEach { (t, sym) ->
                SymbolPill(sym, noteTypeLabel(t, false), chord.type == t, { onDuration(t, chord.dots) }, Modifier.weight(1f))
            }
            SymbolPill("", stringResource(R.string.dotted), chord.dots > 0, { onDuration(chord.type, if (chord.dots > 0) 0 else 1) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        // Zeit und Hand: mit der Nachbarnote tauschen, ins andere System
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            IconAction(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.move_earlier), { onMove(-1) }, size = 44.dp, filled = true)
            IconAction(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.move_later), { onMove(1) }, size = 44.dp, filled = true)
            if (note != null) SecondaryButton(stringResource(R.string.other_hand), Icons.Outlined.SwapVert, onOtherHand, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        // Gegenstücke nebeneinander: Ton dazu / Ton weg
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(if (note == null) stringResource(R.string.insert_note) else stringResource(R.string.add_note), Icons.Outlined.Add, onAdd, Modifier.weight(1f))
            if (note != null) SecondaryButton(
                if (chord.notes.size > 1) stringResource(R.string.remove_note) else stringResource(R.string.make_rest), Icons.Outlined.Remove, onDelete, Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Zeichen und Haltepedal an dieser Stelle (Pedal gilt für beide Hände) – eine Zeile, notfalls zum Wischen
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            if (note != null) {
                Pill(stringResource(R.string.accent), chord.accent, onAccent)
                Pill(stringResource(R.string.tie), note.tieStart, onTie)
            }
            Pill(
                stringResource(R.string.pedal_down),
                pedalHere == com.nicedevelopments.vomblatt.model.PedalType.DOWN ||
                    pedalHere == com.nicedevelopments.vomblatt.model.PedalType.CHANGE,
                { onPedal(true) },
            )
            Pill(stringResource(R.string.pedal_up), pedalHere == com.nicedevelopments.vomblatt.model.PedalType.UP, { onPedal(false) })
        }
        // Warum der rote Punkt? Was im Takt fehlt bzw. zu viel ist, je Hand
        for ((staff, ticks) in balance) {
            val hand = if (staff == 0) stringResource(R.string.right_hand_lower) else stringResource(R.string.left_hand_lower)
            val amount = ScoreEdit.pieces(kotlin.math.abs(ticks)).map { (t, d) -> noteTypeLabel(t, d > 0) }.joinToString(" + ")
            Spacer(Modifier.height(6.dp))
            Text(
                if (ticks > 0) stringResource(R.string.measure_missing, hand, amount) else stringResource(R.string.measure_extra, hand, amount),
                style = Type.label, color = ink.warn,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.edit_hint), style = Type.small, color = ink.ink3)
    }
}
