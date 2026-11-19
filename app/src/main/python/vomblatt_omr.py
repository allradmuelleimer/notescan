"""
Einstieg für die Notenerkennung in „Vom Blatt“.

Ruft homr (AGPL-3.0, https://github.com/liebharc/homr) ohne Kommandozeile auf, mit
Modellpfaden aus der App und Fortschrittsmeldungen an Kotlin. Liefert MusicXML als Text.
"""
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

_configured = False


def _compat() -> None:
    """Brücken für die älteren Paketversionen auf Android (OpenCV 4.5, numpy 1.26)."""
    import types

    import cv2

    if "cv2.typing" not in sys.modules:
        try:
            import cv2.typing  # noqa: F401
        except Exception:
            from typing import Any

            mod = types.ModuleType("cv2.typing")
            mod.__getattr__ = lambda name: Any  # type: ignore[attr-defined]
            sys.modules["cv2.typing"] = mod
            cv2.typing = mod  # type: ignore[attr-defined]

    # OpenCV 4.5.1 verlangt für Größenangaben (dsize) ein Tupel aus int; homr übergibt teils
    # numpy-Arrays oder Listen, was erst neuere Versionen akzeptieren.
    if not getattr(cv2, "_vomblatt_patched", False):
        def _size(v):
            if v is None or isinstance(v, tuple):
                return v
            try:
                return tuple(int(x) for x in v)
            except TypeError:
                return v

        def _wrap(name, index):
            orig = getattr(cv2, name, None)
            if orig is None:
                return

            def fn(*args, **kwargs):
                if len(args) > index:
                    args = list(args)
                    args[index] = _size(args[index])
                if "dsize" in kwargs:
                    kwargs["dsize"] = _size(kwargs["dsize"])
                return orig(*args, **kwargs)

            setattr(cv2, name, fn)

        for _name, _index in (("resize", 1), ("warpAffine", 2), ("warpPerspective", 2)):
            _wrap(_name, _index)
        _small_morphology(cv2)
        cv2._vomblatt_patched = True  # type: ignore[attr-defined]


def _small_morphology(cv2) -> None:
    """
    OpenCV 4.5.1 stürzt auf ARM (Handy) bei Morphologie auf sehr kleinen Bildern ab (Schreibzugriff hinter den
    Puffer, nur auf ARM – Emulator und PC laufen). Kleine Bilder deshalb vorher mit einem neutralen Rand vergrößern
    und danach wieder zuschneiden: bei dilate der kleinste, bei erode der größte Wert – das Ergebnis ist dasselbe.
    """
    import numpy as np

    MIN = 48
    orig_dilate, orig_erode, orig_morph = cv2.dilate, cv2.erode, cv2.morphologyEx

    def _pad_amount(src, kernel, iterations):
        kh, kw = (kernel.shape[:2] if kernel is not None and hasattr(kernel, "shape") else (3, 3))
        it = max(1, int(iterations or 1))
        return max(MIN, kh * it + 2, kw * it + 2)

    def _limits(src):
        if src.dtype == np.uint8:
            return 0, 255
        if src.dtype == np.bool_:
            return False, True
        if np.issubdtype(src.dtype, np.integer):
            info = np.iinfo(src.dtype); return info.min, info.max
        return -np.inf, np.inf

    def _small(src):
        return isinstance(src, np.ndarray) and src.ndim >= 2 and (src.shape[0] < MIN or src.shape[1] < MIN)

    def _run(fn, src, kernel, args, kwargs, fill):
        iterations = kwargs.get("iterations", args[2] if len(args) > 2 else 1)
        p = _pad_amount(src, kernel, iterations)
        pad = [(p, p), (p, p)] + [(0, 0)] * (src.ndim - 2)
        big = np.pad(src, pad, mode="constant", constant_values=fill)
        out = fn(big, kernel, *args, **kwargs)
        return np.ascontiguousarray(out[p:p + src.shape[0], p:p + src.shape[1]])

    def dilate(src, kernel, *args, **kwargs):
        if not _small(src):
            return orig_dilate(src, kernel, *args, **kwargs)
        return _run(orig_dilate, src, kernel, args, kwargs, _limits(src)[0])

    def erode(src, kernel, *args, **kwargs):
        if not _small(src):
            return orig_erode(src, kernel, *args, **kwargs)
        return _run(orig_erode, src, kernel, args, kwargs, _limits(src)[1])

    def morphologyEx(src, op, kernel, *args, **kwargs):
        if not _small(src):
            return orig_morph(src, op, kernel, *args, **kwargs)
        it = kwargs.get("iterations", 1)
        if op == cv2.MORPH_OPEN:
            return dilate(erode(src, kernel, iterations=it), kernel, iterations=it)
        if op == cv2.MORPH_CLOSE:
            return erode(dilate(src, kernel, iterations=it), kernel, iterations=it)
        if op == cv2.MORPH_GRADIENT:
            return cv2.subtract(dilate(src, kernel, iterations=it), erode(src, kernel, iterations=it))
        # übrige Arten: mit Randspiegelung vergrößern (für die verwendeten Fälle nicht nötig)
        p = _pad_amount(src, kernel, it)
        big = cv2.copyMakeBorder(src, p, p, p, p, cv2.BORDER_REPLICATE)
        out = orig_morph(big, op, kernel, *args, **kwargs)
        return np.ascontiguousarray(out[p:p + src.shape[0], p:p + src.shape[1]])

    cv2.dilate, cv2.erode, cv2.morphologyEx = dilate, erode, morphologyEx


_fault_file = None


def install_faulthandler(path: str) -> None:
    """Bei einem Absturz in nativem Code (OpenCV, numpy) die Python-Aufrufstellen in [path] schreiben."""
    global _fault_file
    import faulthandler

    try:
        _fault_file = open(path, "a")
        _fault_file.write("--- Start ---" + chr(10))
        _fault_file.flush()
        faulthandler.enable(file=_fault_file, all_threads=True)
    except Exception as e:
        print("faulthandler:", e, file=sys.stderr)


def configure(models_dir: str) -> None:
    """Modellpfade setzen – muss vor dem Import der homr-Module laufen, die sie übernehmen."""
    global _configured
    if _configured:
        return
    _compat()
    import homr.segmentation.config as seg

    seg.segnet_path_onnx = os.path.join(models_dir, os.path.basename(seg.segnet_path_onnx))
    seg.segnet_path_onnx_fp16 = seg.segnet_path_onnx

    import homr.staff_relations as rel

    rel.model_path = os.path.join(models_dir, os.path.basename(rel.model_path))
    rel.horizontal_model_path = os.path.join(models_dir, os.path.basename(rel.horizontal_model_path))
    # Standardargumente wurden beim Import gebunden – Funktionen mit neuen Pfaden versehen
    rel.StaffRelationModel.__init__.__defaults__ = (rel.model_path,)
    rel.get_model.__defaults__ = (rel.model_path,)

    _patch_piano_grouping(rel)
    _patch_time_signatures()
    _patch_box_merging()
    _patch_decoder()

    import homr.transformer.configs as cfg

    _orig_init = cfg.FilePaths.__init__

    def _init(self, *a, **k):
        _orig_init(self, *a, **k)
        for attr in ("encoder_path", "decoder_path", "encoder_path_fp16", "decoder_path_fp16"):
            setattr(self, attr, os.path.join(models_dir, os.path.basename(getattr(self, attr))))

    cfg.FilePaths.__init__ = _init
    cfg.default_config = cfg.Config()
    _configured = True


def _patch_decoder() -> None:
    """
    Tempo (nur Android): homrs Decoder-Schleife läuft in Kotlin (`OrtBridge.decode`) – dieselbe gierige Auswahl, derselbe
    Abbruch, derselbe Cache. Hier werden aus den gewählten Token die Symbole genau wie in homr gebaut. Spart je Schritt
    die Umwandlungen zwischen Python und Java (866 Schritte auf einer dichten Seite).
    """
    import numpy as np
    import onnxruntime as ort_mod

    bridge = getattr(ort_mod, "_Bridge", None)
    if bridge is None or not hasattr(bridge, "decode") or os.environ.get("VOMBLATT_PY_DECODER"):
        return
    # Vergleich (Testfassung): Datei „py_decoder“ im App-Ordner → homrs Python-Schleife
    if os.path.exists(os.path.join(os.environ.get("HOME", ""), "py_decoder")):
        print("Decoder: Python-Schleife (Vergleich)", file=sys.stderr)
        return
    import homr.transformer.decoder_inference as di

    if getattr(di, "_vomblatt_decoder", False):
        return

    def generate(self, start_tokens, nonote_tokens, **kwargs):
        context = np.ascontiguousarray(kwargs["context"])
        full = ort_mod._to_java(context)
        reduced = ort_mod._to_java(np.ascontiguousarray(context[:, :1]))
        heads = self.config.decoder_heads
        # Schritt-Decoder (tools/make_decoder_step.py --bias): ab Schritt 1 ohne wachsenden Cross-Cache – gleichwertig,
        # ~35 % schnellere Decoder-Schritte. Fehlt die Datei oder liegt „no_step“ im App-Ordner: wie bisher
        step = getattr(self, "_vb_step", False)
        if step is False:
            step = None
            if not os.path.exists(os.path.join(os.environ.get("HOME", ""), "no_step")):
                path = self.config.filepaths.decoder_path.replace("decoder_pytorch_model_", "decoderstep_")
                try:
                    step = ort_mod.InferenceSession(path)._s
                except Exception as e:
                    print("Schritt-Decoder nicht verfügbar:", e, file=sys.stderr)
            self._vb_step = step
        try:
            res = bridge.decode(
                self.net._s, full, reduced, int(np.asarray(start_tokens).reshape(-1)[0]),
                int(np.asarray(nonote_tokens).reshape(-1)[0]), self.config.decoder_depth * 4, heads,
                self.config.decoder_dim // heads, self.max_seq_len, int(self.eos_token), step,
            )
        finally:
            bridge.close(full)
            bridge.close(reduced)
        tokens = np.array(list(res.tokens), dtype=np.int64).reshape(-1, 6)
        shape = [int(v) for v in res.attentionShape]
        att = np.array(list(res.attention), dtype=np.float32)
        per = int(np.prod(shape)) if shape else 0
        symbols = []
        for k in range(tokens.shape[0]):
            r, pch, lft, pos, art, slr = (np.array([[int(v)]]) for v in tokens[k])
            attention = att[k * per:(k + 1) * per].reshape(shape) if per else None
            symbols.append(di.EncodedSymbol(
                rhythm=di.detokenize(r, self.inv_rhythm_vocab)[0],
                pitch=di.detokenize(pch, self.inv_pitch_vocab)[0],
                lift=di.detokenize(lft, self.inv_lift_vocab)[0],
                articulation=di.detokenize(art, self.inv_articulation_vocab)[0],
                slur=di.detokenize(slr, self.inv_slur_vocab)[0],
                position=di.detokenize(pos, self.inv_position_vocab)[0],
                coordinates=attention,
            ))
        return symbols

    di.ScoreDecoder.generate = generate
    di._vomblatt_decoder = True


def _patch_box_merging() -> None:
    """
    Tempo: homr vergleicht beim Zusammenfassen überlappender Rahmen jede Gruppe mit jeder (Bohemian S. 5: 1,6 Mio.
    Paarprüfungen, ~5 s auf dem Handy). Die Schnellprüfung darin (Mittelpunktabstand > Summe der großen Achsen → keine
    Berührung) lässt sich vorab für ganze Gruppen machen: nach x sortiert werden nur Gruppen verglichen, deren Bereiche
    [Mitte − Achse, Mitte + Achse] sich in x und y überschneiden. Übersprungen werden nur Paare, die homrs eigene
    Prüfung ohnehin verwürfe – Gruppen und ihre Reihenfolge bleiben identisch (Union-Find, Ausgabe nach Index).
    """
    import homr.bounding_boxes as bb

    if getattr(bb, "_vomblatt_merge", False) or os.environ.get("VOMBLATT_ORIGINAL_MERGE"):
        return

    def extent(group):
        x0 = y0 = float("inf")
        x1 = y1 = float("-inf")
        for b in group:
            (cx, cy), axes, _ = b.rotated_box if isinstance(b, bb.BoundingBox) else b.box
            r = max(axes) + 1e-3  # Marge gegen Rundung – nur Vorfilter, homrs Prüfung entscheidet
            x0 = min(x0, cx - r); x1 = max(x1, cx + r); y0 = min(y0, cy - r); y1 = max(y1, cy + r)
        return x0, x1, y0, y1

    def merge(groups):
        n = len(groups)
        uf = bb.UnionFind(n)
        ext = [extent(g) for g in groups]
        order = sorted(range(n), key=lambda k: ext[k][0])
        for a_pos, i in enumerate(order):
            ex = ext[i]
            for j in order[a_pos + 1:]:
                ey = ext[j]
                if ey[0] > ex[1]:
                    break  # alle weiteren beginnen noch weiter rechts
                if ey[2] > ex[3] or ex[2] > ey[3]:
                    continue
                lo, hi = (i, j) if i < j else (j, i)
                if bb._do_groups_overlap(groups[lo], groups[hi]):
                    uf.union(lo, hi)
        merged = {}
        for i in range(n):
            merged.setdefault(uf.find(i), []).extend(groups[i])
        return list(merged.values())

    bb._merge_groups_optimized = merge

    # Schnellprüfung „können sich berühren“: Mitte und große Achse je Rahmen einmal bestimmen und am Objekt merken
    # (Rahmen werden nach dem Anlegen nie verändert); gleiche Rechnung wie homr, ohne isinstance je Aufruf
    def shape_of(o):
        try:
            return o._vb_touch
        except AttributeError:
            pass
        if isinstance(o, bb.BoundingBox):
            c, axes, _ = o.rotated_box
        elif isinstance(o, bb.AngledBoundingBox):
            c, axes, _ = o.box
        else:
            raise ValueError(f"Unknown type {type(o)}")
        v = (c[0], c[1], max(axes))
        o._vb_touch = v
        return v

    def touch(self, other):
        x1, y1, m1 = shape_of(self)
        x2, y2, m2 = shape_of(other)
        distance = ((x1 - x2) ** 2 + (y1 - y2) ** 2) ** 0.5
        return not distance > m1 + m2

    bb.AngledBoundingBox._can_shapes_possibly_touch = touch
    bb._vomblatt_merge = True


def _patch_time_signatures() -> None:
    """
    Taktartwechsel: homrs Modell liest von einer Taktart nur den Nenner („timeSignature/4“), den Zähler schätzt
    der MusicXML-Erzeuger aus den Taktlängen bis zur nächsten Taktart. homrs Aufräumen verwirft aber jede Taktart,
    die wie die vorige aussieht – bei gleichem Nenner also jeden Wechsel (4/4 → 5/4 → 4/4, Bohemian Rhapsody T. 3).
    Hier bleibt eine Taktart erhalten, sobald seit der vorigen Noten standen; nur unmittelbare Wiederholungen
    (z. B. je System oben und unten) fallen weg. Ändert sich der geschätzte Zähler nicht, ist es kein Wechsel.
    """
    import homr.transformer.vocabulary as voc

    if getattr(voc, "_vomblatt_times", False):
        return
    orig = voc._remove_redudant_clefs_keys_and_time_signatures

    def keep_times(chords):
        # Schlüssel dem richtigen System zuordnen, bevor homr „doppelte“ Schlüssel entfernt
        try:
            flat = [sym for c in chords for sym in c]
            _fix_clef_positions([flat])
            fixed = _add_missing_clefs(flat)
            if len(fixed) != len(flat):
                # eingefügte Schlüssel als eigene Gruppen an ihre Stelle
                inserted = {id(x) for x in fixed} - {id(x) for x in flat}
                regrouped, k = [], 0
                for c in chords:
                    for sym in c:
                        while k < len(fixed) and id(fixed[k]) in inserted:
                            regrouped.append([fixed[k]]); k += 1
                        k += 1
                    regrouped.append(c)
                chords = regrouped
        except Exception as e:  # Nachbesserung darf die Erkennung nie verhindern
            print("Schlüssel:", e, file=sys.stderr)
        # Taktarten markieren, die nach Noten stehen; homrs Funktion entfernt sie sonst
        marked = []
        time = ""
        notes_since = True
        for chord in chords:
            out = []
            for sym in chord:
                r = sym.rhythm
                if r.startswith("timeSignature"):
                    if r != time or notes_since:
                        time = r
                        notes_since = False
                        out.append(("keep", sym))
                    continue
                if r.startswith(("note", "rest", "measureRest")):
                    notes_since = True
                out.append(("pass", sym))
            marked.append(out)
        cleaned = orig([[s for k, s in c if k == "pass"] for c in marked])
        # Taktarten wieder an ihre Stelle (vor den übrigen Zeichen des Akkords, wie bei homr)
        result = []
        for c, rest in zip(marked, cleaned):
            times = [s for k, s in c if k == "keep"]
            result.append(times + rest if times else rest)
        return result

    voc._remove_redudant_clefs_keys_and_time_signatures = keep_times
    voc._vomblatt_times = True


