package com.nicedevelopments.vomblatt.model

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Liest MusicXML (partwise) in das App-Modell. Gedacht für Klaviersatz mit ein oder zwei Systemen;
 * weitere Stimmen (Parts) werden ignoriert. Versteht auch die `imgpos`-Kommentare von homr.
 */
object MusicXml {

    fun parse(input: InputStream, fallbackTitle: String = "Ohne Titel"): Score {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        // Externe DTDs nicht laden (MusicXML-Dateien verweisen oft darauf); Android kennt die Option nicht immer.
        runCatching { f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val doc = f.newDocumentBuilder().parse(input)
        val root = doc.documentElement
        val title = root.child("work")?.child("work-title")?.text()?.takeIf { it.isNotBlank() }
            ?: root.child("movement-title")?.text()?.takeIf { it.isNotBlank() }
            ?: fallbackTitle

        val part = root.children("part").firstOrNull() ?: return Score(title, 100, emptyList())

        var divisions = 1
        var beats = 4
        var beatType = 4
        var fifths = 0
        val clefs = arrayOf(Clef.TREBLE, Clef.BASS)
        var staves = 2
        var tempo = 0
        var nextId = 1L

        val measures = mutableListOf<Measure>()
        val pedals = mutableListOf<PedalMark>()
        val dynamics = mutableListOf<DynamicMark>()
        val slurs = mutableListOf<Slur>()
        val openSlurs = HashMap<String, Long>()
        val pendingGraces = HashMap<Int, MutableList<Pitch>>()
        val ottava = IntArray(2)
        var volta = 0
        val words = mutableListOf<WordMark>()
        val chordSymbols = mutableListOf<ChordSymbol>()
        val hairpins = mutableListOf<Hairpin>()
        val openWedges = HashMap<String, Triple<Int, Int, Boolean>>()
        for (mEl in part.children("measure")) {
            var cursor = 0
            var lastStart = 0
            var repeatStart = false
            var repeatEnd = false
            var finalBar = false
            var clefsAtStart: List<Clef>? = null
            val clefChanges = ArrayList<ClefChange>()
            var measureVolta = volta
            var voltaClosed = false
            // Zwischenspeicher: (staff, voice, start) -> Akkord im Aufbau
            data class Building(
                val staff: Int, val voice: Int, val start: Int, val type: NoteType, val dots: Int,
                val duration: Int, val notes: MutableList<Note>, var accent: Boolean, var staccato: Boolean,
                val beam: Beam, val rest: Boolean, val measureRest: Boolean,
                val id: Long, val orn: MutableSet<Orn>, val tuplet: Int, val graces: List<Pitch>, val ottava: Int,
            )
            val built = mutableListOf<Building>()
            var current: Building? = null

            for (n in mEl.childNodesList()) {
                if (n !is Element) continue
                when (n.tagName) {
                    "attributes" -> {
                        n.child("divisions")?.text()?.toIntOrNull()?.let { divisions = it }
                        n.child("staves")?.text()?.toIntOrNull()?.let { staves = it }
                        n.child("key")?.child("fifths")?.text()?.toIntOrNull()?.let { fifths = it }
                        n.child("time")?.let { t ->
                            t.child("beats")?.text()?.trim()?.toIntOrNull()?.let { beats = it }
                            t.child("beat-type")?.text()?.trim()?.toIntOrNull()?.let { beatType = it }
                        }
                        for (c in n.children("clef")) {
                            val num = (c.getAttribute("number").toIntOrNull() ?: 1) - 1
                            if (num !in 0..1) continue
                            val clef = if (c.child("sign")?.text() == "F") Clef.BASS else Clef.TREBLE
                            if (cursor == 0) clefs[num] = clef
                            else {
                                // Schlüsselwechsel mitten im Takt: ab hier im Takt, danach als Schlüssel der Folgetakte
                                clefsAtStart = clefsAtStart ?: clefs.toList()
                                clefs[num] = clef
                                clefChanges.add(ClefChange(cursor, num, clef))
                            }
                        }
                    }
                    "direction" -> {
                        n.descendant("pedal")?.let { pd ->
                            val off = ticks(n.child("offset")?.text(), divisions)
                            val type = when (pd.getAttribute("type")) {
                                "start" -> PedalType.DOWN; "change" -> PedalType.CHANGE
                                "stop" -> PedalType.UP; else -> null
                            }
                            if (type != null) pedals.add(PedalMark(measures.size, (cursor + off).coerceAtLeast(0), type))
                        }
                        val dirStaff = ((n.child("staff")?.text()?.trim()?.toIntOrNull() ?: 1) - 1).coerceIn(0, 1)
                        val dirTick = (cursor + ticks(n.child("offset")?.text(), divisions)).coerceAtLeast(0)
                        for (dt in n.children("direction-type")) {
                            dt.child("dynamics")?.let { dyn ->
                                for (d in dyn.childNodesList().filterIsInstance<Element>()) {
                                    val text = if (d.tagName == "other-dynamics") d.text().trim() else d.tagName
                                    if (text.isNotEmpty()) dynamics.add(DynamicMark(measures.size, dirTick, dirStaff, text))
                                }
                            }
                            for (wEl in dt.children("words")) {
                                val text = wEl.text().trim()
                                WordKind.of(text)?.let { words.add(WordMark(measures.size, dirTick, dirStaff, text, it)) }
                            }
                            dt.child("rehearsal")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                words.add(WordMark(measures.size, dirTick, 0, it, WordKind.REHEARSAL))
                            }
                            if (dt.child("segno") != null) words.add(WordMark(measures.size, dirTick, 0, "Segno", WordKind.SEGNO))
                            if (dt.child("coda") != null) words.add(WordMark(measures.size, dirTick, 0, "Coda", WordKind.CODA))
                            dt.child("wedge")?.let { wd ->
                                val key = wd.getAttribute("number").ifEmpty { "1" }
                                when (wd.getAttribute("type")) {
                                    "crescendo" -> openWedges[key] = Triple(measures.size, dirTick, true)
                                    "diminuendo" -> openWedges[key] = Triple(measures.size, dirTick, false)
                                    "stop" -> openWedges.remove(key)?.let { (m0, t0, cr) -> hairpins.add(Hairpin(m0, t0, measures.size, dirTick, cr)) }
                                }
                            }
                            dt.child("octave-shift")?.let { os ->
                                val st = ((n.child("staff")?.text()?.trim()?.toIntOrNull()
                                    ?: os.getAttribute("number").toIntOrNull() ?: 1) - 1).coerceIn(0, 1)
                                val size = os.getAttribute("size").toIntOrNull() ?: 8
                                // MusicXML nennt die Richtung, in die notiert wird: „down“ = 8va
                                ottava[st] = when (os.getAttribute("type")) { "down" -> size / 7; "up" -> -(size / 7); else -> 0 }
                            }
                        }
                        n.descendant("sound")?.getAttribute("tempo")?.toDoubleOrNull()?.let { tempo = it.toInt() }
                        n.descendant("per-minute")?.text()?.toDoubleOrNull()?.let { if (tempo == 0) tempo = it.toInt() }
                    }
                    "harmony" -> {
                        val root = n.child("root")
                        val step = root?.child("root-step")?.text()?.trim()
                        if (step != null) {
                            val alter = root.child("root-alter")?.text()?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                            val kindEl = n.child("kind")
                            val suffix = kindEl?.getAttribute("text")?.takeIf { kindEl.hasAttribute("text") } ?: when (kindEl?.text()?.trim()) {
                                "minor" -> "m"; "dominant" -> "7"; "major-seventh" -> "maj7"; "minor-seventh" -> "m7"
                                "diminished" -> "dim"; "augmented" -> "+"; "suspended-fourth" -> "sus4"; else -> ""
                            }
                            val bass = n.child("bass")?.let { b ->
                                val bs = b.child("bass-step")?.text()?.trim() ?: return@let ""
                                val ba = b.child("bass-alter")?.text()?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                                "/" + bs + accName(ba)
                            } ?: ""
                            val off = ticks(n.child("offset")?.text(), divisions)
                            chordSymbols.add(ChordSymbol(measures.size, (cursor + off).coerceAtLeast(0), step + accName(alter) + suffix + bass))
                        }
                    }
                    "sound" -> n.getAttribute("tempo").toDoubleOrNull()?.let { tempo = it.toInt() }
                    "backup" -> cursor -= ticks(n.child("duration")?.text(), divisions)
                    "forward" -> cursor += ticks(n.child("duration")?.text(), divisions)
                    "barline" -> {
                        val rep = n.child("repeat")?.getAttribute("direction")
                        if (rep == "forward") repeatStart = true
                        if (rep == "backward") repeatEnd = true
                        n.child("ending")?.let { e ->
                            val num = Regex("\\d+").find(e.getAttribute("number"))?.value?.toIntOrNull() ?: 1
                            when (e.getAttribute("type")) {
                                "start" -> { volta = num; measureVolta = num }
                                "stop" -> { volta = 0; voltaClosed = true }
                                "discontinue" -> volta = 0
                            }
                        }
                        val style = n.child("bar-style")?.text()
                        if (style == "light-heavy" || style == "heavy-heavy") finalBar = true
                    }
                    "note" -> {
                        if (n.child("cue") != null) continue
                        if (n.child("grace") != null) {
                            // Vorschlag: merken und dem nächsten Akkord derselben Hand mitgeben
                            val p = n.child("pitch") ?: continue
                            if (n.child("chord") != null) continue // nur der Hauptton eines Vorschlag-Akkords
                            val st = ((n.child("staff")?.text()?.toIntOrNull() ?: 1) - 1).coerceIn(0, 1)
                            val step = Pitch.STEP_LETTERS.indexOf(p.child("step")?.text()?.trim()?.uppercase() ?: "C").coerceAtLeast(0)
                            val oct = p.child("octave")?.text()?.trim()?.toIntOrNull() ?: 4
                            val alter = p.child("alter")?.text()?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                            pendingGraces.getOrPut(st) { mutableListOf() }.add(Pitch(step, oct, alter))
                            continue
                        }
                        val isChord = n.child("chord") != null
                        val dur = ticks(n.child("duration")?.text(), divisions)
                        val staff = ((n.child("staff")?.text()?.toIntOrNull() ?: 1) - 1).coerceIn(0, 1)
                        val voice = n.child("voice")?.text()?.toIntOrNull() ?: 1
                        val start = if (isChord) lastStart else cursor
                        if (!isChord) { lastStart = cursor; cursor += dur }

                        val restEl = n.child("rest")
                        val dots = n.children("dot").size
                        val timeMod = n.child("time-modification")
                        val type = NoteType.fromXml(n.child("type")?.text()) ?: run {
                            // Ohne <type>: aus der Dauer schließen (bei Triolen auf den Notenwert hochrechnen)
                            val actual = timeMod?.child("actual-notes")?.text()?.toIntOrNull() ?: 1
                            val normal = timeMod?.child("normal-notes")?.text()?.toIntOrNull() ?: 1
                            NoteType.forTicks(dur * actual / normal).first
                        }
                        val notations = n.child("notations")
                        val art = notations?.child("articulations")
                        val accent = art?.child("accent") != null || art?.child("strong-accent") != null
                        val staccato = art?.child("staccato") != null || art?.child("staccatissimo") != null
                        val orn = mutableSetOf<Orn>()
                        val ornEl = notations?.child("ornaments")
                        if (art?.child("tenuto") != null) orn.add(Orn.TENUTO)
                        if (art?.child("staccatissimo") != null) orn.add(Orn.STACCATISSIMO)
                        if (art?.child("strong-accent") != null) orn.add(Orn.MARCATO)
                        if (art?.child("breath-mark") != null || art?.child("caesura") != null) orn.add(Orn.BREATH)
                        if (notations?.child("fermata") != null) orn.add(Orn.FERMATA)
                        if (notations?.child("arpeggiate") != null) orn.add(Orn.ARPEGGIO)
                        if (ornEl?.child("trill-mark") != null) orn.add(Orn.TRILL)
                        // homr legt „mordent“ unter articulations ab – beide Orte gelten lassen
                        if (ornEl?.child("mordent") != null || art?.child("mordent") != null) orn.add(Orn.MORDENT)
                        if (ornEl?.child("inverted-mordent") != null) orn.add(Orn.PRALL)
                        if (ornEl?.child("turn") != null) orn.add(Orn.TURN)
                        if (ornEl?.child("inverted-turn") != null) orn.add(Orn.TURN_INV)
                        if (ornEl?.child("tremolo") != null) orn.add(Orn.TREMOLO)
                        val actualNotes = timeMod?.child("actual-notes")?.text()?.trim()?.toIntOrNull()
                        val normalNotes = timeMod?.child("normal-notes")?.text()?.trim()?.toIntOrNull()
                        val tuplet = if (actualNotes != null && actualNotes != normalNotes) actualNotes else 0
                        val beam = when (n.children("beam").firstOrNull { it.getAttribute("number").let { a -> a.isEmpty() || a == "1" } }?.text()) {
                            "begin" -> Beam.BEGIN; "continue" -> Beam.CONTINUE; "end" -> Beam.END; else -> Beam.NONE
                        }

                        var note: Note? = null
                        if (restEl == null) {
                            val p = n.child("pitch")
                            if (p != null) {
                                val step = Pitch.STEP_LETTERS.indexOf(p.child("step")?.text()?.trim()?.uppercase() ?: "C").coerceAtLeast(0)
                                val oct = p.child("octave")?.text()?.trim()?.toIntOrNull() ?: 4
                                val alter = p.child("alter")?.text()?.trim()?.toDoubleOrNull()?.toInt() ?: 0
                                val ties = n.children("tie").map { it.getAttribute("type") } +
                                    (notations?.children("tied")?.map { it.getAttribute("type") } ?: emptyList())
                                val pos = imgPos(n)
                                note = Note(
                                    Pitch(step, oct, alter),
                                    tieStart = "start" in ties, tieStop = "stop" in ties,
                                    imgX = pos?.first, imgY = pos?.second,
                                    finger = notations?.child("technical")?.child("fingering")?.text()?.trim()?.toIntOrNull()?.takeIf { it in 0..5 },
                                )
                            }
                        }

                        val cur = current
                        val target: Building
                        if (isChord && cur != null && cur.staff == staff) {
                            note?.let { cur.notes.add(it) }
                            cur.accent = cur.accent || accent
                            cur.staccato = cur.staccato || staccato
                            cur.orn.addAll(orn)
                            target = cur
                        } else {
                            val b = Building(
                                staff, voice, start, type, dots, dur, mutableListOf<Note>().also { l -> note?.let { l.add(it) } },
                                accent, staccato, beam, rest = restEl != null,
                                measureRest = restEl?.getAttribute("measure") == "yes",
                                id = nextId++, orn = orn, tuplet = tuplet,
                                graces = if (restEl == null) pendingGraces.remove(staff)?.toList() ?: emptyList() else emptyList(),
                                ottava = ottava[staff],
                            )
                            built.add(b); current = b; target = b
                        }
                        // Bindebögen: Anfang merken, beim Ende verbinden (Schlüssel: Hand + Bogennummer)
                        for (sl in notations?.children("slur") ?: emptyList()) {
                            val key = "$staff/" + sl.getAttribute("number").ifEmpty { "1" }
                            when (sl.getAttribute("type")) {
                                "start" -> openSlurs[key] = target.id
                                "stop" -> openSlurs.remove(key)?.let { from -> if (from != target.id) slurs.add(Slur(from, target.id)) }
                            }
                        }
                    }
                }
            }

            val m = Measure(
                beats = beats, beatType = beatType, fifths = fifths,
                clefs = clefsAtStart ?: clefs.toList(),
                clefChanges = clefChanges.filter { ch -> ch.tick > 0 }.groupBy { it.staff to it.tick }.map { it.value.last() },
                chords = emptyList(), repeatStart = repeatStart, repeatEnd = repeatEnd, finalBar = finalBar,
                volta = measureVolta, voltaClosed = voltaClosed && measureVolta != 0,
            )
            val chords = built.map { b ->
                val wholeRest = b.rest && (b.measureRest || (b.type == NoteType.WHOLE && b.duration >= m.length) || b.duration == m.length && built.count { it.staff == b.staff } == 1)
                Chord(
                    id = b.id, staff = b.staff, voice = b.voice, start = b.start, type = b.type, dots = b.dots,
                    notes = b.notes.sortedBy { it.pitch.diatonic }, accent = b.accent, staccato = b.staccato,
                    beam = b.beam, duration = if (wholeRest) m.length else b.duration, wholeMeasureRest = wholeRest,
                    orn = b.orn.toSet(), tuplet = b.tuplet, graces = b.graces, ottava = b.ottava,
                )
            }.sortedWith(compareBy({ it.staff }, { it.voice }, { it.start }))
            measures.add(m.copy(chords = normalizeVoices(chords)))
        }
        if (staves < 2) {
            // Nur ein System: das untere bleibt leer, Bassschlüssel nur zur Anzeige.
        }
        // Ohne Metronomzahl: Tempowort („Andante“) als Anhalt nehmen
        if (tempo !in 20..300) words.firstOrNull { it.kind == WordKind.TEMPO }?.let { w -> WordKind.tempoOf(w.text)?.let { tempo = it } }
        return Score(
            title.trim(), if (tempo in 20..300) tempo else 100, measures, pedals, dynamics, slurs,
            words = words, chordSymbols = chordSymbols, hairpins = hairpins,
        )
    }

