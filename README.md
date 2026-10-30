# DualFrame

An Android camera app that records **once** and gives you **every video shape you need**:
9:16 for Reels/Shorts, 16:9 for YouTube, 4:5, 1:1, 3:4, 4:3 and 21:9, each at its own
resolution (720p to 4K). They are saved in your gallery under **Movies › DualFrame**.

## Install on your phone

1. On your phone, open this repo on GitHub → **Releases** → the newest one → download **`DualFrame.apk`**.
2. Open the downloaded file and allow installing from that app when Android asks.
3. Open DualFrame and allow camera + microphone.

Every push to `main` builds a new release automatically. New APKs install over the old one.
Requires Android 10 or newer.

## Features

**Many formats from one take.** In **Settings → Videos to make**, tick any shapes and pick a
resolution for each. Every chosen shape is drawn as a coloured frame on the preview. Tap a
format chip at the top and drag on the picture to move that frame. Double-tap re-centers.

**Pro camera controls** (top strip, tap any value to change it):
FPS (24/25/30/60) · shutter speed · ISO · white balance presets + lock · manual focus ·
exposure compensation · zoom stops (0.5×, 1×, 2×…) with 35mm-equivalent focal length.
The strip always shows what the camera is actually using. **AUTO** puts everything back
to how your phone's camera behaves.

**Looks.** *Natural* is your phone's own processing, untouched. *Vivid* adds colour and
contrast (previewed live). *Flat* is a log-style curve for colour grading, on phones that allow it.

**Camera recording.** Full sensor 4:3 (most room to cut several shapes) or Widescreen 16:9
(sharpest for a single 9:16/16:9), at 4K / 1080p / 720p, optional stabilization.

**Front + Back.** Both cameras record at the same time on phones that support it. Pick a
corner (↖ ↗ ↙ ↘): the second camera appears in that same corner of every video. The two
cameras are recorded separately and combined per format, so the inset is never cropped off.
On phones that only offer a combined stream, a fallback bakes the inset in and keeps the frames centered.

**Teleprompter.** A floating, see-through script you can drag anywhere. It starts scrolling
when you press record. Speed and text size are adjustable. **◎ eye-contact mode** narrows
it into a short column right under the front camera so your eyes barely move while reading.

**Safety net.** If making the videos fails, the original recording is saved instead.
Optionally keep the uncut original every time.

## How it works

The camera records one master clip (plus a second clip in Front + Back mode). When you stop,
Media3 Transformer cuts out each chosen frame, scales it to the chosen resolution, applies
the look, lays in the second camera where needed, and encodes H.264 on the phone's
hardware encoder.

## Build it yourself

GitHub Actions does this for you. Locally: Android Studio, or `./gradlew assembleRelease`
with JDK 17 and the Android SDK. Output: `app/build/outputs/apk/release/app-release.apk`.

`keystore/sideload.jks` is a throwaway signing key committed on purpose so every build
installs over the last. **Replace it with a private key kept out of git before publishing
to the Play Store.**

## Code map

```
app/src/main/java/com/dualframe/camera/
  MainActivity.kt        camera setup (single / front+back), pro controls UI, settings, results
  Settings.kt            formats, resolutions, camera options, persisted settings
  ProControls.kt         ISO / shutter / WB / focus via Camera2 interop, live readouts
  Looks.kt               Natural / Vivid / Flat
  FrameOverlayView.kt    format frames, inset markers, drag / tap-to-focus / pinch
  CropMath.kt            frame + inset geometry shared by preview and export
  DualExporter.kt        recordings -> one video per format -> gallery
  InsetCutoutEffect.kt   GL effect that makes room for the second camera
  TeleprompterView.kt    floating teleprompter
```
