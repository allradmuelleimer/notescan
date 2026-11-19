import os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import cv2, numpy as np
import vomblatt_omr as v
v._fix_accidentals = lambda *a: 0
class P:
    def update(self, *a): pass
    def overlay(self, s): pass
v.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
p = v._last_predictions["p"]
img = cv2.cvtColor(p.preprocessed, cv2.COLOR_GRAY2BGR) if p.preprocessed.ndim == 2 else p.preprocessed.copy()
img = (img * 0.5).astype(np.uint8) + 127
img[p.clefs_keys > 0] = (0, 0, 255)      # rot: clefs_keys
img[p.symbols > 0] = (255, 120, 0)        # blau: symbols
img[p.notehead > 0] = (0, 170, 0)         # grün: Notenköpfe
x0, y0, x1, y1 = [int(t) for t in sys.argv[3].split(",")]
cv2.imwrite(sys.argv[2], img[y0:y1, x0:x1])
print(img.shape)
