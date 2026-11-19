import os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import vomblatt_omr as v
orig = v._fix_key_changes
def ka(voices):
    seq = voices[0]
    i = [k for k, s in enumerate(seq) if s.rhythm == "keySignature_0"][0]
    print("vor 0:", [(s.rhythm, s.pitch, s.lift) for s in seq[i-4:i]], file=sys.__stdout__)
    print("nach 0:", [(s.rhythm, s.pitch, s.lift) for s in seq[i+1:i+60] if v._is_note(s) and s.pitch[0] == "B"], file=sys.__stdout__)
    n = orig(voices)
    print("korrigiert:", n, [s.rhythm for s in seq if "keySig" in s.rhythm], file=sys.__stdout__)
    return n
v._fix_key_changes = ka
class P:
    def update(self, *a): pass
    def overlay(self, s): pass
v.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
