package com.nicedevelopments.vomblatt.model

/** Zeiteinheit im ganzen Modell: Ticks pro Viertelnote. Teilbar durch 2, 3, 4, 8 – reicht für Triolen. */
const val TPQ = 24

enum class Clef { TREBLE, BASS }

enum class NoteType(val ticks: Int, val xml: String, val label: String) {
    WHOLE(96, "whole", "Ganze"),
    HALF(48, "half", "Halbe"),
    QUARTER(24, "quarter", "Viertel"),
    EIGHTH(12, "eighth", "Achtel"),
    SIXTEENTH(6, "16th", "Sechzehntel"),
    THIRTY_SECOND(3, "32nd", "Zweiunddreißigstel");

    val beams: Int get() = when (this) { EIGHTH -> 1; SIXTEENTH -> 2; THIRTY_SECOND -> 3; else -> 0 }

    companion object {
        fun fromXml(s: String?): NoteType? = entries.firstOrNull { it.xml == s }
        /** Passender Notenwert für eine Dauer in Ticks (ohne Punkt), z. B. für Notenwerte ohne <type>. */
        fun forTicks(t: Int): Pair<NoteType, Int> {
            for (n in entries) {
                if (t == n.ticks) return n to 0
                if (t == n.ticks * 3 / 2) return n to 1
                if (t == n.ticks * 7 / 4) return n to 2
            }
            return (entries.firstOrNull { it.ticks <= t } ?: THIRTY_SECOND) to 0
        }
    }
}

fun dotted(base: Int, dots: Int): Int {
    var t = base; var add = base
    repeat(dots) { add /= 2; t += add }
    return t
}

/** Klingende Tonhöhe: Stammton (0 = C … 6 = H), Oktave (wissenschaftlich, C4 = eingestrichenes c), Vorzeichen in Halbtönen. */
data class Pitch(val step: Int, val octave: Int, val alter: Int) {
    val midi: Int get() = (octave + 1) * 12 + STEP_SEMITONES[step] + alter
    /** Fortlaufende Stammtonnummer – Grundlage für die Position auf dem Notensystem. */
    val diatonic: Int get() = octave * 7 + step

    fun name(): String {
        val base = STEP_NAMES_DE[step]
        val acc = when (alter) { -2 -> "eses"; -1 -> if (step == 6) "" else if (step == 2 || step == 5) "s" else "es"; 1 -> "is"; 2 -> "isis"; else -> "" }
        val letter = if (step == 6 && alter == -1) "B" else base
        return "$letter$acc$octave"
    }

    fun shiftDiatonic(steps: Int): Pitch {
        val d = diatonic + steps
        return Pitch(Math.floorMod(d, 7), Math.floorDiv(d, 7), alter)
    }

    companion object {
        val STEP_SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        val STEP_NAMES_DE = arrayOf("C", "D", "E", "F", "G", "A", "H")
        val STEP_LETTERS = "CDEFGAB"
        fun fromMidi(midi: Int, preferFlat: Boolean): Pitch {
            val pc = Math.floorMod(midi, 12)
            val oct = midi / 12 - 1
            val sharp = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
            val flat = intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6)
            val step = if (preferFlat) flat[pc] else sharp[pc]
            return Pitch(step, oct, pc - STEP_SEMITONES[step])
        }
    }
}

data class Note(
    val pitch: Pitch,
    val tieStart: Boolean = false,
    val tieStop: Boolean = false,
    /** Ungefähre Position im Originalfoto (Pixel), falls aus der Erkennung bekannt. */
    val imgX: Float? = null,
    val imgY: Float? = null,
    /** Fingersatz (1 = Daumen … 5 = kleiner Finger), falls auf dem Blatt angegeben. */
    val finger: Int? = null,
    /** Lage des gedruckten Fingersatzes auf dem Foto: Mitte der Ziffer relativ zur Kopfmitte in Linienabständen
     *  (im Original-Layout steht er dann genau dort, z. B. in einem Bleistiftkreis). */
    val fingerDx: Float? = null,
    val fingerDy: Float? = null,
)

