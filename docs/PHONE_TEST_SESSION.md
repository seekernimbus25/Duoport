# Phone test session (Windows laptop)

A guide for a Claude Code session running **on your Windows laptop** with your Android phone
plugged in. It covers the tests the emulator can't do: the ones that need real camera
hardware, and the ones that need you. The full plan, with expected results, is
[TEST_PLAN.md](TEST_PLAN.md).

## 1. One-time setup on the laptop (about 10 minutes)

In **PowerShell**:

```powershell
winget install Google.PlatformTools     # adb
winget install Gyan.FFmpeg              # ffprobe, to check saved videos
winget install Git.Git
winget install Python.Python.3.12
```

Close and reopen PowerShell, then check: `adb version` and `ffprobe -version`.

Install Claude: the **Claude desktop app** for Windows (Claude Code is inside it), or the
Claude Code command-line tool. Then get the project:

```powershell
cd $HOME
git clone https://github.com/seekernimbus25/Duoport.git
cd Duoport
git checkout claude/dual-format-video-capture-2nrjl5
```

## 2. The phone

1. Settings → About phone → tap **Build number** 7 times (developer options on).
2. Settings → System → Developer options → **USB debugging** on, **Stay awake** on.
3. Plug it into the laptop; on the phone tap **Allow** (tick "Always allow from this computer").
4. In PowerShell: `adb devices` must list the phone as `device`.
5. Install the newest build: download `DualFrame.apk` from the repo's latest release, then
   `adb install -r DualFrame.apk`.

(Wireless works too on Android 11+: Developer options → Wireless debugging → Pair, then
`adb pair` / `adb connect`.)

## 3. Start Claude there

Open a Claude Code session in the `Duoport` folder and say:

> Follow docs/PHONE_TEST_SESSION.md and run the phone session with me.

## 4. For Claude: how to run it

Windows notes: use PowerShell. `adb exec-out screencap -p > shot.png` corrupts PNGs in
PowerShell 5, so use `adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png`. Videos
are in `/sdcard/Movies/DualFrame`, photos in `/sdcard/Pictures/DualFrame`. Check files with
`ffprobe -v error -show_streams -show_format -of json <file>`. Take a screenshot after each
step and look at it before deciding pass/fail; log crashes with
`adb logcat -b crash -d`. Tell the person exactly what to do with the phone, one thing at a
time, and wait for them.

Record every result in `test-results/phone-<build>.md` (ID, ✅/❌/⚠️, notes, screenshot names)
and commit it. For a failure, fix the code, push to the branch
`claude/dual-format-video-capture-2nrjl5` (CI builds it; once green, fast-forward `main` and
CI publishes the release), install the new build and re-run that test.

### A. Needs real camera hardware (Claude drives, you just hold or prop the phone)

| ID | What |
|---|---|
| CAM-09 | Lens lock: Pro → Lens lists the phone's lenses; each one is used, no switching while zooming |
| PROF-04, PROF-05 | HLG 10-bit is offered (if the phone supports it); files are `hevc`, 10-bit, `arib-std-b67`, `bt2020` |
| PROF-06 | HDR cuts: 10-bit when "HDR cuts" is on; otherwise standard range plus the 10-bit original |
| FPS-04 | 120 / 240 fps slow motion where the phone offers it (Settings → Speed) |
| PRO-08 | Camera bitrate 10…200 Mbps: the original's bitrate is close to the setting |
| CAM-04 | Torch on and off |
| DUAL-01, DUAL-02, ANA-02 | Front + back: inset in each corner; anamorphic ignored |
| ROB-06 | 15 min of 4K: note heat; no crash |
| REC-06 | 10 min 4K recording finishes and exports |
| PLAY-02 | Runs on a 16 KB-page phone (if yours is one) |
| GR-04 | Grade an HDR (HLG) clip and save it (the emulator can't decode 10-bit video) |
| REC-12 | Pause / resume on the real camera: the saved video leaves the paused time out |
| ROB-02 | Very low storage: a clear message, no crash |

### B. Needs you

| ID | What you do |
|---|---|
| CAM-02 | Pinch to zoom; the 1× label follows |
| CAM-03 | Tap to focus near, then far |
| CAM-10 | Walk with Stabilisation Off / Standard / Enhanced |
| PROF-01 | Same scene in Standard and DF-Log: does Log look flat? |
| FPS-03 | Move something in front of the camera at 60 fps slow motion |
| PRO-01 – PRO-04 | Shutter, ISO, white balance, manual focus on a real scene |
| MON-01, MON-02 | Zebras on something bright; peaking on something in focus |
| CIN-01 | Stand in frame with cinematic mode on |
| FOL-01, FOL-02 | Walk side to side with Follow me; turn the phone while recording |
| TP-04, TP-05 | Eye-contact teleprompter; read aloud with voice follow |
| CAP-01 – CAP-03 | Captions: speech pack download, then talk 20 s in English and in Hinglish |
| ED-04 | Remove silences on a real talking clip: are words clipped? |
| DES-09 | Design editor person cutout on a video of you |
| GR-06 | Colour wheels and scopes feel right while grading |
| TP-07 | Hold the phone sideways (both ways): the script turns upright and sits by the lens; drag moves it |
| STU-01 | The Edit screen on a real clip: split, speed, music, voiceover, captions, grade, save |

### C. Worth repeating on the real phone

The emulator passed every automated test it can run (last full pass: 1 November); the list of
what it can't do is in A and B above.

Everything the emulator passed was on a fake camera. Spot-check on the phone: FMT-01
(one shape → one video), PROF-02 (Log → LUT → Rec.709, preview keeps moving), REC-01,
SL-05 – SL-13 (shot list end to end), DES-02 (a template saved), PH-01 (photos).

### D. New since session 47 (test on the first build after 3 November)

| ID | What you do | What to look for |
|---|---|---|
| CIN-01 | Cinematic Strong, stand in frame, record 10 s; also take one photo | Hair and glasses edges follow you; two people both stay sharp. Now on the editor's person model (ML Kit is gone, the app is ~21 MB smaller) |
| FOL-02 | Follow me on, start upright, turn the phone sideways after ~10 s, hold, turn back | Both videos stay upright the whole time; the turn eases over ~0.4 s; the 9:16 cut zooms in while sideways, the 16:9 one widens. Try front and back camera |
| PROF-01 | Same scene in Standard and DF-Log | DF-Log flat and grey, shadows lifted (not darker than Standard); the Rec.709 look brings normal contrast back |
| CAM-10 | Stabilisation Standard, then Enhanced: read the info line under the shutter | It says "Steady: standard" / "Steady: enhanced". "Steady: off (camera skipped it)" means the phone ignored the setting. Then the same straight outdoor walk in Off and Enhanced |
| DES-09 | Editor → cutout → Fine edges, Save, on the antlers clip | Antler tips and hair strands kept, no ceiling beam above the head; time the save (expect longer than Quick) |
| DES-10 | Same with Quick | Faster save, as before |
| EYE-01 | Settings → Eye contact on. Record 30 s reading the teleprompter: during "Look into the lens" (first 1.5 s) look at the lens | In the saved videos you seem to look at the viewer while reading; no wobbling eyes, lids or glasses |
| EYE-02 | Same, but blink, look far to the side once, turn your head | Blinks and the look away appear natural (the correction fades out) |
| PLAY-01 | (no tester) CI "Check 16 KB page alignment" step | Passes on every build |
| TAB-01 | (if a tablet or foldable is around) open the app | Stays portrait, letterboxed |
