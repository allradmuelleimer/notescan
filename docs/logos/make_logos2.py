"""Erzeugt logos2.html: fünf Logo-Varianten mit Kamerasucher-Ecken (NoteScan)."""
INK, KOB, BG, DARK, LIGHT, KOBD = "#16181D", "#2B4BDB", "#F2F3F5", "#0E1014", "#ECEEF2", "#8297FF"


def corners(col, inset=28, arm=30, w=9):
    a, b = inset, 170 - inset
    return (f'<g fill="none" stroke="{col}" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round">'
            f'<path d="M{a} {a+arm}V{a}h{arm}M{b-arm} {a}h{arm}v{arm}M{b} {b-arm}v{arm}h-{arm}M{a+arm} {b}H{a}v-{arm}"/></g>')


def glyph(code, x, y, size, col):
    return f'<text x="{x:.1f}" y="{y:.1f}" font-family="Bravura" font-size="{size}" fill="{col}">&#x{code};</text>'


def icon(v, dark):
    ink = LIGHT if dark else INK
    kob = KOBD if dark else KOB
    bg = DARK if dark else BG
    if v == 5:  # Kobalt-Fläche, alles hell
        bg = KOBD if dark else KOB
        fg = DARK if dark else "#FFFFFF"
        body = corners(fg) + glyph("E1D5", 85 - 166 * 0.115, 85 + 367 * 0.115, 115, fg)
    elif v == 1:  # Viertelnote, Ecken Kobalt
        body = corners(kob) + glyph("E1D5", 85 - 166 * 0.115, 85 + 367 * 0.115, 115, ink)
    elif v == 2:  # Achtel mit Fahne, Scanlinie
        body = (corners(ink) + glyph("E1D7", 85 - 283 * 0.1 - 4, 85 + 367 * 0.1, 100, ink)
                + f'<rect x="22" y="104" width="126" height="6" rx="3" fill="{kob}"/>')
    elif v == 3:  # Notenlinien im Sucher, Kopf auf der Scanlinie
        lines = "".join(f'<rect x="44" y="{y}" width="82" height="2.6" fill="{ink}" opacity=".28"/>' for y in (59, 72, 98, 111))
        body = (corners(ink) + lines + f'<rect x="30" y="83.5" width="110" height="5" rx="2.5" fill="{kob}"/>'
                + glyph("E0A4", 85 - 147.5 * 0.13, 86, 130, ink))
    else:  # 4: zwei Achtel mit Balken, Balken = Scanstrahl
        hx = (60, 104)
        heads = "".join(f'<ellipse cx="{x}" cy="{y}" rx="13.5" ry="9.5" transform="rotate(-20 {x} {y})" fill="{ink}"/>' for x, y in ((hx[0], 116), (hx[1], 106)))
        stems = "".join(f'<rect x="{x + 9.5}" y="{t}" width="5" height="{b - t}" fill="{ink}"/>' for x, t, b in ((hx[0], 60, 114), (hx[1], 50, 104)))
        beam = f'<polygon points="{hx[0]+9.5},58 {hx[1]+14.5},48 {hx[1]+14.5},60 {hx[0]+9.5},70" fill="{kob}"/>'
        body = corners(ink) + stems + beam + heads
    return f'<svg viewBox="0 0 170 170" width="{{s}}" height="{{s}}"><rect width="170" height="170" rx="40" fill="{bg}"/>{body}</svg>'


NAMES = {
    1: ("Viertel im Sucher", "Deine Vorlage, ausgearbeitet: Viertelnote mittig, Ecken in Kobalt – klar und sofort lesbar."),
    2: ("Achtel und Scanlinie", "Achtelnote mit Fähnchen, schwarze Ecken; eine Scanlinie in Kobalt läuft durch – „wird gerade gelesen“."),
    3: ("Notenzeile im Sucher", "Ein Ausschnitt Notenzeile im Sucher, die mittlere Linie ist die Scanlinie, der Notenkopf sitzt darauf."),
    4: ("Balken als Strahl", "Zwei Achtel mit Balken; der Balken in Kobalt wirkt wie ein Scanstrahl quer durchs Bild."),
    5: ("Kobalt-Fläche", "Wie 1, aber umgekehrt: Kobalt-Fläche, weiße Ecken und Note – fällt auf dem Startbildschirm am stärksten auf."),
}

cards = []
for v in range(1, 6):
    n, why = NAMES[v]
    light = icon(v, False).replace("{s}", "170")
    dark = icon(v, True).replace("{s}", "170")
    small = icon(v, False).replace("{s}", "96")
    tiny = icon(v, False).replace("{s}", "48")
    cards.append(f'''
<div class="card">
  <div class="label"><span class="num">0{v}</span><span class="name">{n}</span><span class="why">{why}</span></div>
  <div class="lock">{small}<span class="word">NoteScan</span></div>
  <div class="mini">{tiny}<span>48 px</span></div>
  {light}
  {dark}
</div>''')

html = f'''<!doctype html>
<html lang="de"><head><meta charset="utf-8"><title>NoteScan Sucher-Logos</title>
<style>
  @font-face {{ font-family: Bravura; src: url("../../app/src/main/assets/fonts/Bravura.otf"); }}
  @font-face {{ font-family: Inst; src: url("../../app/src/main/res/font/instrument_sans.ttf"); font-weight: 400 700; font-stretch: 75% 100%; }}
  * {{ box-sizing: border-box; margin: 0; }}
  body {{ background: {BG}; font-family: Inst, sans-serif; color: {INK}; padding: 40px; width: 1400px; }}
  h1 {{ font: 620 34px Inst; font-variation-settings: "wdth" 78; letter-spacing: -.4px; margin-bottom: 6px; }}
  .sub {{ color: #5D6370; font-size: 16px; margin-bottom: 28px; }}
  .card {{ background: #fff; border-radius: 22px; padding: 26px 30px; margin-bottom: 22px; display: grid;
          grid-template-columns: 1fr 90px 170px 170px; gap: 26px; align-items: center; }}
  .label {{ grid-column: 1 / -1; display: flex; gap: 12px; align-items: baseline; }}
  .num {{ font: 700 15px Inst; color: {KOB}; }}
  .name {{ font: 620 21px Inst; font-variation-settings: "wdth" 78; }}
  .why {{ color: #5D6370; font-size: 15px; }}
  .lock {{ display: flex; align-items: center; gap: 22px; }}
  .word {{ font: 620 80px Inst; font-variation-settings: "wdth" 78; letter-spacing: -1.4px; line-height: 1; }}
  .mini {{ display: flex; flex-direction: column; align-items: center; gap: 6px; color: #9399A4; font-size: 12px; }}
  svg {{ display: block; }}
</style></head><body>
<h1>NoteScan – Sucher-Varianten</h1>
<div class="sub">Kamerasucher-Ecken mit Notenzeichen aus Bravura. Je Variante: Logo mit Schriftzug, App-Symbol klein (48 px), hell und dunkel.</div>
{"".join(cards)}
</body></html>'''
open(__file__.replace("make_logos2.py", "logos2.html"), "w", encoding="utf-8").write(html)
print("ok")