enum class Beam { NONE, BEGIN, CONTINUE, END }

/**
 * Zeichen an einem Akkord, die über Akzent und Staccato hinausgehen.
 * MORDENT = Pralltriller nach unten (MusicXML „mordent“), PRALL = nach oben („inverted-mordent“).
 */
enum class Orn { TENUTO, STACCATISSIMO, MARCATO, FERMATA, TRILL, MORDENT, PRALL, TURN, TURN_INV, ARPEGGIO, TREMOLO, BREATH }

/** Ein Ereignis einer Stimme: Einzelton, Akkord oder (ohne Noten) Pause. */
data class Chord(
    val id: Long,
    val staff: Int,          // 0 = oberes System (rechte Hand), 1 = unteres (linke Hand)
    val voice: Int,
    val start: Int,          // Ticks ab Taktbeginn
    val type: NoteType,
    val dots: Int,
    val notes: List<Note>,   // leer = Pause
    val accent: Boolean = false,
    val staccato: Boolean = false,
    val beam: Beam = Beam.NONE,
    /** Tatsächliche Dauer in Ticks – weicht bei Triolen vom Notenwert ab. */
    val duration: Int = dotted(type.ticks, dots),
    val wholeMeasureRest: Boolean = false,
    val orn: Set<Orn> = emptySet(),
    /** Triole, Quintole …: Zahl der „tatsächlichen“ Noten (3 bei einer Triole), 0 = keine. */
    val tuplet: Int = 0,
    /** Vorschläge (kurz, vor dem Schlag) direkt vor diesem Akkord. */
    val graces: List<Pitch> = emptyList(),
    /** Oktavierung: +1 = 8va (klingt eine Oktave höher als notiert), −1 = 8vb. Tonhöhen sind klingend. */
    val ottava: Int = 0,
) {
    val isRest: Boolean get() = notes.isEmpty()
    val end: Int get() = start + duration
}

/** Schlüsselwechsel mitten im Takt. */
data class ClefChange(val tick: Int, val staff: Int, val clef: Clef)

data class Measure(
    val beats: Int,
    val beatType: Int,
    val fifths: Int,
    val clefs: List<Clef>,   // Schlüssel je System zu Taktbeginn
    val chords: List<Chord>,
    val repeatStart: Boolean = false,
    val repeatEnd: Boolean = false,
    val finalBar: Boolean = false,
    /** Volta-Klammer: Nummer des Schlusses (1., 2. …), zu dem dieser Takt gehört, 0 = keiner. */
    val volta: Int = 0,
    /** Klammer endet in diesem Takt mit Abschlussstrich (sonst offen). */
    val voltaClosed: Boolean = false,
    /** Schlüsselwechsel mitten im Takt (gilt ab dem Notenanfang [ClefChange.tick] bis Taktende). */
    val clefChanges: List<ClefChange> = emptyList(),
) {
    val length: Int get() = beats * TPQ * 4 / beatType

    /** Schlüssel des Systems [staff] für einen Notenanfang bei [tick]. */
    fun clefAt(staff: Int, tick: Int): Clef =
        clefChanges.filter { it.staff == staff && it.tick <= tick }.maxByOrNull { it.tick }?.clef ?: clefs[staff]

    /** Summe der Notenwerte je Stimme stimmt nicht mit der Taktart überein – Hinweis auf Erkennungsfehler. */
    fun suspicious(): Boolean = chords.groupBy { it.staff to it.voice }.values.any { v ->
        val end = v.maxOf { it.end }
        end != length && !(v.size == 1 && v[0].wholeMeasureRest)
    }
}

enum class PedalType { DOWN, CHANGE, UP }

/** Haltepedal an einer Stelle: Takt (Index) und Ticks ab Taktbeginn. */
data class PedalMark(val measure: Int, val tick: Int, val type: PedalType)

