"""Zeichnet erkannte Pedalstellen ins Foto: grün = treten, orange = wechseln, rot = lösen."""
import json, os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import cv2
import vomblatt_omr

class P:
    ev = []
    def update(self, *a): pass
    def overlay(self, s): pass
    def pedals(self, s): P.ev = json.loads(s)

vomblatt_omr.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
img = cv2.imread(sys.argv[1])
col = {"down": (0, 170, 0), "change": (0, 140, 255), "up": (0, 0, 230)}
for e in P.ev:
    x, y = int(e["x"]), int(e["y"])
    cv2.line(img, (x, y - 40), (x, y + 40), col[e["type"]], 6)
k = 1400 / img.shape[0]
cv2.imwrite(sys.argv[2], cv2.resize(img, None, fx=k, fy=k))
print({t: sum(1 for e in P.ev if e["type"] == t) for t in col})
