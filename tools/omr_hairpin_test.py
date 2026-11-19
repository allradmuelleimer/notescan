"""Zeichnet erkannte Gabeln ins Foto: grün = cresc. (<), rot = decresc. (>).
Aufruf: tools/_homr/venv_old/Scripts/python -I tools/omr_hairpin_test.py <bild> <ausgabe.jpg>"""
import json, os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import cv2
import vomblatt_omr


class P:
    ev = []
    def update(self, *a): pass
    def overlay(self, s): pass
    def pedals(self, s): pass
    def hairpins(self, s): P.ev = json.loads(s)


vomblatt_omr.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
img = cv2.imread(sys.argv[1])
for e in P.ev:
    c = (0, 170, 0) if e["type"] == "cresc" else (0, 0, 230)
    cv2.rectangle(img, (int(e["x0"]), int(e["y"]) - 25), (int(e["x1"]), int(e["y"]) + 25), c, 5)
k = 1400 / max(img.shape[:2])
cv2.imwrite(sys.argv[2], cv2.resize(img, None, fx=k, fy=k))
print(json.dumps(P.ev))
