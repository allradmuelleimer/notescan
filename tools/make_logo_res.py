"""Übernimmt Gideons Logos aus Optik/ in die App.

- Optik/Logo Icon.svg → res/drawable/ic_launcher_fg.xml (adaptives App-Symbol, Vordergrund) und
  res/drawable/ic_launcher_mono.xml (einfarbig für Android-Designs mit Systemfarben)
- Optik/Logo lang.svg → ui/LogoPaths.kt (Pfade für das lange Logo in der Bibliothek, Farben setzt die App je Modus)

Aufruf: python tools/make_logo_res.py
"""
import os
import re
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NS = "{http://www.w3.org/2000/svg}"


def shapes(svg_path):
    """(Art, Pfaddaten) aller Formen: "ink" = schwarz, "accent" = Kobalt, "white" = weiße Innenflächen (werden nicht
    gebraucht – die Buchstaben haben echte Löcher). Zuordnung nach der Füllfarbe aus dem Stilblock, nicht nach dem
    Klassennamen – Illustrator nummeriert die Klassen je Export anders."""
    tree = ET.parse(svg_path)
    style = "".join(e.text or "" for e in tree.iter() if e.tag.replace(NS, "") == "style")
    fills = {m.group(1): m.group(2).lower() for m in re.finditer(r"\.(\w+)\s*\{[^}]*fill:\s*(#[0-9a-fA-F]{6})", style)}

    def kind(cls):
        f = fills.get(cls, "")
        if f in ("#0d0d0d", "#000000"):
            return "ink"
        if f == "#0c3be6":
            return "accent"
        return "white"
    out = []
    for el in tree.iter():
        tag = el.tag.replace(NS, "")
        cls = kind(el.get("class", ""))
        if tag == "path":
            d = el.get("d")
        elif tag == "polygon":
            nums = re.findall(r"-?[\d.]+", el.get("points"))
            pts = [(nums[i], nums[i + 1]) for i in range(0, len(nums), 2)]
            d = "M" + " L".join(f"{x},{y}" for x, y in pts) + "Z"
        elif tag == "rect":
            x, y, w, h = (float(el.get(k)) for k in ("x", "y", "width", "height"))
            d = f"M{x},{y}h{w}v{h}h{-w}z"
        else:
            continue
        out.append((cls, d))
    vb = [float(v) for v in tree.getroot().get("viewBox").split()]
    return out, vb


def bounds(ds):
    """Rahmen aller Pfade (Kontrollpunkte eingeschlossen – eher etwas zu groß, nie zu klein)."""
    xs, ys = [], []
    for d in ds:
        toks = re.findall(r"[MmLlHhVvCcSsQqTtZz]|-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?", d)
        i = 0; cmd = "M"; cx = cy = sx = sy = 0.0
        def num():
            nonlocal i
            v = float(toks[i]); i += 1; return v
        while i < len(toks):
            if re.match(r"[A-Za-z]", toks[i]):
                cmd = toks[i]; i += 1
                if cmd in "Zz":
                    cx, cy = sx, sy
                    continue
            rel = cmd.islower(); c = cmd.upper()
            n = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2}[c]
            vals = [num() for _ in range(n)]
            if c == "H":
                cx = vals[0] + (cx if rel else 0); pts = [(cx, cy)]
            elif c == "V":
                cy = vals[0] + (cy if rel else 0); pts = [(cx, cy)]
            else:
                pts = [(vals[k] + (cx if rel else 0), vals[k + 1] + (cy if rel else 0)) for k in range(0, n, 2)]
                cx, cy = pts[-1]
            if c == "M":
                sx, sy = cx, cy
                cmd = "l" if rel else "L"
            for x, y in pts:
                xs.append(x); ys.append(y)
    return min(xs), min(ys), max(xs), max(ys)


