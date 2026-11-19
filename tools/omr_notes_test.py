"""Freigestellte Notizen sichtbar machen: links Foto, rechts die Notiz-Ebene auf Weiß.
Aufruf: tools/_homr/venv_old/Scripts/python -I tools/omr_notes_test.py <bild> <ausgabe.jpg>"""
import os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import cv2
import numpy as np
import vomblatt_omr


class P:
    def update(self, *a): pass
    def overlay(self, s): pass
    def pedals(self, s): pass
    def hairpins(self, s): pass


vomblatt_omr.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
out = sys.argv[2] + ".png"
# Druck-Rahmen aus der Texterkennung gibt es nur in der App (ML Kit); optional als JSON-Datei übergeben
boxes = open(sys.argv[3]).read() if len(sys.argv) > 3 else "[]"
scale = vomblatt_omr.extract_notes(sys.argv[1], boxes, out)
rgba = cv2.imread(out, cv2.IMREAD_UNCHANGED)
img = cv2.imread(sys.argv[1])
img = cv2.resize(img, (rgba.shape[1], rgba.shape[0]))
a = rgba[:, :, 3:4].astype(np.float32) / 255
layer = (rgba[:, :, :3] * a + 255 * (1 - a)).astype(np.uint8)
both = np.hstack([img, layer])
k = 1600 / both.shape[1]
cv2.imwrite(sys.argv[2], cv2.resize(both, None, fx=k, fy=k, interpolation=cv2.INTER_AREA))
print("ok", rgba.shape, scale, int((rgba[:, :, 3] > 0).sum()))
