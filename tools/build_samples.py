"""Lädt ausgewählte Samples des Salamander Grand Piano V3 (Alexander Holm, CC-BY 3.0)
und bereitet sie für die App auf: 44,1 kHz Stereo, gekürzt je nach Tonlage, sanft ausgeblendet,
als OGG Vorbis in app/src/main/assets/piano/.

Aufruf:  python -I tools/build_samples.py
Benötigt ffmpeg im PATH. Die FLAC-Rohdaten landen in tools/_download (nicht ins Git).
"""
import pathlib
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
DL = ROOT / "tools" / "_download"
OUT = ROOT / "app" / "src" / "main" / "assets" / "piano"
BASE = "https://raw.githubusercontent.com/sfzinstruments/SalamanderGrandPiano/master/Samples/"

# Salamander ist im Kleinterz-Abstand ab dem tiefsten A gesampelt.
NAMES = {9: "A", 0: "C", 3: "D#", 6: "F#"}
# Drei von 16 Anschlagstufen: leise, mittel, kräftig. Die App mischt dazwischen.
LAYERS = {"p": 5, "m": 10, "f": 15}


def roots():
    for midi in range(21, 109):
        pc = midi % 12
        if pc in NAMES:
            octave = midi // 12 - 1
            yield midi, f"{NAMES[pc]}{octave}"


def length_for(midi: int) -> float:
    # Tiefe Saiten klingen lange nach, hohe kurz. Über die Länge hinaus blendet die App aus.
    if midi < 40:
        return 10.0
    if midi < 55:
        return 8.0
    if midi < 67:
        return 6.5
    if midi < 79:
        return 5.0
    if midi < 91:
        return 3.5
    return 2.5


def main():
    DL.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    for midi, name in roots():
        for tag, v in LAYERS.items():
            fname = f"{name}v{v}.flac"
            src = DL / fname
            if not src.exists():
                url = BASE + urllib.parse.quote(fname)
                req = urllib.request.Request(url, headers={"User-Agent": "noten-app-build"})
                with urllib.request.urlopen(req, timeout=120) as r:
                    src.write_bytes(r.read())
            dst = OUT / f"{midi}_{tag}.ogg"
            if dst.exists():
                continue
            dur = length_for(midi)
            fade = min(1.2, dur * 0.3)
            subprocess.run(
                [
                    "ffmpeg", "-loglevel", "error", "-y", "-i", str(src),
                    "-t", f"{dur}",
                    "-af", f"afade=t=out:st={dur - fade:.2f}:d={fade:.2f}",
                    "-ar", "44100", "-ac", "2", "-c:a", "libvorbis", "-q:a", "5",
                    str(dst),
                ],
                check=True,
            )
            print(dst.name, flush=True)
    print("fertig", flush=True)


if __name__ == "__main__":
    sys.exit(main())
