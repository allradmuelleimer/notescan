# Notationselemente – was die App können soll

Stand 17.11.2026. Ziel (Gideon): Die App soll alles beachten, was auf Klaviernoten üblicherweise steht –
erkennen, sauber darstellen und beim Abspielen hörbar umsetzen.

Legende: **E** = Erkennung durch homr · **D** = Darstellung in der App · **W** = Wiedergabe in der App
✓ vorhanden · ◐ teilweise · ✗ fehlt · *eigen* = homr kann es nicht, wir bauen eine eigene Erkennung

## 1. Pedale

| Element | Schreibweisen / Synonyme | E | D | W | Wiedergabe |
|---|---|---|---|---|---|
| Haltepedal (Dämpferpedal, rechtes Pedal, Sustain) | „Ped.“ / „𝆮“ … „✱“ (Loslassen); Pedallinie └──┘ unter dem Bass-System; Kerbe „^“ = Pedalwechsel (in „Sunshine“ und „Hey Roger!“) | *eigen* | ✗ | ✗ | Töne klingen nach dem Loslassen weiter, bis das Pedal hochgeht; Wechsel = kurz hoch, sofort wieder runter |
| Halbpedal | „½ Ped.“, flache Kerbe in der Pedallinie | *eigen* | ✗ | ✗ | Nachklang gedämpft (längere Ausklingzeit statt voll) |
| Sostenuto-Pedal (mittleres Pedal) | „Sost. Ped.“, „S.P.“ … „✱“ | *eigen* | ✗ | ✗ | Nur die beim Treten gehaltenen Töne klingen weiter |
| Leise-Pedal (linkes Pedal) | „una corda“, „u.c.“, „due corde“ … „tre corde“, „tutte le corde“, „t.c.“ | *eigen* (Text) | ✗ | ✗ | Leiser und weicher (Lautstärke −, Klangfarbe dunkler) |
| „senza Ped.“ / „con Ped.“ / „col Ped.“ | allgemeine Anweisung ohne genaue Stellen | *eigen* (Text) | ✗ | ✗ | Automatisches Pedal je Harmoniewechsel (Taktanfang/Akkordwechsel) |

## 2. Dynamik (Lautstärke)

| Element | Schreibweisen / Synonyme | E | D | W |
|---|---|---|---|---|
| Stufen | ppp, pp, p, mp, mf, f, ff, fff (pianissimo … fortissimo) | ✓ | ✗ | ✗ |
| Akzentuierte Einsätze | sf, sfz, sffz, fz, rf, rfz (sforzato, forzato, rinforzando), fp, sfp | ✓ | ✗ | ✗ |
| Lauter werden | Gabel „<“ (Crescendo-Gabel), „cresc.“, „crescendo“, „cresc. poco a poco“ | *eigen* | ✗ | ✗ |
| Leiser werden | Gabel „>“, „dim.“, „diminuendo“, „decresc.“, „decrescendo“, „calando“, „morendo“, „smorzando“ | *eigen* | ✗ | ✗ |
| Zusätze | „subito“ (plötzlich), „poco“, „più“, „meno“, „sempre“, „dolce“, „espress.“ | ✓ (als Modifikator) | ✗ | ◐ (dolce = weicher) |

## 3. Tempo und Agogik

| Element | Schreibweisen / Synonyme | E | D | W |
|---|---|---|---|---|
| Metronomzahl | „♩ = 120“, „M.M. ♩ = 112–160“ (Mälzels Metronom), „BPM“ | ✓ (eigen, OCR) | ✓ | ✓ |
| Tempowörter | Grave, Largo, Lento, Adagio, Andante, Andantino, Moderato, Allegretto, Allegro, Vivace, Presto, Prestissimo; deutsch: langsam, ruhig, mäßig, bewegt, schnell; engl. Pop: Slow, Medium, Moderate, Fast, Swing | *eigen* (OCR) | ✗ | ✗ (→ Tempo-Vorschlag, wenn keine Zahl dasteht) |
| Langsamer werden | rit., ritard., ritardando, rall., rallentando, allargando, „langsamer werden“ | *eigen* | ✗ | ✗ |
| Schneller werden | accel., accelerando, stringendo, „schneller werden“ | *eigen* | ✗ | ✗ |
| Zurück zum Tempo | a tempo, Tempo I / Tempo primo, tempo giusto | *eigen* | ✗ | ✗ |
| Frei | rubato, ad lib., a piacere, senza tempo | *eigen* | ✗ | ◐ (keine Änderung) |
| Fermate | 𝄐 über/unter Note oder Pause; „lunga“ | ✓ | ✗ | ✗ (Note ~1,8× länger) |
| Atemzeichen / Zäsur | „’“, „//“ (Zäsur, „railroad tracks“) | ◐ (Atemzeichen) | ✗ | ✗ (kurze Pause) |
| Swing | „Swing“, „♫ = ♪³♪“, „shuffle“ | *eigen* | ✗ | ✗ (Achtel ternär) |

