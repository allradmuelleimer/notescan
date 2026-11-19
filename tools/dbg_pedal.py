import os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import numpy as np, cv2
import vomblatt_omr as v
orig = v._detect_pedals
def dp(ms, m):
    p = v._last_predictions["p"]
    systems = sorted(ms, key=lambda x: x.staffs[0].min_y)
    for si, s in enumerate(systems):
        st = max(s.staffs, key=lambda q: q.max_y)
        ls = float(np.median([np.median(np.diff(g.y[-5:])) for g in st.grid]))
        print(f"System {si}: staffs={len(s.staffs)} min_x={st.min_x:.0f} max_x={st.max_x:.0f} max_y={st.max_y:.0f} ls={ls:.1f} gridlines={len(st.grid[0].y)}", file=sys.__stdout__)
    return orig(ms, m)
v._detect_pedals = dp
class P:
    def update(self, *a): pass
    def overlay(self, s): pass
    def pedals(self, s): pass
v.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
