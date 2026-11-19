"""Fingersatz-Kandidaten sichtbar machen: Kästchen um alles, was die Erkennung für eine gedruckte Ziffer hält.
Aufruf: tools/_homr/venv_old/Scripts/python -I tools/omr_digits_test.py <bild> <ausgabe.jpg> [druckrahmen.json]
Schreibt zusätzlich <ausgabe>.digits.json (Kandidaten im Eingabebild) und <ausgabe>.state.pkl nicht."""
import json, os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import cv2
import numpy as np
import vomblatt_omr


class P:
    def update(self, *a): pass
    def overlay(self, s): self.ov = s
    def pedals(self, s): pass
    def hairpins(self, s): pass


p = P()
xml = vomblatt_omr.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), p)
open(sys.argv[2] + ".musicxml", "w", encoding="utf-8").write(xml)
open(sys.argv[2] + ".overlay.json", "w", encoding="utf-8").write(p.ov)
out = sys.argv[2] + ".png"
boxes = open(sys.argv[3]).read() if len(sys.argv) > 3 and sys.argv[3] else "[]"
os.environ["VOMBLATT_DEBUG_IMG"] = os.path.dirname(os.path.abspath(sys.argv[2]))
vomblatt_omr.extract_notes(sys.argv[1], boxes, out, sys.argv[4] if len(sys.argv) > 4 else "")
cands = json.load(open(out[:-4] + ".digits.json"))
img = cv2.imread(sys.argv[1])
for b in cands:
    x0, y0, x1, y1 = (int(round(v)) for v in b[:4])
    ok = len(b) > 4 and b[4] > 0
    cv2.rectangle(img, (x0 - 2, y0 - 2), (x1 + 2, y1 + 2), (0, 160, 0) if ok else (0, 0, 255), 1)
    cv2.putText(img, f"{cands.index(b)}" + (f":{int(b[4])}" if ok else ""), (x1 + 2, y0 + 8), cv2.FONT_HERSHEY_SIMPLEX, 0.4, (0, 140, 0) if ok else (0, 0, 200), 1)
cv2.imwrite(sys.argv[2], img)
print("Kandidaten:", len(cands))
