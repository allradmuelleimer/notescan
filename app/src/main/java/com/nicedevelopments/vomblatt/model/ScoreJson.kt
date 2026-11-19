package com.nicedevelopments.vomblatt.model

import org.json.JSONArray
import org.json.JSONObject

/** Speicherformat für bearbeitete Stücke. Bewusst schlicht und verlustfrei für das App-Modell. */
object ScoreJson {
    /** 2 = mit Dynamik, Bögen, Verzierungen, Triolen, Vorschlägen, Volta, 8va; 3 = Schlüsselwechsel im Takt. */
    const val VERSION = 3

    fun version(text: String): Int = runCatching { JSONObject(text).optInt("v", 1) }.getOrDefault(1)

    fun write(s: Score): String = JSONObject().apply {
        put("v", VERSION)
        put("title", s.title)
        put("tempo", s.tempo)
        put("pedals", JSONArray().apply {
            for (p in s.pedals) put(JSONObject().put("m", p.measure).put("t", p.tick).put("k", p.type.name))
        })
        if (s.dynamics.isNotEmpty()) put("dyn", JSONArray().apply {
            for (d in s.dynamics) put(JSONObject().put("m", d.measure).put("t", d.tick).put("st", d.staff).put("x", d.text))
        })
        if (s.words.isNotEmpty()) put("words", JSONArray().apply {
            for (w in s.words) put(JSONObject().put("m", w.measure).put("t", w.tick).put("st", w.staff).put("x", w.text).put("k", w.kind.name))
        })
        if (s.chordSymbols.isNotEmpty()) put("harm", JSONArray().apply {
            for (c in s.chordSymbols) put(JSONObject().put("m", c.measure).put("t", c.tick).put("x", c.text))
        })
        if (s.hairpins.isNotEmpty()) put("pins", JSONArray().apply {
            for (h in s.hairpins) put(JSONArray().put(h.measure).put(h.tick).put(h.endMeasure).put(h.endTick).put(h.cresc))
        })
        if (s.slurs.isNotEmpty()) put("slurs", JSONArray().apply {
            for (sl in s.slurs) put(JSONArray().put(sl.from).put(sl.to))
        })
        put("measures", JSONArray().apply {
            for (m in s.measures) put(JSONObject().apply {
                put("beats", m.beats); put("beatType", m.beatType); put("fifths", m.fifths)
                put("clefs", JSONArray(m.clefs.map { it.name }))
                if (m.repeatStart) put("repStart", true)
                if (m.repeatEnd) put("repEnd", true)
                if (m.finalBar) put("final", true)
                if (m.volta != 0) put("volta", m.volta)
                if (m.voltaClosed) put("voltaEnd", true)
                if (m.clefChanges.isNotEmpty()) put("clefChanges", JSONArray().apply {
                    for (ch in m.clefChanges) put(JSONArray().put(ch.tick).put(ch.staff).put(ch.clef.name))
                })
                put("chords", JSONArray().apply {
                    for (c in m.chords) put(JSONObject().apply {
                        put("id", c.id); put("staff", c.staff); put("voice", c.voice); put("start", c.start)
                        put("type", c.type.name); put("dots", c.dots); put("dur", c.duration)
                        if (c.accent) put("acc", true)
                        if (c.staccato) put("stac", true)
                        if (c.beam != Beam.NONE) put("beam", c.beam.name)
                        if (c.wholeMeasureRest) put("mrest", true)
                        if (c.orn.isNotEmpty()) put("orn", JSONArray(c.orn.map { it.name }))
                        if (c.tuplet != 0) put("tup", c.tuplet)
                        if (c.ottava != 0) put("ott", c.ottava)
                        if (c.graces.isNotEmpty()) put("gr", JSONArray().apply {
                            for (g in c.graces) put(JSONArray().put(g.step).put(g.octave).put(g.alter))
                        })
                        put("notes", JSONArray().apply {
                            for (n in c.notes) put(JSONObject().apply {
                                put("s", n.pitch.step); put("o", n.pitch.octave); put("a", n.pitch.alter)
                                if (n.tieStart) put("ts", true)
                                if (n.tieStop) put("te", true)
                                n.finger?.let { put("f", it) }
                                n.fingerDx?.let { put("fx", it.toDouble()) }
                                n.fingerDy?.let { put("fy", it.toDouble()) }
                                n.imgX?.let { put("x", it.toDouble()) }
                                n.imgY?.let { put("y", it.toDouble()) }
                            })
                        })
                    })
                })
            })
        })
    }.toString()

