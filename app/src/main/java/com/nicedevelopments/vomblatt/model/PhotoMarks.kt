package com.nicedevelopments.vomblatt.model

import kotlin.math.abs

/** Ein von der Texterkennung gelesenes Stück Text mit Rahmen in Fotopixeln; [words] = einzelne Wörter der Zeile. */
/** Gelesener Text im Foto. [checked]: Fingersatz-Ziffer aus der doppelt geprüften Ziffernlesung (TitleReader.readDigits). */
data class OcrText(val text: String, val l: Float, val t: Float, val r: Float, val b: Float, val words: List<OcrText> = emptyList(), val checked: Boolean = false) {
    val cx: Float get() = (l + r) / 2
    val cy: Float get() = (t + b) / 2
}

/** Lage eines Klaviersystems im Foto: Ober-/Unterkante des oberen und unteren Systems, Linienabstand. */
data class SystemGeom(
    val top0: Float, val bot0: Float, val top1: Float, val bot1: Float, val left: Float, val right: Float, val ls: Float,
    /** Oberste Notenlinie als Punktfolge x0,y0,x1,y1 … – gewölbte oder schräge Seiten. */
    val topLine: FloatArray = FloatArray(0),
    /** Erkannte Taktstriche (x im Foto, ungefiltert – auch Hälse und Doppelungen möglich). */
    val bars: FloatArray = FloatArray(0),
) {
    /** Versatz der Notenlinien an Stelle x gegenüber dem Mittel (negativ = höher). */
    fun shiftAt(x: Float): Float {
        if (topLine.size < 4) return 0f
        var i = 0
        while (i + 3 < topLine.size && topLine[i + 2] < x) i += 2
        val x0 = topLine[i]; val y0 = topLine[i + 1]
        val x1 = topLine.getOrElse(i + 2) { x0 }; val y1 = topLine.getOrElse(i + 3) { y0 }
        val y = if (x1 == x0 || x <= x0) y0 else if (x >= x1) y1 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        return y - top0
    }
}

/** Gabel aus der Bilderkennung (vomblatt_omr._detect_hairpins), Fotokoordinaten. */
data class PhotoHairpin(val x0: Float, val x1: Float, val y: Float, val cresc: Boolean, val system: Int)

/**
 * Ordnet Text und Gabeln aus dem Foto den Takten zu: Akkordsymbole, Spielanweisungen, Formteil-Buchstaben.
 * Was nicht eindeutig zu einer bekannten Art passt (Fingersätze, Verlag, Bleistiftnotizen), bleibt weg.
 */
object PhotoMarks {
    private val CHORD = Regex("""^([A-H])([b#])?(m|maj|min|dim|aug|sus|add|\+|°|ø)?(\d{1,2})?((sus|add|b|#)\d{1,2})*(/[A-H][b#]?)?$""")

    /** Akkordsymbol in Schreibweise der App („Bb“, „F#m7“, „C/G“) oder null. */
    fun chordOf(raw: String): String? {
        val t = raw.trim().replace('♭', 'b').replace('♯', '#').replace("þ", "b").replace(" ", "")
        if (t.isEmpty() || t.length > 9) return null
        return t.takeIf { CHORD.matches(it) }
    }

    private val REHEARSAL = Regex("""^\d{0,3}\W?([A-Z])\W?$""")

