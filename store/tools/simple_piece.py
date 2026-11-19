"""Zwei einfache gemeinfreie Klavierstücke (Melodie in Vierteln, linke Hand in Halben) → SVG.
Aufruf: simple_piece.py <ordner>"""
import sys
import verovio

PIECES = {
    "ode": ("Ode to Joy", "Ludwig van Beethoven",
            ["E5 E5 F5 G5", "G5 F5 E5 D5", "C5 C5 D5 E5", "E5. D5/8 D5:2",
             "E5 E5 F5 G5", "G5 F5 E5 D5", "C5 C5 D5 E5", "D5. C5/8 C5:2",
             "D5 D5 E5 C5", "D5 E5/8 F5/8 E5 C5", "D5 E5/8 F5/8 E5 D5", "C5 D5 G4:2",
             "E5 E5 F5 G5", "G5 F5 E5 D5", "C5 C5 D5 E5", "D5. C5/8 C5:2"],
            ["C3:2 G3:2", "B2:2 G3:2", "A2:2 E3:2", "G2:2 B2:2", "C3:2 G3:2", "B2:2 G3:2", "A2:2 E3:2", "G2:2 C3:2",
             "G2:2 B2:2", "C3:2 G2:2", "C3:2 G2:2", "C3:2 B2:2", "C3:2 G3:2", "B2:2 G3:2", "A2:2 E3:2", "G2:2 C3:2"], "Allegro assai"),
    "twinkle": ("Ah vous dirai-je, Maman", "Traditional",
                ["C5 C5 G5 G5", "A5 A5 G5:2", "F5 F5 E5 E5", "D5 D5 C5:2",
                 "G5 G5 F5 F5", "E5 E5 D5:2", "G5 G5 F5 F5", "E5 E5 D5:2",
                 "C5 C5 G5 G5", "A5 A5 G5:2", "F5 F5 E5 E5", "D5 D5 C5:2"],
                ["C3:1", "F3:2 C3:2", "F3:2 C3:2", "G3:2 C3:2", "C3:2 G3:2", "C3:2 G3:2", "C3:2 G3:2", "C3:2 G3:2",
                 "C3:1", "F3:2 C3:2", "F3:2 C3:2", "G3:2 C3:2"], "Andante"),
}


def notes(spec, staff, voice):
    out, total = "", 0
    for tok in spec.split():
        dur, typ, dot = 2, "quarter", ""
        if tok.endswith("."):
            tok, dur, typ, dot = tok[:-1], 3, "quarter", "<dot/>"
        elif tok.endswith("/8"):
            tok, dur, typ = tok[:-2], 1, "eighth"
        elif tok.endswith(":2"):
            tok, dur, typ = tok[:-2], 4, "half"
        elif tok.endswith(":1"):
            tok, dur, typ = tok[:-2], 8, "whole"
        total += dur
        out += (f"<note><pitch><step>{tok[0]}</step><octave>{tok[-1]}</octave></pitch><duration>{dur}</duration>"
                f"<voice>{voice}</voice><type>{typ}</type>{dot}<staff>{staff}</staff></note>")
    return out, total


for key, (title, comp, rh, lh, tempo) in PIECES.items():
    ms = []
    for i, (r, l) in enumerate(zip(rh, lh)):
        attrs = ""
        if i == 0:
            attrs = ("<attributes><divisions>2</divisions><key><fifths>0</fifths></key><time><beats>4</beats><beat-type>4</beat-type></time>"
                     "<staves>2</staves><clef number='1'><sign>G</sign><line>2</line></clef><clef number='2'><sign>F</sign><line>4</line></clef></attributes>"
                     f"<direction placement='above'><direction-type><words font-weight='bold'>{tempo}</words></direction-type><staff>1</staff></direction>"
                     "<direction placement='below'><direction-type><dynamics><mf/></dynamics></direction-type><staff>1</staff></direction>")
        a, ta = notes(r, 1, 1)
        b, _ = notes(l, 2, 5)
        brk = "<print new-system='yes'/>" if i and i % 4 == 0 else ""
        bar = "<barline location='right'><bar-style>light-heavy</bar-style></barline>" if i == len(rh) - 1 else ""
        ms.append(f"<measure number='{i + 1}'>{brk}{attrs}{a}<backup><duration>{ta}</duration></backup>{b}{bar}</measure>")
    xml = ("<?xml version='1.0' encoding='UTF-8'?><score-partwise version='3.1'>"
           f"<work><work-title>{title}</work-title></work><identification><creator type='composer'>{comp}</creator></identification>"
           "<part-list><score-part id='P1'><part-name></part-name></score-part></part-list><part id='P1'>" + "".join(ms) + "</part></score-partwise>")
    tk = verovio.toolkit()
    tk.setOptions({"pageWidth": 1500, "pageHeight": 6000, "scale": 50, "adjustPageHeight": True, "header": "auto", "footer": "none",
                   "breaks": "encoded", "spacingSystem": 14, "pageMarginTop": 90, "pageMarginLeft": 100, "pageMarginRight": 100})
    tk.loadData(xml)
    open(f"{sys.argv[1]}/{key}.svg", "w", encoding="utf-8").write(tk.renderToSVG(1))
    html = f"""<html><body style="margin:0;width:2100px;height:2970px;background:#fff;position:relative;overflow:hidden">
<img src="{key}.svg" style="position:absolute;left:270px;top:60px;width:1560px">
<div style="position:absolute;right:270px;top:188px;font:italic 34px 'Times New Roman'">{comp}</div></body></html>"""
    open(f"{sys.argv[1]}/{key}.html", "w", encoding="utf-8").write(html)
    print(key, "ok")