/** Lautstärkezeichen („p“, „mf“, „sfz“ …) oder Zusatz („dolce“, „più“) an einer Taktstelle. */
data class DynamicMark(val measure: Int, val tick: Int, val staff: Int, val text: String) {
    /** Grundlautstärke (Anschlagstärke), die ab hier gilt – null bei reinen Akzenten und Zusätzen. */
    val level: Int? get() = LEVELS[text] ?: when (text) { "fp" -> LEVELS["p"]; "sfp" -> LEVELS["p"]; else -> null }
    /** Einmaliger Nachdruck auf dem Ton an dieser Stelle (sf, sfz, fz, fp …). */
    val punch: Int get() = when (text) { "sf", "sfz", "fz", "rf", "rfz" -> 24; "sffz" -> 32; "fp" -> 32; "sfp" -> 38; else -> 0 }
    val isLetters: Boolean get() = text.all { it in "pmfrszn" }

    companion object {
        val LEVELS = mapOf("pppp" to 26, "ppp" to 33, "pp" to 42, "p" to 52, "mp" to 62, "mf" to 72, "f" to 84, "ff" to 96, "fff" to 106, "ffff" to 114)
    }
}

/** Bindebogen (Legato) vom einen zum anderen Akkord derselben Hand. */
data class Slur(val from: Long, val to: Long)

/** Bedeutung einer Spielanweisung in Worten. */
enum class WordKind {
    TEMPO, RIT, ACCEL, A_TEMPO, CRESC, DIM, SIM, UNA_CORDA, TRE_CORDE,
    FINE, DC, DC_AL_CODA, DS, DS_AL_CODA, TO_CODA, CODA, SEGNO, REHEARSAL;

    /** Steht über dem System (sonst zwischen bzw. unter den Systemen). */
    val above: Boolean get() = this !in setOf(CRESC, DIM, SIM, UNA_CORDA, TRE_CORDE)

    companion object {
        private fun norm(s: String) = s.lowercase().replace(Regex("[\\s.]+"), " ").trim()

        /** Tempowörter mit ungefährer Metronomzahl (Mitte des üblichen Bereichs). */
        val TEMPO_BPM = linkedMapOf(
            "prestissimo" to 200, "presto" to 176, "vivacissimo" to 172, "vivace" to 156, "vivo" to 156,
            "allegro molto" to 152, "allegro moderato" to 118, "allegretto" to 112, "allegro" to 132,
            "moderato" to 108, "andantino" to 92, "andante" to 80, "adagietto" to 72, "adagio" to 66,
            "larghetto" to 60, "lento" to 56, "largo" to 50, "grave" to 40,
            "sehr schnell" to 176, "schnell" to 144, "lebhaft" to 138, "frisch" to 126, "bewegt" to 112,
            "mäßig" to 100, "gemächlich" to 88, "ruhig" to 76, "langsam" to 60, "sehr langsam" to 48,
        )

        /** Erkennt eine Anweisung (Groß-/Kleinschreibung und Punkte egal); null = keine bekannte. */
        fun of(text: String): WordKind? {
            val t = norm(text)
            if (t.isEmpty()) return null
            fun has(vararg w: String) = w.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") || t.contains(" $it ") }
            return when {
                t.startsWith("d c") || t.startsWith("dc ") || t == "dc" || t.startsWith("da capo") ->
                    if (t.contains("coda")) DC_AL_CODA else DC
                t.startsWith("d s") || t.startsWith("ds ") || t == "ds" || t.startsWith("dal segno") ->
                    if (t.contains("coda")) DS_AL_CODA else DS
                t == "fine" -> FINE
                has("to coda", "al coda") && !t.startsWith("d") -> TO_CODA
                t == "coda" -> CODA
                t == "segno" -> SEGNO
                has("a tempo", "tempo i", "tempo primo", "tempo 1") -> A_TEMPO
                has("rit", "ritard", "ritardando", "rall", "rallentando", "riten", "ritenuto", "allarg", "allargando", "rallent") -> RIT
                has("accel", "accelerando", "string", "stringendo") -> ACCEL
                has("cresc", "crescendo") -> CRESC
                has("dim", "decresc", "diminuendo", "decrescendo", "morendo", "smorz", "smorzando") -> DIM
                has("sim", "simile") -> SIM
                has("una corda", "u c") -> UNA_CORDA
                has("tre corde", "tutte le corde", "t c") -> TRE_CORDE
                TEMPO_BPM.keys.any { t == it || t.startsWith("$it ") } -> TEMPO
                else -> null
            }
        }

        fun tempoOf(text: String): Int? {
            val t = norm(text)
            return TEMPO_BPM.entries.firstOrNull { t == it.key || t.startsWith(it.key + " ") }?.value
        }
    }
}

