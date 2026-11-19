"""Prüft die Overlay-Daten am PC: zeichnet Linien und Notenköpfe ins Foto (Ausgabe als JPG)."""
import json
import os
import sys

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import cv2  # noqa: E402
import vomblatt_omr  # noqa: E402


class P:
    data = None

    def update(self, *a):
        pass

    def overlay(self, s):
        P.data = json.loads(s)


vomblatt_omr.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
img = cv2.imread(sys.argv[1])
for st in P.data["staffs"]:
    for line in st["lines"]:
        pts = [(int(line[i]), int(line[i + 1])) for i in range(0, len(line), 2)]
        for a, b in zip(pts, pts[1:]):
            cv2.line(img, a, b, (219, 75, 43), 3)
    x0, y0, x1, y1 = st["box"]
    cv2.rectangle(img, (x0, y0), (x1, y1), (0, 140, 255), 3)
n = P.data["notes"]
for i in range(0, len(n), 2):
    cv2.circle(img, (int(n[i]), int(n[i + 1])), 9, (219, 75, 43), -1)
img = cv2.resize(img, (img.shape[1] * 1000 // img.shape[0], 1000))
cv2.imwrite(sys.argv[2], img)
print(len(P.data["staffs"]), "Zeilen,", len(n) // 2, "Noten")