def _patch_piano_grouping(rel) -> None:
    """
    Klaviersatz: homrs Beziehungsmodell hält bei dichten Seiten manchmal die Bass-Zeile eines
    Systems und die Violin-Zeile des nächsten für „dasselbe System“. Ist die Seite erkennbar
    Klaviersatz (mindestens ein sicheres Klaviersystem), entscheidet bei solchen Paaren der
    Abstand: so eng wie in den sicheren Klaviersystemen → Klaviersystem, deutlich weiter → getrennt.
    """
    import numpy as np

    if getattr(rel, "_vomblatt_grouping", False):
        return
    orig = rel.group_staffs

    def group_staffs(staffs, pairs, probabilities):
        probs = np.array(probabilities, dtype=np.float32).copy()
        if len(pairs) == 0:
            return orig(staffs, pairs, probs)

        def gap(i, j):
            return float(staffs[j].min_y - staffs[i].max_y)

        sure = [gap(i, j) for (i, j), p in zip(pairs, probs) if p[rel.GRAND_STAFF] > 0.8]
        if sure:
            grand_gap = float(np.median(sure))
            for k, ((i, j), p) in enumerate(zip(pairs, probs)):
                if int(p.argmax()) != rel.SAME_SYSTEM:
                    continue
                g = gap(i, j)
                if g <= grand_gap * 1.35:
                    probs[k] = [0.0, 0.0, 1.0]
                else:
                    probs[k] = [1.0, 0.0, 0.0]
                print(f"Klaviersatz: Paar {i}/{j} Abstand {g:.0f} (Klaviersystem {grand_gap:.0f}) ->",
                      rel.CLASSES[int(probs[k].argmax())], file=sys.stderr)
        return orig(staffs, pairs, probs)

    rel.group_staffs = group_staffs
    rel._vomblatt_grouping = True


_LIFT_ALTER = {"_": 0, "N": 0, "#": 1, "b": -1, "##": 2, "bb": -2}
_ALTER_LIFT = {0: "_", 1: "#", -1: "b", 2: "##", -2: "bb"}
_last_predictions = {}


def _key_alter(fifths: int, step: str) -> int:
    if fifths > 0:
        return 1 if step in "FCGDAEB"[:fifths] else 0
    if fifths < 0:
        return -1 if step in "BEADGCF"[:-fifths] else 0
    return 0


def _is_note(s) -> bool:
    return s.rhythm.startswith("note") and isinstance(s.pitch, str) and len(s.pitch) >= 2 and s.pitch[0] in "CDEFGAB"


def _clef_center(rhythm: str):
    """Diatonische Stufe der Mittellinie für „clef_G2“, „clef_F4“, „clef_C3“ … (None, wenn unbekannt)."""
    try:
        sign, line = rhythm.split("_")[1][0], int(rhythm.split("_")[1][1])
    except (IndexError, ValueError):
        return None
    base = {"G": 4 * 7 + 4, "F": 3 * 7 + 3, "C": 4 * 7 + 0}.get(sign)
    return None if base is None else base + (3 - line) * 2


def _step(pitch: str) -> int:
    return int(pitch[-1]) * 7 + "CDEFGAB".index(pitch[0])


def _fix_clef_positions(voices) -> int:
    """
    Schlüsselwechsel mitten in der Zeile: homrs Modell ordnet den Schlüssel manchmal dem falschen System zu
    (Bohemian Rhapsody T. 27: der zurückkehrende Bassschlüssel des unteren Systems als „upper“ – danach standen
    oben Bass-, unten Violinschlüssel). Die Tonhöhen sind davon unabhängig richtig; entschieden wird deshalb nach
    den folgenden Noten: Der Schlüssel gehört zu dem System, in dem sie mit weniger Hilfslinien stehen.
    """
    fixed = 0
    for seq in voices:
        if not any(getattr(t, "position", "") == "lower" for t in seq if _is_note(t)):
            continue  # kein Klaviersystem
        cur = {"upper": None, "lower": None}
        seen_note = False
        for i, t in enumerate(seq):
            r = t.rhythm
            if r == "newline":
                seen_note = False
                continue
            if _is_note(t):
                seen_note = True
                continue
            if not r.startswith("clef_"):
                continue
            pos = "lower" if t.position == "lower" else "upper"
            other = "upper" if pos == "lower" else "lower"
            new = _clef_center(r)
            if not seen_note or new is None or cur[pos] is None or cur[other] is None:
                cur[pos] = new
                continue
            # folgende Noten je System bis zum nächsten Schlüssel oder Zeilenende (höchstens 16 je System)
            ahead = {"upper": [], "lower": []}
            for u in seq[i + 1:]:
                if u.rhythm == "newline" or u.rhythm.startswith("clef_"):
                    break
                if _is_note(u):
                    lst = ahead["lower" if u.position == "lower" else "upper"]
                    if len(lst) < 16:
                        lst.append(_step(u.pitch))

            def cost(state):
                return sum(max(0, abs(d - state[st]) - 5) for st in ahead for d in ahead[st])

            claimed = dict(cur); claimed[pos] = new
            swapped = dict(cur); swapped[other] = new
            a, b = cost(claimed), cost(swapped)
            if ahead[other] and b + 2 < a and b < 0.5 * a:
                print(f"Schlüssel: {r} gehört ins System {other} (Hilfslinien {a} → {b})", file=sys.stderr)
                t.position = other
                pos, other = other, pos
                fixed += 1
            cur[pos] = new
    return fixed


def _add_missing_clefs(seq) -> list:
    """
    Fehlender Schlüssel am Taktanfang: Ein Wechsel zurück steht im Druck oft direkt vor dem Taktstrich, homrs Modell
    übersieht ihn dann (Bohemian Rhapsody T. 16, 19: Bass-System bleibt im Violinschlüssel, G2/G1 hängen weit unter
    den Linien). Die Tonhöhen sind richtig; steht ein System im „fremden“ Schlüssel (unten Violin-, oben Bassschlüssel)
    und passen seine Noten im Takt mit dem eigenen deutlich besser, wird dieser am Taktanfang eingefügt. Nur die
    Rückkehr – ein Wechsel weg vom eigenen Schlüssel wird nie erfunden (Sunshine T. 4: falsch zugeordnete Noten).
    """
    from homr.transformer.vocabulary import EncodedSymbol

    if not any(getattr(t, "position", "") == "lower" for t in seq if _is_note(t)):
        return seq
    treble, bass = _clef_center("clef_G2"), _clef_center("clef_F4")
    out = []
    cur = {"upper": None, "lower": None}
    at_start = True  # Taktanfang (nach Taktstrich oder Zeilenbeginn)
    for i, t in enumerate(seq):
        r = t.rhythm
        if r.startswith("clef_"):
            cur["lower" if t.position == "lower" else "upper"] = _clef_center(r)
        elif r == "newline" or "barline" in r:
            at_start = True
            out.append(t)
            continue
        elif _is_note(t) and at_start:
            at_start = False
            # Noten je System bis zum nächsten Taktstrich oder Schlüssel dieses Systems
            for pos in ("upper", "lower"):
                c = cur[pos]
                own = treble if pos == "upper" else bass
                if c is None or c == own:
                    continue
                other = own
                notes = []
                for u in seq[i:]:
                    if u.rhythm == "newline" or "barline" in u.rhythm:
                        break
                    if u.rhythm.startswith("clef_") and (u.position == "lower") == (pos == "lower"):
                        break
                    if _is_note(u) and (u.position == "lower") == (pos == "lower"):
                        notes.append(_step(u.pitch))
                if not notes:
                    continue
                a = sum(max(0, abs(d - c) - 5) for d in notes)
                b = sum(max(0, abs(d - other) - 5) for d in notes)
                if a / len(notes) >= 6 and b <= a / 4:
                    name = "clef_F4" if other == bass else "clef_G2"
                    print(f"Schlüssel: {name} am Taktanfang ergänzt (Hilfslinien {a} → {b})", file=sys.stderr)
                    out.append(EncodedSymbol(name, position=pos))
                    cur[pos] = other
        elif _is_note(t):
            at_start = False
        out.append(t)
    return out


def _fix_key_changes(voices) -> int:
    """
    Tonartwechsel ohne Doppelstrich davor sind fast immer Lesefehler (homr liest z. B. in F-Dur
    plötzlich C-Dur und dann folgerichtig H statt B). Solche Wechsel werden zurückgenommen und
    die Noten danach wieder auf die alte Tonart bezogen.
    """
    fixed = 0
    for seq in voices:
        prev = None
        for i, s in enumerate(seq):
            if not s.rhythm.startswith("keySignature_"):
                continue
            k = int(s.rhythm.split("_")[1])
            if prev is not None and k != prev:
                bar = next((t.rhythm for t in reversed(seq[:i]) if "barline" in t.rhythm or "repeat" in t.rhythm), "")
                # Bestätigt: Die nächste Zeile beginnt mit der neuen Tonart (Bohemian Rhapsody T. 25 – Wechsel nach
                # Es-Dur hinter einfachem Taktstrich). Ein Lesefehler wiederholt sich am Zeilenanfang nicht.
                # homrs Aufräumen hat eine gleiche Tonart am Zeilenanfang schon entfernt: keine Tonart vor den ersten
                # Noten der nächsten Zeile heißt also „unverändert“ = neue Tonart.
                nl = next((j for j in range(i + 1, len(seq)) if seq[j].rhythm == "newline"), None)
                nk = None
                for t in seq[nl + 1:] if nl is not None else []:
                    if t.rhythm.startswith("keySignature_"):
                        nk = int(t.rhythm.split("_")[1])
                        break
                    if _is_note(t) or "barline" in t.rhythm:
                        nk = k
                        break
                has_next_line = nl is not None and any(_is_note(u) for u in seq[nl + 1:])
                # letzte Zeile: keine Zeile, die bestätigen könnte. Lesefehler ändern um ein Vorzeichen (Hey Roger: F → C),
                # echte Wechsel am Seitenende oft um mehr (Bohemian T. 55: 3 ♭ → 3 ♯) – die bleiben
                if not has_next_line and abs(k - prev) >= 2:
                    print(f"Tonart: Wechsel {prev}->{k} in der letzten Zeile (mehrere Vorzeichen), bleibt", file=sys.stderr)
                    prev = k
                    continue
                if nk == k:
                    print(f"Tonart: Wechsel {prev}->{k} von der nächsten Zeile bestätigt", file=sys.stderr)
                    prev = k
                    continue
                if bar == "barline":  # einfacher Taktstrich -> kein echter Tonartwechsel
                    print(f"Tonart: Wechsel {prev}->{k} ohne Doppelstrich verworfen", file=sys.stderr)
                    s.rhythm = f"keySignature_{prev}"
                    for t in seq[i + 1:]:
                        if t.rhythm.startswith("keySignature_"):
                            break
                        if not _is_note(t):
                            continue
                        a_prev, a_new = _key_alter(prev, t.pitch[0]), _key_alter(k, t.pitch[0])
                        if a_prev != a_new and _LIFT_ALTER.get(t.lift, 0) == a_new:
                            t.lift = _ALTER_LIFT[a_prev]
                    k = prev
                    fixed += 1
            prev = k
    return fixed


def _fix_accidentals(voices, multi_staffs, to_input_image) -> int:
    """
    Fingersatz-Ziffern werden manchmal als ♭ gelesen. Ein Vorzeichen, das nicht schon durch die
    Tonart oder früher im Takt gilt, muss als Zeichen links vom Notenkopf stehen – sonst fällt es weg.
    Beweis ist die Ebene „Schlüssel/Vorzeichen“ der Segmentierung (clefs_keys).
    """
    import numpy as np

    pred = _last_predictions.get("p")
    if pred is None:
        return 0
    mask = pred.clefs_keys
    units = [float(st.average_unit_size) for ms in multi_staffs for st in ms.staffs]
    if not units:
        return 0
    u = float(np.median(units))
    # Fotokoordinaten -> Segmentierungskoordinaten (die Abbildung ist Skalierung + Verschiebung)
    ox, oy = to_input_image((0.0, 0.0))
    sx = (to_input_image((1000.0, 0.0))[0] - ox) / 1000.0
    sy = (to_input_image((0.0, 1000.0))[1] - oy) / 1000.0
    import cv2

    staffs = [st for ms in multi_staffs for st in ms.staffs]
    # Maßstab: mittlere Notenkopfhöhe (aus der Ebene „Notenköpfe“)
    nh, _, hstats, _ = cv2.connectedComponentsWithStats((pred.notehead > 0).astype(np.uint8), connectivity=8)
    heads = [hstats[i, cv2.CC_STAT_HEIGHT] for i in range(1, nh) if hstats[i, cv2.CC_STAT_AREA] > 15]
    head = float(np.median(heads)) if heads else u * 0.6
    # Kandidaten für gedruckte Vorzeichen: hohe, schmale Zeichen der Ebene „Schlüssel/Vorzeichen“.
    # ♯ ♭ ♮ sind gut doppelt so hoch wie ein Notenkopf; Fingersatz-Ziffern etwa so hoch wie einer.
    # Notenlinien zerschneiden ♯ und ♮ – vorher senkrecht zusammenfügen.
    joined = cv2.dilate((mask > 0).astype(np.uint8), np.ones((max(3, int(head * 0.5)), 1), np.uint8))
    n, _, stats, centers = cv2.connectedComponentsWithStats(joined, connectivity=8)
    signs = []
    for i in range(1, n):
        cw, ch = stats[i, cv2.CC_STAT_WIDTH], stats[i, cv2.CC_STAT_HEIGHT]
        if 1.75 * head <= ch <= 4.5 * head and cw <= 1.6 * head:
            signs.append((float(centers[i][0]), float(centers[i][1])))

    def has_sign(coords, fifths) -> bool:
        if coords is None:
            return True  # ohne Position nichts verwerfen
        px = (coords[0] - ox) / sx
        py = (coords[1] - oy) / sy
        # Zeilenanfang (Schlüssel, Tonart, Taktart) zählt nicht als Vorzeichen
        left = 0.0
        for st in staffs:
            if st.min_y - 4 * u <= py <= st.max_y + 4 * u:
                left = max(left, st.min_x + (5 + 1.2 * abs(fifths)) * head)
        # Die Position stammt aus der Aufmerksamkeit des Modells und ist ungenau – großzügiges Fenster
        return any(
            max(left, px - 8 * head) <= cx <= px + 3 * head and abs(cy - py) <= 3 * head
            for cx, cy in signs
        )

    removed = 0
    for seq in voices:
        key = 0
        state = {}
        for s in seq:
            r = s.rhythm
            if r.startswith("keySignature_"):
                key = int(r.split("_")[1])
                continue
            if "barline" in r or r == "newline":
                state.clear()
                continue
            if not _is_note(s):
                continue
            a = _LIFT_ALTER.get(s.lift, 0)
            k = (s.position, s.pitch)
            expected = state.get(k, _key_alter(key, s.pitch[0]))
            if a == expected:
                continue
            if has_sign(s.image_coordinates, key):
                state[k] = a
                continue
            print(f"Vorzeichen verworfen: {s.pitch} {s.lift} -> {_ALTER_LIFT.get(expected, '_')}", file=sys.stderr)
            s.lift = _ALTER_LIFT.get(expected, "_")
            removed += 1
    return removed


def _debug() -> bool:
    return bool(os.environ.get("VOMBLATT_DEBUG"))