## 4. Artikulation und Spielweise

| Element | Schreibweisen / Synonyme | E | D | W |
|---|---|---|---|---|
| Staccato | Punkt über/unter der Note, „stacc.“ | ✓ | ✓ | ✓ |
| Staccatissimo | Keil ▾ | ✓ | ✗ | ✗ |
| Tenuto | Strich „–“, „ten.“ | ✓ | ✗ | ✗ |
| Portato / Mezzostaccato | Punkt + Strich, Punkte unter Bogen | ✓ | ✗ | ✗ |
| Akzent | „>“ | ✓ | ✓ | ✓ |
| Marcato / starker Akzent | „^“ | ✓ | ✗ | ✗ |
| Legato / Bindebogen | Bogen über mehrere Noten, „legato“ | ✓ | ✗ | ✗ (Noten ohne Lücke, leicht überlappend) |
| Haltebogen | Bogen zwischen gleichen Tönen | ✓ | ✓ | ✓ |
| Arpeggio | Wellenlinie vor dem Akkord, „arpeggiando“ | ✓ | ✗ | ✗ (Akkord gebrochen) |
| „non legato“, „leggiero“, „marcato“, „cantabile“, „dolce“ | Textanweisungen | ◐ | ✗ | ✗ |

## 5. Verzierungen

| Element | Schreibweisen / Synonyme | E | D | W |
|---|---|---|---|---|
| Vorschlag (kurz) | kleine Note mit Strich (Acciaccatura) | ✓ | ✗ | ✗ (wird bisher übersprungen) |
| Vorschlag (lang) | kleine Note ohne Strich (Appoggiatura) | ✓ | ✗ | ✗ |
| Triller | „tr“, „tr~~~“ | ✓ | ✗ | ✗ |
| Pralltriller / Mordent | „𝆙“ (Pralltriller, inverted mordent), „𝆚“ (Mordent) | ◐ (Mordent) | ✗ | ✗ |
| Doppelschlag | „∽“ (Turn, Gruppetto) | ✓ | ✗ | ✗ |
| Glissando | Linie/Welle „gliss.“ | ✗ | ✗ | ✗ |
| Tremolo | Striche durch den Hals | ✓ | ✗ | ✗ |

## 6. Ablauf (Wiederholungen und Sprünge)

| Element | Schreibweisen / Synonyme | E | D | W |
|---|---|---|---|---|
| Wiederholungszeichen | 𝄆 … 𝄇, „repeat“ | ✓ | ✓ | ✓ |
| Volta-Klammern | 1. / 2. Klammer, „prima / seconda volta“, „1st / 2nd ending“, „1.–3.“ | ✓ | ✗ | ✗ |
| Da Capo | „D.C.“, „Da Capo“, „vom Anfang“ | *eigen* (Text) | ✗ | ✗ |
| Dal Segno | „D.S.“, „Dal Segno“, Zeichen 𝄋 | *eigen* | ✗ | ✗ |
| Fine | „Fine“, „Ende“ | *eigen* (Text) | ✗ | ✗ |
| Coda | „To Coda ⊕“, „al Coda“, Zeichen 𝄌 | *eigen* | ✗ | ✗ |
| Kombinationen | D.C. al Fine, D.C. al Coda, D.S. al Fine, D.S. al Coda | *eigen* | ✗ | ✗ |
| Mehrtaktpause | Balken mit Zahl | ✓ | ✗ | ◐ |
| Simile | „sim.“, „simile“ (in „Hey Roger!“) | *eigen* (Text) | ✗ | ✗ (Artikulation weiterführen) |

## 7. Tonhöhe und Notensatz

| Element | Schreibweisen / Synonyme | E | D | W |
|---|---|---|---|---|
| Oktavierung | „8va“, „8va---┐“ (eine Oktave höher), „8vb“ / „8va bassa“ (tiefer), „15ma“, „loco“ (Ende) | ✓ (8va/8vb) | ✗ | ✗ |
| Oktavierter Schlüssel | Violinschlüssel mit kleiner 8 | ✗ | ✗ | ✗ |
| Triolen / N-tolen | Klammer mit „3“, „5“, „6“ | ✓ | ◐ | ◐ |
| Punktierung | ein/zwei Punkte | ✓ | ✓ | ✓ |
| Mehrere Stimmen in einer Zeile | Hälse nach oben/unten | ◐ | ◐ | ✓ |
| Akkordsymbole | „C“, „Am“, „F/G“, „Em/B“ über den Noten | ✗ | ✗ | ✗ (könnte als Begleitung/Anzeige dienen) |
| Fingersatz | Ziffern 1–5 an Noten | ✗ (wird jetzt nicht mehr als ♭ fehlgelesen) | ✗ | – (Anzeige wäre beim Üben wertvoll) |

