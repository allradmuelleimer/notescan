"""
Gedruckte Fingersätze (Ziffern 1–5) finden und lesen – in voller Auflösung, unabhängig von der Texterkennung.

Suche: um die Notensysteme dunkle, kleine, ziffernförmige Stücke in den Notenspalten (über/unter Akkorden oder
links neben Köpfen), ohne Notenlinien, Köpfe, Hälse, Balken und ohne die gedruckten Pedallinien.
Lesen: Vergleich mit Vorlagen aus vielen Druckschriften (vomblatt_digits.npz, erzeugt mit
tools/make_digit_templates.py). Gegenbeispiele (Akkordbuchstaben, Striche, Pedalzacken, Haken) stehen mit in der
Vorlagenliste: gewinnt eines davon oder ist der Abstand zu knapp, gilt das Stück als nicht lesbar.
"""
import os
import sys

_T = {}


def _templates():
    import numpy as np

    if "t" not in _T:
        d = np.load(os.path.join(os.path.dirname(os.path.abspath(__file__)), "vomblatt_digits.npz"))
        t = d["t"].astype(np.float32)
        y = d["y"].astype(np.int16)
        f = d["f"].astype(np.int16)
        v = np.stack([_vec(a) for a in t])
        _T.update(t=v, y=y, f=f)
    return _T["t"], _T["y"], _T["f"]


def _vec(a24):
    import cv2
    import numpy as np

    a = cv2.GaussianBlur(a24.astype(np.float32), (0, 0), 0.9)
    a = a - a.mean()
    n = float(np.sqrt((a * a).sum()))
    return (a / max(n, 1e-3)).ravel()