def _detect_pedals(multi_staffs, to_input_image) -> list:
    """
    Pedallinien unter den Klaviersystemen finden (homr kennt kein Pedal):
    ┌ Senkrechtstrich = treten, ── Linie = gehalten, ^ Kerbe = wechseln, ┘ Ende = lösen.
    Liefert Ereignisse in Fotokoordinaten: {"x", "y", "type": down|change|up, "system": i}.
    """
    import cv2
    import numpy as np

    pred = _last_predictions.get("p")
    if pred is None:
        return []
    gray = pred.preprocessed
    if gray.ndim == 3:
        gray = cv2.cvtColor(gray, cv2.COLOR_BGR2GRAY)
    ink = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 8)
    h, w = ink.shape[:2]
    systems = sorted(multi_staffs, key=lambda ms: ms.staffs[0].min_y)
    events = []
    for si, ms in enumerate(systems):
        st = max(ms.staffs, key=lambda s: s.max_y)  # unterstes System (Bass)
        ls = float(np.median([np.median(np.diff(p.y[-5:])) for p in st.grid])) or 10.0
        y0 = int(st.max_y + 0.8 * ls)
        nxt = systems[si + 1].staffs[0].min_y if si + 1 < len(systems) else h
        y1 = int(min(h, st.max_y + 8.5 * ls, nxt - 1.5 * ls))
        x0, x1 = int(max(0, st.min_x - 2 * ls)), int(min(w, st.max_x + 2 * ls))
        if y1 - y0 < 2 * ls or x1 <= x0:
            continue
        region = ink[y0:y1, x0:x1]
        rh, rw = region.shape[:2]
        _, comp = cv2.connectedComponents(region, connectivity=8)

        def comp_at(x, yy):
            xa, xb = int(max(0, x - 2)), int(min(rw, x + 3))
            ya, yb = int(max(0, yy - 3)), int(min(rh, yy + 4))
            ids = comp[ya:yb, xa:xb]
            ids = ids[ids > 0]
            return set(int(i) for i in np.unique(ids))
        # Lange, dünne waagrechte Stücke behalten (Hilfslinien ~1,6 ls sind kürzer, Balken dicker)
        kern = cv2.getStructuringElement(cv2.MORPH_RECT, (max(3, int(2.5 * ls)), 1))
        horiz = cv2.morphologyEx(region, cv2.MORPH_OPEN, kern)
        n, lab, stats, _ = cv2.connectedComponentsWithStats(horiz, connectivity=8)
        segs = []
        for i in range(1, n):
            sx, sy, sw, sh, area = stats[i]
            if sw < 2.5 * ls or area / max(sw, 1) > 0.45 * ls:
                continue
            sub = lab[sy:sy + sh, sx:sx + sw] == i
            k = max(2, min(6, sw // 4))
            yl = sy + float(np.mean(np.where(sub[:, :k])[0]))
            yr = sy + float(np.mean(np.where(sub[:, -k:])[0]))
            segs.append([sx, sx + sw, yl, yr])
        segs.sort()

        def ink_above(xa, xb, yy) -> int:
            box = region[int(max(0, yy - 1.8 * ls)):int(max(1, yy - 0.25 * ls)), int(max(0, xa)):int(min(rw, max(xa + 1, xb)))]
            return int(np.count_nonzero(box))

        def touches_line(xa, xb, yy) -> bool:
            # Schenkel der Zacke setzen direkt auf der Linie an (ein darüber schwebender Akzent nicht)
            band = region[int(max(0, yy - 0.7 * ls)):int(max(1, yy - 0.1 * ls)), int(max(0, xa)):int(min(rw, max(xa + 1, xb)))]
            return int(np.count_nonzero(band)) > 0.3 * ls

        def ink_below(xa, xb, yy) -> int:
            # Eine Pedal-Zacke ragt nur nach oben; Schrift oder Noten reichen auch unter die Linie
            box = region[int(yy + 0.35 * ls):int(min(rh, yy + 1.5 * ls)), int(max(0, xa)):int(min(rw, max(xa + 1, xb)))]
            return int(np.count_nonzero(box))

        def vertical_up(x, yy) -> bool:
            # echter Senkrechtstrich: in einer schmalen Spalte durchgehend Tinte nach oben
            top, bottom = int(max(0, yy - 2.0 * ls)), int(max(1, yy - 0.15 * ls))
            best = 0
            for cx in range(int(max(0, x - 0.6 * ls)), int(min(rw, x + 0.6 * ls + 1))):
                run = 0
                for yv in range(bottom, top, -1):
                    if region[yv, cx]:
                        run += 1
                    elif run > 0:
                        break
                best = max(best, run)
            return best >= 0.75 * ls

        lines = []
        for sx, ex, yl, yr in segs:
            if lines:
                ln = lines[-1]
                gap = sx - ln["x1"]
                yy = (ln["yr"] + yl) / 2
                # Echte Zacke: Tinte über der Lücke, nichts darunter, und mit beiden Linienenden verbunden
                notch = (gap > 0.4 * ls and ink_above(ln["x1"], sx, yy) > 0.4 * ls
                         and ink_below(ln["x1"], sx, yy) < 0.3 * ls
                         and (bool(comp_at(ln["x1"] - 1, ln["yr"]) & comp_at(sx + 1, yl))
                              or touches_line(ln["x1"], sx, yy)))
                if -ls <= gap <= 4.0 * ls and abs(yl - ln["yr"]) <= (2.0 if notch else 1.4) * ls:
                    if notch:
                        ln["changes"].append((ln["x1"] + sx) / 2)   # Zacke = Pedalwechsel
                        ln["x1"], ln["yr"] = max(ln["x1"], ex), yr
                        continue
                    if gap <= 1.2 * ls or (gap <= 3.0 * ls and abs(yl - ln["yr"]) <= 0.5 * ls):  # Hals / blasse Stelle
                        ln["x1"], ln["yr"] = max(ln["x1"], ex), yr
                        continue
            lines.append({"x0": sx, "x1": ex, "yl": yl, "yr": yr, "changes": []})

        for ln in lines:
            if ln["x1"] - ln["x0"] < 3.5 * ls:
                continue
            start_tick = vertical_up(ln["x0"], ln["yl"])
            end_tick = vertical_up(ln["x1"], ln["yr"])
            starts_system = ln["x0"] + x0 <= st.min_x + 6 * ls
            ends_system = ln["x1"] + x0 >= st.max_x - 3 * ls
            if _debug():
                print(f"  Pedal-Kandidat Sys{si}: x {ln['x0'] + x0:.0f}-{ln['x1'] + x0:.0f} Zacken {len(ln['changes'])} "
                      f"Anfangsstrich {start_tick} Endstrich {end_tick}", file=sys.stderr)
            if not (start_tick or starts_system or ln["changes"]):
                continue  # ohne Anfangsstrich und ohne Zacken eher Bogen oder Unterstreichung
            if not (start_tick or ln["changes"]) and ln["x1"] - ln["x0"] < 8 * ls:
                continue  # nur „am Zeilenanfang“ reicht bei kurzen Stücken nicht (Papierkante, Rahmen)
            pts = [("down", ln["x0"], ln["yl"])]
            pts += [("change", c, (ln["yl"] + ln["yr"]) / 2) for c in ln["changes"]]
            if end_tick or not ends_system:  # läuft die Linie bis zum Zeilenende weiter, bleibt das Pedal unten
                pts.append(("up", ln["x1"], ln["yr"]))
            for kind, x, yy in pts:
                px, py = to_input_image((float(x + x0), float(yy + y0)))
                events.append({"x": round(px, 1), "y": round(py, 1), "type": kind, "system": si})
    return events


def _detect_hairpins(multi_staffs, to_input_image) -> list:
    """
    Gabeln (cresc. <, decresc. >) zwischen den Systemen eines Klaviersystems und darunter finden:
    zwei gerade, dünne Linien, die sich an einem Ende treffen und am anderen ~1 Linienabstand offen sind.
    Liefert {"x0", "x1", "y", "type": cresc|dim, "system": i} in Fotokoordinaten.
    """
    import cv2
    import numpy as np

    pred = _last_predictions.get("p")
    if pred is None:
        return []
    gray = pred.preprocessed
    if gray.ndim == 3:
        gray = cv2.cvtColor(gray, cv2.COLOR_BGR2GRAY)
    ink = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 31, 8)
    h, w = ink.shape[:2]
    systems = sorted(multi_staffs, key=lambda ms: ms.staffs[0].min_y)
    found = []
    for si, ms in enumerate(systems):
        for st in ms.staffs:
            grid = st.grid
            n_lines = len(grid[0].y)
            ls = float(np.median([np.median(np.diff(p.y[:5])) for p in grid])) or 10.0
            xs = np.array([float(p.x) for p in grid])
            bands = []
            if n_lines >= 10:
                # zwischen oberem und unterem System
                bands.append((np.array([float(p.y[4]) for p in grid]) + 0.6 * ls, np.array([float(p.y[5]) for p in grid]) - 0.6 * ls))
            bottom = np.array([float(p.y[-1]) for p in grid])
            nxt = systems[si + 1].staffs[0].min_y if si + 1 < len(systems) else h
            bands.append((bottom + 0.6 * ls, np.minimum(bottom + 6.0 * ls, nxt - 1.0 * ls)))
            for top_y, bot_y in bands:
                mask = np.zeros_like(ink)
                poly = np.array([[x, y] for x, y in zip(xs, top_y)] + [[x, y] for x, y in zip(xs[::-1], bot_y[::-1])], np.int32)
                cv2.fillPoly(mask, [poly], 255)
                band = cv2.bitwise_and(ink, mask)
                # nur dünne Striche: dickere Flächen (Notenköpfe, Balken, Schrift-Kerne) abziehen
                thick = cv2.morphologyEx(band, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (max(5, int(0.65 * ls)),) * 2))
                thin = cv2.subtract(band, cv2.dilate(thick, np.ones((3, 3), np.uint8)))
                if os.environ.get("VOMBLATT_DEBUG_IMG"):
                    cv2.imwrite(os.path.join(os.environ["VOMBLATT_DEBUG_IMG"], f"pin_{si}_{len(bands)}_{int(top_y[0])}.png"), np.hstack([band, thin]))
                # Senkrechtes (Taktstriche, Hälse) entfernen, sonst hängt es an der Gabel
                vert = cv2.morphologyEx(thin, cv2.MORPH_OPEN, np.ones((max(3, int(1.2 * ls)), 1), np.uint8))
                thin = cv2.subtract(thin, vert)
                # kleine Lücken (unscharfe Spitze, Taktstrich-Kreuzung) schließen
                thin = cv2.morphologyEx(thin, cv2.MORPH_CLOSE, np.ones((3, max(3, int(0.5 * ls))), np.uint8))
                n, lab, stats, _ = cv2.connectedComponentsWithStats(thin, connectivity=8)
                for ci in range(1, n):
                    cx, cy, cw, ch, area = stats[ci]
                    if cw < 6 * ls or ch < 0.5 * ls or ch > 3.5 * ls:
                        continue
                    sub = lab[cy:cy + ch, cx:cx + cw] == ci
                    cols = np.where(sub.any(axis=0))[0]
                    if len(cols) < 0.8 * cw:
                        continue
                    top = np.array([np.argmax(sub[:, c]) for c in range(cw)], float)
                    bot = np.array([ch - 1 - np.argmax(sub[::-1, c]) for c in range(cw)], float)
                    has = sub.any(axis=0)
                    open_ = np.where(has, bot - top, np.nan)
                    # Spalten mit zwei getrennten Strichen (Gabel innen hohl)
                    hollow = np.array([has[c] and (np.diff(sub[:, c].astype(np.int8)) == 1).sum() + int(sub[0, c]) >= 2 for c in range(cw)])

                    def piece(a, b):
                        """Abschnitt [a, b) als Gabel prüfen; gibt (art, öffnung) oder None."""
                        seg = open_[a:b]
                        if b - a < 6 * ls or np.isnan(seg).mean() > 0.2:
                            return None
                        k = max(2, int(0.15 * (b - a)))
                        left, right = np.nanmedian(seg[:k]), np.nanmedian(seg[-k:])
                        mid = np.nanmedian(seg[(b - a) // 2 - k // 2:(b - a) // 2 + k // 2 + 1])
                        narrow, wide = min(left, right), max(left, right)
                        if wide - narrow < 0.55 * ls or narrow > 0.9 * ls or wide > 2.6 * ls:
                            return None
                        if abs(mid - (left + right) / 2) > 0.3 * ls:  # Öffnung wächst gleichmäßig
                            return None
                        if hollow[a:b].mean() < 0.4:  # zwei Schenkel, keine gefüllte Fläche
                            return None
                        return ("cresc" if left < right else "dim"), wide

                    # „<>“ ohne Lücke: an der breitesten Stelle teilen
                    sm = np.convolve(np.nan_to_num(open_), np.ones(5) / 5, "same")
                    peak = int(np.argmax(sm))
                    parts = [(0, cw)]
                    if 6 * ls <= peak <= cw - 6 * ls:
                        parts = [(0, peak), (peak, cw)]
                    for a, b in parts:
                        r = piece(a, b)
                        if _debug():
                            print(f"  Gabel-Kandidat Sys{si}: x {cx + a}-{cx + b} h {ch / ls:.1f} ls -> {r}", file=sys.stderr)
                        if r is None:
                            continue
                        kind, wide = r
                        yc = cy + float(np.nanmedian((top[a:b] + bot[a:b]) / 2))
                        p0 = to_input_image((float(cx + a), yc))
                        p1 = to_input_image((float(cx + b), yc))
                        if _debug():
                            print(f"  Gabel Sys{si}: {kind} x {cx + a}-{cx + b} offen {wide / ls:.2f} ls", file=sys.stderr)
                        found.append({"x0": round(p0[0], 1), "x1": round(p1[0], 1), "y": round(p0[1], 1), "type": kind, "system": si})
    return found


class _ProgressStream(io.TextIOBase):
    """Liest homrs Statusmeldungen mit und übersetzt sie in Fortschritt (Phase, erledigt, gesamt)."""

    def __init__(self, cb, echo):
        self.cb = cb
        self.echo = echo
        self.total = 0
        self.buf = ""

    def write(self, s):
        if self.echo is not None:
            try:
                self.echo.write(s)
            except Exception:
                pass
        self.buf += s
        while "\n" in self.buf:
            line, self.buf = self.buf.split("\n", 1)
            self._line(line)
        return len(s)

    def _line(self, line):
        if self.cb is None:
            return
        if line.startswith("Creating bounds for noteheads") or line.startswith("Found ") and "staff line fragments" in line:
            self.cb("STAFFS", 0, 0)
        m = re.match(r"Systems after the repair of the layout: \[(.*)\]", line)
        if m:
            # Jede Notenzeile (bzw. jedes Klaviersystem) wird einzeln gelesen
            self.total = sum(int(x) for x in m.group(1).split(",") if x.strip())
        m = re.match(r"Running TrOmr inference on staff image (\d+)", line)
        if m:
            self.cb("READ", int(m.group(1)) + 1, max(self.total, int(m.group(1)) + 1))
        if line.startswith("Writing XML") or line.startswith("Found title"):
            self.cb("WRITE", 0, 0)


def _true_bars(multi_staffs):
    import numpy as np
    """
    Erkannte Taktstriche ohne Bleistift: Im Klaviersystem geht ein Taktstrich durch beide Systeme und wird in jedem
    erkannt. Ein senkrechter Bleistiftbogen durch ein System (Harmony T. 4, 8, 12, 16) erscheint nur in einem – er ist
    kein Taktstrich (sonst bekäme das Original-Layout einen falschen Takt-Anker und die Zeichnung würde als „Druck“
    zerschnitten). Zeilen mit nur einem System bleiben unverändert.
    """
    bars = list(_last_predictions.get("bars", []))
    if not bars:
        return bars
    where = []
    for bl in bars:
        cx_, cy_ = float(bl.center[0]), float(bl.center[1])
        # System mit der nächsten Mitte (die Bereiche der beiden Systeme eines Klavierpaars überlappen sich)
        hit = None
        best = 1e9
        for mi_, ms in enumerate(multi_staffs):
            for si_, st in enumerate(ms.staffs):
                if st.min_y - 2 <= cy_ <= st.max_y + 2 and st.min_x - 5 <= cx_ <= st.max_x + 5:
                    d_ = abs(cy_ - (float(st.min_y) + float(st.max_y)) / 2)
                    if d_ < best:
                        best = d_
                        # homr führt ein Klavierpaar oft als EIN System mit 10 Linien: oben/unten an der Mitte trennen
                        half = 0
                        try:
                            if len(st.grid[0].y) >= 10:
                                mid_ = float(np.median([(float(gp.y[4]) + float(gp.y[5])) / 2 for gp in st.grid]))
                                half = 0 if cy_ < mid_ else 1
                        except Exception:
                            pass
                        hit = (mi_, si_ * 2 + half, float(st.average_unit_size), len(ms.staffs) >= 2 or half >= 0 and len(st.grid[0].y) >= 10)
        where.append((cx_, hit))
    if _debug():
        print("Taktstriche:", [(round(c), h[:2] if h else None, round(float(b.center[1])), [round(float(v)) for v in b.size]) for (c, h), b in zip(where, bars)], file=sys.stderr)
    # Bild der Segmentierung (gleiche Koordinaten wie die Taktstriche): läuft eine gerade Linie durch die Lücke zwischen
    # den beiden Systemen? So sind Klavier-Taktstriche gedruckt – homr erkennt sie manchmal nur in einem System
    pre = None
    try:
        pp_ = _last_predictions.get("p")
        if pp_ is not None:
            pre = np.asarray(pp_.preprocessed)
            if pre.ndim == 3:
                pre = pre.mean(axis=2)
    except Exception:
        pre = None

    def through_gap(bl, hit):
        if pre is None:
            return False
        try:
            st = multi_staffs[hit[0]].staffs[hit[1] // 2]
            if len(st.grid[0].y) < 10:
                return False
            cx_ = float(bl.center[0])
            gp = min(st.grid, key=lambda q: abs(float(q.x) - cx_))
            ls_ = (float(gp.y[4]) - float(gp.y[0])) / 4
            y0, y1 = int(float(gp.y[4]) + 0.5 * ls_), int(float(gp.y[5]) - 0.5 * ls_)
            if y1 - y0 < 2:
                return False
            r_ = max(1, int(round(0.2 * ls_)))
            x0, x1 = int(round(cx_)) - r_, int(round(cx_)) + r_ + 1
            band = pre[y0:y1, max(0, x0):x1]
            ctx = pre[y0:y1, max(0, x0 - int(2 * ls_)):x1 + int(2 * ls_)]
            if band.size == 0 or ctx.size == 0:
                return False
            thr = (float(np.percentile(ctx, 5)) + float(np.percentile(ctx, 95))) / 2
            if float(np.percentile(ctx, 95)) - float(np.percentile(ctx, 5)) < 30:
                return False
            rows = (band < thr).any(axis=1)
            return float(rows.mean()) >= 0.85
        except Exception:
            return False

    keep = []
    for i, (cx_, hit) in enumerate(where):
        if hit is None or not hit[3]:
            keep.append(bars[i])
            continue
        tol = 0.75 * hit[2]  # ~1,5 Linienabstände (average_unit_size ≈ 2 Linienabstände)
        cy_ = float(bars[i].center[1])
        partner = any(
            j != i and abs(c2 - cx_) <= tol and (
                (h2 is not None and h2[0] == hit[0] and h2[1] != hit[1])
                # Taktstrich mit Mitte zwischen den Systemen (keinem zugeordnet): geht durch beide
                or (h2 is None and abs(float(bars[j].center[1]) - cy_) < 4 * hit[2]))
            for j, (c2, h2) in enumerate(where))
        if partner or through_gap(bars[i], hit):
            keep.append(bars[i])
    if len(keep) < len(bars):
        print(f"Taktstriche: {len(bars) - len(keep)} nur in einem System (Bleistift) verworfen", file=sys.stderr)
        if _debug():
            print("  verworfen bei x:", sorted(round(float(b.center[0])) for b in bars if not any(b is k for k in keep)), file=sys.stderr)
    return keep


def _overlay(multi_staffs, to_input_image) -> str:
    """Erkannte Notenlinien, Notenköpfe und Zeilenrahmen in Fotokoordinaten (JSON für die App)."""
    import json

    staffs = []
    notes = []
    true_bars = _true_bars(multi_staffs)
    for ms in multi_staffs:
        for st in ms.staffs:
            grid = st.grid
            step = max(1, len(grid) // 28)
            pts = grid[::step] + ([grid[-1]] if grid[-1] is not grid[::step][-1] else [])
            n_lines = len(grid[0].y)
            lines = []
            for li in range(n_lines):
                line = []
                for gp in pts:
                    x, y = to_input_image((float(gp.x), float(gp.y[li])))
                    line += [round(x, 1), round(y, 1)]
                lines.append(line)
            x0, y0 = to_input_image((float(st.min_x), float(st.min_y)))
            x1, y1 = to_input_image((float(st.max_x), float(st.max_y)))
            # Taktstriche (Mitte, Fotokoordinaten) – für den Notensatz im Original-Layout
            bars = []
            for bl in true_bars:
                cx_, cy_ = float(bl.center[0]), float(bl.center[1])
                if st.min_y - 2 <= cy_ <= st.max_y + 2 and st.min_x - 5 <= cx_ <= st.max_x + 5:
                    bx, by = to_input_image((cx_, cy_))
                    bars.append(round(bx, 1))
            staffs.append({"lines": lines, "box": [round(x0), round(y0), round(x1), round(y1)], "bars": sorted(bars)})
            for n in st.get_notes():
                x, y = to_input_image((float(n.center[0]), float(n.center[1])))
                notes += [round(x, 1), round(y, 1)]
    return json.dumps({"staffs": staffs, "notes": notes})


def hole_full(band, hole, oy, ox):
    """Maske „liegt im Loch“ in Bandgröße."""
    import numpy as np

    m = np.zeros(band.shape, bool)
    m[oy:oy + hole.shape[0], ox:ox + hole.shape[1]] = hole
    return m


def _extract_notes(image_path, multi_staffs, to_input_image, print_boxes=(), pedals=(), max_side=1800, return_clean=False):
    """
    Handschriftliche Notizen (Bleistift, Kuli, Buntstift) vom Druck trennen und freistellen.
    1. Weißabgleich: Papierhintergrund schätzen (Schließen mit großem Kern) und herausrechnen.
    2. Was die Segmentierung als Druck kennt (Linien, Köpfe, Hälse, Schlüssel, Symbole), abziehen.
    3. Übrige Stücke einzeln prüfen: farbig → Notiz; grau → Notiz nur, wenn deutlich heller als
       Druckerschwärze (Bleistift); tiefschwarz → Druck (Akkordbuchstaben, Fingersätze, Text).
    [print_boxes]: Rahmen (l, t, r, b im Eingabebild) von Text, den die Texterkennung als Druck gelesen hat
    (Akkordsymbole, Anweisungen, Fingersätze) – Stücke darin sind Druck, egal wie hell sie wirken.
    Nur in den Bereichen um die Notensysteme. Liefert (RGBA-Bild, Maßstab gegenüber dem Eingabebild).
    """
    import cv2
    import numpy as np

    pred = _last_predictions.get("p")
    img = cv2.imread(image_path, cv2.IMREAD_COLOR)
    if img is None or pred is None:
        return None, 1.0
    H0, W0 = img.shape[:2]
    scale = min(1.0, max_side / max(H0, W0))
    if scale < 1.0:
        img = cv2.resize(img, (int(W0 * scale), int(H0 * scale)), interpolation=cv2.INTER_AREA)
    h, w = img.shape[:2]

    # Abbildung Segmentierung (vorverarbeitetes Bild) → dieses Bild
    ph, pw = pred.preprocessed.shape[:2]
    ax0, ay0 = to_input_image((0.0, 0.0))
    ax1, ay1 = to_input_image((float(pw), float(ph)))
    sx, sy = (ax1 - ax0) / pw * scale, (ay1 - ay0) / ph * scale
    M = np.float32([[sx, 0, ax0 * scale], [0, sy, ay0 * scale]])

    def to_img(mask):
        m = (mask > 0).astype(np.uint8) * 255
        return cv2.warpAffine(m, M, (w, h), flags=cv2.INTER_NEAREST)

    # Linienabstand im Bild (für Kerngrößen)
    systems = sorted(multi_staffs, key=lambda ms: ms.staffs[0].min_y)
    ls_pre = float(np.median([np.median(np.diff(p.y[:5])) for ms in systems for st in ms.staffs for p in st.grid])) or 10.0
    ls = max(4.0, ls_pre * sy)

    # 1. Weißabgleich je Farbkanal
    k = max(15, int(2.5 * ls)) | 1
    kern = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
    norm = np.empty_like(img)
    for c in range(3):
        bg = cv2.morphologyEx(img[:, :, c], cv2.MORPH_CLOSE, kern)
        bg = cv2.GaussianBlur(bg, (0, 0), k / 3)
        norm[:, :, c] = np.clip(img[:, :, c].astype(np.float32) / np.maximum(bg, 1) * 255.0, 0, 255).astype(np.uint8)
    gray = cv2.cvtColor(norm, cv2.COLOR_BGR2GRAY)
    hsv = cv2.cvtColor(norm, cv2.COLOR_BGR2HSV)
    sat = hsv[:, :, 1]
    # Textmarker (Akkordbuchstaben übermalt, Gideon 18.11.): breite, helle, farbige Flächen. Bleistift und farbige
    # Stifte sind dünne Striche und überstehen das Öffnen mit ~0,7 ls nicht. Die Fläche samt allem darin (gedruckte
    # Buchstaben, die durch den Marker farbig wirken) gehört nicht in die Notiz-Ebene – sonst bleibt ein Farbfleck
    # mit ausgeschnittenem Buchstaben oder ein farbiger Doppelgänger des Akkordsymbols der App.
    marker = np.zeros(gray.shape, bool)
    try:
        colm = ((sat > 22) & (hsv[:, :, 2] > 140)).astype(np.uint8)
        ko_ = max(3, int(0.7 * ls)) | 1
        broad_ = cv2.morphologyEx(colm, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (ko_, ko_)))
        if broad_.any():
            kc2_ = max(3, int(1.6 * ls)) | 1  # Buchstaben in der Fläche schließen
            broad_ = cv2.morphologyEx(broad_, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kc2_, kc2_)))
            kd2_ = 2 * max(1, int(0.3 * ls)) + 1  # weicher Rand
            marker = cv2.dilate(broad_, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kd2_, kd2_))) > 0
        if _debug():
            print(f"  Notizen: Textmarker {float(marker.mean()) * 100:.2f} % der Fläche", file=sys.stderr)
        if os.environ.get("VOMBLATT_DEBUG_IMG"):
            dm_ = norm.copy(); dm_[marker] = (dm_[marker] * 0.5 + np.array([255, 0, 255]) * 0.5).astype(np.uint8)
            cv2.imwrite(os.path.join(os.environ["VOMBLATT_DEBUG_IMG"], "marker_dbg.png"), dm_)
            cv2.imwrite(os.path.join(os.environ["VOMBLATT_DEBUG_IMG"], "sat_dbg.png"), sat)
    except Exception as e:
        print("Textmarker:", e, file=sys.stderr)

    # Druck laut Segmentierung – aber nur, wo das Foto auch druckdunkel ist: homr hält lange Bleistiftstriche
    # (Seiten eines Kreises) gern für Hälse oder Symbole; blasser Bleistift darf nicht als Druck verschwinden
    staff_px = to_img(pred.staff) > 0
    thin_ref = float(np.percentile(gray[staff_px], 25)) if staff_px.any() else 120.0
    print_dark = gray < thin_ref + 0.35 * (255 - thin_ref)
    printed = np.zeros((h, w), np.uint8)
    for layer in (pred.staff, pred.notehead, pred.stems_rest, pred.clefs_keys, pred.symbols):
        printed |= to_img(layer)
    seg_raw = printed > 0
    printed = ((printed > 0) & (print_dark | staff_px | (to_img(pred.notehead) > 0))).astype(np.uint8) * 255
    pencil_in_seg = int((seg_raw & ~(printed > 0)).sum())
    printed = cv2.dilate(printed, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (max(3, int(0.35 * ls)) | 1,) * 2))

    # Bereich um die Systeme
    zone = np.zeros((h, w), np.uint8)
    for ms in systems:
        for st in ms.staffs:
            x0, y0 = to_input_image((float(st.min_x), float(st.min_y)))
            x1, y1 = to_input_image((float(st.max_x), float(st.max_y)))
            m = 6 * ls_pre * sy / scale
            # waagrecht knapp (sonst kommen Buchkanten und Nachbarseiten mit), senkrecht großzügig
            mx = 1.5 * ls_pre * sy / scale
            # links erst hinter Klammer und Anfangsstrich, rechts knapp über das Zeilenende
            cv2.rectangle(zone, (int((x0 + mx / 4) * scale), int((y0 - m) * scale)), (int((x1 + mx / 3) * scale), int((y1 + m) * scale)), 255, -1)

    # Druckerschwärze: typischer Grauwert in den Notenköpfen
    heads = to_img(pred.notehead) > 0
    ink_dark = float(np.percentile(gray[heads], 30)) if heads.any() else 40.0
    pencil_limit = ink_dark + 0.45 * (255 - ink_dark)
    # Dünner Druck (Notenlinien): wie dunkel erscheint er in diesem Foto? Je unschärfer, desto heller.
    lines_m = (to_img(pred.staff) > 0) & (zone > 0)
    thin_dark = float(np.percentile(gray[lines_m], 25)) if lines_m.any() else ink_dark
    if _debug():
        print(f"  Notizen: Linien {thin_dark:.0f} (p25), Köpfe {ink_dark:.0f}, ls {ls:.1f}px", file=sys.stderr)

    cand = ((gray < 234) | (sat > 70)) & (zone > 0) & (printed == 0)
    # Bleistiftstriche, die eine Notenlinie kreuzen, sind dort unterbrochen (die Linie gilt als Druck). Linienpixel
    # mit Notiz-Tinte direkt darüber und darunter gehören zum Strich – so bleibt ein Kreis ein Stück und wird nicht in
    # kleine Teile zerlegt, die einzeln als „Druckrest im System“ verworfen würden. Grauwert von oben übernehmen.
    try:
        staff_line = (cv2.dilate(to_img(pred.staff), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (max(3, int(0.35 * ls)) | 1,) * 2)) > 0) & (printed > 0)
        staff_line &= ~(cv2.dilate(to_img(pred.stems_rest) | to_img(pred.notehead), np.ones((3, 3), np.uint8)) > 0)
        T = max(2, int(0.6 * ls))
        up = cand.copy(); dn = cand.copy()
        gfill = np.where(cand, gray, 255).astype(np.uint8)
        for _ in range(T):
            nxt = up[:-1] & staff_line[1:] & ~up[1:]
            gfill[1:][nxt] = gfill[:-1][nxt]
            up[1:] |= nxt
            dn[:-1] |= dn[1:] & staff_line[:-1]
        bridged = staff_line & up & dn & ~cand
        if bridged.any():
            gray = gray.copy()
            gray[bridged] = gfill[bridged]
            norm = norm.copy()
            norm[bridged] = np.dstack([gfill] * 3)[bridged]
            cand = cand | bridged
    except Exception as e:
        print("Notizen überbrücken:", e, file=sys.stderr)
    # Tinte pixelgenau trennen: druckdunkel (samt weichem Rand) und heller. Ein zarter Bleistiftkreis, der eine
    # gedruckte Linie (Pedal, Bogen, Taktstrich) berührt, bleibt so ein eigenes Stück und wird nicht mit ihr
    # zusammen verworfen – und umgekehrt kommt keine Druckzeile mit dem Kreis in die Notizen.
    print_thr = thin_dark + 0.35 * (255 - thin_dark)
    core = cand & (gray < print_thr)
    rr = max(1, int(round(0.12 * ls)))
    darkp = cand & (cv2.dilate(core.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * rr + 1, 2 * rr + 1))) > 0)
    # Helle Tinte unabhängig von der Druck-Segmentierung: alles deutlich unter Papierweiß, außer dem weichen Rand
    # druckdunkler Striche (Notenlinien, Köpfe, Hälse, Schrift). Ob es Bleistift ist, entscheidet danach die Form.
    r2 = max(2, int(round(0.2 * ls)))
    near_print = cv2.dilate((gray < print_thr).astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r2 + 1, 2 * r2 + 1))) > 0
    # Notenlinien sind an unscharfen Stellen heller als Druck – sie samt Rand nie als helle Tinte zählen
    staff_zone = cv2.dilate(staff_px.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r2 + 1, 2 * r2 + 1))) > 0
    lightp = ((gray < 238) & ~near_print & ~staff_zone & (zone > 0) & (sat <= 70)) & ~darkp
    # Gleichmäßig graue Flächen (Abzeichen „Demo Track“, Titelbalken, Rasterflächen): mit allem darin keine Notiz
    try:
        kf = max(3, int(0.9 * ls)) | 1
        disk = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kf, kf))
        # mittelgraue Flächen (Abzeichen, Raster) und dunkle Flächen fern von Notenköpfen (Balken mit weißer Schrift);
        # Akkordtrauben aus Notenköpfen sind auch dunkle Flächen – die zählen nicht
        # graue Flächen dürfen weiße Schrift enthalten (vorher schließen); auch sehr hellgraue Flächen zählen –
        # Bleistift ist ein dünner Strich und passt nie ganz in die Prüfscheibe
        midm = ((gray > 100) & (gray < 232)).astype(np.uint8)
        kc = 3
        midm = cv2.morphologyEx(midm, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kc, kc)))
        # größere Prüfscheibe: Handschrift (Striche mit grauem Hof) füllt nie eine Fläche von 1,4 Linienabständen
        disk_m = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(1.4 * ls) | 1,) * 2)
        fill_mid = cv2.morphologyEx(midm, cv2.MORPH_OPEN, disk_m) > 0
        fill_mid &= ~(cv2.dilate(heads.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(1.5 * ls) | 1,) * 2)) > 0)
        fill_dark = cv2.morphologyEx((gray <= 100).astype(np.uint8), cv2.MORPH_OPEN, disk) > 0
        fill_dark &= ~(cv2.dilate(heads.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(4 * ls) | 1,) * 2)) > 0)
        kd = int(3 * kf) | 1  # großzügig: auch der helle Rand eines Abzeichens gehört dazu
        fill_reg = cv2.dilate((fill_mid | fill_dark).astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kd, kd))) > 0
        lightp &= ~fill_reg
        cand &= ~fill_reg
        darkp &= ~fill_reg
    except Exception as e:
        print("Flächen:", e, file=sys.stderr)
    cand = cand | lightp
    # Einkreisungen: Nicht-Druck-Tinte (auch blasse Striche), leicht verdickt – schließt Lücken an Notenlinien und
    # Hälsen –, die eine Fläche rundum umschließt, in der gedruckte Noten liegen. Gedruckte Bögen, Pedallinien, Gabeln
    # umschließen nichts; Buchstaben umschließen keine Druckschwärze; Kästen (Formteil-Buchstaben) sind rechteckig.
    # Die Striche gefundener Einkreisungen kommen samt blasser Stellen vollständig in die Notiz-Ebene.
    ring_px = np.zeros((h, w), bool)
    try:
        r3 = max(3, int(round(0.4 * ls)))
        far = ~(cv2.dilate(((gray < print_thr) | staff_px).astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r3 + 1, 2 * r3 + 1))) > 0)
        faint = (gray < 244) & far & (zone > 0) & (sat <= 70) & (printed == 0)
        try:
            faint &= ~fill_reg
            faint &= ~marker
        except NameError:
            pass
        fn_, flab_, fst_, _ = cv2.connectedComponentsWithStats(faint.astype(np.uint8), connectivity=8)
        okf = np.zeros(fn_, bool)
        for k in range(1, fn_):
            okf[k] = max(fst_[k][2], fst_[k][3]) >= 0.4 * ls
        faint = okf[flab_]
        src = cand | faint
        re_ = max(2, int(round(0.5 * ls)))
        ringm = cv2.dilate(src.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * re_ + 1, 2 * re_ + 1)))
        if os.environ.get("VOMBLATT_DEBUG_IMG"):
            dv = np.dstack([gray] * 3).copy()
            dv[ringm > 0] = (dv[ringm > 0] * 0.5 + np.array([0, 200, 255]) * 0.5).astype(np.uint8)
            dv[faint] = (0, 120, 255)
            dv[cand] = (0, 0, 200)
            cv2.imwrite(os.path.join(os.environ["VOMBLATT_DEBUG_IMG"], "ring_dbg.png"), dv)
        res_ = cv2.findContours(ringm, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
        contours, hier = res_[-2], res_[-1]
        dark_print = (gray < print_thr) & ~staff_px & ~cand
        n_rings = 0
        if hier is not None:
            for ci, cnt_ in enumerate(contours):
                if hier[0][ci][3] < 0:
                    continue  # nur Löcher
                bx_, by_, bw_, bh_ = cv2.boundingRect(cnt_)
                if bw_ < 1.5 * ls or bh_ < 1.5 * ls or bw_ > 14 * ls or bh_ > 16 * ls:
                    continue
                hole = np.zeros((bh_, bw_), np.uint8)
                cv2.drawContours(hole, [cnt_ - np.array([bx_, by_])], -1, 1, -1)
                hole = hole > 0
                area_ = float(hole.sum())
                if area_ > 0.88 * bw_ * bh_:
                    continue  # rechteckig (gedruckter Kasten)
                inside_dark = float((dark_print[by_:by_ + bh_, bx_:bx_ + bw_] & hole).sum())
                inside_heads = float((heads[by_:by_ + bh_, bx_:bx_ + bw_] & hole).sum())
                if inside_heads < 0.3 * ls * ls and inside_dark < 0.5 * ls * ls:
                    continue
                # nicht am Zeilenanfang (Klammer, Schlüssel, Vorzeichen bilden dort geschlossene Formen)
                zc_ = np.where(zone[by_:by_ + bh_].any(axis=0))[0]
                if len(zc_) and bx_ < zc_.min() + 5 * ls:
                    continue
                y0b, x0b = max(0, by_ - re_ - 2), max(0, bx_ - re_ - 2)
                y1b, x1b = min(h, by_ + bh_ + re_ + 2), min(w, bx_ + bw_ + re_ + 2)
                big_band = np.zeros((y1b - y0b, x1b - x0b), np.uint8)
                big_band[by_ - y0b:by_ - y0b + bh_, bx_ - x0b:bx_ - x0b + bw_] = hole
                big_band = cv2.dilate(big_band, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * re_ + 3, 2 * re_ + 3))) > 0
                # nur der Ring um das Loch, nicht sein Inneres (Akkordbuchstaben, Schatten darin)
                inner = cv2.erode(hole_full(big_band, hole, by_ - y0b, bx_ - x0b).astype(np.uint8),
                                  cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * re_ + 3, 2 * re_ + 3))) > 0
                big_band &= ~inner
                band_src = big_band & src[y0b:y1b, x0b:x1b]
                band_cand = big_band & cand[y0b:y1b, x0b:x1b]
                # überwiegend echte Striche (nicht nur blasser Schleier)
                if band_src.sum() == 0 or band_cand.sum() < 0.35 * band_src.sum():
                    continue
                ring_px[y0b:y1b, x0b:x1b] |= band_src
                n_rings += 1
        # blasse Ringteile als helle Tinte übernehmen
        add = ring_px & ~cand
        if add.any():
            lightp = lightp | add
            cand = cand | add
        print(f"Notizen: {n_rings} Einkreisungen", file=sys.stderr)
    except Exception as e:
        print("Einkreisungen:", e, file=sys.stderr)
    n1, lab1, st1, _ = cv2.connectedComponentsWithStats(darkp.astype(np.uint8), connectivity=8)
    n2, lab2, st2, _ = cv2.connectedComponentsWithStats(lightp.astype(np.uint8), connectivity=8)
    lab = lab1.astype(np.int32)
    lab[lightp] = lab2[lightp] + (n1 - 1)
    stats = np.vstack([st1, st2[1:]])
    n = n1 + n2 - 1
    is_light = np.zeros(n, bool)
    is_light[n1:] = True
    # Zusammengehörige helle Striche (Abstand bis ~0,5 Linienabstände): ergeben sie zusammen eine große Form
    # (Kreis, Schleife, Pfeil ≥ 3 Linienabstände), sind auch ihre kleinen Teile Notiz – ein Kreisbogen zwischen
    # zwei Notenlinien ist dann kein „Druckrest im System“
    rd = max(1, int(round(0.35 * ls)))  # überbrückt auch die Lücke an einer Notenlinie samt Rand
    near = cv2.dilate(lightp.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * rd + 1, 2 * rd + 1)))
    sn, slab, sst, _ = cv2.connectedComponentsWithStats(near, connectivity=8)
    big_struct = np.zeros(sn, bool)
    for k in range(1, sn):
        bx_, by_, bw_, bh_ = sst[k][:4]
        if max(bw_, bh_) - 2 * rd < 3 * ls:
            continue
        # nur dünne, gekrümmte Striche: keine Flächen (graue Abzeichen, Balken) und keine geraden Linien
        # (Kastenränder, Seitenkanten, Falz)
        reg = ((slab[by_:by_ + bh_, bx_:bx_ + bw_] == k) & lightp[by_:by_ + bh_, bx_:bx_ + bw_]).astype(np.uint8)
        cnt = int(reg.sum())
        if cnt == 0:
            continue
        fill = cnt / float(bw_ * bh_)
        # gerade: Hauptachsen der Tinte (eine Linie ist extrem schmal gegenüber ihrer Länge, eine Schleife nicht)
        yy_, xx_ = np.nonzero(reg)
        cov = np.cov(np.vstack([xx_.astype(np.float64), yy_.astype(np.float64)])) if cnt > 3 else np.eye(2)
        ev = np.sort(np.linalg.eigvalsh(cov))
        straight = float(np.sqrt(max(ev[0], 0.0) / max(ev[1], 1e-6))) < 0.08
        # Fläche: irgendwo dicker als ein Bleistiftstrich
        thick_ = float(cv2.distanceTransform(reg, cv2.DIST_L2, 3).max()) > 0.35 * ls
        # deutlich grau (kein Durchscheinen der Rückseite, kein Schatten): genug Pixel klar unter Papierweiß
        gsub = gray[by_:by_ + bh_, bx_:bx_ + bw_][reg > 0]
        clear = float((gsub < 228).mean()) >= 0.12
        big_struct[k] = fill < 0.25 and not straight and not thick_ and clear
    cand = cand.astype(np.uint8) * 255
    keep = np.zeros(n, bool)
    comp_dark = np.full(n, 200.0, np.float32)  # dunkelste Stellen je Stück (für die Deckkraft)
    whys = [""] * n
    min_area = max(6, int(0.08 * ls * ls))
    dbg_items = []
    # gelesener Text ist nie höher als ~2,5 Linienabstände – höhere „Wörter“ sind falsch gelesene Zeichnungen
    # (ein Kreis um eine Note wird gern als „O“ gelesen)
    boxes = [(l * scale, t * scale, r * scale, b * scale) for (l, t, r, b) in print_boxes if (b - t) * scale <= 2.5 * ls]
    pad = 0.3 * ls

    # Gedruckte Pedallinien (aus _detect_pedals): je System Höhe und waagrechter Bereich
    pedal_lines = []  # je System: Stützpunkte (x, y) der Linie, nach x sortiert
    by_sys = {}
    for e in pedals:
        by_sys.setdefault(e.get("system", -1), []).append((float(e["x"]) * scale, float(e["y"]) * scale))
    for pts in by_sys.values():
        pedal_lines.append(sorted(pts))

    def on_pedal_line(cx, cy):
        for pts in pedal_lines:
            if not (pts[0][0] - ls <= cx <= pts[-1][0] + 4 * ls):
                continue
            yl = float(np.interp(cx, [q[0] for q in pts], [q[1] for q in pts]))
            if abs(cy - yl) < 1.2 * ls:
                return True
        return False

    # Innerhalb der Notenlinien (je System ±1 ls): kleine Stücke dort sind Druck (Haltebögen, Taktstrich-Reste)
    staff_band = np.zeros((h, w), np.uint8)
    for ms in systems:
        for st in ms.staffs:
            n_l = len(st.grid[0].y)
            for g0 in range(0, n_l, 5):
                g1 = min(n_l - 1, g0 + 4)
                top = [to_input_image((float(gp.x), float(gp.y[g0]) - ls_pre)) for gp in st.grid]
                bot = [to_input_image((float(gp.x), float(gp.y[g1]) + ls_pre)) for gp in st.grid]
                poly = np.array([[px * scale, py * scale] for px, py in top] + [[px * scale, py * scale] for px, py in bot[::-1]], np.int32)
                cv2.fillPoly(staff_band, [poly], 255)
    staff_band = staff_band > 0
    # Rand des Suchbereichs (Seitenkanten, Nachbarseite)
    zone_cols = np.where(zone.any(axis=0))[0]
    zx0, zx1 = (int(zone_cols.min()), int(zone_cols.max())) if len(zone_cols) else (0, w - 1)

    # Abstand zum nächsten Notenkopf (für die Bogen-Erkennung)
    head_dist = cv2.distanceTransform((~heads).astype(np.uint8) * 255, cv2.DIST_L2, 3) if heads.any() else np.full((h, w), 1e9, np.float32)

    def printed_arc(sel, x, y, cw, ch, area):
        """Gedruckter Bogen/Haltebogen: dünne, saubere Kurve (Parabel), beide Enden an Notenköpfen."""
        # auch Bogenstücke, die die Notenlinien zerschnitten haben (kürzer, steiler)
        # dünn – außer bei kurzen Bögen (Fermaten sind eine dickere Sichel)
        if cw < 0.9 * ls or ch > 1.1 * cw or (area > 0.55 * ls * max(cw, ch) and cw > 3.5 * ls):
            return False
        cols = np.where(sel.any(axis=0))[0]
        if len(cols) < 0.8 * cw:
            return False
        ys = np.array([np.mean(np.where(sel[:, c])[0]) for c in cols], np.float64)
        xs = cols.astype(np.float64)
        coef = np.polyfit(xs, ys, 2)
        resid = float(np.sqrt(np.mean((np.polyval(coef, xs) - ys) ** 2)))
        if resid > 0.25 * ls:
            return False
        if area > 0.55 * ls * max(cw, ch):
            # dicke, kurze Sichel nur, wenn symmetrisch wie eine Fermate (Scheitel mittig, Enden gleich hoch)
            vx = -coef[1] / (2 * coef[0]) if abs(coef[0]) > 1e-9 else -1e9
            if abs(vx - (xs[0] + xs[-1]) / 2) > 0.2 * cw or abs(ys[0] - ys[-1]) > 0.35 * ls:
                return False
        ends = [(int(x + cols[0]), int(y + ys[0])), (int(x + cols[-1]), int(y + ys[-1]))]
        # mindestens ein Ende an einem Notenkopf (hohle Köpfe kennt die Segmentierung nicht immer)
        if any(head_dist[min(h - 1, ey), min(w - 1, ex)] < 3.0 * ls for ex, ey in ends):
            return True
        # von Notenlinien zerschnittenes Bogenstück: im System und so dunkel wie gedruckte Linien
        # (auch Fermaten knapp über/unter dem System)
        inside = float(system_near[y:y + ch, x:x + cw][sel].mean()) > 0.6
        dark = float(np.percentile(gray[y:y + ch, x:x + cw][sel], 10))
        return inside and dark <= thin_dark + 10

    # Fläche der Systeme (oberste bis unterste Linie, ±1 ls)
    system_area = np.zeros((h, w), np.uint8)
    for ms in systems:
        for st in ms.staffs:
            top = [to_input_image((float(gp.x), float(gp.y[0]) - ls_pre)) for gp in st.grid]
            bot = [to_input_image((float(gp.x), float(gp.y[-1]) + ls_pre)) for gp in st.grid]
            poly = np.array([[px * scale, py * scale] for px, py in top] + [[px * scale, py * scale] for px, py in bot[::-1]], np.int32)
            cv2.fillPoly(system_area, [poly], 255)
    system_near = cv2.dilate(system_area, cv2.getStructuringElement(cv2.MORPH_RECT, (3, int(6 * ls) | 1))) > 0
    system_area = system_area > 0

    # Pedalband unter jedem Klaviersystem: dünne Striche dort sind gedrucktes Pedal, Gabeln, Klammern
    pedal_band = np.zeros((h, w), np.uint8)
    for ms in systems:
        st = max(ms.staffs, key=lambda q: q.max_y)
        top = [to_input_image((float(gp.x), float(gp.y[-1]) + 0.6 * ls_pre)) for gp in st.grid]
        bot = [to_input_image((float(gp.x), float(gp.y[-1]) + 6.5 * ls_pre)) for gp in st.grid]
        poly = np.array([[px * scale, py * scale] for px, py in top] + [[px * scale, py * scale] for px, py in bot[::-1]], np.int32)
        cv2.fillPoly(pedal_band, [poly], 255)
    pedal_band = pedal_band > 0

    # erkannte Gabeln (aus _detect_hairpins)
    hairpin_boxes = [(float(e["x0"]) * scale, float(e["x1"]) * scale, float(e["y"]) * scale) for e in _notes_state.get("hairpins", [])]

    # Notenspalten: über und unter Notenköpfen stehen gedruckte Fingersätze (kleine Ziffern)
    head_cols = cv2.dilate(heads.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_RECT, (int(2.6 * ls) | 1, int(12 * ls) | 1))) > 0

    # Nähe der Notenköpfe: kleine Stücke dort sind Haltebögen, Artikulationen, Fingersätze
    near_heads = cv2.dilate(heads.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(3.6 * ls) | 1,) * 2)) > 0

    def in_print(x, y, cw, ch):
        # Anteil des Stücks, den gelesener Druck (alle Rahmen zusammen) abdeckt
        area = max(1.0, float(cw * ch))
        covered = 0.0
        for l, t, r, b in boxes:
            ow = min(x + cw, r + pad) - max(x, l - pad)
            oh = min(y + ch, b + pad) - max(y, t - pad)
            if ow > 0 and oh > 0:
                covered += ow * oh
        return covered >= 0.4 * area

    def in_print_px(sel, x, y):
        # Anteil der Pixel (nicht des Rahmens), die in gelesenem Druck liegen – ein Bleistiftbogen dicht neben einem
        # Akkordbuchstaben ist kein Druck
        hh, ww = sel.shape
        cover = np.zeros_like(sel)
        for l, t, r, b in boxes:
            x0_, y0_ = int(max(0, l - x)), int(max(0, t - y))
            x1_, y1_ = int(min(ww, r - x)), int(min(hh, b - y))
            if x1_ > x0_ and y1_ > y0_:
                cover[y0_:y1_, x0_:x1_] = True
        return float((cover & sel).sum()) >= 0.6 * float(sel.sum())

    staff_near = cv2.dilate(staff_px.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(1.0 * ls) | 1,) * 2)) > 0
    # oberste Notenlinie je System im Bild (für die Akkordzeile)
    top_lines = []
    for ms in systems:
        st0 = min(ms.staffs, key=lambda q: q.min_y)
        top_lines.append(sorted((to_input_image((float(gp.x), float(gp.y[0])))[0] * scale, to_input_image((float(gp.x), float(gp.y[0])))[1] * scale) for gp in st0.grid))

    def top_line_at(si_, xx):
        pts_ = top_lines[si_]
        return float(np.interp(xx, [q[0] for q in pts_], [q[1] for q in pts_]))

    n_print = 0
    dbg_glyph = {}
    digit_boxes = []  # vermutlich gedruckte Fingersatz-Ziffern, die die Texterkennung nicht gelesen hat
    for i in range(1, n):
        x, y, cw, ch, area = stats[i]
        if area < min_area:
            # winzige Stücke nur als Teil einer Einkreisung oder großen Bleistiftform
            if float(ring_px[y:y + ch, x:x + cw][lab[y:y + ch, x:x + cw] == i].mean()) > 0.5:
                pass
            elif not is_light[i]:
                continue
            else:
                sl0 = slab[y:y + ch, x:x + cw][lab[y:y + ch, x:x + cw] == i]
                sl0 = sl0[sl0 > 0]
                if len(sl0) == 0 or not big_struct[int(np.bincount(sl0).argmax())]:
                    continue
        sel = lab[y:y + ch, x:x + cw] == i
        g = gray[y:y + ch, x:x + cw][sel]
        s_ = sat[y:y + ch, x:x + cw][sel]
        dark = float(np.percentile(g, 10))
        colored = float(np.median(s_)) > 60
        rel = (dark - ink_dark) / max(1.0, 255 - ink_dark)  # 0 = so schwarz wie Druck, 1 = papierweiß
        # Form: Reste von Taktstrichen/Hälsen (schmal und hoch) und gedruckte Linien (Pedal, Gabeln,
        # Klammern: lang, flach, dünn) sind kein Bleistift
        sliver = cw < 0.45 * ls and ch > 1.5 * ls
        flat_line = ((ch < 0.7 * ls and cw > 1.6 * ls) or (ch < 1.8 * ls and cw > 5 * ls)  # Bogenstücke, Pedallinien mit Zacken
                     or (cw > 5 * ls and area < 0.4 * ls * cw)  # lange dünne Bögen
                     or (cw > 8 * ls and ch < 2.5 * ls and area < 0.6 * ls * cw))  # lange Linien mit Zacken
        on_pedal = on_pedal_line(x + cw / 2, y + ch / 2)
        at_heads = max(cw, ch) < 4 * ls and float(near_heads[y:y + ch, x:x + cw][sel].mean()) > 0.8
        finger_digit = (max(cw, ch) <= 1.25 * ls and ch >= 0.4 * ls and not colored
                        and float(head_cols[y:y + ch, x:x + cw][sel].mean()) > 0.8)
        in_staff = max(cw, ch) < 3 * ls and float(staff_band[y:y + ch, x:x + cw][sel].mean()) > 0.7
        at_edge = ch > 2 * ls and (x <= zx0 + 0.6 * ls or x + cw >= zx1 - 0.6 * ls)
        # Pedal-Band: dort steht gedrucktes Pedal (Linien, Zacken, Haken) – alles Kleinere ist Druck,
        # große Bleistiftkreise bleiben
        in_pedal_band = (float(pedal_band[y:y + ch, x:x + cw][sel].mean()) > 0.7 and max(cw, ch) < 3 * ls) or             (cw >= 1.3 * ch and float(pedal_band[y:y + ch, x:x + cw][sel].mean()) > 0.7 and area < 0.5 * ls * max(cw, ch))
        in_hairpin = any(hx0 - ls <= x + cw / 2 <= hx1 + ls and abs(y + ch / 2 - hy) < 1.5 * ls for hx0, hx1, hy in hairpin_boxes)
        # gefüllte Flächen (Abzeichen, Titelbalken, Druckgrafik): Bleistift ist ein Strich, keine Fläche
        thick = False
        if max(cw, ch) > 1.0 * ls and area > 0.45 * cw * ch:
            dt = cv2.distanceTransform(sel.astype(np.uint8), cv2.DIST_L2, 3)
            thick = float(dt.max()) > 0.45 * ls
        # dunkle Einzelzeichen, die wie gedruckte Schrift aussehen (Akkordbuchstaben, Ziffern, die die
        # Texterkennung nicht gelesen hat): Vergleich mit Druckschrift-Vorlagen
        glyph = False
        if not colored and rel < 0.45 and 0.6 * ls <= ch <= 2.6 * ls and cw <= 3.0 * ls:
            try:
                import vomblatt_digits
                m_ = cv2.dilate(sel.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
                ink_ = np.clip((255.0 - gray[y:y + ch, x:x + cw].astype(np.float32)) / max(1.0, 255.0 - ink_dark) * 255.0, 0, 255)
                ink_[~m_] = 0
                gs, glab = vomblatt_digits.best_glyph(ink_.astype(np.uint8))
                # auch mit dem vollen Bildausschnitt (die Druck-Segmentierung schneidet Buchstaben gern an)
                pg = max(2, int(0.25 * ls))
                ya, yb, xa, xb = max(0, y - pg), min(h, y + ch + pg), max(0, x - pg), min(w, x + cw + pg)
                full = np.clip((255.0 - gray[ya:yb, xa:xb].astype(np.float32)) / max(1.0, 255.0 - ink_dark) * 255.0, 0, 255)
                full[full < 60] = 0
                gs2, _ = vomblatt_digits.best_glyph(full.astype(np.uint8))
                gs = max(gs, gs2)
                # in der Akkordzeile über einem System (1,5–7 Linienabstände über der obersten Linie) steht Druck:
                # dort genügt eine etwas geringere Schriftähnlichkeit
                in_chord_band = any(1.5 * ls <= top_line_at(sy_, x + cw / 2) - (y + ch / 2) <= 7 * ls for sy_ in range(len(systems)))
                glyph = gs >= (0.8 if in_chord_band else 0.9)
                dbg_glyph[i] = gs
            except Exception as e:
                print("Druckzeichen:", e, file=sys.stderr)
        why = ""
        # heller Teil einer großen Bleistiftform?
        in_big = False
        if is_light[i]:
            sl_ = slab[y:y + ch, x:x + cw][sel]
            sl_ = sl_[sl_ > 0]
            in_big = len(sl_) > 0 and bool(big_struct[int(np.bincount(sl_).argmax())])
        # heller, waagrechter Saum an einer gedruckten Linie (Notenlinie, Pedallinie): unscharfer Druckrand
        fringe = bool(is_light[i]) and cw >= 3 * ch and ch <= 0.35 * ls and (
            float(staff_near[y:y + ch, x:x + cw][sel].mean()) > 0.7 or on_pedal_line(x + cw / 2, y + ch / 2))
        in_ring = float(ring_px[y:y + ch, x:x + cw][sel].mean()) > 0.5
        if float(marker[y:y + ch, x:x + cw][sel].mean()) > 0.6:
            ok = False
            why = "mk"
        elif colored and in_print_px(sel, x, y):
            # gelesener Druck bleibt Druck, auch wenn er farbig wirkt (mit Textmarker übermalter Akkordbuchstabe)
            ok = False
            why = "pr"
        elif colored:
            ok = True
        elif in_ring and not (glyph or thick or at_edge or in_print_px(sel, x, y)):
            ok = True
            why = "ring"
        elif fringe:
            ok = False
            why = "fr"
        elif in_big and not (glyph or thick or at_edge or in_print_px(sel, x, y)):
            ok = True
            why = "big"
        elif is_light[i] and dark > 226:
            ok = False  # blasses Einzelstück ohne kräftige Stelle: Durchscheinen der Rückseite, Schatten
            why = "gh"
        elif area < 0.25 * ls * ls and not in_ring:
            ok = False  # graue Krümel
            why = "kr"
        elif sliver or flat_line or on_pedal or in_pedal_band or in_hairpin or finger_digit or at_heads or in_staff or at_edge or thick or glyph or in_print(x, y, cw, ch)                 or printed_arc(sel, x, y, cw, ch, area):
            ok = False
            why = "".join(k for k, v in (("sl", sliver), ("fl", flat_line), ("op", on_pedal), ("pb", in_pedal_band), ("hp", in_hairpin),
                                         ("fd", finger_digit), ("ah", at_heads), ("is", in_staff), ("ae", at_edge), ("th", thick), ("gl", glyph)) if v)
            if not why:
                why = "pr" if in_print(x, y, cw, ch) else "arc"
        elif rel >= 0.45:
            ok = True  # hell: Bleistift
        else:
            # dunkler Bleistift (fest aufgedrückt): nur als größere Schrift/Zeichnung, nicht tiefschwarz
            ok = rel >= 0.12 and ch >= 1.2 * ls and cw >= 0.4 * ls
        keep[i] = ok
        comp_dark[i] = dark
        whys[i] = why
        # ziffernförmig: nicht winzig (Punkte), höher als breit (keine Bogenstücke)
        if finger_digit and ch >= 0.55 * ls and 0.9 <= ch / max(1, cw) <= 3.5 and not in_print(x, y, cw, ch):
            digit_boxes.append([x / scale, y / scale, (x + cw) / scale, (y + ch) / scale])
        if not colored and in_print(x, y, cw, ch):
            n_print += 1
        if os.environ.get("VOMBLATT_DEBUG_IMG") and area >= 4 * min_area:
            dbg_items.append((x, y, cw, ch, dark, float(np.median(s_)), bool(ok), int(area), (why if not ok or why else ("" if rel >= 0.45 else "dk")) + (" g%.2f" % dbg_glyph[i] if i in dbg_glyph else "")))
    # Anwachsen: Bruchstücke, die nur wegen ihrer Lage/Form verworfen wurden (klein im System, an Köpfen, schmal,
    # bogenförmig), aber direkt an einer behaltenen Notiz anschließen und genauso dunkel sind, gehören zu ihr
    # (Teile eines Bleistiftkreises zwischen Notenlinien). Was als Druck erkannt ist, wächst nie an.
    # Was eindeutig als Druck erkannt ist, wächst nie an: erkannte Pedallinie, Gabel, Seitenrand, Fläche,
    # Druckschrift, gelesener Text, Druckrand einer Linie. Alles andere (Stücke zwischen Notenlinien, an Köpfen,
    # im Pedalband, flache Kreisböden, Krümel) gehört zur Zeichnung, an der es hängt – bis nichts mehr dazukommt.
    printed_codes = ("op", "hp", "ae", "th", "gl", "pr", "fr", "fd", "mk")

    def growable_(j):
        # lange gerade Stücke (Kastenränder, Seitenkanten, Linien) wachsen nie an
        if max(stats[j][2], stats[j][3]) > 4 * ls and ("fl" in whys[j] or "sl" in whys[j] or stats[j][4] < 0.5 * ls * max(stats[j][2], stats[j][3])):
            return False
        return not any(c in whys[j] for c in printed_codes)

    for _ in range(40):
        kept_mask = keep[lab] & (lab > 0)
        # bis ~0,6 Linienabstände: so weit unterbricht eine Notenlinie samt Rand einen Strich
        near_kept = cv2.dilate(kept_mask.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (max(3, int(1.2 * ls)) | 1,) * 2)) > 0
        touched = np.unique(lab[near_kept & ~kept_mask & (lab > 0)])
        grew = 0
        for j in touched:
            if keep[j] or not growable_(j):
                continue
            x, y, cw, ch, area = stats[j]
            selj = lab[y:y + ch, x:x + cw] == j
            # Nachbar-Notiz mit ähnlicher Schwärze?
            pr = max(2, int(0.6 * ls))
            yy0, xx0 = max(0, y - pr), max(0, x - pr)
            yy1, xx1 = min(h, y + ch + pr), min(w, x + cw + pr)
            selp = np.zeros((yy1 - yy0, xx1 - xx0), np.uint8)
            selp[y - yy0:y - yy0 + ch, x - xx0:x - xx0 + cw] = selj
            ring = cv2.dilate(selp, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * pr + 1, 2 * pr + 1))) > 0
            neigh = lab[yy0:yy1, xx0:xx1][ring & (selp == 0)]
            neigh = [int(v) for v in np.unique(neigh) if v > 0 and keep[v]]
            # Schwärze eines Bleistiftstrichs schwankt; an einer großen hellen Form darf ein Stück deutlich dunkler sein
            if neigh and min(abs(comp_dark[v] - comp_dark[j]) - (25 if is_light[v] and whys[v] == "big" else 0) for v in neigh) <= 25:
                keep[j] = True
                grew += 1
        if not grew:
            break
    mask = keep[lab]
    print(f"Notizen: {len(boxes)} Druck-Rahmen, {n_print} Stücke darin verworfen, {int(keep.sum())} behalten, {pencil_in_seg} helle Pixel aus der Druck-Segmentierung zurückgeholt", file=sys.stderr)
    if boxes:
        print("  erste Rahmen:", [tuple(int(v) for v in bx) for bx in boxes[:5]], "Bild", (w, h), file=sys.stderr)
    if _debug():
        print(f"  Notizen: Schwärze Druck {ink_dark:.0f}, Grenze {pencil_limit:.0f}, {int(keep.sum())} Stücke behalten", file=sys.stderr)

    if os.environ.get("VOMBLATT_DEBUG_IMG"):
        d = norm.copy()
        d[(printed > 0) & (zone > 0)] = (d[(printed > 0) & (zone > 0)] * 0.5 + np.array([255, 200, 0]) * 0.5).astype(np.uint8)
        for l, t, r, b in boxes:
            cv2.rectangle(d, (int(l), int(t)), (int(r), int(b)), (200, 0, 200), 1)
        for x, y, cw, ch, dark, sm, kp, area, why in dbg_items:
            col = (0, 160, 0) if kp else (0, 0, 220)
            cv2.rectangle(d, (int(x), int(y)), (int(x + cw), int(y + ch)), col, 1)
            cv2.putText(d, f"{dark:.0f} {why}", (int(x), int(y) - 2), cv2.FONT_HERSHEY_SIMPLEX, 0.35, col, 1)
        cv2.imwrite(os.path.join(os.environ["VOMBLATT_DEBUG_IMG"], "notes_dbg.png"), d)

    gray_n = gray.astype(np.float32)
    norm_n = norm.astype(np.float32)
    # Zeichnung vollständig: von jeder erkannten Zeichnung aus der Linie entlang alles mitnehmen, was ein Strich ist
    # (Linienfilter), auch wenn er blass ist. Abgezogen wird nur, was als Druck erkannt ist: Notenzeichen der
    # Segmentierung, Pedallinien und -zacken, Gabeln, gelesener Text.
    try:
        g32 = gray.astype(np.float32)
        ridge = np.zeros_like(g32)
        for sg_r in (1.2, 2.0):
            b_ = cv2.GaussianBlur(g32, (0, 0), sg_r)
            xx_ = cv2.Sobel(b_, cv2.CV_32F, 2, 0, ksize=3); yy_ = cv2.Sobel(b_, cv2.CV_32F, 0, 2, ksize=3)
            xy_ = cv2.Sobel(b_, cv2.CV_32F, 1, 1, ksize=3)
            ridge = np.maximum(ridge, ((xx_ + yy_) + np.sqrt((xx_ - yy_) ** 2 + 4 * xy_ * xy_)) / 2 * sg_r * sg_r)
        ridge_thr = max(8.0, 6.0 * float(np.median(ridge[zone > 0])))
        # Notenlinien: nur die tatsächlichen dünnen, waagrechten Linienpixel – die Linien-Ebene der Erkennung ist breiter
        # und deckt die Zwischenräume mit ab, in denen die Zeichnung weiterläuft
        dark_line = ((gray < 236) & staff_px).astype(np.uint8)
        thin_lines = cv2.morphologyEx(dark_line, cv2.MORPH_OPEN, np.ones((1, max(3, int(1.0 * ls))), np.uint8)) > 0
        known = thin_lines | heads
        # Hälse und Taktstriche bleiben NICHT ausgespart: im Original-Layout liegen sie im Notenbild an derselben
        # Stelle, ein Bleistiftstrich daran entlang (Kreis am Taktstrich) kommt ganz mit
        for layer_ in (pred.clefs_keys, pred.symbols):
            known |= to_img(layer_) > 0
        known = cv2.dilate(known.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
        # erkannte Pedallinien samt Zacken
        for pts in pedal_lines:
            xs_ = np.array([q[0] for q in pts]); ys_ = np.array([q[1] for q in pts])
            x0p, x1p = int(max(0, xs_.min() - ls)), int(min(w, xs_.max() + 4 * ls))
            cols_ = np.arange(x0p, x1p)
            yl_ = np.interp(cols_, xs_, ys_)
            for cxp, ylp in zip(cols_, yl_):
                known[int(max(0, ylp - 0.6 * ls)):int(min(h, ylp + 0.6 * ls)), cxp] = True
            for qx, qy in pts:  # Zacken und Haken ragen bis ~1,3 ls über die Linie
                known[int(max(0, qy - 1.0 * ls)):int(min(h, qy + 0.4 * ls)), int(max(0, qx - 0.6 * ls)):int(min(w, qx + 0.6 * ls))] = True
        for hx0, hx1, hy in hairpin_boxes:
            known[int(max(0, hy - 1.5 * ls)):int(min(h, hy + 1.5 * ls)), int(max(0, hx0)):int(min(w, hx1))] = True
        for l_, t_, r_, b_b in boxes:
            known[int(max(0, t_ - 1)):int(min(h, b_b + 1)), int(max(0, l_ - 1)):int(min(w, r_ + 1))] = True
        # nur Bleistift-Grau: Gedrucktes (Hälse, Taktstriche, Buchstaben, Pedalzacken) ist schwärzer und wird nie
        # Teil einer Zeichnung – so läuft das Wachsen auch nicht über Hälse von Note zu Note
        gsm_ = cv2.GaussianBlur(gray, (0, 0), 1.0)
        # Gedrucktes laut Erkennung (Linien, Köpfe, Hälse, Taktstriche, Zeichen) samt Saum: dort wächst nichts – auch
        # fest aufgedrückter Bleistift ist so dunkel wie Druck, unterscheidet sich aber durch seine Lage
        # Hälse/Taktstriche laut Erkennung – aber nur echte: am Notenkopf oder an einem erkannten Taktstrich.
        # Senkrechte Bleistiftstriche (Seiten eines Kreises) hält die Erkennung sonst auch für Hälse.
        stems_all = (to_img(pred.stems_rest) > 0).astype(np.uint8)
        sn_, slab_ = cv2.connectedComponents(stems_all, connectivity=8)
        anchor_ = cv2.dilate(heads.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(1.0 * ls) | 1,) * 2)) > 0
        # Taktstriche: nur ihre eigene Spalte UND ihre eigene Höhe (vorher die ganze Bildhöhe – ein Taktstrich in einer
        # anderen Zeile an derselben Stelle machte einen Bleistiftbogen zum „Taktstrich“, Harmony T. 16)
        bar_cols = np.zeros((h, w), bool)
        for bl in _true_bars(multi_staffs):
            bcx, bcy = float(bl.center[0]), float(bl.center[1])
            bhh = max(float(bl.size[0]), float(bl.size[1])) / 2
            bxi = int(round(to_input_image((bcx, bcy))[0] * scale))
            byt = int(round(to_input_image((bcx, bcy - bhh))[1] * scale - 0.5 * ls))
            byb = int(round(to_input_image((bcx, bcy + bhh))[1] * scale + 0.5 * ls))
            bar_cols[max(0, byt):min(h, byb + 1), max(0, bxi - int(0.25 * ls)):min(w, bxi + int(0.25 * ls) + 1)] = True
        # Hälse: das ganze Stück am Kopf – aber nur, wenn es wie ein Hals am Kopf sitzt: rechts am Kopf nach oben oder
        # links am Kopf nach unten (Notensatzregel). Ein Bleistiftbogen, der links an einem Kopf vorbei nach oben durchs
        # System läuft (Harmony T. 4, 16), ist sonst „Hals“ und wird aus der Zeichnung geschnitten.
        real_ = np.zeros(sn_, bool)
        try:
            _, _, sst2_, scen_ = cv2.connectedComponentsWithStats(stems_all, connectivity=8)
            hn_, hlab_, _, hcen_ = cv2.connectedComponentsWithStats(heads.astype(np.uint8), connectivity=8)
            hlab_d = cv2.dilate(hlab_.astype(np.float32), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (int(1.0 * ls) | 1,) * 2))
            for k_ in np.unique(slab_[anchor_]):
                if k_ == 0:
                    continue
                bx_, by_, bw_, bh_ = sst2_[k_][:4]
                selk = slab_[by_:by_ + bh_, bx_:bx_ + bw_] == k_
                hs_ = np.unique(hlab_d[by_:by_ + bh_, bx_:bx_ + bw_][selk]).astype(int)
                cx_, cy_ = scen_[k_]
                for hk in hs_:
                    if hk <= 0 or hk >= hn_:
                        continue
                    hx_, hy_ = hcen_[hk]
                    if (cx_ > hx_ and cy_ < hy_ - 0.3 * ls) or (cx_ < hx_ and cy_ > hy_ + 0.3 * ls):
                        real_[k_] = True
                        break
        except Exception as e:
            print("Hälse prüfen:", e, file=sys.stderr)
            real_[np.unique(slab_[anchor_])] = True
        real_[0] = False
        # Von Notenlinien zerteilte Hälse: schmale Stücke in derselben Flucht über/unter einem erkannten Hals gehören dazu
        try:
            ext_ = cv2.dilate(real_[slab_].astype(np.uint8), np.ones((int(3 * ls) | 1, 1), np.uint8)) > 0
            for k_ in np.unique(slab_[ext_ & (stems_all > 0)]):
                if k_ > 0 and not real_[k_] and sst2_[k_][2] <= 0.4 * ls:
                    real_[k_] = True
        except Exception as e:
            print("Halsstücke:", e, file=sys.stderr)
        # Taktstriche: nur ihre eigene Spalte (ein Bleistiftstrich, der den Taktstrich berührt, bleibt Zeichnung)
        stems_true = real_[slab_] | ((stems_all > 0) & bar_cols)
        blobs_ = heads | (to_img(pred.clefs_keys) > 0) | (to_img(pred.symbols) > 0) | stems_true
        if os.environ.get("VOMBLATT_DEBUG_IMG"):
            ds_ = np.dstack([gray] * 3).copy()
            ds_[stems_all > 0] = (0, 160, 255); ds_[stems_true] = (0, 0, 220); ds_[heads] = (200, 120, 0)
            ds_[to_img(pred.symbols) > 0] = (200, 0, 200)
            cv2.imwrite(os.path.join(os.environ["VOMBLATT_DEBUG_IMG"], "stems_dbg.png"), ds_)
        thin_ = staff_px
        pd_ = (cv2.dilate(blobs_.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * max(2, int(0.3 * ls)) + 1,) * 2)) > 0) |               (cv2.dilate(thin_.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * max(1, int(0.15 * ls)) + 1,) * 2)) > 0)
        # tiefschwarzer Druck (Zacken, Buchstaben, Balken) ist nie Bleistift
        line_ = (ridge > ridge_thr) & (gray < 250) & ~known & (zone > 0) & (sat <= 70) & ~pd_ & (gsm_ >= ink_dark + 0.25 * (255 - ink_dark))
        # lange waagrechte Linien (Pedallinien, Kastenränder) und graue Flächen (Abzeichen) sind Druck
        hl_ = cv2.morphologyEx(line_.astype(np.uint8), cv2.MORPH_OPEN, np.ones((1, max(3, int(2.5 * ls))), np.uint8))
        line_ &= ~(cv2.dilate(hl_, np.ones((3, 3), np.uint8)) > 0)
        try:
            line_ &= ~fill_reg
        except NameError:
            pass
        line_ &= ~marker
        if os.environ.get("VOMBLATT_PROBE"):
            for pp_ in os.environ["VOMBLATT_PROBE"].split(";"):
                px_, py_ = (int(v) for v in pp_.split(","))
                win = (slice(py_ - 5, py_ + 6), slice(px_ - 5, px_ + 6))
                rm = ridge[win] > ridge_thr
                print("PROBE", pp_, "ridge>thr", int(rm.sum()), "gray", int(gray[win].min()), "known", int((known[win] & rm).sum()), "pd", int((pd_[win] & rm).sum()), "dark", int(((gsm_ < ink_dark + 0.25 * (255 - ink_dark))[win] & rm).sum()), "line", int(line_[win].sum()), "mask", int(mask[win].sum()), file=sys.stderr)
        if os.environ.get("VOMBLATT_DEBUG_IMG"):
            dv_ = np.dstack([gray] * 3).copy()
            dv_[known] = (dv_[known] * 0.5 + np.array([255, 200, 0]) * 0.5).astype(np.uint8)
            dv_[line_] = (0, 0, 255); dv_[mask] = (0, 170, 0)
            cv2.imwrite(os.path.join(os.environ["VOMBLATT_DEBUG_IMG"], "grow_dbg.png"), dv_)
        lk = max(2, int(round(1.1 * ls)))  # springt über Notenlinien, Hälse und blasse Stellen des Strichs
        kern_ = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * lk + 1, 2 * lk + 1))
        # Ausgangspunkte nur Zeichnungen, die selbst schon ein Strich sind (≥ 1,5 ls) und grau (Bleistift): Punkte,
        # Krümel und Farbflecken bleiben Notiz, ziehen aber nichts nach – sonst sammelt ein Fleck am Rand blassen,
        # unscharfen Druck in der Nähe ein (Balken, Hälse, Pausen; Bohemian-Foto T. 8). Ein Farbfleck fände ohnehin
        # nur graue Pixel, also keinen Strich seiner Farbe.
        cur = np.zeros_like(mask)
        mn_, mlab_, mst_, _ = cv2.connectedComponentsWithStats(cv2.dilate(mask.astype(np.uint8), np.ones((3, 3), np.uint8)), connectivity=8)
        for k_ in range(1, mn_):
            bx_, by_, bw_, bh_ = mst_[k_][:4]
            if max(bw_, bh_) < 1.5 * ls:
                continue
            selk = (mlab_[by_:by_ + bh_, bx_:bx_ + bw_] == k_) & mask[by_:by_ + bh_, bx_:bx_ + bw_]
            if float(np.median(sat[by_:by_ + bh_, bx_:bx_ + bw_][selk])) > 60:
                continue
            cur[by_:by_ + bh_, bx_:bx_ + bw_] |= selk
        for _ in range(80):
            nxt = (cv2.dilate(cur.astype(np.uint8), kern_) > 0) & line_ & ~cur
            if not nxt.any():
                break
            cur |= nxt
        grown = cur & ~mask
        if os.environ.get("VOMBLATT_DEBUG_IMG"):
            dv_ = np.dstack([gray] * 3).copy()
            dv_[line_] = (0, 0, 255); dv_[grown] = (255, 0, 255); dv_[mask] = (0, 170, 0)
            cv2.imwrite(os.path.join(os.environ["VOMBLATT_DEBUG_IMG"], "grow2_dbg.png"), dv_)
        mask = mask | grown
        # Keine Aussparungen um Gedrucktes: wo die Zeichnung über Notenlinien, Hälse, Köpfe läuft, das Foto dort
        # unverändert mitnehmen (die Überschneidung gehört zur Zeichnung)
        kc_ = int(1.2 * ls) | 1
        closed_ = cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kc_, kc_))) > 0
        over_ = closed_ & ~mask & ((printed > 0) | known) & (gray < 250)
        mask = mask | over_
        # Stellen dicht an Taktstrichen und Hälsen, wo der Strich daneben weiterläuft: zwischen zwei Enden derselben
        # Zeichnung (bis ~1,6 ls) das Foto dort übernehmen, wo es einen Strich zeigt
        kb_ = int(1.6 * ls) | 1
        closed2 = cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kb_, kb_))) > 0
        mask = mask | (closed2 & ~mask & (ridge > ridge_thr) & (gray < 250) & ~known)
        # Gedrucktes selbst nicht mitzeichnen: druckdunkle Pixel von Notenlinien, Hälsen, Taktstrichen und Köpfen
        # (samt 1 px Rand) liegen im Original-Layout unter dem Gedruckten der App – als graue Kopie daneben störten sie
        # Notenlinien und Köpfe bleiben in der Zeichnung: im Original-Layout liegen sie genau unter denen der App, ein
        # Herausschneiden hinterließe nur Lücken im Strich. Hälse nicht (ihre Richtung/Länge kann im Notensatz abweichen).
        # Gideon 18.11.: Kreuzt eine Zeichnung Gedrucktes, bleibt der Strich stehen – nichts herausschneiden (sonst
        # Lücken und Krümel). Herausgenommen wird nur das gedruckte Pedal (die App zeichnet ihr eigenes).
        pdark = np.zeros((h, w), bool)
        # gedruckte Pedallinie samt Zacken (tiefschwarz im Pedalband): die App zeichnet ihr eigenes Pedal
        ped_band = np.zeros((h, w), bool)
        for pts in pedal_lines:
            xs_ = np.array([q[0] for q in pts]); ys_ = np.array([q[1] for q in pts])
            for cxp in range(int(max(0, xs_.min() - 2 * ls)), int(min(w, xs_.max() + 4 * ls))):
                ylp = float(np.interp(cxp, xs_, ys_))
                ped_band[int(max(0, ylp - 1.6 * ls)):int(min(h, ylp + 0.6 * ls)), cxp] = True
        pdark |= ped_band & (cv2.GaussianBlur(gray, (0, 0), 0.8) < print_thr)
        mask &= ~pdark
        print(f"Notizen: {int(grown.sum())} Pixel an Zeichnungen ergänzt (Linienfilter > {ridge_thr:.0f})", file=sys.stderr)
    except Exception as e:
        print("Notizen vervollständigen:", e, file=sys.stderr)

    # 4. Ergebnis: Farbe aus dem abgeglichenen Bild, Deckkraft aus der Dunkelheit
    # Deckkraft je Notiz an ihren dunkelsten Stellen ausrichten: auch zarter Bleistift wird gut lesbar
    ref = np.minimum(comp_dark[lab], 218.0)
    alpha = np.clip((250.0 - gray_n) / np.maximum(250.0 - ref, 12.0), 0, 1) * 255
    alpha[~mask] = 0
    rgba = np.dstack([np.clip(norm_n, 0, 255).astype(np.uint8), alpha.astype(np.uint8)])
    if return_clean:
        clean = norm.copy()
    rgba[alpha == 0, :3] = 0  # unsichtbare Pixel ohne Farbe – das PNG wird viel kleiner
    # Fingersatz-Ziffern unabhängig suchen (auch dort, wo homrs Symbol-Ebene sie verdeckt): dunkle Druckstücke
    # ohne Notenlinien und Köpfe, ziffernförmig, an Notenspalten
    try:
        staff_px = to_img(pred.staff) > 0
        dark = (gray < ink_dark + 0.5 * (255 - ink_dark)) & (zone > 0) & ~staff_px
        dark &= ~(cv2.dilate(heads.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0)
        # Teile langer Senkrechter (Taktstriche, Hälse) sind keine Ziffern
        allink = ((gray < ink_dark + 0.5 * (255 - ink_dark)) & (zone > 0)).astype(np.uint8) * 255
        vert = cv2.dilate(cv2.morphologyEx(allink, cv2.MORPH_OPEN, np.ones((int(2.5 * ls), 1), np.uint8)), np.ones((3, 3), np.uint8)) > 0
        dn, dlab, dstats, _ = cv2.connectedComponentsWithStats(dark.astype(np.uint8) * 255, connectivity=8)
        found = []
        for i in range(1, dn):
            x, y, cw, ch, area = dstats[i]
            if not (0.45 * ls <= ch <= 1.35 * ls and 0.2 * ls <= cw <= 1.0 * ls and 1.0 <= ch / max(1, cw) <= 4.0):
                continue
            sel = dlab[y:y + ch, x:x + cw] == i
            if float(head_cols[y:y + ch, x:x + cw][sel].mean()) < 0.8 or in_print(x, y, cw, ch)                     or float(vert[y:y + ch, x:x + cw][sel].mean()) > 0.3:
                continue
            # senkrechter Lauf durch die Mitte (Notenlinien eingerechnet): länger als 2 ls = Strich, keine Ziffer
            cxp = x + cw // 2
            col = allink[:, max(0, cxp - 1):cxp + 2].max(axis=1) > 0
            top_r, bot_r = y, y + ch - 1
            while top_r > 0 and col[top_r - 1]:
                top_r -= 1
            while bot_r < h - 1 and col[bot_r + 1]:
                bot_r += 1
            if bot_r - top_r > 2.0 * ls:
                continue
            found.append([x / scale, y / scale, (x + cw) / scale, (y + ch) / scale])
        # mit den bisherigen Kandidaten vereinen (ohne Dubletten)
        def overlaps(a, o):
            w = min(a[2], o[2]) - max(a[0], o[0]); hh = min(a[3], o[3]) - max(a[1], o[1])
            return w > 0 and hh > 0 and w * hh > 0.3 * min((a[2] - a[0]) * (a[3] - a[1]), (o[2] - o[0]) * (o[3] - o[1]))
        for b in found:
            if not any(overlaps(b, o) for o in digit_boxes):
                digit_boxes.append(b)
    except Exception as e:
        print("Ziffern:", e, file=sys.stderr)
    if return_clean:
        return rgba, scale, clean
    return rgba, scale


def _find_digits(image_path, orig_path, print_boxes):
    """Fingersatz-Ziffern finden und lesen (vomblatt_digits), wenn möglich im Original in voller Auflösung."""
    import cv2
    import numpy as np
    import vomblatt_digits

    pred = _last_predictions.get("p")
    st = _notes_state
    if pred is None:
        return []
    to_input_image = st["map"]
    systems = sorted(st["staffs"], key=lambda ms: ms.staffs[0].min_y)
    img = cv2.imread(image_path, cv2.IMREAD_COLOR)
    if img is None:
        return []
    factor = 1.0
    if orig_path and os.path.exists(orig_path):
        o = cv2.imread(orig_path, cv2.IMREAD_COLOR)
        # nur, wenn das Original genauso ausgerichtet ist wie das Arbeitsfoto
        if o is not None and abs(o.shape[0] / o.shape[1] - img.shape[0] / img.shape[1]) < 0.01 and o.shape[1] > img.shape[1]:
            factor = o.shape[1] / img.shape[1]
            img = o
    ph, pw = pred.preprocessed.shape[:2]
    ax0, ay0 = to_input_image((0.0, 0.0))
    ax1, ay1 = to_input_image((float(pw), float(ph)))
    sx, sy = (ax1 - ax0) / pw, (ay1 - ay0) / ph
    M = [[sx * factor, 0, ax0 * factor], [0, sy * factor, ay0 * factor]]
    ls_pre = float(np.median([np.median(np.diff(p.y[:5])) for ms in systems for s_ in ms.staffs for p in s_.grid])) or 10.0
    ls = ls_pre * sy * factor
    boxes = []
    for ms in systems:
        pts = []
        for s_ in ms.staffs:
            pts.append(to_input_image((float(s_.min_x), float(s_.min_y))))
            pts.append(to_input_image((float(s_.max_x), float(s_.max_y))))
        boxes.append((min(p[0] for p in pts) * factor, min(p[1] for p in pts) * factor,
                      max(p[0] for p in pts) * factor, max(p[1] for p in pts) * factor))
    dbg = os.environ.get("VOMBLATT_DEBUG_IMG")
    found = vomblatt_digits.find(img, pred, M, boxes, ls, st.get("pedals", ()), print_boxes, factor, dbg)
    print(f"Ziffern: {len(found)} Kandidaten, {sum(1 for d in found if d[4] > 0)} gelesen (Faktor {factor:.2f})", file=sys.stderr)
    return found


_notes_state = {}


def extract_notes(image_path: str, boxes_json: str, out_path: str, orig_path: str = "", with_notes: bool = True) -> float:
    """
    Notizen der letzten Erkennung freistellen und als PNG (RGBA) speichern. [boxes_json]: Rahmen gedruckten
    Texts aus der Texterkennung der App. Rückgabe: Maßstab des PNG gegenüber dem Foto, −1 bei Fehlschlag.
    """
    import json
    import cv2

    st = _notes_state
    if st.get("image") != image_path:
        return -1.0
    try:
        boxes = [tuple(b) for b in json.loads(boxes_json or "[]")]
        # Fingersätze zuerst (volle Auflösung): gelesene Ziffern sind Druck und gehören nicht in die Notiz-Ebene
        digits = []
        try:
            digits = _find_digits(image_path, orig_path, boxes)
        except Exception as e:
            print("Ziffern:", e, file=sys.stderr)
        st["digits"] = digits
        if not with_notes:
            # PDF: keine Handschrift – nur die Fingersatz-Kandidaten (spart ~10 s je Seite auf dem Handy)
            open(out_path[:-4] + ".digits.json", "w").write(json.dumps([[round(float(v), 3) for v in b] for b in digits]))
            return -1.0
        printed = boxes + [tuple(d[:4]) for d in digits if d[4] > 0 or d[5] >= 0.62]
        rgba, sc, norm = _extract_notes(image_path, st["staffs"], st["map"], printed, st.get("pedals", ()), return_clean=True)
        if rgba is None:
            return -1.0
        cv2.imwrite(out_path, rgba)
        # Ziffern-Kandidaten für die Fingersatz-Erkennung der App
        open(out_path[:-4] + ".digits.json", "w").write(json.dumps([[round(float(v), 3) for v in b] for b in digits]))
        # Saubere Foto-Ansicht: weißabgeglichen, Papier rein weiß, Grautöne kräftiger (Tonwertkurve)
        import numpy as np

        lut = np.array([255.0 * (v / 255.0) ** 1.5 for v in range(256)], np.uint8)
        clean = cv2.LUT(np.ascontiguousarray(norm), lut)
        cv2.imwrite(out_path[:-4] + ".clean.jpg", clean, [cv2.IMWRITE_JPEG_QUALITY, 90])
        print(f"Notizen: {int((rgba[:, :, 3] > 0).sum())} Pixel", file=sys.stderr)
        return float(sc)
    except Exception as e:
        print("Notizen:", e, file=sys.stderr)
        return -1.0
    finally:
        st.clear()
        _last_predictions.clear()


def _crop_main_page(image_path):
    """
    Bei Fotos mit Nachbarseite (Buch, Heft) das Bild vor der Erkennung auf die Hauptseite zuschneiden –
    sonst verbindet homr Notenzeilen beider Seiten über den Falz hinweg. Seiten erkennt man an den langen
    waagrechten Notenlinien: je Seite ein „Plateau“ in der Spaltensumme, dazwischen Falz oder Seitenrand.
    Liefert (Pfad, x-Versatz); ohne Nachbarseite das Original und 0.
    """
    import cv2
    import numpy as np

    img = cv2.imread(image_path, cv2.IMREAD_COLOR)
    if img is None:
        return image_path, 0
    H, W = img.shape[:2]
    s = 1000.0 / max(H, W)
    small = cv2.cvtColor(cv2.resize(img, (int(W * s), int(H * s)), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
    h, w = small.shape
    ink = cv2.adaptiveThreshold(small, 255, cv2.ADAPTIVE_THRESH_MEAN_C, cv2.THRESH_BINARY_INV, 25, 10)
    lines = cv2.morphologyEx(ink, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (max(15, int(w * 0.035)), 1)))
    prof = np.convolve(lines.sum(axis=0).astype(np.float64), np.ones(7) / 7, "same")
    if prof.max() <= 0:
        return image_path, 0
    on = prof > 0.18 * prof.max()
    segs = []
    x = 0
    while x < w:
        if on[x]:
            a = x
            while x < w and on[x]:
                x += 1
            segs.append([a, x])
        x += 1
    # kleine Lücken (Taktstriche, Schlüssel) überbrücken
    merged = []
    for sg in segs:
        if merged and sg[0] - merged[-1][1] < 0.015 * w:
            merged[-1][1] = sg[1]
        else:
            merged.append(sg)
    if len(merged) < 2:
        return image_path, 0

    def weight(sg):
        cx = (sg[0] + sg[1]) / 2
        return prof[sg[0]:sg[1]].sum() * (1.0 - min(0.9, abs(cx - w / 2) / w))

    main = max(merged, key=weight)
    if (main[1] - main[0]) < 0.3 * w:
        return image_path, 0  # keine eindeutige Hauptseite – lieber nichts abschneiden
    others = sum(prof[a:b].sum() for a, b in merged if [a, b] != main)
    if others < 0.005 * prof.sum():
        return image_path, 0
    # Rand lassen: Klammer, Schlüssel, Akkordsymbole – aber nicht bis in die Nachbarseite
    left_gap = max([b for a, b in merged if b <= main[0]] or [0])
    right_gap = min([a for a, b in merged if a >= main[1]] or [w])
    x0 = max(left_gap, main[0] - int(0.06 * w))
    x1 = min(right_gap, main[1] + int(0.03 * w))
    X0, X1 = int(x0 / s), int(min(W, x1 / s))
    if X1 - X0 > 0.95 * W:
        return image_path, 0
    out = image_path + ".page.jpg"
    cv2.imwrite(out, img[:, X0:X1], [cv2.IMWRITE_JPEG_QUALITY, 95])
    print(f"Hauptseite: x {X0}–{X1} von {W} (Nachbarseite abgeschnitten)", file=sys.stderr)
    return out, X0


def _main_page(multi_staffs, image_width):
    """
    Nur die Notenzeilen des fotografierten Blatts behalten, nicht die einer Nachbarseite am Bildrand.
    Zeilen, die sich waagrecht deutlich überlappen, bilden eine Spalte (= eine Seite). Behalten wird die
    Spalte mit der meisten Notenfläche, gewichtet danach, wie mittig sie im Bild liegt.
    """
    if len(multi_staffs) < 2:
        return multi_staffs

    def span(ms):
        return min(st.min_x for st in ms.staffs), max(st.max_x for st in ms.staffs), \
            min(st.min_y for st in ms.staffs), max(st.max_y for st in ms.staffs)

    spans = [span(ms) for ms in multi_staffs]

    def overlap(a, b):
        w = min(a[1], b[1]) - max(a[0], b[0])
        return max(0.0, w) / max(1.0, min(a[1] - a[0], b[1] - b[0]))

    # Spalten bilden (Zeilen, die sich zu mindestens der Hälfte überlappen)
    cols = []
    for i, sp in enumerate(spans):
        for c in cols:
            if any(overlap(sp, spans[j]) >= 0.5 for j in c):
                c.append(i)
                break
        else:
            cols.append([i])
    if len(cols) == 1:
        return multi_staffs

    def score(c):
        area = sum((spans[j][1] - spans[j][0]) * (spans[j][3] - spans[j][2]) for j in c)
        cx = sum((spans[j][0] + spans[j][1]) / 2 for j in c) / len(c)
        return area * (1.0 - min(0.9, abs(cx - image_width / 2) / image_width))

    best = max(cols, key=score)
    keep = [multi_staffs[j] for j in sorted(best)]
    dropped = len(multi_staffs) - len(keep)
    if dropped:
        print(f"Nachbarseite: {dropped} Notenzeile(n) am Bildrand verworfen", file=sys.stderr)
    return keep


def _rss_mb() -> int:
    """Belegter Arbeitsspeicher des Prozesses in MB (Android/Linux; sonst 0)."""
    try:
        with open("/proc/self/status") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) // 1024
    except Exception:
        pass
    return 0


_t0 = [0.0]


def _mem(stage: str) -> None:
    import time

    now = time.monotonic()
    if stage == "Seitenbeginn":
        _t0[0] = now
    print(f"Speicher: {stage} {_rss_mb()} MB nach {now - _t0[0]:.1f} s", file=sys.stderr, flush=True)


def _release_memory() -> None:
    """Nach einer Seite: große Zwischenergebnisse loslassen, Bindungen schließen, Speicher bereinigen."""
    import gc

    _last_predictions.clear()
    try:
        import homr.transformer.staff2score as s2s  # noqa: F401
    except Exception:
        pass
    gc.collect()


def _forget_sessions():
    """
    homr hält Modelle zwischen zwei Aufrufen fest. Die App schließt nach jeder Erkennung alle ONNX-Sitzungen
    (Speicher), also müssen sie beim nächsten Mal neu geöffnet werden – sonst „closed OrtSession“.
    """
    try:
        import homr.staff_relations as sr

        sr._models.clear()
    except Exception:
        pass
    try:
        import homr.segmentation.inference_segnet as seg

        seg._segnet_inference = None
    except Exception:
        pass
    try:
        import homr.staff_parsing_tromr as tromr

        tromr.inference = None  # Encoder/Decoder (Staff2Score)
    except Exception:
        pass


class Cancelled(Exception):
    """Der Nutzer hat die Erkennung abgebrochen."""


def recognize(image_path: str, models_dir: str, progress=None, inherited_time=None) -> str:
    # Messung (Testfassung): liegt neben dem Bild eine Datei „profile“, läuft die Erkennung unter cProfile und schreibt
    # die teuersten Funktionen nach profile.txt
    marker = os.path.join(os.path.dirname(image_path), "profile")
    if os.path.exists(marker):
        import cProfile
        import io
        import pstats

        pr = cProfile.Profile()
        pr.enable()
        try:
            return _recognize(image_path, models_dir, progress, inherited_time)
        finally:
            pr.disable()
            out = io.StringIO()
            st = pstats.Stats(pr, stream=out)
            st.sort_stats("cumulative").print_stats(60)
            st.sort_stats("tottime").print_stats(40)
            with open(os.path.join(os.path.dirname(image_path), "profile.txt"), "w") as f:
                f.write(out.getvalue())
    return _recognize(image_path, models_dir, progress, inherited_time)


def _recognize(image_path: str, models_dir: str, progress=None, inherited_time=None) -> str:
    def cb(phase, done, total):
        if progress is not None:
            stop = False
            try:
                stop = bool(progress.cancelled())
            except Exception:
                stop = False
            if stop:
                raise Cancelled()
            try:
                progress.update(phase, int(done), int(total))
            except Exception:
                pass

    configure(models_dir)
    _release_memory()  # Reste der vorigen Seite (die Notiz-Freistellung danach braucht sie bis hierhin)
    _forget_sessions()
    _release_memory()
    _mem("Seitenbeginn")
    cb("SEGMENT", 0, 0)

    old_err = sys.stderr
    sys.stderr = _ProgressStream(cb, old_err)
    try:
        from homr.main import (
            ProcessingConfig,
            detect_staffs_in_image,
        )
        from homr.music_xml_generator import XmlGeneratorArguments, generate_xml
        from homr.staff_parsing import parse_staffs
        from homr.system_repair import repair_systems
        from homr.transformer.configs import Config

        config = ProcessingConfig(
            enable_debug=False,
            enable_cache=False,
            write_staff_positions=False,
            read_staff_positions=False,
            selected_staff=-1,
            transformer_use_gpu=False,
            segnet_use_gpu=False,
            coreml_encoder=False,
            title_detection=False,
        )
        import homr.main as hm

        if not getattr(hm, "_vomblatt_stash", False):
            _orig_single = hm.detect_single_staffs

            def _single(*a, **k):
                res = _orig_single(*a, **k)
                _last_predictions["p"] = res.predictions  # für die Vorzeichen-Prüfung
                _mem("nach Segmentierung")
                return res

            hm.detect_single_staffs = _single
            _orig_bars = hm.detect_bar_lines

            def _bars(*a, **k):
                res = _orig_bars(*a, **k)
                _last_predictions["bars"] = list(res)  # Taktstriche für den Notensatz im Original-Layout
                return res

            hm.detect_bar_lines = _bars
            hm._vomblatt_stash = True
        _last_predictions.clear()
        # Nachbarseite vorher abschneiden; Koordinaten danach wieder aufs ganze Foto beziehen
        page_path, page_dx = image_path, 0
        try:
            page_path, page_dx = _crop_main_page(image_path)
        except Exception as e:
            print("Seite zuschneiden:", e, file=sys.stderr)
        multi_staffs, image, debug, title_future, _, page_map = detect_staffs_in_image(page_path, config)
        if page_dx:
            def to_input_image(pt, _m=page_map, _dx=page_dx):
                x, y = _m(pt)
                return x + _dx, y
        else:
            to_input_image = page_map
        multi_staffs = repair_systems(debug, multi_staffs)
        try:
            multi_staffs = _main_page(multi_staffs, float(image.shape[1]))
        except Exception as e:  # im Zweifel alles behalten
            print("Seite:", e, file=sys.stderr)
        if progress is not None:
            try:
                progress.overlay(_overlay(multi_staffs, to_input_image))
            except Exception as e:  # Anzeige ist Beiwerk – Erkennung nie daran scheitern lassen
                print("Overlay:", e, file=sys.stderr)
        transformer_config = Config()
        transformer_config.use_gpu_inference = False
        transformer_config.use_coreml_encoder = False
        _mem("vor Notenlesen")
        result_staffs = parse_staffs(
            debug, multi_staffs, image, selected_staff=-1, config=transformer_config,
            page_to_input_image=to_input_image,
        )
        try:
            _fix_key_changes(result_staffs)
            _fix_accidentals(result_staffs, multi_staffs, to_input_image)
        except Exception as e:  # Nachbesserung darf die Erkennung nie verhindern
            print("Nachbesserung:", e, file=sys.stderr)
        if progress is not None:
            try:
                import json

                pedals = _detect_pedals(multi_staffs, to_input_image)
                _notes_state['pedals'] = pedals
                print(f"Pedal: {len(pedals)} Stellen", file=sys.stderr)
                progress.pedals(json.dumps(pedals))
            except Exception as e:
                print("Pedal:", e, file=sys.stderr)
            try:
                import json

                pins = _detect_hairpins(multi_staffs, to_input_image)
                _notes_state["hairpins"] = pins
                print(f"Gabeln: {len(pins)}", file=sys.stderr)
                if hasattr(progress, "hairpins"):
                    progress.hairpins(json.dumps(pins))
            except Exception as e:
                print("Gabeln:", e, file=sys.stderr)
            _notes_state.update(image=image_path, staffs=multi_staffs, map=to_input_image)
        title = ""
        try:
            if title_future is not None:
                title = title_future.result(1) or ""
        except Exception:
            title = ""
        cb("WRITE", 0, 0)
        # Taktart der vorigen Seite („12/8“): gilt, wenn diese Seite ohne eigene Taktart beginnt
        inherited = None
        if inherited_time and "/" in str(inherited_time):
            b, t = str(inherited_time).split("/", 1)
            inherited = [(b.strip(), t.strip())]
        _mem("nach Notenlesen")
        xml = generate_xml(XmlGeneratorArguments(), result_staffs, title, inherited)
        try:
            debug.clean_debug_files_from_previous_runs()
        except Exception:
            pass
        return ET.tostring(xml.getroot() if hasattr(xml, "getroot") else xml, encoding="unicode")
    finally:
        sys.stderr = old_err