/** Spielanweisung in Worten an einer Taktstelle (Text wie auf dem Blatt). */
data class WordMark(val measure: Int, val tick: Int, val staff: Int, val text: String, val kind: WordKind)

/** Akkordsymbol über dem System („C“, „Am“, „F/G“, „B♭“). */
data class ChordSymbol(val measure: Int, val tick: Int, val text: String)

/** Gabel: lauter (cresc.) oder leiser werden, von einer Stelle bis zu einer anderen. */
data class Hairpin(val measure: Int, val tick: Int, val endMeasure: Int, val endTick: Int, val cresc: Boolean)

data class Score(
    val title: String,
    val tempo: Int,
    val measures: List<Measure>,
    val pedals: List<PedalMark> = emptyList(),
    val dynamics: List<DynamicMark> = emptyList(),
    val slurs: List<Slur> = emptyList(),
    val words: List<WordMark> = emptyList(),
    val chordSymbols: List<ChordSymbol> = emptyList(),
    val hairpins: List<Hairpin> = emptyList(),
) {
    /** Fortlaufende notierte Position (Ticks ab Stückbeginn, ohne Wiederholungen). */
    fun abs(measure: Int, tick: Int): Int = (measureStarts.getOrElse(measure.coerceIn(0, measures.size)) { 0 }) + tick

    /** Ist das Haltepedal unmittelbar vor dieser Stelle getreten? */
    fun pedalDownBefore(measure: Int, tick: Int): Boolean {
        val before = pedals.filter { it.measure < measure || (it.measure == measure && it.tick < tick) }
            .maxWithOrNull(compareBy({ it.measure }, { it.tick }))
        return before != null && before.type != PedalType.UP
    }

    /** Startzeit jedes Takts in Ticks (ohne Wiederholungen). */
    val measureStarts: IntArray by lazy {
        val a = IntArray(measures.size + 1)
        for (i in measures.indices) a[i + 1] = a[i] + measures[i].length
        a
    }

    fun chordById(id: Long): Pair<Int, Chord>? {
        measures.forEachIndexed { mi, m -> m.chords.firstOrNull { it.id == id }?.let { return mi to it } }
        return null
    }

    fun replaceChord(id: Long, transform: (Chord) -> Chord?): Score = copy(
        measures = measures.map { m ->
            if (m.chords.none { it.id == id }) m
            else m.copy(chords = m.chords.mapNotNull { if (it.id == id) transform(it) else it })
        }
    )

    val keyName: String get() = keyName(measures.firstOrNull()?.fifths ?: 0)

    companion object {
        private val MAJOR = arrayOf("Ces", "Ges", "Des", "As", "Es", "B", "F", "C", "G", "D", "A", "E", "H", "Fis", "Cis")
        fun keyName(fifths: Int): String = MAJOR.getOrElse(fifths + 7) { "?" } + "-Dur"
    }
}

/** Vorzeichen, das die Tonart für einen Stammton vorgibt. */
fun keyAlter(fifths: Int, step: Int): Int {
    // Reihenfolge der Kreuze: F C G D A E H, der Bs: H E A D G C F
    val sharps = intArrayOf(3, 0, 4, 1, 5, 2, 6)
    val flats = intArrayOf(6, 2, 5, 1, 4, 0, 3)
    return when {
        fifths > 0 -> if (sharps.take(fifths).contains(step)) 1 else 0
        fifths < 0 -> if (flats.take(-fifths).contains(step)) -1 else 0
        else -> 0
    }
}