def launcher():
    items, vb = shapes(os.path.join(ROOT, "Optik", "Logo Icon.svg"))
    # Adaptives Symbol: 108 dp Raster, sichtbar sind die mittleren 72 dp (Kreis-, Kreisel- oder Rundeck-Maske).
    # Maßgeblich ist der Sucher (die vier Kobalt-Ecken, Gideon 19.11.: „nicht richtig zentriert“, „wirkt recht klein“):
    # mittig gesetzt – der Notenkopf darf unten herausragen, er gehört nicht zur Mitte –, und so groß, dass seine
    # Diagonale 70 dp misst: die Ecken passen damit auch in die runde Maske (72 dp), nichts wird abgeschnitten.
    frame = [d for c, d in items if c == "accent" and " L" in d]  # die Ecken (Polygone)
    x0, y0, x1, y1 = bounds(frame)
    print("Sucher:", round(x0, 1), round(y0, 1), round(x1, 1), round(y1, 1))
    diag = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5
    s = 70.0 / diag
    tx = 54 - (x0 + x1) / 2 * s
    ty = 54 - (y0 + y1) / 2 * s
    ax0, ay0, ax1, ay1 = bounds([d for c, d in items if c != "white"])
    print("Inhalt bis unten: %.1f dp (sichtbar bis 90 dp)" % (ay1 * s + ty))
    colors = {"ink": "#FF0D0D0D", "accent": "#FF0C3BE6"}

    # Notenhals (das Rechteck in Kobalt): in der einfarbigen Fassung verschmilzt er sonst mit dem S zum Dollarzeichen –
    # dort bekommt das S links und rechts vom Hals eine schmale Lücke (Ausschnitt über zwei Rechtecke neben dem Hals)
    stem = [d for c, d in items if c == "accent" and "c" not in d and " L" not in d]
    sx0, _, sx1, _ = bounds(stem) if stem else (0, 0, 0, 0)
    gap = (sx1 - sx0) * 0.55
    W, H = vb[2], vb[3]
    clip = f"M0,0H{sx0 - gap:.1f}V{H}H0Z M{sx1 + gap:.1f},0H{W}V{H}H{sx1 + gap:.1f}Z"

    def vector(color_of, comment, knockout=False):
        def line(c, d):
            return f'        <path android:fillColor="{color_of(c)}" android:pathData="{d}" />'
        if knockout and stem:
            inner = "\n".join("    " + line(c, d) for c, d in items if c == "ink")
            paths = (f'        <group>\n            <clip-path android:pathData="{clip}" />\n{inner}\n        </group>\n'
                     + "\n".join(line(c, d) for c, d in items if c == "accent"))
        else:
            paths = "\n".join(line(c, d) for c, d in items if c in colors)
        return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} Erzeugt mit tools/make_logo_res.py aus Optik/Logo Icon.svg – nicht von Hand ändern. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="{s:.5f}" android:scaleY="{s:.5f}" android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">
{paths}
    </group>
</vector>
'''
    res = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
    open(os.path.join(res, "ic_launcher_fg.xml"), "w", encoding="utf-8").write(
        vector(lambda c: colors[c], "App-Symbol (Vordergrund): Notenschlüssel-S im Kamerasucher."))
    open(os.path.join(res, "ic_launcher_mono.xml"), "w", encoding="utf-8").write(
        vector(lambda c: "#FFFFFFFF", "App-Symbol einfarbig (Android-Designs mit Systemfarben), Hals frei vom S.", knockout=True))
    print("App-Symbol: Maßstab %.4f" % s)


def long_logo():
    items, vb = shapes(os.path.join(ROOT, "Optik", "Logo lang.svg"))
    ink = [d for c, d in items if c == "ink"]
    accent = [d for c, d in items if c == "accent"]
    def arr(ds):
        return ",\n".join(f'        "{d}"' for d in ds)
    kt = f'''package com.nicedevelopments.vomblatt.ui

/** Langes Logo „NoteScan“ (Optik/Logo lang.svg). Erzeugt mit tools/make_logo_res.py – nicht von Hand ändern. */
internal object LogoPaths {{
    const val WIDTH = {vb[2]}f
    const val HEIGHT = {vb[3]}f
    /** Schwarze Teile (im dunklen Modus hell). */
    val ink = listOf(
{arr(ink)},
    )
    /** Kobalt: Notenhals, Notenkopf, Sucherecken. */
    val accent = listOf(
{arr(accent)},
    )
}}
'''
    path = os.path.join(ROOT, "app", "src", "main", "java", "com", "nicedevelopments", "vomblatt", "ui", "LogoPaths.kt")
    open(path, "w", encoding="utf-8").write(kt)
    print("Langes Logo: %d schwarze, %d Kobalt-Pfade" % (len(ink), len(accent)))


if __name__ == "__main__":
    launcher()
    long_logo()
