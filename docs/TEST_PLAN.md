# DualFrame on-device test plan

Every feature, what to do, and what "working" means. Written so Claude can run it on your
phone through a computer session (adb), and you can follow it by hand too.

**Legend**
- 🤖 Claude can do it alone over adb: taps, screenshots, screen recordings, logcat, and
  checking the saved files with `ffprobe`.
- 👤 Needs you: pointing the camera at something, talking, moving, or judging how it looks.
- **P0** must work before any release. **P1** important. **P2** nice to check.

Each test has an ID (e.g. `REC-03`) so results can be logged in the table at the end.

---

## 0. Setup (once)

### On the computer
- Android platform-tools (`adb`) and `ffmpeg` (for `ffprobe`) installed.
- A Claude Code session running on the computer (Claude desktop app, or
  `claude remote-control` in this repo's folder).

### On the phone
- Developer options → **USB debugging** on; connected by cable; "Allow" the computer.
- Developer options → **Stay awake** on (screen stays on while charging).
- Plenty of free space (at least 5 GB); battery above 50%.
- Phone language: English for the first pass.

### Commands Claude uses
```
adb devices                                         # phone connected?
adb install -r DualFrame.apk                        # install / update the build under test
adb shell getprop ro.product.model                  # record which phone
adb shell getprop ro.build.version.release          # Android version
adb exec-out screencap -p > shot.png                # screenshot
adb shell screenrecord --time-limit 30 /sdcard/r.mp4 && adb pull /sdcard/r.mp4
adb shell input tap X Y / input swipe … / input text …
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml   # find buttons by text
adb logcat -c ; adb logcat -v time | grep -iE "dualframe|AndroidRuntime|FATAL|Transformer|Camera" 
adb shell ls -l /sdcard/Movies/DualFrame /sdcard/Pictures/DualFrame /sdcard/Download
adb pull /sdcard/Movies/DualFrame ./out
ffprobe -v error -show_streams -show_format -of json out/<file>.mp4
adb shell dumpsys meminfo com.dualframe.camera     # memory
adb shell am force-stop com.dualframe.camera       # kill the app
```

### Test media to have on the phone (Claude can push them with `adb push`)
- A 20–40 s talking-to-camera clip in English, and one in Hinglish.
- A clip with long pauses (for silence removal).
- An HDR (HLG) clip recorded by the phone's own camera.
- A few photos: a person (for cutouts), a landscape, a transparent PNG logo.
- An animated GIF.
- A `.cube` LUT file (e.g. one exported from Resolve), plus a broken text file renamed `.cube`.
- A `.ttf` font file.

### What to check in every saved video (ffprobe)
| Field | Expected |
|---|---|
| `width` × `height` | the shape and resolution chosen (e.g. 9:16 1080p → 1080×1920) |
| `codec_name` | `h264` or `hevc`, matching Settings → Codec |
| `r_frame_rate` | the chosen fps (30/1, 24/1, 30000/1001…) |
| `bit_rate` | roughly the export quality (Standard / High / Max); recording bitrate for originals |
| audio stream | present (except slow motion) |
| HDR clips | `color_transfer=arib-std-b67`, `color_primaries=bt2020`, `pix_fmt=yuv420p10le` |
| playback | plays in the app's player and in Google Photos without errors |

### General checks (on every test)
- No crash, no "App isn't responding" dialog (logcat: no `FATAL EXCEPTION`).
- The preview never freezes (the picture keeps moving).
- Text fits on a small screen; buttons are tappable; nothing hides behind the notch or the
  navigation bar.

---

## 1. Install, first launch, permissions
| ID | Pri | Test | Expected |
|---|---|---|---|
| INS-01 🤖 | P0 | Install the APK fresh (uninstall first) | Installs; icon and the name DualFrame show |
| INS-02 🤖 | P0 | Install a newer build over the old one | Installs without uninstalling; settings, LUTs, captions styles, templates and shot lists are kept |
| INS-03 🤖 | P0 | First launch | Asks for camera and microphone only (no photos/media permission) |
| INS-04 🤖 | P0 | Deny camera | Permission panel with a Grant button; no crash; Grant opens the prompt again |
| INS-05 🤖 | P1 | Deny microphone, record | Records without sound or explains why; no crash |
| INS-06 🤖 | P1 | Rotate the phone, lock and unlock the screen, go home and come back | Camera comes back each time; preview isn't frozen or black |

## 2. Auto and Pro screens
| ID | Pri | Test | Expected |
|---|---|---|---|
| MODE-01 🤖 | P0 | Switch between AUTO and PRO | Pro shows the control strip (profile, LUT, fps, shutter, ISO, WB, focus, exposure); Auto hides it |
| MODE-02 🤖 | P1 | Change a Pro value, go to Auto and back | The Pro value is remembered; Auto still behaves like the phone's own camera |
| MODE-03 🤖 | P0 | VIDEO · PHOTO · SHOTS fit on the screen | All five mode labels are visible on a 360 dp wide phone, none cut off |

## 3. Camera basics
| ID | Pri | Test | Expected |
|---|---|---|---|
| CAM-01 🤖 | P0 | Preview on the back and front cameras (flip button) | Live, smooth, correct orientation; the front camera is mirrored only if mirroring is on |
| CAM-02 🤖👤 | P0 | Zoom: tap 0.5× and 1×, then pinch | Only two zoom buttons; the 1× label shows the real zoom while pinching (e.g. 2.4×); 0.5× switches to the ultra-wide if the phone has one |
| CAM-03 👤 | P1 | Tap to focus on something close, then far | Focus moves to the tapped spot |
| CAM-04 🤖 | P1 | Torch on and off | Light on and off; the icon changes |
| CAM-05 🤖 | P1 | Self-timer off → 3 → 10 → off | Countdown shows the seconds and records after it; tapping during the countdown cancels it |
| CAM-06 🤖 | P1 | Volume key records (if on in settings) | A volume press starts and stops recording |
| CAM-07 🤖 | P2 | Grid, level, centre mark, safe-area guides | Each draws over the preview when on and is not baked into the videos |
| CAM-08 🤖 | P1 | Night button | In Auto it turns on night mode where supported; in Pro it explains that it needs Auto |
| CAM-09 🤖 | P1 | Lens lock (Pro → LENS) | Lists the phone's lenses; picking one uses that lens and it doesn't switch while zooming |
| CAM-10 🤖 | P1 | Stabilisation Off / Standard / Enhanced | Picture gets tighter with stronger stabilisation; recordings are smoother (👤 walk with it) |
| CAM-11 🤖 | P1 | Settings → This phone | Lists cameras, lenses, max fps, HLG and Log support; only supported options are offered elsewhere |

## 4. Shapes (formats) and frames
| ID | Pri | Test | Expected |
|---|---|---|---|
| FMT-01 🤖 | P0 | Pick only 9:16, record 5 s | **Exactly one** video saved (plus the original only if "Keep original" is on) — regression check |
| FMT-02 🤖 | P0 | Pick 9:16 + 16:9 + 1:1, record | Three videos with the right sizes (ffprobe) |
| FMT-03 🤖 | P1 | Different resolution per shape (e.g. 9:16 4K, 16:9 720p) | Each file has its own resolution |
| FMT-04 🤖 | P1 | Drag a shape's frame to one side, record | That video is cut from where the frame was |
| FMT-05 🤖 | P1 | Camera frame "Open gate 4:3" | Records the full 4:3 sensor; the shapes are cut from it |
| FMT-06 🤖 | P2 | Every shape: 9:16, 16:9, 4:5, 1:1, 3:4, 4:3, 21:9 | Each file has the right aspect ratio |

## 5. Recording and saving
| ID | Pri | Test | Expected |
|---|---|---|---|
| REC-01 🤖 | P0 | Record 10 s and stop | Timer counts; the "Making 9:16…" progress shows; a results panel lists each video with a thumbnail, play and share |
| REC-02 🤖 | P0 | Files land in Movies/DualFrame | Names like `Name_T007_20261030_141502_9x16_1080p.mp4`; take numbers go up each time |
| REC-03 🤖 | P0 | Play each result in the app | Plays with sound, correct shape |
| REC-04 🤖 | P1 | Share a result | The Android share sheet opens with the video |
| REC-05 🤖 | P1 | "Keep original" on | An extra `_original.mp4` is saved |
| REC-06 🤖 | P0 | Long recording (10 min, 4K) | Doesn't stop by itself; export finishes; no out-of-memory; the phone may get warm |
| REC-07 🤖 | P1 | Go to the home screen during export, come back | Export carried on and finished |
| REC-08 🤖 | P1 | Force-stop during export | Next launch is fine; ideally the original was saved |
| REC-09 🤖 | P1 | Audio gain (if set) | The audio level changes accordingly; no clipping at moderate gain |
| REC-10 🤖 | P2 | Timecode | The original has a timecode track starting at the recording's time of day |
| REC-11 🤖 | P1 | Stall watchdog | If the preview stops getting frames, it recovers by itself within ~2 s (watch logcat during switching modes) |
| REC-12 🤖 | P0 | Pause / resume | While recording, the pause button sits where flip was; the timer blinks "Paused"; resume carries on in the same file, which leaves the paused time out |

## 6. Recording profiles (Pro)
| ID | Pri | Test | Expected |
|---|---|---|---|
| PROF-01 🤖👤 | P0 | Standard vs DF-Log, same scene | DF-Log looks **flat / low contrast** in the recorded file (a view LUT can make the preview look normal); Standard looks normal |
| PROF-02 🤖 | P0 | DF-Log → pick a LUT, then back to Rec.709 / none | **Preview keeps moving** (old freeze bug) |
| PROF-03 🤖 | P1 | Save the DF-Log → Rec.709 LUT | `DF-Log_to_Rec709.cube` appears in Downloads; a valid text .cube |
| PROF-04 🤖 | P0 | HLG 10-bit can be picked (on phones that support it) | Selectable; recording works (old build-8 bug) |
| PROF-05 🤖 | P0 | HLG files | Original: `hevc`, 10-bit, `arib-std-b67`, `bt2020`. Cut videos are 10-bit HDR when "HDR cuts" is on, otherwise standard range |
| PROF-06 🤖 | P1 | HDR fallback | On a phone that can't encode edited HDR: cuts come out in standard range, a note says so, and the 10-bit original is saved |
| PROF-07 🤖 | P1 | Log/HDR originals | Saved only when "Keep the original" is on (one shape in, one video out) |

## 7. LUTs and looks
| ID | Pri | Test | Expected |
|---|---|---|---|
| LUT-01 🤖 | P0 | Each built-in look (Vivid, Teal & Orange, Film, Golden Hour, Moody, Bleach Bypass, Noir) | Preview changes live and the recorded videos match the preview |
| LUT-02 🤖 | P1 | Import a `.cube` file | Appears in the list and works; long-press deletes it |
| LUT-03 🤖 | P1 | Import a broken `.cube` | Clear error message; no crash |
| LUT-04 🤖 | P1 | "Bake LUT" off | The look shows in the preview only; files are clean |

## 8. Frame rates and slow motion
| ID | Pri | Test | Expected |
|---|---|---|---|
| FPS-01 🤖 | P0 | 24, 25, 30, 60 fps | `r_frame_rate` matches |
| FPS-02 🤖 | P1 | NTSC (23.976, 29.97, 59.94) | `24000/1001`, `30000/1001`, `60000/1001` |
| FPS-03 🤖👤 | P0 | Slow motion 60 fps (2×) | The video plays at 30 fps, twice as long as recorded, without sound |
| FPS-04 🤖👤 | P0 | 120 fps (4×) and 240 fps (8×), where offered | Offered only if the phone supports it; when offered, it records and plays 4× / 8× slower; when not offered, the reason is shown |
| FPS-05 🤖 | P1 | Slow motion + several shapes | Every shape is slowed down the same way |

## 9. Pro controls
| ID | Pri | Test | Expected |
|---|---|---|---|
| PRO-01 🤖👤 | P0 | Shutter speed (and shutter angle display) | Changing it changes brightness/motion blur; the readout shows the real value |
| PRO-02 🤖👤 | P0 | ISO | Changes brightness and noise; the readout matches |
| PRO-03 🤖👤 | P1 | White balance Kelvin + tint, and lock | The colour changes as expected (low K = cooler, i.e. bluer, rendering); lock holds it |
| PRO-04 🤖👤 | P1 | Manual focus slider | Focus moves near ↔ far |
| PRO-05 🤖 | P1 | Exposure compensation | Brighter / darker |
| PRO-06 🤖 | P1 | Shutter or ISO priority (set one, the other auto) | The other value follows the light |
| PRO-07 🤖 | P1 | **A** (hand everything back) | All values go back to auto |
| PRO-08 🤖 | P1 | Record bitrate: Auto, 10…200 Mbps | The original's bitrate is close to the number picked (where the phone allows) |
| PRO-09 🤖 | P1 | Codec H.264 / H.265 | `codec_name` matches for the cut videos |
| PRO-10 🤖 | P1 | Export quality Standard / High / Max (shown in Mbps) | Bitrates go up accordingly |

## 10. Monitoring tools (Pro)
| ID | Pri | Test | Expected |
|---|---|---|---|
| MON-01 🤖👤 | P1 | Zebras at 70–100% | Stripes over bright areas only above the level |
| MON-02 🤖👤 | P1 | Focus peaking low/high, each colour | In-focus edges light up in the chosen colour |
| MON-03 🤖 | P1 | False colour | Exposure shown as a colour map |
| MON-04 🤖 | P1 | Histogram → waveform → vectorscope | Each draws and updates live |
| MON-05 🤖 | P2 | Exposure meter, audio meter | Exposure meter moves with the light; audio meter moves when you talk |
| MON-06 🤖 | P0 | None of these are in the recorded files | Files are clean |

## 11. Cinematic, anamorphic, follow-me
| ID | Pri | Test | Expected |
|---|---|---|---|
| CIN-01 🤖👤 | P1 | Cinematic mode on, person in frame | Background is blurred live; the person stays sharp; the files are blurred too |
| CIN-02 🤖 | P1 | Cinematic on a phone that can't | Videos are made without the blur and a note says so |
| CIN-03 🤖 | P2 | Cinematic on a photo | The photo's background is blurred |
| ANA-01 🤖 | P1 | Anamorphic 1.33× / 1.55× / 2× | Preview and videos are de-squeezed to the wider shape (a circle looks round with a real anamorphic lens) |
| ANA-02 🤖 | P2 | Anamorphic with front + back | Squeeze is ignored (second camera has no anamorphic lens) |
| FOL-01 🤖👤 | P1 | "◎ Follow me" on, walk side to side | The 9:16 cut pans smoothly to keep your face in frame, with no jitter |
| FOL-02 🤖👤 | P2 | Follow rotation: turn the phone while recording | The video stays upright |

## 12. Front + back (two cameras)
| ID | Pri | Test | Expected |
|---|---|---|---|
| DUAL-01 🤖 | P0 | Turn on (where supported) | Second camera appears in the chosen corner; on unsupported phones a message explains why |
| DUAL-02 🤖 | P1 | Each corner | The inset is in the same corner of every video |
| DUAL-03 🤖 | P0 | Dual off → one shape | Only one recording/video (the old "two recordings" bug) |

## 13. Teleprompter
| ID | Pri | Test | Expected |
|---|---|---|---|
| TP-01 🤖 | P1 | Open, edit the script, save | Text shows; it is remembered after restarting the app |
| TP-02 🤖 | P1 | Speed and text size | Changes live and is remembered |
| TP-03 🤖 | P1 | Starts scrolling when recording starts, stops when it stops | Yes |
| TP-04 🤖👤 | P2 | Eye-contact mode | Narrow column right under the front camera |
| TP-05 👤 | P1 | 🎙 Voice follow | Scrolls only while you talk, waits when you pause |
| TP-06 🤖 | P0 | Not burned into the video | Files are clean |

## 14. Photo mode
| ID | Pri | Test | Expected |
|---|---|---|---|
| PH-01 🤖 | P0 | PHOTO mode, shutter | One photo per chosen shape in Pictures/DualFrame at full sensor resolution |
| PH-02 🤖 | P1 | Photo with a LUT / cinematic / watermark on | Each is applied to the photo |
| PH-03 🤖 | P1 | Photos in the library | Show with videos; open in the photo viewer; share and delete work |

## 15. Captions (record with captions on)
| ID | Pri | Test | Expected |
|---|---|---|---|
| CAP-01 🤖 | P0 | Settings → Captions → English: speech pack download | Download starts (free, on-device); no crash if offline |
| CAP-02 👤🤖 | P0 | Record 20 s talking with captions in English | Captions burned into every shape, in sync, readable; a `.srt` file is saved |
| CAP-03 👤🤖 | P1 | Hinglish | Roman-script Hindi + English words recognised reasonably |
| CAP-04 🤖 | P1 | "Review before saving" on | The caption editor opens before the videos are made; edits appear in the videos |
| CAP-05 🤖 | P1 | Silence / no speech | Videos are made without captions and a note explains why |
| CAP-06 🤖 | P1 | Placement | Captions stay in the centre safe area (not at the edges, not under the like/comment buttons on 9:16); Top / Middle / Bottom options work |
| CAP-07 🤖 | P1 | Style editor: every preset, font (bundled + imported .ttf), colour, size, outline, shadow, background, highlight, animations (Pop, Bounce, Slide up, Typewriter, Flicker, Glitch, Shake), effects (Neon, Gradient) | Live preview matches the burned-in result |
| CAP-08 🤖 | P1 | Per-word colour / size / effect in the editor | Only that word changes |
| CAP-09 🤖 | P1 | Auto emphasis on / off; "same look for all" | Key words are emphasised only when on |
| CAP-10 🤖 | P1 | Edit words: retype, split, join, delete, apply to all | All work; the timing stays right |
| CAP-11 🤖 | P1 | Library → ⋯ → Add captions to an existing video | Picks a language, transcribes, lets you edit, saves a captioned copy; the original is unchanged |
| CAP-12 🤖 | P2 | Captions + slow motion | Captions are skipped (speech isn't real-time) |

## 16. Library and player
| ID | Pri | Test | Expected |
|---|---|---|---|
| LIB-01 🤖 | P0 | Open the library from the thumbnail | All videos and photos, newest first |
| LIB-02 🤖 | P0 | Player: play, pause, seek, full screen | Works |
| LIB-03 🤖 | P1 | ⋯ menu: Grade, Add captions, Trim & join, Design, Open with | Each opens the right screen |
| LIB-04 🤖 | P1 | Delete | Asks for confirmation (Android dialog on 11+); the video goes away |
| LIB-05 🤖 | P1 | Long-press several → Trim & join | The editor opens with those clips in order |
| LIB-06 🤖 | P1 | Long-press several → delete | The bin asks "Delete 2 videos?"; both go (Android asks too for videos from before a reinstall) |

## 17. Trim & join (library)
| ID | Pri | Test | Expected |
|---|---|---|---|
| ED-01 🤖 | P0 | Trim the start and end of one clip, save | The new file is the trimmed part; the original is unchanged |
| ED-02 🤖 | P0 | Join three clips | Plays in order with sound throughout |
| ED-03 🤖 | P1 | Join clips of different shapes / HDR + standard | Joins cleanly (fitted to the first clip's shape; HDR tone-mapped if mixed) |
| ED-04 🤖👤 | P1 | Remove silences on the clip with pauses | The pauses are cut; words aren't clipped; the amount removed is reasonable |

## 18. Colour grading (library)
| ID | Pri | Test | Expected |
|---|---|---|---|
| GR-01 🤖 | P0 | Grade a video: Light, Colour, Wheels, HSL, Finish tabs | The preview updates live for every slider |
| GR-02 🤖 | P1 | Looks + "Save as my look" | The saved look appears in the look list (and in the camera's LUT list) |
| GR-03 🤖 | P0 | Save the graded copy | The new file matches the preview; the original is unchanged |
| GR-04 🤖 | P1 | Grade an HDR clip | Saves (tone-mapped) without failing |
| GR-05 🤖 | P0 | No grading controls on the camera screen | Grading is library-only (as decided) |
| GR-06 🤖👤 | P1 | Colour wheels (Lift, Gamma, Gain, Offset) and scopes (waveform, RGB parade, vectorscope, histogram) | Wheels are round with a draggable dot and a master slider; double-tap resets; scopes update live as you grade and move the way Resolve's do (warmer gain → parade red rises, vectorscope leans toward R/Yl) |

## 19. Design editor (library → ⋯ → Design)
| ID | Pri | Test | Expected |
|---|---|---|---|
| DES-01 🤖 | P0 | Opens on Templates; Dreamy heart and Scrapbook show previews with a frame from your video | Yes |
| DES-02 🤖 | P0 | Apply each template, save | The saved video matches the preview: heart/rounded shape, border, text, stickers moving |
| DES-03 🤖 | P1 | Save this design as a template; apply it to another video; long-press to delete | Works; built-ins can't be deleted |
| DES-04 🤖 | P0 | Canvas: 9:16, 16:9, 1:1, 4:5, 4:3 | The output size changes; layers keep their place |
| DES-05 🤖 | P0 | Background: blurred video, colour, gradient, your photo | Each shows around/behind the video, in the preview and the saved file |
| DES-06 🤖 | P0 | Background: video (pick a second clip), soft blur on/off | Plays on a loop behind; only the main video's sound in the saved file; preview doesn't stutter badly |
| DES-07 🤖 | P1 | Video: drag, pinch, twist; size and rotate sliders; reset | Moves smoothly; the saved file matches |
| DES-08 🤖 | P1 | Shapes: full, rounded (corners), circle, heart, arch; border width + colour | Edges are clean (not jagged); the arch has a round top |
| DES-09 🤖👤 | P1 | Cut out the person (one person clearly visible) | Only the person shows on the background, in preview and file; preview speed is acceptable |
| DES-10 🤖 | P1 | Text layers: font, colour, outline, multi-line | Look the same in the file |
| DES-11 🤖 | P1 | Stickers: Emoji, 3D, Animated, Your GIF / PNG | 3D are sharp; animated ones animate in preview **and** the file; GIFs keep moving; PNG transparency kept |
| DES-12 🤖 | P1 | Photo layer and photo cutout layer | Shows; the cutout has a transparent background |
| DES-13 🤖 | P1 | Layer: size, rotate, opacity, when it shows (time range), entrance animation, keeps moving (float, pulse, wiggle, bounce, spin), forward/back, delete | Each works and matches in the file |
| DES-14 🤖 | P1 | Logo: pick, pick + cut out, corner, size, opacity, "on every video and photo" | Appears on designs **and** on new camera videos and photos; turning it off removes it |
| DES-15 🤖 | P1 | Intro and outro cards (title, subtitle, colours, font, photo, length) | They play before/after the video in the canvas shape; layers stay in sync with the video |
| DES-16 🤖 | P2 | Sticker credit line is shown in the Stickers tab | Yes |
| DES-17 🤖 | P1 | Design a 1-minute 4K video with cutout + video background | Finishes saving; memory OK; note the time it takes |

## 19b. The editor (library → ⋯ → Edit, or long-press clips → Edit together)
| ID | Pri | Test | Expected |
|---|---|---|---|
| STU-01 🤖 | P0 | Timeline: scrub, split at the playhead, trim, speed 0.25×–4×, volume, duplicate, move, delete, add clips | Timeline and preview follow each change; the saved video matches |
| STU-02 🤖 | P1 | Music: add a song, choose its start, volume, fade in/out; mute the clips | Plays along in the preview; mixed into the saved video, cut to its length |
| STU-03 👤 | P1 | Voiceover from the playhead | Lines up with the video in the preview and the saved file |
| STU-04 🤖 | P1 | Captions in the editor: made from all clips, follow cuts and speed; edit words; style | Timed to the edit, in the preview and the saved video |
| STU-05 🤖 | P1 | Adjust: looks, wheels, scopes inside the editor | Grade previews live and is in the saved video; background colours aren't graded |
| STU-06 🤖 | P1 | Remove silences across all clips | Pauses cut; clips split where needed |

## 20. Shot lists (SHOTS)
| ID | Pri | Test | Expected |
|---|---|---|---|
| SL-01 🤖 | P0 | SHOTS → New shot list → name it | The list opens; shapes row shows the camera's current shapes |
| SL-02 🤖 | P0 | Add a shot: number, size (e.g. MS), description, lines, notes | Shows as a card "SHOT 1 · MEDIUM" with the description |
| SL-03 🤖 | P0 | Paste a list (see the example in the app) | One shot per line; sizes picked up (WS, MS, CU, OTS, POV, wide, close-up…); `Script:` lines become lines to say; `Note:` lines become notes; numbers like 3A kept |
| SL-04 🤖 | P1 | Edit, move up/down, delete a shot; rename the list; delete a list | All work; deleting never removes videos from the library |
| SL-05 🤖 | P0 | Pick shapes (e.g. 9:16 + 16:9) → Shoot | Camera switches to those shapes ("Shooting this list in 9:16 + 16:9"); the shot card shows shot number, "1 OF n", size, description, "Next: take 1" |
| SL-06 🤖 | P0 | Shot with lines | The teleprompter opens with that shot's lines; editing them there updates the shot; leaving the list brings your own script back |
| SL-07 🤖 | P0 | Record a take | Files named `List_S01_T01_MS_<date>_9x16_….mp4` and `…_16x9_…`; the card asks "How was take 1?" |
| SL-08 🤖 | P0 | ✓ Good, next shot | Take marked good; card moves to shot 2 |
| SL-09 🤖 | P0 | ✓ Good, go again | Stays on the same shot; next take is T02; both good takes are kept |
| SL-10 🤖 | P0 | ↻ No good, retake | Take marked ✗; stays on the shot |
| SL-11 🤖 | P1 | ‹ and › between shots; Done leaves shot-list mode | Works; can't change shots while recording |
| SL-12 🤖 | P0 | Back in the list: takes show as thumbnails with ✓ / ✗ / ★ | ★ is on the latest good take by default; "Use this take" moves the ★; Play plays it |
| SL-13 🤖 | P0 | Assemble → Join all shapes now | One assembled video per shape, takes in shot order, saved to the library; the library opens |
| SL-14 🤖 | P1 | Assemble → Trim and join 9:16 in the editor | The editor opens with the starred 9:16 takes in order |
| SL-15 🤖 | P1 | Shots without a usable take | Assemble warns how many are left out |
| SL-16 🤖 | P1 | Record a take, go home before it finishes saving, come back | The take still shows up under its shot |
| SL-17 🤖 | P1 | Rate a take before its export finishes | The rating sticks once it's saved |
| SL-18 🤖 | P1 | Change shapes in the camera while in shot-list mode | The list's shapes update to match |

## 21. Robustness and performance
| ID | Pri | Test | Expected |
|---|---|---|---|
| ROB-01 🤖 | P0 | 20 recordings in a row | No slow-down, no crash, memory stable (`dumpsys meminfo`) |
| ROB-02 🤖 | P1 | Very low storage (fill the phone) | Clear message; no corrupt files |
| ROB-03 🤖 | P1 | Phone call / alarm during recording | Recording stops cleanly and is saved |
| ROB-04 🤖 | P1 | Kill the app in every screen (`am force-stop`) and reopen | Starts fine; nothing lost that was saved |
| ROB-05 🤖 | P1 | Dark mode and large font size (Settings → Display) | Everything readable; nothing overlaps |
| ROB-06 🤖 | P2 | Thermal: 15 min of 4K | Note heat warnings; app shouldn't crash |

## 22. Play Store readiness
| ID | Pri | Test | Expected |
|---|---|---|---|
| PLAY-01 🤖 | P0 | `adb shell dumpsys package com.dualframe.camera` permissions | Only camera, microphone (and what's needed for saving); no broad media read permissions |
| PLAY-02 🤖 | P1 | Runs on Android 15/16 with 16 KB pages (if the phone supports it) | Launches and records; CI's alignment report says all native libs are 16 KB aligned |
| PLAY-03 🤖 | P1 | Privacy policy link and store text match what the app does | Yes |

---

## Results log

Fill one row per test run. Claude adds screenshots/logs to a `test-results/<build>/` folder.

| Build | Phone / Android | Test ID | Result (✅ / ❌ / ⚠️ / skipped) | Notes, file, screenshot |
|---|---|---|---|---|
| build-41 | | | | |

## Order to run them in

1. P0 tests across all sections (a smoke pass, about 1–2 hours with you nearby for the 👤 ones).
2. P1 tests, section by section; fix what fails, ship a new build, re-run the failed IDs.
3. P2 tests and the long/thermal ones last.