    fun read(text: String): Score {
        val o = JSONObject(text)
        val ms = o.getJSONArray("measures")
        val measures = (0 until ms.length()).map { i ->
            val m = ms.getJSONObject(i)
            val cs = m.getJSONArray("chords")
            val clefs = m.getJSONArray("clefs")
            Measure(
                beats = m.getInt("beats"), beatType = m.getInt("beatType"), fifths = m.getInt("fifths"),
                clefs = (0 until clefs.length()).map { Clef.valueOf(clefs.getString(it)) },
                repeatStart = m.optBoolean("repStart"), repeatEnd = m.optBoolean("repEnd"), finalBar = m.optBoolean("final"),
                volta = m.optInt("volta"), voltaClosed = m.optBoolean("voltaEnd"),
                clefChanges = m.optJSONArray("clefChanges")?.let { a ->
                    (0 until a.length()).map { k -> a.getJSONArray(k).let { e -> ClefChange(e.getInt(0), e.getInt(1), Clef.valueOf(e.getString(2))) } }
                } ?: emptyList(),
                chords = (0 until cs.length()).map { j ->
                    val c = cs.getJSONObject(j)
                    val ns = c.getJSONArray("notes")
                    Chord(
                        id = c.getLong("id"), staff = c.getInt("staff"), voice = c.getInt("voice"), start = c.getInt("start"),
                        type = NoteType.valueOf(c.getString("type")), dots = c.getInt("dots"), duration = c.getInt("dur"),
                        accent = c.optBoolean("acc"), staccato = c.optBoolean("stac"),
                        beam = Beam.valueOf(c.optString("beam", "NONE")), wholeMeasureRest = c.optBoolean("mrest"),
                        orn = c.optJSONArray("orn")?.let { a -> (0 until a.length()).mapNotNull { k -> runCatching { Orn.valueOf(a.getString(k)) }.getOrNull() }.toSet() } ?: emptySet(),
                        tuplet = c.optInt("tup"), ottava = c.optInt("ott"),
                        graces = c.optJSONArray("gr")?.let { a -> (0 until a.length()).map { k -> a.getJSONArray(k).let { g -> Pitch(g.getInt(0), g.getInt(1), g.getInt(2)) } } } ?: emptyList(),
                        notes = (0 until ns.length()).map { k ->
                            val n = ns.getJSONObject(k)
                            Note(
                                Pitch(n.getInt("s"), n.getInt("o"), n.getInt("a")),
                                tieStart = n.optBoolean("ts"), tieStop = n.optBoolean("te"),
                                finger = if (n.has("f")) n.getInt("f") else null,
                                fingerDx = if (n.has("fx")) n.getDouble("fx").toFloat() else null,
                                fingerDy = if (n.has("fy")) n.getDouble("fy").toFloat() else null,
                                imgX = if (n.has("x")) n.getDouble("x").toFloat() else null,
                                imgY = if (n.has("y")) n.getDouble("y").toFloat() else null,
                            )
                        },
                    )
                },
            )
        }
        val ps = o.optJSONArray("pedals")
        val pedals = if (ps == null) emptyList() else (0 until ps.length()).map { i ->
            val p = ps.getJSONObject(i)
            PedalMark(p.getInt("m"), p.getInt("t"), PedalType.valueOf(p.getString("k")))
        }
        val ds = o.optJSONArray("dyn")
        val dynamics = if (ds == null) emptyList() else (0 until ds.length()).map { i ->
            val d = ds.getJSONObject(i)
            DynamicMark(d.getInt("m"), d.getInt("t"), d.optInt("st"), d.getString("x"))
        }
        val ss = o.optJSONArray("slurs")
        val slurs = if (ss == null) emptyList() else (0 until ss.length()).map { i -> ss.getJSONArray(i).let { Slur(it.getLong(0), it.getLong(1)) } }
        val ws = o.optJSONArray("words")
        val words = if (ws == null) emptyList() else (0 until ws.length()).mapNotNull { i ->
            val w = ws.getJSONObject(i)
            val kind = runCatching { WordKind.valueOf(w.getString("k")) }.getOrNull() ?: return@mapNotNull null
            WordMark(w.getInt("m"), w.getInt("t"), w.optInt("st"), w.getString("x"), kind)
        }
        val hs = o.optJSONArray("harm")
        val harm = if (hs == null) emptyList() else (0 until hs.length()).map { i ->
            hs.getJSONObject(i).let { ChordSymbol(it.getInt("m"), it.getInt("t"), it.getString("x")) }
        }
        val ps2 = o.optJSONArray("pins")
        val pins = if (ps2 == null) emptyList() else (0 until ps2.length()).map { i ->
            ps2.getJSONArray(i).let { Hairpin(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3), it.getBoolean(4)) }
        }
        return Score(o.getString("title"), o.getInt("tempo"), measures, pedals, dynamics, slurs, words, harm, pins)
    }
}
