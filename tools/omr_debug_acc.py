import os, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import vomblatt_omr as v

orig_ka, orig_fa = v._fix_key_changes, v._fix_accidentals
def ka(voices):
    for seq in voices:
        print("SEQ", type(seq).__name__, len(seq), file=sys.__stdout__)
        print(" keys:", [s.rhythm for s in seq if "keySig" in s.rhythm], file=sys.__stdout__)
    return orig_ka(voices)
def fa(voices, ms, m):
    n = orig_fa(voices, ms, m)
    for seq in voices:
        key = 0
        for s in seq:
            if s.rhythm.startswith("keySignature_"): key = int(s.rhythm.split("_")[1])
            if v._is_note(s) and v._LIFT_ALTER.get(s.lift, 0) != v._key_alter(key, s.pitch[0]):
                print(" bleibt:", s.pitch, s.lift, s.position, [round(c) for c in (s.image_coordinates or (0, 0))], file=sys.__stdout__)
    return n
v._fix_key_changes, v._fix_accidentals = ka, fa
class P:
    def update(self, *a): pass
    def overlay(self, s): pass
v.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
