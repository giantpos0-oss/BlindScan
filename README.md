# BlindScan

A free Android document scanner and live text reader, made by a blind person for blind people.
Everything runs on the phone. The app has **no internet permission at all**, so nothing you scan can ever leave the device.

## What's in this first version

- **Four tabs** across the bottom: Live mode, Scan document, Help, Settings.
- **Live mode**: point the camera at anything and new text is read out as soon as it's steady. Scribe-style DVR controls: rewind a sentence, play/pause, fast forward a sentence. Pressing any of them pauses live reading so new text doesn't interrupt you.
- **Scan document**:
  - The *Scan document now* button.
  - A *Check view* button that reports how many lines it sees, whether text runs off an edge, whether the page edges are visible, and the lighting.
  - Spoken positioning prompts ("Move the phone a little to the left", "Lift the phone up a little", "Bring the phone a little closer to the page", "Move the phone a little away from you", "Hold still"), and a double buzz when ready.
  - A tilt check.
  - The light turns on automatically in dim places.
  - It focuses on the centre before every picture.
  - *Open last document* brings back your most recent scan.
- **Volume buttons take the picture** while the scan camera is showing, with or without TalkBack. Everywhere else they work normally.
- **Reading results**: straight away, through your screen reader or through Android text to speech with DVR controls (your choice in Settings).
- **Document actions** button at the top right:
  - Read aloud
  - Save to device
  - Share
  - Copy text
  - Scan another document
  - Discard

  The same actions are on every paragraph as **accessibility actions**: with TalkBack's reading control set to Actions, swipe up or down and double tap.
- **Upside-down and sideways pages** are turned the right way round automatically.

## New in 0.2.0

- **Faster**:
  - Lines are read in parallel on several processor cores.
  - The XNNPACK fast math library is used where the phone supports it.
  - The document opens the moment you scan, and with Android text to speech the first sentences are spoken while the rest of the page is still being read.
- **Unclear words corrected**: a low-confidence word is checked against similar English words using the recognizer's own letter probabilities. It is replaced only when the evidence is clearly better. Numbers are never changed into other numbers, and confidently read words (names) are left alone. On small unusual fonts this removed about 17% of errors, with no correct words made wrong.
- **Batch scanning**: scan page after page, each read in the background. Then *Read pages* opens them as one document, with a heading per page.
- **Add another page** to a document from Document actions.
- **Press the Scan document tab again** to clear the scan and start fresh.
- **Share scan for testing**: sends the original photos plus a report (text, confidence, timings, phone details).

## The reading engine

PaddleOCR **PP-OCRv6**, a deep-learning OCR model under the Apache 2.0 licence, running in Microsoft's ONNX Runtime. The models ship inside the app (about 37 MB), so it works in airplane mode.

| Used for | Model | Character errors on our hard test set | Speed (test machine) |
|---|---|---|---|
| Document scans | PP-OCRv6 small | **0.15%** | ~1.8 s/page |
| Live mode | PP-OCRv6 tiny | 0.8% | ~0.5 s/frame |
| (older, for comparison) | PP-OCRv5 | 1.6% | ~2.4 s/page |

Rejected after testing: int8-compressed models made errors 4 to 30 times worse and weren't faster. So the app keeps the full-precision models.

The hard test set includes thin, extra-light, italic, black italic, condensed and monospace fonts, as well as:
- blur, dim light, low contrast, noise and uneven lighting;
- tilted, perspective-warped, upside-down and sideways pages;
- tiny print (12–15 pixel text): 0–1.2% errors on readable tiny print.

Our own improvements on top of the stock engine:
- Whole-page orientation by majority vote. Checking line by line flipped single lines wrongly.
- Adaptive resolution: it detects again at higher detail when the print is small.
- A second, sharpened pass on any line it isn't confident about. The better reading wins.

## Building it

**Android Studio:** File > Open, choose this folder, wait for Gradle sync, then Run on a phone with USB or wireless debugging.

**Without Android Studio:** push this folder to a GitHub repository. The included workflow (`.github/workflows/build.yml`) builds the APKs on every push. Open the repository's Actions tab, choose the latest run, and download **BlindScan-apks**.

The release build is signed with the debug key so it installs straight away. Use your own signing key before publishing to the Play Store.

## What has and hasn't been tested yet

Tested:
- The OCR pipeline, in a Python reference that mirrors the app step for step. The accuracy numbers above come from it.
- The Kotlin text-box geometry, compiled and run against real detector output. It matches the reference to 0.00006 pixel.
- The Kotlin sentence splitting (letters, receipts, abbreviations).
- The Kotlin live-text tracker.
- The Kotlin paragraph ordering.

Not tested yet:
- A full Android build and a run on a phone. Those couldn't be done where this was written. Expect a round of fixes after the first build. Build errors are usually quick to fix: paste them in and they'll be sorted.

## Project layout

```
app/src/main/java/com/willwalsh/blindscan/
  MainActivity.kt        tabs, volume-button capture
  Settings.kt            all settings
  ocr/                   OCR engine (ONNX Runtime), geometry, reading order, word correction
  camera/                CameraX preview, capture, frames, light
  guidance/              positioning prompts, page edges, tilt
  speech/                text to speech with DVR controls, sentence splitting, streaming
  ui/                    the four tabs and the document screen
app/src/main/assets/models/   PP-OCRv6 small + tiny, orientation classifier
tools/ocr-testbench/     Python reference pipeline + hard test-page generator
```

## Licences

App code: yours. English word list: SymSpell frequency dictionary (MIT), see `WORDLIST-LICENSE.txt`. OCR models: PaddleOCR (Apache 2.0), ONNX conversions from the OnnxOCR project (Apache 2.0); see `MODELS-LICENSE.txt`. ONNX Runtime: MIT.
