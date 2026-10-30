# DualFrame

An Android camera app that records **once** and gives you **two videos**:

- **9:16** (1080×1920): Instagram Reels, YouTube Shorts, Stories
- **16:9** (1920×1080): regular YouTube videos

Both land in your gallery under **Movies › DualFrame**.

## Install on your phone

1. On your phone, open this repo on GitHub → **Releases** → the newest one → download **`DualFrame.apk`**.
2. Open the downloaded file. Android will ask you to allow installing from your browser/Files app the first time. Allow it.
3. Open DualFrame and allow camera + microphone.

Every push to `main` builds a new release automatically (see `.github/workflows/build.yml`).
New APKs install over the old one. Requires Android 10 or newer.

## How it works

The camera records one taller-than-needed master video. When you stop, the app cuts it
into the two formats on the phone (Media3 Transformer, hardware encoder), saves both,
and deletes the master. If cutting ever fails, the master is saved instead so the take
isn't lost.

On screen you see the whole recorded picture with both frames drawn on it:

- **yellow** = what ends up in the 9:16 video, **white** = what ends up in the 16:9 video
- drag **sideways** to move the 9:16 frame, **up/down** to move the 16:9 frame
- double-tap re-centers, tap focuses, pinch zooms

### Capture modes

| Mode | Records | 9:16 result | 16:9 result |
|---|---|---|---|
| **Wide 4:3** (default) | 4:3, up to 4K-class if the phone allows | full height | full sensor width: a properly wide shot |
| **Sharp 16:9** | 16:9 up to 4K | the whole frame, sharpest | a tighter band from the middle |
| **Front + Back** | both cameras at once (4:3, ~1080p) | main camera + small inset | same, inset still visible |

**Front + Back** uses Android's concurrent-camera support (CameraX `ConcurrentCamera`),
so it only appears to work on phones that support it (many recent Pixels and Samsungs). On
other phones the button says so. The ⟳ button swaps which camera is the big one. Frames
stay centered in this mode so the inset is inside both cuts.

## Build it yourself

GitHub Actions does this for you. Locally: Android Studio, or `./gradlew assembleRelease`
with JDK 17 and the Android SDK installed. The APK is at
`app/build/outputs/apk/release/app-release.apk`.

`keystore/sideload.jks` is a throwaway signing key committed on purpose, so every build
is signed the same way and updates install cleanly. **Replace it with a private key kept
out of git before publishing to the Play Store.**

## Code map

```
app/src/main/java/com/dualframe/camera/
  MainActivity.kt      camera setup (single + front/back), recording, results screen
  FrameOverlayView.kt  the two frame guides + drag / tap-to-focus / pinch gestures
  CropMath.kt          frame geometry shared by the overlay and the export, inset placement
  DualExporter.kt      master video -> 9:16 + 16:9 files -> gallery
  SourceMode.kt        Wide 4:3 vs Sharp 16:9
```
