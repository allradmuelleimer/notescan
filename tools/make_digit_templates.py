"""Vorlagen für die Fingersatz-Erkennung erzeugen: Ziffern 0–9 in vielen Druckschriften (Serif, Grotesk, fett),
dazu Gegenbeispiele (Buchstaben der Akkordsymbole, Striche, Pedalzacken, Haken, Bogenstücke).
Ergebnis: app/src/main/python/vomblatt_digits.npz (uint8, je Vorlage 24×24, Tinte = 255).
Aufruf: tools/_homr/venv_old/Scripts/python -I tools/make_digit_templates.py"""
import os
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONTS = [
    "times.ttf", "timesbd.ttf", "TimesTen-Roman.otf", "TimesTen-Bold.otf", "georgia.ttf", "georgiab.ttf",
    "cambria.ttc", "cambriab.ttf", "BOOKOS.TTF", "BOOKOSB.TTF", "pala.ttf", "palab.ttf", "GARA.TTF", "GARABD.TTF",
    "CENTURY.TTF", "BOD_R.TTF", "BOD_B.TTF", "constan.ttf", "constanb.ttf", "ROCK.TTF", "ROCKB.TTF", "BELL.TTF",
    "arial.ttf", "arialbd.ttf", "ARIALN.TTF", "ARIALNB.TTF", "calibri.ttf", "calibrib.ttf", "segoeui.ttf",
    "segoeuib.ttf", "verdana.ttf", "verdanab.ttf", "tahoma.ttf", "tahomabd.ttf", "corbel.ttf", "corbelb.ttf",
    "Candara.ttf", "Candarab.ttf", "bahnschrift.ttf", "GIL_____.TTF", "GILB____.TTF", "FRAMDCN.TTF",
    "cour.ttf", "courbd.ttf", "consola.ttf", "consolab.ttf", "CENTAUR.TTF",
]
NEG_CHARS = "ACDEFGHBbm#()/\\|-<>^LJ[]{}7STZPRMNKsotraceknfpdz?%&@+†‡x"  # Gegenbeispiele; 7 zählt als Fremdziffer


def render(ch, path, size=120):
    try:
        font = ImageFont.truetype(path, size)
    except OSError:
        return None
    im = Image.new("L", (size * 2, size * 2), 0)
    ImageDraw.Draw(im).text((size // 2, size // 4), ch, fill=255, font=font)
    a = np.array(im)
    ys, xs = np.where(a > 60)
    if len(ys) == 0:
        return None
    return a[ys.min():ys.max() + 1, xs.min():xs.max() + 1]


def shapes():
    """Gedruckte Pedalzeichen und Bogenreste, wie sie als Ziffern-Kandidaten auftauchen."""
    out = []
    for t in (4, 7, 10):
        for kind in range(9):
            im = np.zeros((120, 120), np.uint8)
            if kind == 0:   # Zacke ∧
                cv2.polylines(im, [np.array([[15, 110], [60, 10], [105, 110]])], False, 255, t)
            elif kind == 1:  # Zacke schmal
                cv2.polylines(im, [np.array([[35, 110], [60, 10], [85, 110]])], False, 255, t)
            elif kind == 2:  # Schrägstrich /
                cv2.line(im, (30, 110), (90, 10), 255, t)
            elif kind == 3:  # Schrägstrich \
                cv2.line(im, (30, 10), (90, 110), 255, t)
            elif kind == 4:  # Pedalanfang └
                cv2.polylines(im, [np.array([[30, 10], [30, 105], [110, 105]])], False, 255, t)
            elif kind == 5:  # Pedalende ┘
                cv2.polylines(im, [np.array([[10, 105], [90, 105], [90, 10]])], False, 255, t)
            elif kind == 6:  # Bogenstück
                cv2.ellipse(im, (60, 110), (50, 90), 0, 200, 340, 255, t)
            elif kind == 7:  # Haken / Fähnchen
                cv2.ellipse(im, (20, 20), (70, 90), 0, 0, 80, 255, t)
            else:  # Senkrechter Strich mit Verdickung unten (Hals mit Kopfrest)
                cv2.line(im, (60, 10), (60, 100), 255, t)
                cv2.ellipse(im, (50, 100), (25, 15), -20, 0, 360, 255, -1)
            ys, xs = np.where(im > 0)
            out.append(im[ys.min():ys.max() + 1, xs.min():xs.max() + 1])
    # gerade und leicht schräge Striche (Hals- und Taktstrichreste, Fähnchenansatz) in verschiedenen Stärken
    for wdt in (5, 8, 11, 15, 20, 26):
        for slant in (-12, -6, 0, 6, 12):
            im = np.zeros((120, 120), np.uint8)
            cv2.line(im, (60 - slant, 8), (60 + slant, 112), 255, wdt)
            ys, xs = np.where(im > 0)
            out.append(im[ys.min():ys.max() + 1, xs.min():xs.max() + 1])
    return out


def norm24(a):
    """Wie in vomblatt_omr._glyph24: Höhe auf 20 px, mittig in 24×24, weichgezeichnet."""
    h, w = a.shape
    nh = 20
    nw = max(2, min(24, int(round(w * nh / h))))
    small = cv2.resize(a, (nw, nh), interpolation=cv2.INTER_AREA)
    out = np.zeros((24, 24), np.uint8)
    x0 = (24 - nw) // 2
    out[2:22, x0:x0 + nw] = small
    return out


def main():
    fdir = "C:/Windows/Fonts"
    temps, labels, fonts = [], [], []
    used = 0
    for fi, fn in enumerate(FONTS):
        p = os.path.join(fdir, fn)
        if not os.path.exists(p):
            continue
        used += 1
        for d in "0123456789":
            a = render(d, p)
            if a is not None:
                temps.append(norm24(a)); labels.append(int(d)); fonts.append(fi)
        for ch in NEG_CHARS:
            a = render(ch, p)
            if a is not None:
                temps.append(norm24(a)); labels.append(-1); fonts.append(fi)
    for a in shapes():
        temps.append(norm24(a)); labels.append(-2); fonts.append(-1)
    out = os.path.join(root, "app", "src", "main", "python", "vomblatt_digits.npz")
    np.savez_compressed(out, t=np.array(temps, np.uint8), y=np.array(labels, np.int8), f=np.array(fonts, np.int16))
    print(used, "Schriften,", len(temps), "Vorlagen ->", out, os.path.getsize(out), "Bytes")


main()
