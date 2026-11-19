"""Prüfblatt: echte Versetzungszeichen (♯ ♭ ♮) und Fingersätze. Erwartung je Note in expected.txt."""
import sys
import verovio

# (Tonname, Oktave, Alter, gedrucktes Vorzeichen, Fingersatz)
rh = [
    [("C", 5, 0, None, 1), ("D", 5, 0, None, 2), ("F", 5, 1, "sharp", 4), ("G", 5, 0, None, 5)],
    [("B", 4, -1, "flat", 3), ("A", 4, 0, None, 2), ("B", 4, -1, None, 3), ("C", 5, 0, None, 4)],
    [("E", 5, 0, None, 3), ("E", 5, -1, "flat", 2), ("D", 5, 0, None, 1), ("C", 5, 1, "sharp", 2)],
    [("D", 5, 0, None, 3), ("F", 5, 1, "sharp", 5), ("F", 5, 0, "natural", 4), ("E", 5, 0, None, 3)],
    [("G", 4, 1, "sharp", 1), ("A", 4, 0, None, 2), ("B", 4, 0, None, 3), ("C", 5, 0, None, 4)],
    [("A", 4, -1, "flat", 2), ("G", 4, 0, None, 1), ("F", 4, 0, None, 3), ("E", 4, 0, None, 2)],
    [("D", 5, 1, "sharp", 4), ("E", 5, 0, None, 5), ("C", 5, 0, None, 3), ("A", 4, 0, None, 1)],
    [("C", 5, 0, None, 1), ("E", 5, 0, None, 3), ("G", 5, 0, None, 5), ("C", 5, 0, None, 1)],
]
lh = [("C", 3), ("G", 2), ("A", 2), ("D", 3), ("E", 3), ("F", 2), ("G", 2), ("C", 3)]

def note(step, octv, alter, acc, finger, staff, dur, typ):
    a = f"<alter>{alter}</alter>" if alter else ""
    ac = f"<accidental>{acc}</accidental>" if acc else ""
    f = f"<notations><technical><fingering>{finger}</fingering></technical></notations>" if finger else ""
    return (f"<note><pitch><step>{step}</step>{a}<octave>{octv}</octave></pitch><duration>{dur}</duration>"
            f"<voice>{staff}</voice><type>{typ}</type>{ac}<staff>{staff}</staff>{f}</note>")

ms = []
for i in range(8):
    attrs = ""
    if i == 0:
        attrs = ("<attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats>"
                 "<beat-type>4</beat-type></time><staves>2</staves><clef number='1'><sign>G</sign><line>2</line></clef>"
                 "<clef number='2'><sign>F</sign><line>4</line></clef></attributes>")
    br = "<print new-system='yes'/>" if i == 4 else ""
    body = "".join(note(*n, 1, 1, "quarter") for n in rh[i])
    body += "<backup><duration>4</duration></backup>" + note(lh[i][0], lh[i][1], 0, None, 5 if i % 2 else 1, 2, 4, "whole")
    ms.append(f"<measure number='{i + 1}'>{br}{attrs}{body}</measure>")
xml = ("<?xml version='1.0' encoding='UTF-8'?><score-partwise version='3.1'><part-list><score-part id='P1'>"
       "<part-name>Piano</part-name></score-part></part-list><part id='P1'>" + "".join(ms) + "</part></score-partwise>")
open(sys.argv[1] + "/acc.musicxml", "w", encoding="utf-8").write(xml)
tk = verovio.toolkit()
tk.setOptions({"pageWidth": 2100, "pageHeight": 2970, "scale": 60, "adjustPageHeight": True, "breaks": "encoded"})
tk.loadData(xml)
svg = tk.renderToSVG(1)
open(sys.argv[1] + "/acc.html", "w", encoding="utf-8").write(
    "<html><body style='margin:0;background:white'>" + svg + "</body></html>")
exp = [f"{s}{o}:{a}" for m in rh for (s, o, a, _, _) in m]
open(sys.argv[1] + "/expected.txt", "w").write(" ".join(exp))
print("ok")