def glyph24(ink):
    """Tintenbild (0 = Papier, 255 = Tinte) eng zuschneiden, Höhe 20 px, mittig in 24×24 (wie die Vorlagen)."""
    import cv2
    import numpy as np

    ys, xs = np.where(ink > 70)
    if len(ys) < 4:
        return None
    a = ink[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    h, w = a.shape
    nh = 20
    nw = max(2, min(24, int(round(w * nh / h))))
    small = cv2.resize(a, (nw, nh), interpolation=cv2.INTER_AREA if h > nh else cv2.INTER_LINEAR)
    out = np.zeros((24, 24), np.uint8)
    x0 = (24 - nw) // 2
    out[2:22, x0:x0 + nw] = small
    return out


def classify(ink):
    """Liefert (Ziffer oder 0, Wert der besten Ziffer 1–5, Abstand zum besten Nicht-Kandidaten, beste Klasse)."""
    import numpy as np

    g = glyph24(ink)
    if g is None:
        return 0, 0.0, 0.0, -9, 0
    T, Y, _ = _templates()
    best = None
    # kleine Verschiebungen zulassen (Zuschnitt ist bei unscharfen Fotos nie pixelgenau)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            s = np.roll(np.roll(g, dy, axis=0), dx, axis=1)
            sc = T @ _vec(s)
            best = sc if best is None else np.maximum(best, sc)
    cls = {}
    for lab in np.unique(Y):
        cls[int(lab)] = float(best[Y == lab].max())
    top = max(cls, key=cls.get)
    dig = max((d for d in range(1, 6) if d in cls), key=lambda d: cls[d])
    other = max(v for k, v in cls.items() if k != dig)
    return (dig if top == dig else 0), cls[dig], cls[dig] - other, top, dig


def best_glyph(ink):
    """Ähnlichkeit mit gedruckter Schrift: (bester Wert über alle Ziffern und Buchstaben, Klasse)."""
    import numpy as np

    g = glyph24(ink)
    if g is None:
        return 0.0, -9
    T, Y, _ = _templates()
    sel = Y >= -1  # Ziffern und Schriftzeichen, keine gezeichneten Striche/Bögen
    Ts, Ys = T[sel], Y[sel]
    best = None
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            s = np.roll(np.roll(g, dy, axis=0), dx, axis=1)
            sc = Ts @ _vec(s)
            best = sc if best is None else np.maximum(best, sc)
    k = int(np.argmax(best))
    return float(best[k]), int(Ys[k])


def find(img_bgr, pred, to_img_matrix, systems, ls, pedals=(), print_boxes=(), factor=1.0, debug_dir=None):
    """
    [img_bgr]: Foto (Arbeitsfoto oder Original), [to_img_matrix]: 2×3-Abbildung Segmentierung → dieses Bild,
    [systems]: je System (l, t, r, b) in diesem Bild, [ls]: Linienabstand in diesem Bild,
    [pedals]: Pedal-Ereignisse in Arbeitsfoto-Koordinaten, [factor]: dieses Bild / Arbeitsfoto.
    Liefert [[x0, y0, x1, y1, ziffer, wert, abstand, an_linie, beste_ziffer, groesse_passt]] in Arbeitsfoto-
    Koordinaten (ziffer 0 = nicht sicher; beste_ziffer = ähnlichste der Ziffern 1–5, für den Abgleich mit der
    Texterkennung; groesse_passt = so groß wie die sicher gelesenen Fingersätze der Seite).
    """
    import cv2
    import numpy as np

    H, W = img_bgr.shape[:2]
    M = np.float32(to_img_matrix)
    out = []
    dbg = []
    # Pedallinien je System: Stützpunkte in diesem Bild
    ped = {}
    for e in pedals:
        ped.setdefault(e.get("system", -1), []).append((float(e["x"]) * factor, float(e["y"]) * factor))
    ped_lines = [sorted(v) for v in ped.values()]

    for (l, t, r, b) in systems:
        x0 = int(max(0, l - 3.5 * ls)); x1 = int(min(W, r + 1.0 * ls))
        y0 = int(max(0, t - 7 * ls)); y1 = int(min(H, b + 7 * ls))
        if x1 - x0 < 10 or y1 - y0 < 10:
            continue
        crop = img_bgr[y0:y1, x0:x1]
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        # Weißabgleich: Papierhelligkeit schätzen und herausteilen
        k = max(15, int(2.5 * ls)) | 1
        bg = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
        bg = cv2.GaussianBlur(bg, (0, 0), k / 3)
        norm = np.clip(gray.astype(np.float32) / np.maximum(bg, 1) * 255.0, 0, 255).astype(np.uint8)
        Mc = M.copy(); Mc[0, 2] -= x0; Mc[1, 2] -= y0
        cw_, ch_ = x1 - x0, y1 - y0

        def warp(mask):
            m = (mask > 0).astype(np.uint8) * 255
            return cv2.warpAffine(m, Mc, (cw_, ch_), flags=cv2.INTER_NEAREST) > 0

        heads = warp(pred.notehead)
        staff = warp(pred.staff)
        stems = warp(pred.stems_rest)
        ink_dark = float(np.percentile(norm[heads], 30)) if heads.any() else 60.0
        strong = norm < ink_dark + 0.45 * (255 - ink_dark)
        weak = norm < ink_dark + 0.72 * (255 - ink_dark)
        # Druck, der keine Ziffer ist, entfernen: Köpfe, Linien (auch Pedal- und Hilfslinien), Hälse, Balken
        allink = weak.astype(np.uint8) * 255
        horiz = cv2.morphologyEx(allink, cv2.MORPH_OPEN, np.ones((1, max(3, int(1.6 * ls))), np.uint8)) > 0
        # nur lange Senkrechte (Taktstriche, Hälse); kürzere wie der Rücken einer Viertelpause bleiben ganz
        vert = cv2.morphologyEx(allink, cv2.MORPH_OPEN, np.ones((max(3, int(3.0 * ls)), 1), np.uint8)) > 0
        heads_d = cv2.dilate(heads.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
        stems_d = cv2.dilate(stems.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
        clefs_d = cv2.dilate(warp(pred.clefs_keys).astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
        syms = warp(pred.symbols)
        if os.environ.get("VOMBLATT_DIGIT_TRACE"):
            vis = crop.copy()
            vis[heads] = (0, 0, 255); vis[stems] = (0, 200, 0); vis[warp(pred.clefs_keys)] = (255, 0, 0); vis[syms] = (255, 0, 255)
            cv2.imwrite(os.path.join(debug_dir or ".", "masks_%d.jpg" % y0), vis)
            print("MASK", y0, {k: int((getattr(pred, k) > 0).sum()) for k in ("notehead", "stems_rest", "clefs_keys", "symbols", "staff")}, getattr(pred, "stems_rest").shape, getattr(pred, "notehead").shape, file=sys.stderr)
        staff_d = cv2.dilate(staff.astype(np.uint8), np.ones((3, 1), np.uint8)) > 0
        remove = heads_d | horiz | vert | staff_d
        cand_w = weak & ~remove
        # Tinte rings um eine Ziffer: ohne Notenlinien, Köpfe und kurze Waagrechte (Hilfslinien neben Akkorden)
        hshort = cv2.morphologyEx(allink, cv2.MORPH_OPEN, np.ones((1, max(3, int(0.8 * ls))), np.uint8)) > 0
        ring_ink = weak & ~staff_d & ~heads_d & ~hshort
        # Von Notenlinien zerschnittene Zeichen wieder zusammensetzen: Linienpixel mit Tinte direkt darüber UND
        # darunter gehören zum Zeichen (Ziffern im System werden wieder ganz, Pausen werden als Ganzes zu groß)
        line = (staff_d | horiz) & ~heads_d
        T = max(2, int(0.3 * ls))
        up = cand_w.copy(); dn = cand_w.copy()
        for _ in range(T):
            up[1:] |= up[:-1] & line[1:]
            dn[:-1] |= dn[1:] & line[:-1]
        cand_w = cand_w | (line & up & dn)
        if os.environ.get("VOMBLATT_DIGIT_TRACE"):
            vis = np.dstack([norm] * 3)
            vis[remove] = (vis[remove] * 0.5 + np.array([0, 0, 255]) * 0.5).astype(np.uint8)
            vis[cand_w] = (0, 160, 0)
            vis[line & up & dn] = (255, 0, 0)
            cv2.imwrite(os.path.join(debug_dir or ".", "cand_%d.png" % y0), vis)
        n, lab, st, _ = cv2.connectedComponentsWithStats(cand_w.astype(np.uint8), connectivity=8)
        # Schlüssel/Vorzeichen am Zeilenanfang: Fingersätze stehen frühestens kurz vor dem ersten Notenkopf
        hx = np.where(heads.any(axis=0))[0]
        first_head = int(hx.min()) if len(hx) else 0
        # Notenspalten (über/unter Köpfen) und Nähe links neben Köpfen
        # (Stapel über dem System stehen bis zu ~9 Linienabstände über dem tiefsten Akkordton)
        cols = cv2.dilate(heads.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_RECT, (int(3.0 * ls) | 1, int(19 * ls) | 1))) > 0
        strong_any = strong & ~remove
        for i in range(1, n):
            x, y, w, h, area = st[i]
            if not (0.42 * ls <= h <= 1.45 * ls and 0.12 * ls <= w <= 1.15 * ls and 0.85 <= h / max(1, w) <= 6.0):
                continue
            sel = lab[y:y + h, x:x + w] == i
            if not strong_any[y:y + h, x:x + w][sel].any() or sel.sum() < 0.08 * h * w:
                continue
            if float(cols[y:y + h, x:x + w][sel].mean()) < 0.7:
                continue
            gv = norm[y:y + h, x:x + w][sel]
            darkness = (float(np.percentile(gv, 10)) - ink_dark) / max(1.0, 255 - ink_dark)  # 0 = Druckschwarz
            strong_frac = float(strong[y:y + h, x:x + w][sel].mean())
            if os.environ.get("VOMBLATT_DIGIT_TRACE"):
                print("CAND", round((x + x0) / factor), round((y + y0) / factor), w, h, "dark %.2f strong %.2f st %.2f cl %.2f" % (darkness, strong_frac, float(stems_d[y:y + h, x:x + w][sel].mean()), float(clefs_d[y:y + h, x:x + w][sel].mean())), file=sys.stderr)
            # Bleistift: deutlich heller als der Druck derselben Seite
            if darkness > 0.4 or strong_frac < 0.15:
                continue
            # Hals-, Fähnchen- und Schlüsselstücke: liegen in den Druck-Ebenen der Segmentierung oder hängen an einem Hals
            # (die Schlüssel-/Taktart-Ebene deckt oft auch Fingersatzstapel am Zeilenanfang ab – Taktartziffern sind
            # ohnehin zu groß, Schlüssel liegen vor dem ersten Notenkopf)
            if float(stems_d[y:y + h, x:x + w][sel].mean()) > 0.35:
                continue
            gr = cv2.dilate(sel.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0
            yy0, xx0 = max(0, y - 2), max(0, x - 2)
            near_stem = stems_d[yy0:yy0 + gr.shape[0], xx0:xx0 + gr.shape[1]]
            if near_stem.shape == gr.shape and float(near_stem[gr].mean()) > 0.08:
                if os.environ.get("VOMBLATT_DIGIT_TRACE"):
                    print("  am Hals", round((x + x0) / factor), round((y + y0) / factor), file=sys.stderr)
                continue
            if x + w < first_head - 1.8 * ls:
                if os.environ.get("VOMBLATT_DIGIT_TRACE"):
                    print("  vor erstem Kopf", round((x + x0) / factor), round((y + y0) / factor), "erster Kopf", round((first_head + x0) / factor), file=sys.stderr)
                continue
            # Ziffern stehen frei auf dem Papier: ringsum kaum Tinte (außer Notenlinien und Köpfen). Dunkle Flächen
            # ringsum = Schrift auf dunklem Grund (Abzeichen, Titelbalken), Schraffuren, Bleistiftgekritzel
            rp = max(2, int(0.3 * ls))
            ry0, ry1, rx0, rx1 = max(0, y - rp), min(ch_, y + h + rp), max(0, x - rp), min(cw_, x + w + rp)
            ring = ~(cv2.dilate(sel.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0)
            ring_full = np.ones((ry1 - ry0, rx1 - rx0), bool)
            ring_full[y - ry0:y - ry0 + h, x - rx0:x - rx0 + w] = ring
            around = ring_ink[ry0:ry1, rx0:rx1][ring_full]
            if around.size and float(around.mean()) > 0.3:
                if os.environ.get("VOMBLATT_DIGIT_TRACE"):
                    print("  Ring", round((x + x0) / factor), round((y + y0) / factor), "%.2f" % float(around.mean()), file=sys.stderr)
                continue
            # Notenlinie direkt darüber/darunter: die Ziffer ist dort beschnitten (für den Größenvergleich)
            touch = bool(staff_d[max(0, y - 2):y, x:x + w].any() or staff_d[y + h:min(ch_, y + h + 2), x:x + w].any())
            gx, gy, gw, gh = x + x0, y + y0, w, h
            # gedruckte Pedallinie: das Stück reicht bis auf die Linie hinunter (Zacke, Anfangs-/Endhaken)
            on_ped = False
            cxp = gx + gw / 2
            for pts in ped_lines:
                if pts[0][0] - 1.5 * ls <= cxp <= pts[-1][0] + 1.5 * ls:
                    yl = float(np.interp(cxp, [q[0] for q in pts], [q[1] for q in pts]))
                    if gy + gh > yl - 0.5 * ls and gy < yl + 0.5 * ls:
                        on_ped = True
            if on_ped:
                continue
            # Teil eines gelesenen Worts (Akkordsymbol wie „sus4“, Tempoangabe): Rahmen deutlich breiter als das Stück
            in_word = False
            for (bl, bt, br, bb) in print_boxes:
                bl, bt, br, bb = bl * factor, bt * factor, br * factor, bb * factor
                ow = min(gx + gw, br) - max(gx, bl); oh = min(gy + gh, bb) - max(gy, bt)
                if ow > 0.6 * gw and oh > 0.6 * gh and (br - bl) > 2.2 * gw:
                    in_word = True
            if in_word:
                print("Ziffer im Wort verworfen:", round(gx / factor), round(gy / factor), file=sys.stderr)
                continue
            # Tinte des Stücks (mit 1 px Rand, damit dünne Ränder nicht fehlen)
            m = cv2.dilate(sel.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
            pad = 2
            ink = np.zeros((h + 2 * pad, w + 2 * pad), np.uint8)
            sub = norm[max(0, y - pad):y + h + pad, max(0, x - pad):x + w + pad]
            mm = np.zeros_like(sub, bool)
            oy, ox = y - max(0, y - pad), x - max(0, x - pad)
            mm[oy:oy + h, ox:ox + w] = m
            val = np.clip((255.0 - sub.astype(np.float32)) / max(1.0, 255.0 - ink_dark) * 255.0, 0, 255)
            val[~mm] = 0
            ink = val.astype(np.uint8)
            d, s, mg, top, dig = classify(ink)
            out.append([gx / factor, gy / factor, (gx + gw) / factor, (gy + gh) / factor, d, round(s, 3), round(mg, 3), 1 if touch else 0, dig])
            if debug_dir:
                dbg.append((ink, d, s, mg, top))
    # Alle Fingersätze einer Seite sind gleich groß gesetzt: sicher gelesene Ziffern geben die Höhe vor,
    # deutlich kleinere/größere Treffer (Schrift im Abzeichen, Taktzahlen, Akkordbuchstaben) zählen nicht
    import numpy as np  # noqa: F811
    sure = [b[3] - b[1] for b in out if b[4] > 0 and b[6] >= 0.05]
    ref = float(np.median(sure)) if len(sure) >= 2 else None
    for b in out:
        size_ok = 1
        if ref is not None:
            r = (b[3] - b[1]) / ref
            # von Notenlinien beschnittene Ziffern dürfen kleiner wirken
            if not ((0.6 if b[7] else 0.75) <= r <= 1.25):
                size_ok = 0
                b[4] = 0
        b.append(size_ok)
    if debug_dir and dbg:
        _sheet(dbg, os.path.join(debug_dir, "digits_sheet.png"))
    return out


def _sheet(items, path):
    import cv2
    import numpy as np

    cell = 72
    per = 16
    rows = (len(items) + per - 1) // per
    sheet = np.full((rows * (cell + 26), per * cell), 255, np.uint8)
    for k, (ink, d, s, mg, top) in enumerate(items):
        r, c = divmod(k, per)
        g = glyph24(ink)
        if g is not None:
            sheet[r * (cell + 26):r * (cell + 26) + cell, c * cell:(c + 1) * cell] = 255 - cv2.resize(g, (cell, cell), interpolation=cv2.INTER_NEAREST)
        cv2.putText(sheet, f"{k}:{d} {s:.2f}", (c * cell + 2, r * (cell + 26) + cell + 11), cv2.FONT_HERSHEY_SIMPLEX, 0.33, 0, 1)
        cv2.putText(sheet, f"{mg:+.2f} t{top}", (c * cell + 2, r * (cell + 26) + cell + 23), cv2.FONT_HERSHEY_SIMPLEX, 0.33, 0, 1)
    cv2.imwrite(path, sheet)