## 8. Eigene Bleistift-Notizen (Wunsch Gideon, 17.11.2026)

Handschriftliche Zusätze (Fingersätze, Wörter wie „Synkope“, Kreise, Datum) sollen im Notenbild an der
exakt passenden Stelle erscheinen – mit Weißabgleich, sodass Papier wirklich weiß ist und nur das
Wesentliche schwarz/grau bleibt.

Machbarkeit: **gut machbar, als eigene Ebene**, ohne die Schrift zu „lesen“:

1. **Weißabgleich / Tonwertkorrektur:** Papierhintergrund schätzen (großflächiger Median bzw.
   morphologisches Schließen), Foto durch den Hintergrund teilen → Papier = reinweiß, Schatten weg;
   danach Tonwertkurve, die Hellgrau auf Weiß schiebt. Das lohnt sich auch für die Foto-Ansicht.
2. **Druck von Notiz trennen:** Alles, was die Erkennung erklärt (Notenlinien, Notenköpfe, Hälse, Balken,
   Schlüssel, Taktstriche – die Segmentierungsebenen von homr), wird abgezogen. Übrig bleibt, was nicht
   zum Druck gehört. Bleistift ist zudem heller als Druckerschwärze, Buntstift (lila Smiley) farbig.
3. **An die richtige Stelle setzen:** Jeder Takt hat im Foto einen Rahmen (Taktstriche + Systemrahmen)
   und im Notenbild einen Rahmen. Notizen werden takt- und systemweise von Foto- in
   Notenbild-Koordinaten abgebildet (waagrecht über die Notenpositionen, senkrecht über die Notenlinien).
4. **Darstellung:** als graue, leicht transparente Bildebene im Notenbild, abschaltbar.

Risiko: Notizen, die Noten berühren oder durchstreichen, lassen sich nicht immer sauber trennen.

## Umsetzungsplan

**Stufe 1 – homr liefert es schon, die App muss es nur übernehmen** – ✓ umgesetzt am 17.11.2026 (Mehrtaktpausen erscheinen ausgeschrieben als einzelne Pausentakte):
Dynamik (Lautstärke + Anzeige), Fermate, Tenuto/Staccatissimo/Marcato, Bindebögen (Legato-Spiel +
Darstellung), Vorschläge, Triolen vollständig, Volta-Klammern, Oktavierung 8va/8vb, Triller/Mordent/
Doppelschlag/Arpeggio, Mehrtaktpausen.

**Stufe 2 – eigene Erkennung per Bildverarbeitung + Texterkennung (ML Kit)** – ✓ umgesetzt am 17.11.2026 (offen: gedruckte „Ped.“/✱-Zeichen, Segno-/Coda-Zeichen als Grafik):
Haltepedal (Ped./✱ und Pedallinien mit Kerben – kommt in Gideons Heften häufig vor), Gabeln
cresc./dim., Tempowörter (→ Tempo-Vorschlag), rit./accel./a tempo, D.C./D.S./Fine/Coda/Segno,
una corda/tre corde, sim., Akkordsymbole.

**Stufe 3 – Bleistift-Notizen** mit Weißabgleich als eigene Ebene (siehe 8), dazu die „saubere“
Foto-Ansicht.

Prüfung je Element mit einem gesetzten Prüfblatt (verovio, wie `tools/make_acc_test.py`) und Gideons
echten Fotos.

## Quellen

- Pedalzeichen: [hellomusictheory.com – Pedals on the piano](https://hellomusictheory.com/learn/pedals-on-the-piano/),
  [musicnotes.com – What do the 3 pedals do](https://www.musicnotes.com/blog/what-do-the-3-pedals-on-a-piano-do),
  [VexFlow PedalMarking](https://unpkg.com/vexflow@3.0.9/releases/docs/pedalmarking.html)
- Sprungzeichen: [Wikipedia – Dal segno](https://Www.Wikipedia.org/wiki/Dal_segno),
  [Wikipedia – Da capo](https://www.wikipedia.org/wiki/Da_capo),
  [Musikschulen Steiermark – Fachausdrücke](https://www.musikschulen.steiermark.at/cms/dokumente/12748894_153409558/6b27e4e9/Musikkunde%202%20Fachausdr%C3%BCcke.pdf)
- homr-Fähigkeiten: `tools/_homr/src/IDEAS_FOR_CONTRIBUTORS.md` (Symboltabelle),
  `homr/transformer/vocabulary.py` (Dynamik, Artikulationen, Oktavierung)
