> **Umgezogen / Moved:** Der Quellcode wird jetzt unter <https://github.com/NiceDevelopments/notescan> gepflegt; dieses Repository wird nicht mehr aktualisiert. The source code now lives at https://github.com/NiceDevelopments/notescan; this repository is no longer updated.

# NoteScan

Android app that reads piano sheet music from a photo and plays it back with a grand piano sound.
Music recognition runs entirely on the phone – no server, no upload, no account.

- Photograph a page (or several pages / a PDF), the notes are recognized on the device
- Engraved score in the layout of the original sheet, playback with a moving cursor and keyboard
- Correct misread notes, keep pencil markings from the paper copy
- Practice tools: mute one hand, loop bars, change the tempo, metronome
- 21 languages

Coming to Google Play (free, optional one-time purchase “NoteScan Pro”).

## Building

Requirements: Android Studio (JDK 17+), Android SDK 36, Python 3.10 for the Chaquopy build
(set `CHAQUOPY_PYTHON` to its path if `python3.10` is not on the `PATH`).

```bash
./gradlew assembleDebug testDebugUnitTest
```

The recognition models (`app/src/main/assets/models`, ONNX) come from
[homr](https://github.com/liebharc/homr); `decoderstep_*.onnx` is derived from homr's decoder with
`tools/make_decoder_step.py`.

## Structure

| Path | Content |
|---|---|
| `app/src/main/java/.../model` | Score model, MusicXML reader, playback order (repeats, ties, dynamics), corrections |
| `app/src/main/java/.../engrave` | Own music engraving with the Bravura font, layout following the photo |
| `app/src/main/java/.../audio` | Sampler and sequencer (Salamander Grand Piano samples) |
| `app/src/main/java/.../recognize` | homr via Chaquopy (Python 3.10) and ONNX Runtime, text recognition with ML Kit |
| `app/src/main/python` | homr (unchanged) and `vomblatt_omr.py`: entry point and post-processing (pedal, hairpins, fingerings, pencil notes …) |
| `store/` | Play Store graphics (HTML rendered with headless Chrome) and the public-domain sample sheets used for them |

## License

NoteScan is free software under the **GNU Affero General Public License v3.0** (see `LICENSE`),
because it includes [homr](https://github.com/liebharc/homr) (AGPL-3.0).

Third-party components:

- homr – Christian Liebhardt and contributors, AGPL-3.0; segmentation based on oemer (MIT)
- Salamander Grand Piano V3 – Alexander Holm, CC BY 3.0
- Bravura – Steinberg Media Technologies, SIL Open Font License 1.1
- Instrument Sans – Rodrigo Fuenzalida and Jordan Egstad, SIL Open Font License 1.1
- ONNX Runtime (MIT), Chaquopy, Google ML Kit text recognition and Google Play Billing under their own terms

The license texts are in `app/src/main/assets/licenses`. The name “NoteScan” and the logo are not
covered by the AGPL license.
