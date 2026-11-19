import os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import cv2, numpy as np
import vomblatt_omr as v
def fa(voices, ms, m):
    p = v._last_predictions["p"]
    units = [float(st.average_unit_size) for x in ms for st in x.staffs]
    print("units", [round(x, 1) for x in units], file=sys.__stdout__)
    n, _, stats, c = cv2.connectedComponentsWithStats((p.clefs_keys > 0).astype(np.uint8), connectivity=8)
    print("Komponenten (x,y,w,h):", [(int(c[i][0]), int(c[i][1]), int(stats[i, 2]), int(stats[i, 3])) for i in range(1, n) if stats[i, 4] > 20], file=sys.__stdout__)
    print("Noten mit Vorzeichen:", [(s.pitch, s.lift, [round(t) for t in s.image_coordinates]) for s in voices[0] if v._is_note(s) and s.lift not in ("_", ".")][:12], file=sys.__stdout__)
    ox, oy = m((0.0, 0.0)); print("map", ox, oy, m((1000.0, 1000.0)), file=sys.__stdout__)
    return 0
v._fix_accidentals = fa
class P:
    def update(self, *a): pass
    def overlay(self, s): pass
v.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
