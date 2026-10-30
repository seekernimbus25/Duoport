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

**Two screens.** *Auto* is the phone's own camera behaviour with a minimal UI: frame, zoom,
record. *Pro* adds a glass control strip: profile, LUT, frame rate, shutter, ISO, white balance
(+ lock), manual focus and exposure. It always shows what the camera is really doing; tap
any value to take control, tap **A** to hand everything back.

**Many formats from one take.** Settings → *Videos to make*: 9:16, 16:9, 4:5, 1:1, 3:4,
4:3, 21:9, each at its own resolution (720p to 4K). Each shows as a coloured frame; tap
its pill and drag to move it.

**Recording profiles (Pro).**
- *Standard*: your phone's own colour processing.
- *DF-Log*: a log-style tone curve sent to the camera, keeping highlight and shadow
  detail for grading. Needs a phone that allows custom tone curves (Pixels do).
- *HLG HDR*: 10-bit HEVC HDR. The original is kept as HDR. The cut videos are
  tone-mapped to standard range so LUTs and the inset compositor work.

Log and HDR originals are always kept, since they are the "negative".

**LUTs (Pro).** Built-in: Vivid, Teal & Orange, Film, Golden Hour, Moody, Bleach Bypass,
Noir, and Rec.709 for DF-Log. Import any **.cube** 3D LUT (Resolve/Adobe format, up to 65
points); long-press an imported LUT to delete it. LUTs preview live on the GPU and are baked
into every video. The uncut original stays clean, so you can always re-grade it. With
DF-Log, every LUT is applied after a Log→Rec.709 conversion, so LUTs made for normal
footage just work.

**Library.** The thumbnail next to the shutter opens an in-app library of everything in
Movies › DualFrame, with a full-screen player, share, open in another app, and delete.

**Front + Back.** Both cameras at once on phones that support it. Pick a corner and the
second camera appears in that same corner of every video.

**Teleprompter.** Floating glass script that starts with the recording. **◎** eye-contact
mode is a narrow column right under the front camera so your eyes barely move.

**Export.** Quality (Standard / High / Max) and codec (H.264 / HEVC). If making the videos
ever fails, the original recording is saved instead.

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
  Luts.kt                built-in looks, DF-Log curve, .cube import
  LutPreviewProcessor.kt live LUT preview on the GPU (CameraX effect)
  Glass.kt, SettingsSheet.kt  glass UI building blocks, settings sheet
  GalleryActivity.kt, PlayerActivity.kt  library and player
  FrameOverlayView.kt    format frames, inset markers, drag / tap-to-focus / pinch
  CropMath.kt            frame + inset geometry shared by preview and export
  DualExporter.kt        recordings -> one video per format -> gallery
  InsetCutoutEffect.kt   GL effect that makes room for the second camera
  TeleprompterView.kt    floating teleprompter
```
