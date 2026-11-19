"""Für Elise (Beethoven, gemeinfrei) – Anfang als MusicXML + verovio-SVG fürs Store-Foto.
Aufruf: fuer_elise.py <ordner>"""
import sys
import verovio

# Tokens: (Tonhöhe oder 'r', Dauer in Sechzehnteln, Fingersatz oder None)
E = lambda p, d=1, f=None: (p, d, f)
R = lambda d=1: ('r', d, None)

A_RH = [
    [E('E5', 1, 5), E('D#5', 1, 4), E('E5'), E('B4', 1, 1), E('D5'), E('C5', 1, 2)],
    [E('A4', 2, 1), R(), E('C4', 1, 1), E('E4'), E('A4')],
    [E('B4', 2, 4), R(), E('E4', 1, 1), E('G#4'), E('B4')],
    [E('C5', 2, 4), R(), E('E4'), E('E5'), E('D#5')],
]
A_RH2 = [A_RH[0], A_RH[1], [E('B4', 2), R(), E('E4'), E('C5'), E('B4')]]
LH_A = [R(6), [E('A2', 1, 5), E('E3', 1, 2), E('A3', 1, 1), R(3)], [E('E2'), E('E3'), E('G#3'), R(3)],
        [E('A2'), E('E3'), E('A3'), R(3)]]
B_RH = [
    [E('E5', 3, 5), E('G4', 1, 1), E('F5'), E('E5')],
    [E('D5', 3, 4), E('F4'), E('E5'), E('D5')],
    [E('C5', 3, 3), E('E4'), E('D5'), E('C5')],
    [E('B4', 2, 2), R(), E('E4'), E('E5'), E('D#5')],
]
B_LH = [[E('C3'), E('G3'), E('C4'), R(3)], [E('G2'), E('G3'), E('B3'), R(3)], [E('A2'), E('E3'), E('A3'), R(3)],
        [E('E2'), E('E3'), E('E4'), R(3)]]

rh, lh = [], []
# Takt 0: Auftakt
rh.append([E('E5', 1, 5), E('D#5', 1, 4)]); lh.append([R(2)])
for rep in range(2):
    rh += [A_RH[0], A_RH[1], A_RH[2], A_RH[3]]; lh += [None, LH_A[1], LH_A[2], LH_A[3]]
    rh += A_RH2; lh += [None, LH_A[1], LH_A[2]]
    if rep == 0:
        rh.append([E('A4', 2), R(), E('B4'), E('C5'), E('D5')]); lh.append(LH_A[3])
        rh += B_RH; lh += B_LH
rh.append([E('A4', 2), R(4)]); lh.append([E('A2', 2), R(4)])
for i, m in enumerate(lh):
    if m is None:
        lh[i] = [R(6)]

STEPS = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}


def note_xml(tok, staff, voice, beam, state, chordfirst=True):
    p, d, f = tok
    typ = {1: '16th', 2: 'eighth', 3: 'eighth', 4: 'quarter', 6: 'quarter'}[d]
    dot = '<dot/>' if d in (3, 6) else ''
    if p == 'r':
        return f"<note><rest/><duration>{d}</duration><voice>{voice}</voice><type>{typ}</type>{dot}<staff>{staff}</staff></note>"
    step, alter, octv = p[0], (1 if '#' in p else 0), p[-1]
    key = step + octv
    acc = ''
    if state.get(key, 0) != alter:
        acc = '<accidental>' + ('sharp' if alter else 'natural') + '</accidental>'
        state[key] = alter
    a = f"<alter>{alter}</alter>" if alter else ''
    b = ''.join(f"<beam number='{n}'>{v}</beam>" for n, v in beam)
    tech = f"<notations><technical><fingering placement='{'above' if staff == 1 else 'below'}'>{f}</fingering></technical></notations>" if f else ''
    return (f"<note><pitch><step>{step}</step>{a}<octave>{octv}</octave></pitch><duration>{d}</duration>"
            f"<voice>{voice}</voice><type>{typ}</type>{dot}{acc}"
            f"<staff>{staff}</staff>{b}{tech}</note>")


def beams(toks):
    out = [[] for _ in toks]
    i = 0
    while i < len(toks):
        j = i
        while j < len(toks) and toks[j][0] != 'r' and toks[j][1] <= 3:
            j += 1
        if j - i >= 2:
            for k in range(i, j):
                out[k].append((1, 'begin' if k == i else 'end' if k == j - 1 else 'continue'))
            # zweite Ebene für Sechzehntelfolgen
            k = i
            while k < j:
                if toks[k][1] == 1:
                    m = k
                    while m < j and toks[m][1] == 1:
                        m += 1
                    if m - k >= 2:
                        for q in range(k, m):
                            out[q].append((2, 'begin' if q == k else 'end' if q == m - 1 else 'continue'))
                    k = m
                else:
                    k += 1
        i = max(j, i + 1)
    return out


def stems_uniform(toks, staff):
    return toks


ms = []
for i, (r, l) in enumerate(zip(rh, lh)):
    attrs = ''
    if i == 0:
        attrs = ("<attributes><divisions>4</divisions><key><fifths>0</fifths><mode>minor</mode></key><time><beats>3</beats>"
                 "<beat-type>8</beat-type></time><staves>2</staves><clef number='1'><sign>G</sign><line>2</line></clef>"
                 "<clef number='2'><sign>F</sign><line>4</line></clef></attributes>"
                 "<direction placement='above'><direction-type><words font-weight='bold'>Poco moto</words></direction-type><staff>1</staff></direction>"
                 "<direction placement='below'><direction-type><dynamics><pp/></dynamics></direction-type><staff>1</staff></direction>")
    state = {}
    body = attrs
    for tok, bm in zip(r, beams(r)):
        body += note_xml(tok, 1, 1, bm, state)
    dur = sum(t[1] for t in r)
    body += f"<backup><duration>{dur}</duration></backup>"
    state2 = {}
    for tok, bm in zip(l, beams(l)):
        body += note_xml(tok, 2, 5, bm, state2)
    implicit = " implicit='yes'" if i == 0 else ''
    bar = "<barline location='right'><bar-style>light-heavy</bar-style></barline>" if i == len(rh) - 1 else ''
    BRK = "<print new-system='yes'/>"
    ms.append(f"<measure number='{i}'{implicit}>{BRK if i in (5, 9, 13, 17) else ''}{body}{bar}</measure>")

xml = ("<?xml version='1.0' encoding='UTF-8'?><score-partwise version='3.1'>"
       "<work><work-title>Für Elise</work-title></work><identification><creator type='composer'>Ludwig van Beethoven</creator></identification>"
       "<part-list><score-part id='P1'><part-name></part-name></score-part></part-list><part id='P1'>"
       + ''.join(ms) + "</part></score-partwise>")
open(sys.argv[1] + '/fuer_elise.musicxml', 'w', encoding='utf-8').write(xml)
tk = verovio.toolkit()
tk.setOptions({"pageWidth": 1500, "pageHeight": 6000, "scale": 50, "adjustPageHeight": True, "header": "auto",
               "footer": "none", "breaks": "encoded", "spacingSystem": 12, "pageMarginTop": 90, "pageMarginLeft": 100, "pageMarginRight": 100})
tk.loadData(xml)
print('Seiten', tk.getPageCount(), 'Takte', len(ms))
open(sys.argv[1] + '/fuer_elise.svg', 'w', encoding='utf-8').write(tk.renderToSVG(1))