    /**
     * Rahmen von Text, der gedruckt ist: Tempoangaben, bekannte Anweisungen, Akkordsymbole über dem System,
     * Formbuchstaben und kleine Ziffern (Fingersätze, Taktzahlen). Handschrift ist meist größer oder steht
     * dort, wo kein Druck dieser Art steht – sie bleibt. Ergebnis: [l, t, r, b] in Fotokoordinaten.
     */
    fun printBoxes(texts: List<OcrText>, systems: List<SystemGeom>): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        fun add(t: OcrText) { out.add(floatArrayOf(t.l, t.t, t.r, t.b)) }
        for (line in texts) {
            // „über einem System“: bis 7 Linienabstände über dessen oberster Notenlinie (örtliche Höhe)
            val over = systems.firstOrNull { g -> val d = g.top0 + g.shiftAt(line.cx) - line.cy; d > 0 && d < 7 * g.ls }
            val s = over ?: systems.minByOrNull { g -> kotlin.math.abs(line.cy - (g.top0 + g.bot1) / 2) } ?: continue
            val above = over != null
            if ('=' in line.text || WordKind.of(line.text) != null) { add(line); continue }
            for (w in line.words.ifEmpty { listOf(line) }) {
                val txt = w.text.trim()
                val h = w.b - w.t
                when {
                    txt.isEmpty() -> {}
                    txt.all { it.isDigit() || it in "()[].," } && h <= 1.9f * s.ls -> add(w)          // Fingersatz, Takt-, Seitenzahl
                    WordKind.of(txt) != null -> add(w)
                    above && chordOf(txt.trim('[', ']', '|')) != null -> add(w)                       // Akkordsymbol
                    above && REHEARSAL.matches(txt) -> add(w)                                          // Formteil
                    // Akkordzeile: kurze Wörter in Druckhöhe sind falsch gelesene Akkorde („FIG“ = F/G)
                    above && txt.length <= 4 && h <= 2.2f * s.ls -> add(w)
                }
            }
        }
        return out
    }

    private class Anchor(val measure: Int, val tick: Int, val x: Float, val y: Float)

    /** [hasSheetTempo]: Metronomzahl schon vom Blatt gelesen – dann gilt die, nicht das Tempowort. */
    fun place(score: Score, texts: List<OcrText>, pins: List<PhotoHairpin>, systems: List<SystemGeom>, hasSheetTempo: Boolean = false): Score {
        if (systems.isEmpty()) return score
        val anchors = score.measures.flatMapIndexed { mi, m ->
            m.chords.filter { c -> c.notes.any { it.imgX != null } }.groupBy { it.start }.map { (start, cs) ->
                val ns = cs.flatMap { it.notes }.filter { it.imgX != null && it.imgY != null }
                Anchor(mi, start, ns.minOf { it.imgX!! }, ns.map { it.imgY!! }.average().toFloat())
            }
        }
        if (anchors.isEmpty()) return score
        fun systemOfAnchor(a: Anchor) = systems.indices.minByOrNull { i -> val s = systems[i]; if (a.y in s.top0 - s.ls * 4..s.bot1 + s.ls * 4) 0f else minOf(abs(a.y - s.top0), abs(a.y - s.bot1)) }!!
        val bySystem = anchors.groupBy { systemOfAnchor(it) }

        fun nearest(si: Int, x: Float): Anchor? = bySystem[si]?.minByOrNull { abs(it.x - x) }

        val words = mutableListOf<WordMark>()
        val chords = mutableListOf<ChordSymbol>()

        // Zone eines Texts: 0 = über dem System, 1 = zwischen den Systemen, 2 = unter dem Bass-System
        fun locate(t: OcrText): Pair<Int, Int>? {
            var best: Pair<Int, Int>? = null
            var bestD = Float.MAX_VALUE
            systems.forEachIndexed { i, s ->
                val y = t.cy - s.shiftAt(t.cx)
                val (zone, d) = when {
                    y < s.top0 -> 0 to (s.top0 - y)
                    y > s.bot0 && y < s.top1 -> 1 to 0f
                    y > s.bot1 -> 2 to (y - s.bot1)
                    else -> -1 to Float.MAX_VALUE // im System selbst: keine Anweisung
                }
                val limit = if (zone == 0) s.ls * (if (i == 0) 5.5f else 7f) else s.ls * 5f
                if (zone >= 0 && d <= limit && d < bestD) { bestD = d; best = i to zone }
            }
            return best
        }

        fun addWord(t: OcrText, kind: WordKind, si: Int, zone: Int) {
            val s = systems[si]
            val a = nearest(si, t.l + s.ls * 0.4f) ?: return
            val staff = if (zone == 2) 1 else 0
            // „Fine“, „D.C.“ stehen am Taktende: der Takt, in dem der Text endet
            val anchor = if (kind in setOf(WordKind.FINE, WordKind.DC, WordKind.DC_AL_CODA, WordKind.DS, WordKind.DS_AL_CODA, WordKind.TO_CODA))
                bySystem[si]?.filter { it.x < t.r }?.maxByOrNull { it.x } ?: a else a
            words.add(WordMark(anchor.measure, anchor.tick, staff, t.text.trim(), kind))
        }

        for (line in texts) {
            if ('=' in line.text) continue // Tempoangabe „♩ = 120“ liest der Kopf
            val (si, zone) = locate(line) ?: continue
            val s = systems[si]
            val whole = WordKind.of(line.text)
            if (whole != null && !(whole == WordKind.TEMPO && zone != 0)) { addWord(line, whole, si, zone); continue }
            for (w in line.words.ifEmpty { listOf(line) }) {
                val txt = w.text.trim().trim('[', ']', '|', '(', ')')
                if (txt.isEmpty() || txt.all { it.isDigit() || it in ".,()" }) continue // Fingersätze, Zahlen
                val kind = WordKind.of(txt)
                if (kind != null && kind != WordKind.TEMPO) { addWord(w, kind, si, zone); continue }
                if (zone != 0) continue
                val firstX = bySystem[si]?.minOfOrNull { it.x } ?: continue
                // Formteil-Buchstabe, oft mit der Taktzahl davor zusammengelesen („9B“, „[A]“)
                val letter = REHEARSAL.matchEntire(txt)?.groupValues?.get(1)
                if (letter != null && w.r < firstX - s.ls * 0.3f) {
                    val a = nearest(si, firstX) ?: continue
                    words.add(WordMark(a.measure, 0, 0, letter, WordKind.REHEARSAL))
                    continue
                }
                var chord = chordOf(txt) ?: continue
                // Die Texterkennung verliert das ♭ gern. In einer B-Tonart ist der Grundton dann gemeint wie
                // vorgezeichnet (in F-Dur ist „B“ der B♭-Akkord – im Deutschen heißt er ohnehin so).
                val fifths = score.measures.getOrNull(nearest(si, w.cx)?.measure ?: 0)?.fifths ?: 0
                if (fifths < 0 && chord[0] != 'H' && chord.getOrNull(1) != 'b' && chord.getOrNull(1) != '#' &&
                    keyAlter(fifths, Pitch.STEP_LETTERS.indexOf(chord[0])) == -1) {
                    chord = chord[0] + "b" + chord.substring(1)
                }
                // Akkordsymbole stehen dicht über dem System
                if (s.top0 + s.shiftAt(w.cx) - w.b > s.ls * 6f) continue
                val a = nearest(si, w.l + s.ls * 0.5f) ?: continue
                chords.add(ChordSymbol(a.measure, a.tick, chord))
            }
        }

        val hairpins = pins.mapNotNull { p ->
            val si = p.system.takeIf { it in systems.indices } ?: return@mapNotNull null
            val list = bySystem[si] ?: return@mapNotNull null
            val start = list.filter { it.x >= p.x0 - systems[si].ls * 1.5f }.minByOrNull { it.x } ?: return@mapNotNull null
            val endA = list.filter { it.x <= p.x1 + systems[si].ls * 0.5f }.maxByOrNull { it.x } ?: return@mapNotNull null
            // Ende: bis zur nächsten Note nach der letzten überspannten (bzw. Taktende)
            val m = score.measures[endA.measure]
            val next = m.chords.map { it.start }.filter { it > endA.tick }.minOrNull() ?: m.length
            if (endA.measure < start.measure || (endA.measure == start.measure && next <= start.tick)) return@mapNotNull null
            Hairpin(start.measure, start.tick, endA.measure, next, p.cresc)
        }

        val fingered = fingerings(score, texts, systems)

        val dedupWords = words.distinctBy { Triple(it.measure, it.kind, it.text.lowercase()) }
        val dedupChords = chords.distinctBy { it.measure to it.tick }
        val tempoWord = dedupWords.firstOrNull { it.kind == WordKind.TEMPO && it.measure == 0 }
        return fingered.copy(
            words = dedupWords.sortedWith(compareBy({ it.measure }, { it.tick })),
            chordSymbols = dedupChords.sortedWith(compareBy({ it.measure }, { it.tick })),
            hairpins = hairpins.distinct(),
        ).let { sc -> if (tempoWord != null && !hasSheetTempo) WordKind.tempoOf(tempoWord.text)?.let { sc.copy(tempo = it) } ?: sc else sc }
    }

    /**
     * Gedruckte Fingersätze (einzelne Ziffern 1–5) den Noten zuordnen: über/unter einem Akkord gestapelt
     * (oberste Ziffer = oberster Ton) oder direkt neben einem Notenkopf.
     */
    /**
     * Fingersatz [d] (Ziffer, Mitte x/y im Foto) an [n], samt Lage relativ zum Kopf in Linienabständen. Die Höhe des
     * Kopfs kommt aus der Tonhöhe (dort zeichnet ihn die App), nicht aus homrs Fotoposition – die liegt manchmal auf
     * einer Bleistiftmarke daneben (Harmony T. 4: Kreis um die 3).
     */
    private fun withFinger(n: Note, d: Triple<Int, Float, Float>, systems: List<SystemGeom>, m: Measure, c: Chord): Note {
        val x = n.imgX; val y = n.imgY
        val g = systemOf(n, systems)
        if (x == null || y == null || g == null || g.ls <= 0f) return n.copy(finger = d.first)
        return n.copy(finger = d.first, fingerDx = (d.second - x) / g.ls, fingerDy = (d.third - headY(n, g, m, c)) / g.ls)
    }

    private fun systemOf(n: Note, systems: List<SystemGeom>): SystemGeom? {
        val y = n.imgY ?: return null
        return systems.minByOrNull { s -> if (y in s.top0 - 6 * s.ls..s.bot1 + 6 * s.ls) 0f else minOf(abs(y - s.top0), abs(y - s.bot1)) }
    }

    /** Höhe des Notenkopfs im Foto nach der Tonhöhe (dort zeichnet ihn die App). */
    private fun headY(n: Note, g: SystemGeom, m: Measure, c: Chord): Float {
        val bottomLine = if (m.clefAt(c.staff, c.start) == Clef.TREBLE) 30 else 18
        val pos = n.pitch.diatonic - 7 * c.ottava - bottomLine
        return (if (c.staff == 0) g.top0 else g.top1) + g.shiftAt(n.imgX ?: 0f) + (8 - pos) * g.ls / 2
    }

    /**
     * Stücke vom 18.11. (erste Fassung): Fingersatzlage war relativ zu homrs Fotoposition gespeichert statt zur
     * Kopfhöhe nach der Tonhöhe – umrechnen (die absolute Lage der Ziffer bleibt gleich).
     */
    fun rebaseFingers(score: Score, systems: List<SystemGeom>): Score = score.copy(measures = score.measures.map { m ->
        m.copy(chords = m.chords.map { c ->
            c.copy(notes = c.notes.map { n ->
                val g = systemOf(n, systems)
                if (n.fingerDy == null || n.imgY == null || g == null || g.ls <= 0f) n
                else n.copy(fingerDy = (n.imgY + n.fingerDy * g.ls - headY(n, g, m, c)) / g.ls)
            })
        })
    })

    fun fingerings(score: Score, texts: List<OcrText>, systems: List<SystemGeom>): Score {
        class Head(val measure: Int, val chordId: Long, val index: Int, val staff: Int, val x: Float, val y: Float)
        val heads = score.measures.flatMapIndexed { mi, m ->
            m.chords.flatMap { c -> c.notes.mapIndexedNotNull { i, n -> if (n.imgX != null && n.imgY != null) Head(mi, c.id, i, c.staff, n.imgX, n.imgY) else null } }
        }
        if (heads.isEmpty() || systems.isEmpty()) return score
        // Gibt es die geprüfte Ziffernlesung, zählt nur sie (einzelne Ziffern der Seitenlesung sind zu oft Pedalzacken,
        // Taktzahlen oder Teile von Akkordsymbolen); ältere Stücke haben nur die Seitenlesung
        val pool = if (texts.any { it.checked }) texts.filter { it.checked } else texts
        val digits = pool.flatMap { it.words.ifEmpty { listOf(it) } }.filter { w ->
            val t = w.text.trim().trim('(', ')', '.')
            t.length == 1 && t[0] in '1'..'5'
        }.let { all ->
            // dieselbe Ziffer aus mehreren Quellen (Seite, Streifen, Ziffernreihe) nur einmal
            val kept = ArrayList<OcrText>()
            for (d in all) if (kept.none { k ->
                    val w = minOf(k.r, d.r) - maxOf(k.l, d.l); val h = minOf(k.b, d.b) - maxOf(k.t, d.t)
                    w > 0 && h > 0 && w * h > 0.3f * minOf((k.r - k.l) * (k.b - k.t), (d.r - d.l) * (d.b - d.t))
                }) kept.add(d)
            kept
        }
        // Zuordnung: Ziffer → (Akkord, Ziffer, y). Gestapelte Ziffern gehören immer als Ganzes zu einem Akkord:
        // von oben nach unten = höchster bis tiefster Ton. Die Lage der einzelnen Notenköpfe im Foto ist dafür nicht
        // genau genug (homr legt in dichten Akkorden gern zwei Töne auf denselben Kopf).
        // je Eintrag: Ziffer, Mitte x, Mitte y (Foto)
        val byChord = HashMap<Long, MutableList<Triple<Int, Float, Float>>>()
        fun digitOf(d: OcrText) = d.text.trim().trim('(', ')', '.')[0] - '0'
        fun sysOf(y: Float) = systems.minByOrNull { s -> if (y in s.top0 - 6 * s.ls..s.bot1 + 6 * s.ls) 0f else minOf(abs(y - s.top0), abs(y - s.bot1)) }
        val usable = digits.indices.filter { k -> val g = sysOf(digits[k].cy); g != null && digits[k].b - digits[k].t <= 1.9f * g.ls } // große Ziffern sind Handschrift
        val used = HashSet<Int>()
        val chordHeads = heads.groupBy { it.chordId }
        // 1. links neben einem Akkord (auf Höhe seiner Köpfe): zum Akkord gleich rechts daneben
        val leftOf = HashMap<Long, MutableList<Int>>()
        for (k in usable) {
            val d = digits[k]; val g = sysOf(d.cy) ?: continue
            val target = chordHeads.entries.filter { (_, hs) ->
                val cx = hs.minOf { it.x }
                cx - d.cx in 0.3f * g.ls..2.4f * g.ls && d.cy in hs.minOf { it.y } - 1.6f * g.ls..hs.maxOf { it.y } + 1.6f * g.ls
            }.minByOrNull { (_, hs) -> hs.minOf { it.x } - d.cx } ?: continue
            leftOf.getOrPut(target.key) { mutableListOf() }.add(k)
        }
        for ((cid, ks) in leftOf) {
            val hs = chordHeads[cid] ?: continue
            val notesInChord = hs.size
            if (ks.size >= notesInChord || ks.size >= 2) {
                // Stapel: der Reihe nach
                for (k in ks) byChord.getOrPut(cid) { mutableListOf() }.add(Triple(digitOf(digits[k]), digits[k].cx, digits[k].cy))
            } else for (k in ks) {
                // einzelne Ziffer: zum Kopf auf ihrer Höhe
                val h = hs.minByOrNull { abs(it.y - digits[k].cy) } ?: continue
                byChord.getOrPut(-(cid * 100 + h.index) - 1) { mutableListOf() }.add(Triple(digitOf(digits[k]), digits[k].cx, digits[k].cy))
            }
            used.addAll(ks)
        }
        // 2. über/unter einem Akkord: senkrechte Stapel bilden (gleiche Spalte, Lücke kleiner als eine Ziffer)
        val rest = usable.filter { it !in used }.sortedBy { digits[it].cy }
        val stacks = mutableListOf<MutableList<Int>>()
        for (k in rest) {
            val d = digits[k]; val g = sysOf(d.cy) ?: continue
            val st = stacks.lastOrNull { st ->
                val last = digits[st.last()]
                abs(last.cx - d.cx) < 0.9f * g.ls && d.t - last.b < 1.0f * (d.b - d.t) && d.t - last.b > -0.5f * (d.b - d.t)
            }
            if (st != null) st.add(k) else stacks.add(mutableListOf(k))
        }
        for (st in stacks) {
            val first = digits[st.first()]
            val g = sysOf(first.cy) ?: continue
            val cx = st.map { digits[it].cx }.average().toFloat()
            val inSys = heads.filter { it.y in g.top0 - 3 * g.ls..g.bot1 + 3 * g.ls && abs(it.x - cx) < 1.3f * g.ls }
            // der Stapel gehört zum Akkord, dem seine nächste Ziffer am nächsten steht
            var best: Head? = null; var bestD = Float.MAX_VALUE
            for (k in st) for (h in inSys) {
                val dd = abs(h.y - digits[k].cy) + abs(h.x - digits[k].cx) * 0.5f
                if (dd < bestD) { bestD = dd; best = h }
            }
            val chosen = best?.takeIf { st.minOf { k -> abs(it.y - digits[k].cy) } < 7 * g.ls } ?: continue
            for (k in st) byChord.getOrPut(chosen.chordId) { mutableListOf() }.add(Triple(digitOf(digits[k]), digits[k].cx, digits[k].cy))
        }
        // Wo gedruckte Fingersätze stehen (auch unlesbare „?“), können keine Verzierungen sein: homr liest einen
        // Ziffernstapel links neben einem Akkord gern als Arpeggio, darüber als Triller/Mordent
        val noArp = HashSet<Long>(); val noOrn = HashSet<Long>()
        val anyDigit = texts.flatMap { it.words.ifEmpty { listOf(it) } }.filter { w ->
            val t = w.text.trim().trim('(', ')', '.'); t == "?" || (t.length == 1 && t[0] in '0'..'9')
        }
        for (m in score.measures) for (c in m.chords) {
            val ns = c.notes.filter { it.imgX != null && it.imgY != null }
            if (ns.isEmpty()) continue
            val cx = ns.map { it.imgX!! }.average().toFloat()
            val y0 = ns.minOf { it.imgY!! }; val y1 = ns.maxOf { it.imgY!! }
            val g = systems.minByOrNull { s -> abs((s.top0 + s.bot1) / 2 - (y0 + y1) / 2) } ?: continue
            for (d in anyDigit) {
                if (cx - d.cx in 0.3f * g.ls..3f * g.ls && d.cy in y0 - g.ls..y1 + g.ls) noArp.add(c.id)
                if (abs(d.cx - cx) < 1.3f * g.ls && (d.cy < y0 - 0.5f * g.ls || d.cy > y1 + 0.5f * g.ls) &&
                    minOf(abs(d.cy - y0), abs(d.cy - y1)) < 7 * g.ls) noOrn.add(c.id)
            }
        }
        val ornaments = setOf(Orn.TRILL, Orn.PRALL, Orn.MORDENT, Orn.TURN, Orn.TURN_INV)
        val cleaned = if (noArp.isEmpty() && noOrn.isEmpty()) score else score.copy(measures = score.measures.map { m ->
            m.copy(chords = m.chords.map { c ->
                var o = c.orn
                if (c.id in noArp) o = o - Orn.ARPEGGIO
                if (c.id in noOrn) o = o - ornaments
                if (o == c.orn) c else c.copy(orn = o)
            })
        })
        if (byChord.isEmpty()) return cleaned
        return cleaned.copy(measures = cleaned.measures.map { m ->
            m.copy(chords = m.chords.map { c ->
                var notes = c.notes
                // gestapelt über/unter dem Akkord: von oben nach unten = höchster bis tiefster Ton
                byChord[c.id]?.let { list ->
                    val sorted = list.sortedBy { it.third }
                    val stack = sorted.indices.toList()
                    val order = notes.indices.sortedByDescending { notes[it].pitch.diatonic }
                    val assign = HashMap<Int, Int>()
                    if (stack.size >= order.size) order.forEachIndexed { k, ni -> assign[ni] = stack[k] }
                    else {
                        // weniger Ziffern als Töne: über dem System gehören sie zu den oberen, darunter zu den unteren
                        val headY = c.notes.mapNotNull { it.imgY }.average().toFloat()
                        val aboveChord = list.map { it.third }.average() < headY
                        val targets = if (aboveChord) order.take(stack.size) else order.takeLast(stack.size)
                        targets.forEachIndexed { k, ni -> assign[ni] = stack[k] }
                    }
                    notes = notes.mapIndexed { i, n -> assign[i]?.let { withFinger(n, sorted[it], systems, m, c) } ?: n }
                }
                notes = notes.mapIndexed { i, n -> byChord[-(c.id * 100 + i) - 1]?.firstOrNull()?.let { withFinger(n, it, systems, m, c) } ?: n }
                if (notes === c.notes) c else c.copy(notes = notes)
            })
        })
    }
}
