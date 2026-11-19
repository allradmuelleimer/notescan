"""Testet den App-Einstieg vomblatt_omr auf dem PC (echtes onnxruntime, Android-nahe Paketversionen).

Aufruf: tools/_homr/venv_old/Scripts/python -I tools/omr_pc_test.py <bild> <ausgabe.musicxml>
"""
import os
import sys
import time

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import vomblatt_omr  # noqa: E402


class P:
    def update(self, phase, done, total):
        print(f"[progress] {phase} {done}/{total}", file=sys.__stdout__, flush=True)


t = time.time()
xml = vomblatt_omr.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
open(sys.argv[2], "w", encoding="utf-8").write(xml)
print("ok", len(xml), "Zeichen in", round(time.time() - t, 1), "s", file=sys.__stdout__)
