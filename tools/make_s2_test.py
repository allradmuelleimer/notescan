"""
Prüfblatt Stufe 2: Tempowort, Akkordsymbole, Formteil-Buchstaben, Gabeln, cresc., sim., rit./a tempo,
una corda/tre corde, Fine, D.C. al Fine. Aufruf: make_s2_test.py <ordner>  → s2.musicxml, s2.html
(danach mit Chrome headless als Bild aufnehmen, siehe Doku in CLAUDE.md).
"""
import sys
import verovio

CHORDS = ["C", "G", "Am", "F", "C", "Em/B", "Dm7", "G7", "F/G", "Bb", "Am", "C"]
RH = [["E5", "D5", "C5", "D5"], ["D5", "B4", "G4", "B4"], ["C5", "E5", "A5", "E5"], ["F5", "C5", "A4", "C5"],
      ["G5", "E5", "C5", "E5"], ["G5", "E5", "B4", "E5"], ["F5", "D5", "A4", "D5"], ["F5", "D5", "B4", "D5"],
      ["A5", "F5", "C5", "F5"], ["F5", "D5", "B4", "D5"], ["E5", "C5", "A4", "C5"], ["C5", "E5", "G5", "C6"]]
LH = ["C3", "G2", "A2", "F2", "C3", "B2", "D3", "G2", "G2", "B2", "A2", "C3"]
ALTER = {"Bb": {"B": -1}}


def pitch(p, alter=0):
    a = f"<alter>{alter}</alter>" if alter else ""
    return f"<pitch><step>{p[0]}</step>{a}<octave>{p[1:]}</octave></pitch>"


def words(text, placement="above", staff=1, size=None, italic=True, bold=False):
    st = ' font-style="italic"' if italic else ""
    st += ' font-weight="bold"' if bold else ""
    return (f'<direction placement="{placement}"><direction-type><words{st}>{text}</words></direction-type>'
            f"<staff>{staff}</staff></direction>")


def wedge(kind, staff=1):
    return (f'<direction placement="below"><direction-type><wedge type="{kind}"/></direction-type>'
            f"<staff>{staff}</staff></direction>")


def harmony(name):
    root, rest = name[0], name[1:]
    alter = ""
    if rest.startswith("b"):
        alter, rest = "<root-alter>-1</root-alter>", rest[1:]
    bass = ""
    if "/" in rest:
        rest, b = rest.split("/")
        bass = f"<bass><bass-step>{b}</bass-step></bass>"
    kind = {"": "major", "m": "minor", "m7": "minor-seventh", "7": "dominant"}[rest]
    text = {"": "", "m": "m", "m7": "m7", "7": "7"}[rest]
    return f'<harmony><root><root-step>{root}</root-step>{alter}</root><kind text="{text}">{kind}</kind>{bass}</harmony>'


extra_before = {
    0: '<direction placement="above"><direction-type><rehearsal enclosure="square">A</rehearsal></direction-type><staff>1</staff></direction>'
       + words("Andante", bold=True, italic=False) + wedge("crescendo"),
    2: wedge("diminuendo"),
    4: '<direction placement="above"><direction-type><rehearsal enclosure="square">B</rehearsal></direction-type><staff>1</staff></direction>'
       + words("cresc.", "below"),
    5: words("sim.", "below"),
    6: words("rit."),
    8: words("a tempo"),
    9: words("una corda", "below", staff=2),
    10: words("tre corde", "below", staff=2),
}
wedge_stop_after = {1, 3}
extra_after = {7: words("Fine", italic=False, bold=True), 11: words("D.C. al Fine")}

ms = []
for i in range(12):
    attrs = ""
    if i == 0:
        attrs = ("<attributes><divisions>1</divisions><key><fifths>0</fifths></key><time><beats>4</beats>"
                 "<beat-type>4</beat-type></time><staves>2</staves><clef number='1'><sign>G</sign><line>2</line></clef>"
                 "<clef number='2'><sign>F</sign><line>4</line></clef></attributes>")
    br = "<print new-system='yes'/>" if i in (4, 8) else ""
    body = harmony(CHORDS[i]) + extra_before.get(i, "")
    alt = ALTER.get(CHORDS[i], {})
    for k, p in enumerate(RH[i]):
        body += f"<note>{pitch(p, alt.get(p[0], 0))}<duration>1</duration><voice>1</voice><type>quarter</type><staff>1</staff></note>"
        if k == 3 and i in wedge_stop_after:
            body += wedge("stop")
    body += f"<backup><duration>4</duration></backup><note>{pitch(LH[i], alt.get(LH[i][0], 0))}<duration>4</duration><voice>5</voice><type>whole</type><staff>2</staff></note>"
    body += extra_after.get(i, "")
    bar = "<barline location='right'><bar-style>light-heavy</bar-style></barline>" if i == 11 else ""
    ms.append(f"<measure number='{i + 1}'>{br}{attrs}{body}{bar}</measure>")

xml = ("<?xml version='1.0' encoding='UTF-8'?><score-partwise version='3.1'><work><work-title>Stufe Zwei</work-title></work>"
       "<part-list><score-part id='P1'><part-name>Piano</part-name></score-part></part-list><part id='P1'>"
       + "".join(ms) + "</part></score-partwise>")
open(sys.argv[1] + "/s2.musicxml", "w", encoding="utf-8").write(xml)
tk = verovio.toolkit()
tk.setOptions({"pageWidth": 2100, "pageHeight": 2970, "scale": 55, "adjustPageHeight": True, "breaks": "encoded",
               "header": "auto"})
tk.loadData(xml)
svg = tk.renderToSVG(1)
open(sys.argv[1] + "/s2.html", "w", encoding="utf-8").write(
    "<html><head><style>svg{width:2000px;height:auto;margin:40px}</style></head><body style='margin:0;background:white'>" + svg + "</body></html>")
print("ok")