    /** Stimmen je System durchnummerieren (1, 2 …), damit Darstellung und Bearbeitung nicht von XML-Stimmnummern abhängen. */
    private fun normalizeVoices(chords: List<Chord>): List<Chord> {
        val result = mutableListOf<Chord>()
        for (staff in 0..1) {
            val voices = chords.filter { it.staff == staff }.map { it.voice }.distinct().sorted()
            for (c in chords.filter { it.staff == staff }) result.add(c.copy(voice = voices.indexOf(c.voice) + 1))
        }
        return result
    }

    private fun accName(alter: Int) = when (alter) { -1 -> "b"; 1 -> "#"; -2 -> "bb"; 2 -> "##"; else -> "" }

    private fun ticks(text: String?, divisions: Int): Int {
        val d = text?.trim()?.toDoubleOrNull() ?: return 0
        return Math.round(d * TPQ / divisions).toInt()
    }

    private val IMGPOS = Regex("""imgpos:\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)""")

    private fun imgPos(n: Element): Pair<Float, Float>? {
        for (c in n.childNodesList()) {
            if (c.nodeType == Node.COMMENT_NODE) {
                IMGPOS.find(c.nodeValue)?.let { return it.groupValues[1].toFloat() to it.groupValues[2].toFloat() }
            }
        }
        return null
    }

    private fun Node.childNodesList(): List<Node> = (0 until childNodes.length).map { childNodes.item(it) }
    private fun Element.children(tag: String): List<Element> = childNodesList().filterIsInstance<Element>().filter { it.tagName == tag }
    private fun Element.child(tag: String): Element? = childNodesList().firstOrNull { it is Element && it.tagName == tag } as Element?
    private fun Element.descendant(tag: String): Element? = getElementsByTagName(tag).item(0) as Element?
    private fun Element.text(): String = textContent ?: ""
}
