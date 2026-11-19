"""Gibt die erkannten Zeilenrahmen (Fotokoordinaten) und die Taktzahl aus – zum Prüfen von Seitenzuschnitt
und Nachbarseiten. Aufruf: tools/_homr/venv_old/Scripts/python -I tools/omr_boxes_test.py <bild>"""
import json, os, re, sys
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(root, "app", "src", "main", "python"))
import vomblatt_omr


class P:
    def update(self, *a): pass
    def overlay(self, s):
        for st in json.loads(s)["staffs"]:
            print("Zeile", st["box"], file=sys.__stdout__)
    def pedals(self, s): pass
    def hairpins(self, s): pass


xml = vomblatt_omr.recognize(sys.argv[1], os.path.join(root, "app", "src", "main", "assets", "models"), P())
print("Takte:", len(re.findall(r"<measure ", xml)), file=sys.__stdout__)
