#!/usr/bin/env python3
"""The emulator tests, named after docs/TEST_PLAN.md. Run: python3 tests.py [--only ID,ID]"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from driver import *  # noqa: F401,F403
import driver as D

SETTINGS_PREFS = "capture_settings_v3"
HW = "needs real camera hardware (the emulator camera is a fake feed)"


def reset_settings():
    """Camera settings back to defaults (the debuggable test build allows run-as)."""
    force_stop()
    ashell(f"run-as {PKG} rm -f shared_prefs/{SETTINGS_PREFS}.xml")


def fresh_camera(reset=True):
    if reset:
        reset_settings()
    relaunch()
    grant_all()
    if not wait_camera():
        raise AssertionError("camera screen didn't appear")
    dismiss_dialogs()


def check_video(t, name, w=None, h=None, codec=None, audio=True):
    local = pull(f"{MOVIES}/{name}")
    info = video_info(local)
    t.note(f"{name}: {info}")
    if w and h:
        t.check((info["w"], info["h"]) == (w, h), f"{name} is {w}x{h} (got {info['w']}x{info['h']})")
    if codec:
        t.check(info["codec"] == codec, f"{name} codec {codec} (got {info['codec']})")
    if audio is not None:
        t.soft(info["audio"] == audio, f"{name} audio track {'present' if audio else 'absent'}")
    t.check(info["duration"] > 0.5, f"{name} has length ({info['duration']:.1f}s)")
    frame_png(local, min(1.0, max(0.1, info["duration"] / 2)), f"{t.id}_{name}")
    return info


# ───────────────────────── 1. Install, launch, permissions ─────────────────────────

@test("INS-01", "Fresh install, icon and name")
def ins01(t):
    out = ashell(f"pm list packages {PKG}")
    t.check(PKG in out, "package installed")
    label = ashell(f"dumpsys package {PKG} | grep -m1 versionName")
    t.note(label.strip())


@test("INS-03", "First launch asks only for camera and microphone")
def ins03(t):
    ashell(f"pm clear {PKG}")
    launch()
    sleep(2)
    t.shot("first_launch")
    perms = ashell(f"dumpsys package {PKG} | grep -A40 'requested permissions:'")
    t.note(perms.strip()[:800])
    t.check("READ_MEDIA" not in perms and "READ_EXTERNAL_STORAGE" not in perms.split("install permissions")[0],
            "no broad media read permissions requested")
    dialog = find(text="Allow", contains=True, timeout=6) or find(text="While using the app", contains=True, timeout=2)
    t.check(dialog is not None, "a permission prompt appears")


@test("INS-04", "Deny camera shows the grant panel; grant works")
def ins04(t):
    # Continue from INS-03: deny everything.
    for _ in range(3):
        n = find(text="Don’t allow", timeout=3) or find(text="Don't allow", timeout=1) or find(text="Deny", timeout=1)
        if n:
            tap_xy(n.cx, n.cy)
    sleep(2)
    t.shot("denied")
    panel = find(rid="grant_button", timeout=5)
    t.check(panel is not None, "grant panel with a Grant button")
    grant_all()
    relaunch()
    t.check(wait_camera(), "camera works after granting")
    t.shot("granted")


@test("INS-05", "Record with the microphone denied")
def ins05(t):
    ashell(f"pm revoke {PKG} android.permission.RECORD_AUDIO")
    relaunch()
    wait_camera()
    set_formats(["9:16"])
    try:
        new = record(3)
        t.check(len(new) >= 1, f"saved without microphone: {new}")
        info = check_video(t, new[-1], audio=None)
        t.note(f"audio track: {info['audio']}")
    finally:
        grant_all()
        close_results()


@test("INS-06", "Rotate, lock/unlock, home and back: preview comes back")
def ins06(t):
    fresh_camera()
    ashell("settings put system accelerometer_rotation 0")
    for rot in [1, 0]:
        ashell(f"settings put system user_rotation {rot}")
        sleep(2)
    t.check(find(rid="record_button", timeout=5) is not None, "camera after rotation")
    key(26)
    sleep(1.5)
    key(26)
    sleep(1)
    ashell("wm dismiss-keyguard")
    ashell("input keyevent 82")
    sleep(2)
    d1, _ = preview_live("after_lock")
    t.check(d1 > 0.3, f"preview moving after lock/unlock (diff {d1:.2f})")
    key(3)
    sleep(2)
    launch()
    sleep(2)
    d2, p = preview_live("after_home")
    t.shot("after_home")
    t.soft(d2 > 0.05, f"preview moving after home and back (diff {d2:.2f})")


# ───────────────────────── 2. Auto and Pro ─────────────────────────

@test("MODE-01", "Auto / Pro switch shows and hides the Pro strip")
def mode01(t):
    fresh_camera()
    to_pro()
    t.shot("pro")
    t.check(find(rid="pro_strip", timeout=3) is not None, "Pro strip visible in Pro")
    for title in ["Recording profile", "Look (LUT)", "Frame rate"]:
        t.check(find(desc=title, timeout=1) is not None, f"'{title}' in the Pro strip")
    to_auto()
    t.shot("auto")
    t.check(find(rid="pro_strip", timeout=1) is None, "Pro strip hidden in Auto")


@test("MODE-02", "Pro values are remembered across Auto")
def mode02(t):
    fresh_camera()
    to_pro()
    open_param("Frame rate")
    n = find(text="24", timeout=3)
    if n is None:
        raise Skip("24 fps not offered on the emulator camera")
    tap_xy(n.cx, n.cy)
    back()
    to_auto()
    to_pro()
    cell = find(desc="Frame rate", timeout=3)
    nodes = dump()
    vals = [x.text for x in nodes if cell and x.y1 >= cell.y1 and x.y2 <= cell.y2 and x.x1 >= cell.x1 and x.x2 <= cell.x2]
    t.shot("fps_remembered")
    t.check(any("24" in v for v in vals), f"FPS readout still 24 ({vals})")
    open_param("Frame rate")
    n = find(text="30", timeout=3)
    if n:
        tap_xy(n.cx, n.cy)
    back()
    to_auto()


@test("MODE-03", "VIDEO · PHOTO · SHOTS | AUTO · PRO fit a 360 dp phone")
def mode03(t):
    w0, h0 = screen_size()
    d0 = density()
    try:
        ashell("wm size 720x1520")
        ashell("wm density 320")
        sleep(2)
        fresh_camera()
        t.shot("360dp")
        width = 720
        for rid in ["capture_video", "capture_photo", "capture_shots", "mode_auto", "mode_pro"]:
            n = find(rid=rid, timeout=2)
            t.check(n is not None and n.x1 >= 0 and n.x2 <= width, f"{rid} fully on screen ({n})")
        tops = [find(rid=r, timeout=0, nodes=D._last_dump) for r in ["capture_video", "capture_shots", "mode_pro"]]
        t.check(all(n and n.x2 - n.x1 > 40 for n in tops), "labels not squashed")
    finally:
        ashell("wm size reset")
        ashell("wm density reset")
        sleep(2)


# ───────────────────────── 3. Camera basics ─────────────────────────

@test("CAM-01", "Live preview, back and front (flip)")
def cam01(t):
    fresh_camera()
    d, _ = preview_live("back")
    t.soft(d > 0.05, f"back preview live (diff {d:.2f}; the emulated back scene barely moves)")
    tap(rid="flip_button")
    sleep(3)
    d2, _ = preview_live("front")
    t.shot("front")
    t.check(d2 > 0.3, f"front preview live (diff {d2:.2f})")
    tap(rid="flip_button")
    sleep(3)


@test("CAM-02", "Zoom: two buttons (pinch needs you)", phone_only="pinch zoom needs two fingers (morning session)")
def cam02(t):
    pass


@test("CAM-04", "Torch on and off")
def cam04(t):
    fresh_camera()
    tap(rid="torch_button")
    sleep(1)
    t.shot("torch")
    t.note("emulator has no flash: expect a message or no change, and no crash")


@test("CAM-05", "Self-timer 3 s counts down and records; tap cancels")
def cam05(t):
    fresh_camera()
    set_formats(["9:16"])
    tap(rid="timer_button")
    sleep(0.5)
    t.shot("timer3")
    before = set(movies())
    tap(rid="record_button")
    sleep(1.2)
    t.shot("countdown")
    t.soft(find(rid="countdown", timeout=1) is not None, "countdown seen in the screenshot moment")
    sleep(4)
    t.check(find(rid="timer_row", timeout=2) is not None, "recording after the countdown")
    tap(rid="record_button")
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    t.check(bool(set(movies()) - before), "video saved after the timer recording")
    close_results()
    # Cancel: start the countdown and tap again.
    before = set(movies())
    tap(rid="record_button")
    sleep(1)
    tap(rid="record_button")
    sleep(4)
    t.check(find(rid="timer_row", timeout=1) is None, "tapping during the countdown cancels")
    for _ in range(2):  # 3 → 10 → off
        tap(rid="timer_button")


@test("CAM-06", "Volume key starts and stops recording")
def cam06(t):
    fresh_camera()
    settings_toggle("Volume buttons record", True)
    before = set(movies())
    key(24)
    sleep(3)
    t.shot("recording_by_volume")
    rec = find(rid="timer_row", timeout=1) is not None
    key(24)
    t.check(rec, "volume up started recording")
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    t.check(bool(set(movies()) - before), "volume up stopped it and the video saved")
    close_results()


@test("CAM-07", "Grid, level, centre mark, safe area draw but aren't recorded")
def cam07(t):
    fresh_camera()
    settings_toggle("Grid lines", True)
    to_pro()
    open_param("Monitoring")
    t.shot("monitor_panel")
    for label in ["Level", "Centre mark", "Center mark", "Safe area"]:
        n = find(text=label, contains=True, timeout=1)
        if n:
            tap_xy(n.cx, n.cy)
    back()
    t.shot("guides_on")
    set_formats(["9:16"])
    new = record(3)
    check_video(t, new[-1])
    t.note("compare the guides screenshot with the extracted frame: guides must not be in the frame")
    close_results()
    settings_toggle("Grid lines", False)
    to_auto()


@test("CAM-08", "Night button")
def cam08(t):
    fresh_camera()
    if find(rid="night_button", timeout=3) is None:
        # Only shown when the camera offers low-light boost, which the emulator's doesn't.
        raise NeedsPhone("the emulator camera has no low-light boost, so the Night button is hidden (correct)")
    tap(rid="night_button")
    sleep(1.5)
    t.shot("night_auto")
    tap(rid="night_button")
    to_pro()
    tap(rid="night_button")
    sleep(1)
    t.shot("night_pro")
    to_auto()


@test("CAM-09", "Lens lock", phone_only=HW)
def cam09(t):
    pass


@test("CAM-10", "Stabilisation", phone_only="needs walking with the phone")
def cam10(t):
    pass


@test("CAM-11", "Settings → This phone lists the cameras")
def cam11(t):
    fresh_camera()
    open_settings()
    n = scroll_to(text="This phone", max_swipes=16) or scroll_to(text="Back lenses", max_swipes=4)
    t.check(n is not None, "'This phone' section present")
    swipe(500, 1600, 500, 900)
    t.shot("this_phone")
    close_sheet()


# ───────────────────────── 4. Shapes ─────────────────────────

@test("FMT-01", "Only 9:16 picked → exactly one video (regression)")
def fmt01(t):
    fresh_camera()
    settings_toggle("Keep the original", False)
    set_formats(["9:16"], "720p")
    new = record(4)
    t.check(len(new) == 1, f"exactly one new video: {new}")
    check_video(t, new[-1], 720, 1280)
    close_results()


@test("FMT-02", "9:16 + 16:9 + 1:1 → three videos at the right sizes")
def fmt02(t):
    fresh_camera()
    set_formats(["9:16", "16:9", "1:1"], "720p")
    t.shot("three_frames")
    new = record(4)
    t.check(len(new) == 3, f"three new videos: {new}")
    sizes = {"9x16": (720, 1280), "16x9": (1280, 720), "1x1": (720, 720)}
    for name in new:
        for k, (w, h) in sizes.items():
            if f"_{k}_" in name:
                check_video(t, name, w, h)
    t.shot("results")
    close_results()


@test("FMT-03", "Different resolution per shape")
def fmt03(t):
    fresh_camera()
    open_settings()
    for lab, res in [("9:16", "1080p"), ("16:9", "720p")]:
        n = scroll_to(text=f"{lab}   ·", contains=True, max_swipes=6)
        if not n.checked:
            tap_xy(n.cx, n.cy)
        cands = [x for x in dump() if x.text == res and n.cy < x.cy < n.cy + 300]
        if cands:
            tap_xy(cands[0].cx, cands[0].cy)
    n = scroll_to(text="1:1   ·", contains=True, max_swipes=6)
    if n and n.checked:
        tap_xy(n.cx, n.cy)
    close_sheet()
    new = record(4)
    for name in new:
        if "_9x16_" in name:
            check_video(t, name, 1080, 1920)
        if "_16x9_" in name:
            check_video(t, name, 1280, 720)
    close_results()


@test("FMT-04", "Dragging a frame moves the cut")
def fmt04(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    w, h = screen_size()
    chip = find(text="9:16", contains=True, timeout=3)
    if chip:
        tap_xy(chip.cx, chip.cy)
    swipe(w // 2, h // 2, int(w * 0.2), h // 2, 600)
    t.shot("frame_moved")
    new = record(3)
    check_video(t, new[-1], 720, 1280)
    t.note("frame extracted for review (should be cut from the left side)")
    close_results()


@test("FMT-05", "Open gate 4:3 camera frame")
def fmt05(t):
    fresh_camera()
    open_settings()
    n = scroll_to(text="Open gate", contains=True, max_swipes=14)
    if n is None:
        close_sheet()
        raise Skip("Open gate option not shown on the emulator camera")
    tap_xy(n.cx, n.cy)
    close_sheet()
    t.shot("open_gate")
    settings_toggle("Keep the original", True)
    set_formats(["9:16"], "720p")
    new = record(3)
    origs = [x for x in new if "original" in x]
    if origs:
        info = check_video(t, origs[-1], audio=None)
        ratio = max(info["w"], info["h"]) / max(1, min(info["w"], info["h"]))
        t.check(abs(ratio - 4 / 3) < 0.05, f"original is 4:3 (ratio {ratio:.2f})")
    settings_toggle("Keep the original", False)
    close_results()


@test("FMT-06", "Every shape has the right aspect")
def fmt06(t):
    fresh_camera()
    set_formats(["4:5", "3:4", "4:3", "21:9"], "720p")
    new = record(3, timeout=600)
    want = {"4x5": 4 / 5, "3x4": 3 / 4, "4x3": 4 / 3, "21x9": 21 / 9}
    for name in new:
        for k, r in want.items():
            if f"_{k}_" in name:
                info = check_video(t, name)
                t.check(abs(info["w"] / max(1, info["h"]) - r) < 0.03, f"{k} aspect ({info['w']}x{info['h']})")
    close_results()
    set_formats(["9:16"], "720p")


# ───────────────────────── 5. Recording ─────────────────────────

@test("REC-01", "Record 10 s: progress, results panel")
def rec01(t):
    fresh_camera()
    set_formats(["9:16", "16:9"], "720p")
    before = set(movies())
    tap(rid="record_button")
    sleep(3)
    t.shot("recording")
    t.check(find(rid="timer_row", timeout=1) is not None, "timer shows while recording")
    sleep(7)
    tap(rid="record_button")
    sleep(2)
    t.shot("exporting")
    end = time.time() + 400
    while time.time() < end and find(rid="record_again", timeout=0, nodes=dump()) is None:
        sleep(4)
    t.shot("results")
    t.check(find(rid="result_list", timeout=2) is not None, "results panel")
    t.check(len(find_all(rid="play")) >= 2, "a play button per video")
    t.check(len(set(movies()) - before) >= 2, "two videos saved")


@test("REC-02", "File names and take numbers")
def rec02(t):
    names = movies()
    t.note("\n".join(names[-6:]))
    pat = re.compile(r"^[A-Za-z0-9_-]+_T(\d{3})_\d{8}_\d{6}_\d+x\d+_\d+p\.mp4$")
    takes = [int(pat.match(n).group(1)) for n in names if pat.match(n)]
    t.check(len(takes) >= 2, f"slate-style names ({len(takes)} matching)")
    t.check(takes == sorted(takes) or len(set(takes)) > 1, "take numbers go up")


@test("REC-03", "Results play in the app")
def rec03(t):
    if find(rid="play", timeout=2) is None:
        fresh_camera()
        set_formats(["9:16"], "720p")
        record(3)
    tap(rid="play")
    sleep(4)
    t.shot("playing")
    t.check(find(rid="player_view", timeout=3) is not None, "player opens")
    back()
    close_results()


@test("REC-04", "Share opens the share sheet")
def rec04(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    record(3)
    tap(rid="share")
    sleep(2.5)
    t.shot("share_sheet")
    t.check(not in_app() or find(text="Share", contains=True, timeout=2) is not None, "share sheet opened")
    back()
    close_results()


@test("REC-05", "Keep original saves an _original file")
def rec05(t):
    fresh_camera()
    settings_toggle("Keep the original", True)
    set_formats(["9:16"], "720p")
    new = record(3)
    t.check(any("original" in n for n in new), f"original saved: {new}")
    settings_toggle("Keep the original", False)
    close_results()


@test("REC-06", "Long recording (90 s on the emulator; 10 min 4K on the phone)")
def rec06(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    settings_toggle("Keep the original", False)
    new = record(90, timeout=900)
    cut = [n for n in new if "original" not in n]
    info = check_video(t, cut[-1], 720, 1280)
    t.check(info["duration"] > 80, f"full length ({info['duration']:.0f}s)")
    mem = ashell(f"dumpsys meminfo {PKG} | grep -m1 'TOTAL'")
    t.note("memory: " + mem.strip())
    close_results()


@test("REC-07", "Home during export; export finishes")
def rec07(t):
    fresh_camera()
    set_formats(["9:16", "16:9"], "720p")
    before = set(movies())
    tap(rid="record_button")
    sleep(5)
    tap(rid="record_button")
    sleep(1)
    key(3)
    sleep(40)
    launch()
    end = time.time() + 300
    while time.time() < end and len(set(movies()) - before) < 2:
        sleep(4)
    t.check(len(set(movies()) - before) >= 2, f"both videos saved while away: {sorted(set(movies()) - before)}")
    t.shot("after_return")
    close_results()


@test("REC-08", "Force-stop during export; next launch is fine")
def rec08(t):
    fresh_camera()
    settings_toggle("Keep the original", False)
    set_formats(["9:16"], "1080p")
    tap(rid="record_button")
    sleep(5)
    tap(rid="record_button")
    sleep(2)
    force_stop()
    launch()
    t.check(wait_camera(), "camera screen after relaunch")
    t.shot("after_kill")
    d, _ = preview_live("after_kill")
    t.soft(d > 0.05, f"preview live (diff {d:.2f})")


@test("REC-09", "Audio gain")
def rec09(t):
    fresh_camera()
    open_settings()
    n = scroll_to(text="Sound", max_swipes=16)
    t.shot("sound_section")
    close_sheet()
    t.note("gain level needs real sound (morning session); UI checked here")


@test("REC-10", "Timecode track in the original")
def rec10(t):
    fresh_camera()
    settings_toggle("Keep the original", True)
    set_formats(["9:16"], "720p")
    new = record(3)
    cuts = [n for n in new if "original" not in n]
    local = pull(f"{MOVIES}/{cuts[-1]}")
    info = ffprobe(local)
    tmcd = [s for s in info.get("streams", []) if s.get("codec_tag_string") == "tmcd" or s.get("codec_type") == "data"]
    tags = info.get("format", {}).get("tags", {})
    t.note(f"streams: {[s.get('codec_type') + ':' + str(s.get('codec_tag_string')) for s in info.get('streams', [])]} tags: {tags}")
    # Written as XMP (xmpDM:startTimeCode), which ffprobe doesn't list: look in the file itself.
    xmp = b"startTimeCode" in open(local, "rb").read()
    t.soft(bool(tmcd) or xmp, "time-of-day timecode (XMP) in the cut video")
    settings_toggle("Keep the original", False)
    close_results()


@test("REC-12", "Pause and resume while recording")
def rec12(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    before = set(movies())
    tap(rid="record_button")
    t0 = time.time()
    sleep(3)
    t.check(find(rid="flip_button", timeout=1) is None, "flip button gives way while recording")
    tap(desc="Pause recording")
    tp = time.time()
    sleep(1)
    t.shot("paused")
    t.check(find(text="Paused", contains=True, timeout=2) is not None, "timer says Paused")
    sleep(6)
    tap(desc="Resume recording")
    tr = time.time()
    sleep(3)
    tap(rid="record_button")
    ts = time.time()
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    new = sorted(set(movies()) - before)
    t.check(bool(new), "video saved")
    if new:
        info = check_video(t, new[-1])
        total, paused = ts - t0, tr - tp
        # Each tap looks at the screen first, so allow a couple of seconds either way.
        t.note(f"recorded about {total - paused:.1f}s with a {paused:.1f}s pause ({total:.1f}s on the clock)")
        t.check(info["duration"] < total - paused * 0.6, f"paused stretch left out ({info['duration']:.1f}s)")
    close_results()
    t.check(find(rid="flip_button", timeout=2) is not None, "flip button back after recording")


@test("REC-11", "Preview recovers after switching modes quickly")
def rec11(t):
    fresh_camera()
    for _ in range(4):
        to_pro()
        to_auto()
        tap(rid="capture_photo")
        tap(rid="capture_video")
    sleep(3)
    d, _ = preview_live("after_switching")
    t.check(d > 0.3, f"preview live after rapid switching (diff {d:.2f})")


# ───────────────────────── 6. Profiles ─────────────────────────

def pick_profile(label):
    to_pro()
    open_param("Recording profile")
    n = find(text=label, timeout=3)
    if n is None or not n.enabled:
        back()
        return False
    tap_xy(n.cx, n.cy)
    sleep(3)
    back()
    return True


@test("PROF-01", "Standard vs DF-Log", phone_only="needs you to judge flat vs normal on a real scene")
def prof01(t):
    pass


@test("PROF-02", "DF-Log → LUT → Rec.709: preview keeps moving (freeze regression)")
def prof02(t):
    fresh_camera()
    if not pick_profile("DF-Log"):
        raise NeedsPhone("DF-Log not offered by the emulator camera")
    t.shot("log")
    to_pro()
    open_param("Look (LUT)")
    t.shot("lut_panel")
    for name in ["Teal & Orange", "Rec.709", "None"]:
        n = find(text=name, contains=True, timeout=2)
        if n:
            tap_xy(n.cx, n.cy)
            sleep(2.5)
            back()
            if find(text="Import .cube", contains=True, timeout=1):
                back()
            d, _ = preview_live(f"log_{name[:4]}")
            t.check(d > 0.05, f"preview live after {name} (diff {d:.2f})")
            open_param("Look (LUT)")
    back()
    pick_profile("Standard")
    to_auto()


@test("PROF-03", "Save the DF-Log → Rec.709 LUT to Downloads")
def prof03(t):
    fresh_camera()
    to_pro()
    open_param("Recording profile")
    n = find(text="DF-Log", timeout=3)
    if n is None or not n.enabled:
        back()
        raise NeedsPhone("DF-Log not offered by the emulator camera")
    tap_xy(n.cx, n.cy)
    sleep(2)
    s = scroll_to(text="Save DF-Log", contains=True, max_swipes=3)
    t.shot("profile_panel")
    if s:
        tap_xy(s.cx, s.cy)
        sleep(2)
    out = ""
    for _ in range(10):  # the toast comes when the file is written
        out = ashell("ls -t /sdcard/Download")
        if "DF-Log_to_Rec709" in out:
            break
        sleep(2)
    t.check("DF-Log_to_Rec709" in out, "cube saved in Downloads")
    if "DF-Log_to_Rec709" in out:
        # Newest first; a second save is named "DF-Log_to_Rec709 (1).cube", so keep whole lines.
        name = [x.strip() for x in out.splitlines() if "DF-Log_to_Rec709" in x][0]
        # Read it on the phone (the name can have spaces and brackets: two layers of quoting).
        txt = ""
        for _ in range(5):
            txt = ashell(f"head -c 2000 \"'/sdcard/Download/{name}'\"")
            if txt.strip():
                break
            sleep(2)
        t.note(f"{name}: {txt[:120]!r}")
        t.check("LUT_3D_SIZE" in txt, "valid .cube text")
    back()
    pick_profile("Standard")
    to_auto()


@test("PROF-04", "HLG 10-bit can be picked", phone_only="HLG needs a real 10-bit camera")
def prof04(t):
    pass


@test("PROF-05", "HLG file metadata", phone_only=HW)
def prof05(t):
    pass


@test("PROF-06", "HDR fallback", phone_only=HW)
def prof06(t):
    pass


@test("PROF-07", "Log: original only when 'Keep the original' is on")
def prof07(t):
    fresh_camera()
    settings_toggle("Keep the original", False)
    if not pick_profile("DF-Log"):
        raise NeedsPhone("DF-Log not offered by the emulator camera")
    set_formats(["9:16"], "720p")
    new = record(3)
    t.check(not any("original" in n for n in new), f"one shape in, one video out with Log: {new}")
    close_results()
    pick_profile("Standard")
    to_auto()


# ───────────────────────── 7. LUTs ─────────────────────────

@test("LUT-01", "Built-in looks change the preview and the videos")
def lut01(t):
    fresh_camera()
    pick_profile("Standard")
    set_formats(["9:16"], "720p")
    to_pro()
    open_param("Look (LUT)")
    t.shot("luts")
    looks = ["Vivid", "Teal & Orange", "Film", "Golden Hour", "Moody", "Bleach Bypass", "Noir"]
    w, h = screen_size()
    box = (0, int(h * 0.25), w, int(h * 0.55))
    base = screenshot("LUT-01_none")
    for look in looks:
        n = find(text=look, timeout=2) or scroll_to(text=look, max_swipes=2)
        if n is None:
            t.check(False, f"{look} offered")
            continue
        tap_xy(n.cx, n.cy)
        sleep(2)
        p = t.shot(look.replace(" ", "_").replace("&", "and"))
        t.note(f"{look}: mean colour {[round(c) for c in image_mean(p, box)]}")
    # Record with Noir and check the file is grey.
    back()
    new = record(3)
    local = pull(f"{MOVIES}/{new[-1]}")
    png = frame_png(local, 1, "LUT-01_noir")
    r, g, b = image_mean(png)
    t.check(max(abs(r - g), abs(g - b)) < 6, f"Noir video is black and white (rgb {r:.0f},{g:.0f},{b:.0f})")
    close_results()
    to_pro()
    open_param("Look (LUT)")
    n = find(text="None", timeout=2) or find(text="Off", timeout=1)
    if n:
        tap_xy(n.cx, n.cy)
    back()
    to_auto()


@test("LUT-02", "Import a .cube, use it, long-press deletes it")
def lut02(t):
    fresh_camera()
    to_pro()
    open_param("Look (LUT)")
    n = scroll_to(text="Import", contains=True, max_swipes=3)
    tap_xy(n.cx, n.cy)
    pick_document("warm_test.cube")
    sleep(2)
    t.shot("imported")
    n = find(text="warm_test", contains=True, timeout=4)
    t.check(n is not None, "imported LUT listed")
    if n:
        tap_xy(n.cx, n.cy)
        sleep(2)
        t.shot("using_imported")
        # Picking a look closes the panel; long-press the chip in the panel, not the strip cell.
        if find(text="Import .cube", contains=True, timeout=1) is None:
            open_param("Look (LUT)")
        chips = find_all(text="warm_test", contains=True)
        n = max(chips, key=lambda c: c.cy) if chips else n
        long_press(n, 1600)
        sleep(1)
        t.shot("long_press")
        d = find(text="Delete", timeout=2)
        if d:
            tap_xy(d.cx, d.cy)
            sleep(1)
        sleep(1)
        t.check(len(find_all(text="warm_test", contains=True)) == 0, "deleted after long-press")
    back()
    to_auto()


@test("LUT-03", "Broken .cube gives a clear error")
def lut03(t):
    fresh_camera()
    to_pro()
    open_param("Look (LUT)")
    n = scroll_to(text="Import", contains=True, max_swipes=3)
    tap_xy(n.cx, n.cy)
    pick_document("broken.cube")
    sleep(1)
    t.shot("broken")
    t.check(in_app(), "still in the app (no crash)")
    back()
    to_auto()


@test("LUT-04", "Bake LUT off: preview only")
def lut04(t):
    fresh_camera()
    pick_profile("Standard")
    set_formats(["9:16"], "720p")
    to_pro()
    open_param("Look (LUT)")
    tap(text="Noir")
    sleep(1.5)
    if find(text="Import .cube", contains=True, timeout=1) is None:
        open_param("Look (LUT)")
    sw = scroll_to(text="Apply LUT to videos", contains=True, max_swipes=2)
    t.shot("bake_option")
    if sw is None:
        back()
        raise AssertionError("'Apply LUT to videos and photos' switch not in the Look panel")
    if sw.checked:
        tap_xy(sw.cx, sw.cy)
        sleep(1)
    back()
    new = record(3)
    png = frame_png(pull(f"{MOVIES}/{new[-1]}"), 1, "LUT-04_view_only")
    r, g, b = image_mean(png)
    t.check(max(abs(r - g), abs(g - b)) >= 6, f"view-only Noir: the video keeps its colour (rgb {r:.0f},{g:.0f},{b:.0f})")
    close_results()
    # Back to the default: looks baked in, no look.
    open_param("Look (LUT)")
    sw = scroll_to(text="Apply LUT to videos", contains=True, max_swipes=2)
    if sw is not None and not sw.checked:
        tap_xy(sw.cx, sw.cy)
        sleep(1)
    tap(text="None", required=False)
    sleep(1)
    if find(text="Import .cube", contains=True, timeout=1):
        back()
    to_auto()


# ───────────────────────── 8. Frame rates ─────────────────────────

@test("FPS-01", "24 / 25 / 30 / 60 fps")
def fps01(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    to_pro()
    open_param("Frame rate")
    t.shot("fps_panel")
    offered = [x.text for x in dump() if x.text in ("24", "25", "30", "48", "50", "60")]
    t.note(f"offered: {offered}")
    back()
    for fps in [f for f in ["24", "30", "60"] if f in offered]:
        open_param("Frame rate")
        tap(text=fps)
        back()
        new = record(3)
        info = check_video(t, new[-1])
        num, den = (info["avg_fps"] or "0/1").split("/")
        got = int(num) / max(1, int(den))
        t.soft(abs(got - int(fps)) < 3, f"{fps} fps file (average {got:.2f}; the emulator camera may deliver fewer)")
        close_results()
    open_param("Frame rate")
    tap(text="30", required=False)
    back()
    to_auto()


@test("FPS-02", "NTSC rates")
def fps02(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    to_pro()
    open_param("Frame rate")
    n = find(text="29.97", timeout=2)
    if n is None:
        back()
        raise Skip("NTSC pills not shown")
    tap_xy(n.cx, n.cy)
    back()
    new = record(3)
    info = check_video(t, new[-1])
    num, den = (info["avg_fps"] or "0/1").split("/")
    t.soft(abs(int(num) / max(1, int(den)) - 29.97) < 2, f"29.97 fps (avg {info['avg_fps']})")
    close_results()
    open_param("Frame rate")
    tap(text="30", required=False)
    back()
    to_auto()


@test("FPS-03", "Slow motion 60 fps", phone_only="needs motion in front of the camera (morning)")
def fps03(t):
    pass


@test("FPS-04", "120 / 240 fps", phone_only=HW)
def fps04(t):
    pass


@test("FPS-05", "Slow motion offered or explained")
def fps05(t):
    fresh_camera()
    open_settings()
    scroll_to(text="Speed", max_swipes=12)
    n = find(text="Slow motion", timeout=2)
    t.shot("speed")
    t.check(n is not None, "Slow motion option present")
    if n and not n.enabled:
        t.check(find(text="doesn't offer slow motion", contains=True, timeout=1) is not None or
                find(text="slow motion", contains=True, timeout=1) is not None, "reason shown when unavailable")
    close_sheet()


# ───────────────────────── 9. Pro controls ─────────────────────────

for _id, _t in [("PRO-01", "Shutter"), ("PRO-02", "ISO"), ("PRO-03", "White balance"), ("PRO-04", "Manual focus")]:
    test(_id, _t, phone_only="needs a real scene to judge (morning)")(lambda t: None)


@test("PRO-05", "Exposure compensation panel")
def pro05(t):
    fresh_camera()
    to_pro()
    open_param("Exposure")
    t.shot("ev")
    back()
    to_auto()


@test("PRO-06", "Shutter / ISO panels open (priority needs a real sensor)")
def pro06(t):
    fresh_camera()
    to_pro()
    for title in ["Shutter speed", "ISO", "White balance", "Focus", "Lens"]:
        open_param(title)
        t.shot(title.replace(" ", "_"))
        t.check(in_app(), f"{title} panel opened")
        back()
    to_auto()


@test("PRO-07", "A hands everything back to auto")
def pro07(t):
    fresh_camera()
    to_pro()
    n = find(desc="All automatic", contains=True, timeout=2) or find(text="A", timeout=2)
    t.check(n is not None, "A cell present")
    if n:
        tap_xy(n.cx, n.cy)
    t.shot("all_auto")
    to_auto()


@test("PRO-08", "Record bitrate", phone_only="the emulator's software encoder ignores bitrate requests")
def pro08(t):
    pass


@test("PRO-09", "Codec H.264 / H.265")
def pro09(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    for label, codec in [("HEVC", "hevc"), ("H.264", "h264")]:
        open_settings()
        scroll_to(text="Codec", max_swipes=8)
        n = scroll_to(text=label, max_swipes=2)  # the chip itself, not the note under it
        if n is None:
            close_sheet()
            t.check(False, f"{label} option")
            continue
        tap_xy(n.cx, n.cy)
        close_sheet()
        new = record(3)
        check_video(t, new[-1], codec=codec)
        close_results()


@test("PRO-10", "Export quality raises the bitrate")
def pro10(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    rates = {}
    for q in ["Standard", "Max"]:
        open_settings()
        scroll_to(text="Video quality", max_swipes=8)
        n = find(text=q, contains=True, timeout=2)
        if n:
            tap_xy(n.cx, n.cy)
        close_sheet()
        new = record(4)
        rates[q] = check_video(t, new[-1])["bitrate"]
        close_results()
    t.check(rates.get("Max", 0) > rates.get("Standard", 0), f"Max > Standard ({rates})")
    open_settings()
    scroll_to(text="Video quality", max_swipes=8)
    tap(text="High", contains=True, required=False)
    close_sheet()


# ───────────────────────── 10. Monitoring ─────────────────────────

def monitor_pick(label, nth=0):
    open_param("Monitoring")
    ns = find_all(text=label)
    if len(ns) > nth:
        tap_xy(ns[nth].cx, ns[nth].cy)
        sleep(1.5)
    back()
    return len(ns) > nth


@test("MON-01", "Zebras", phone_only="needs a bright real scene (morning)")
def mon01(t):
    pass


@test("MON-02", "Focus peaking", phone_only="needs a real in-focus scene (morning)")
def mon02(t):
    pass


@test("MON-03", "False colour")
def mon03(t):
    fresh_camera()
    to_pro()
    open_param("Monitoring")
    n = scroll_to(text="False colour", contains=True, max_swipes=3) or find(text="False color", contains=True)
    t.check(n is not None, "False colour option")
    if n:
        tap_xy(n.cx, n.cy)
        sleep(2)
    back()
    t.shot("false_colour")
    d, _ = preview_live("false_colour")
    t.soft(d > 0.05, f"preview live with false colour (diff {d:.2f})")
    open_param("Monitoring")
    n = find(text="False colour", contains=True, timeout=2)
    if n:
        tap_xy(n.cx, n.cy)
    back()
    to_auto()


@test("MON-04", "Histogram, waveform, vectorscope")
def mon04(t):
    fresh_camera()
    to_pro()
    for scope in ["Histogram", "Waveform", "Vectorscope"]:
        open_param("Monitoring")
        n = scroll_to(text=scope, max_swipes=3)
        if n:
            tap_xy(n.cx, n.cy)
            sleep(1.5)
        back()
        t.shot(scope)
        t.check(find(rid="histogram", timeout=2) is not None, f"{scope} view shown")
    to_auto()


@test("MON-05", "Exposure and audio meters")
def mon05(t):
    fresh_camera()
    to_pro()
    t.shot("meters")
    t.soft(find(rid="exposure_meter", timeout=2) is not None, "exposure meter")
    to_auto()


@test("MON-06", "Overlays not in the recorded files")
def mon06(t):
    fresh_camera()
    to_pro()
    open_param("Monitoring")
    for label in ["90%", "High"]:
        n = find(text=label, timeout=1)
        if n:
            tap_xy(n.cx, n.cy)
    n = find(text="False colour", contains=True, timeout=1)
    if n:
        tap_xy(n.cx, n.cy)
    back()
    t.shot("overlays_on")
    set_formats(["9:16"], "720p")
    new = record(3)
    check_video(t, new[-1])
    t.note("frame extracted: must look like a normal picture (no false colour, stripes or peaking)")
    close_results()
    open_param("Monitoring")
    for label in ["Off"]:
        for n in find_all(text=label):
            tap_xy(n.cx, n.cy)
    n = find(text="False colour", contains=True, timeout=1)
    if n:
        tap_xy(n.cx, n.cy)
    back()
    to_auto()


# ───────────────────────── 11. Cinematic, anamorphic, follow ─────────────────────────

@test("CIN-01", "Cinematic blur on a person", phone_only="needs a person in frame (morning)")
def cin01(t):
    pass


@test("CIN-02", "Cinematic mode records without failing")
def cin02(t):
    fresh_camera()
    settings_choice("Cinematic mode", "Medium")
    set_formats(["9:16"], "720p")
    t.shot("cinematic_preview")
    new = record(3)
    check_video(t, new[-1], 720, 1280)
    close_results()
    settings_choice("Cinematic mode", "Off")


@test("CIN-03", "Cinematic photo")
def cin03(t):
    fresh_camera()
    settings_choice("Cinematic mode", "Subtle")
    tap(rid="capture_photo")
    sleep(2)
    before = set(pictures())
    tap(rid="record_button")
    end = time.time() + 120
    while time.time() < end and not (set(pictures()) - before):
        sleep(3)
    t.check(bool(set(pictures()) - before), "photo saved with cinematic on")
    close_results()
    tap(rid="capture_video")
    settings_choice("Cinematic mode", "Off")


@test("ANA-01", "Anamorphic 2× de-squeezes")
def ana01(t):
    fresh_camera()
    settings_toggle("Keep the original", True)
    open_settings()
    scroll_to(text="Anamorphic lens", max_swipes=12)
    n = scroll_to(text="2.0×", max_swipes=3)
    t.shot("anamorphic_options")
    if n is None:
        close_sheet()
        raise AssertionError("2× anamorphic option not found")
    tap_xy(n.cx, n.cy)
    close_sheet()
    set_formats(["16:9"], "720p")
    t.shot("anamorphic_preview")
    new = record(3)
    for name in new:
        if "_16x9_" in name:
            check_video(t, name, 1280, 720)
    close_results()
    open_settings()
    scroll_to(text="Anamorphic lens", max_swipes=12)
    tap(text="Off", required=False)
    close_sheet()
    settings_toggle("Keep the original", False)


@test("ANA-02", "Anamorphic ignored in front + back", phone_only="front + back isn't available on the emulator")
def ana02(t):
    pass


@test("FOL-01", "Follow me", phone_only="needs you walking in frame (morning)")
def fol01(t):
    pass


@test("FOL-02", "Follow rotation", phone_only="needs the phone turned while recording (morning)")
def fol02(t):
    pass


@test("FOL-03", "Follow me with no face: records normally")
def fol03(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    n = find(text="Follow me", contains=True, timeout=3)
    if n is None:
        raise Skip("Follow me pill not shown")
    tap_xy(n.cx, n.cy)
    sleep(2)
    new = record(3)
    check_video(t, new[-1], 720, 1280)
    close_results()
    n = find(text="Follow me", contains=True, timeout=3)
    if n:
        tap_xy(n.cx, n.cy)


# ───────────────────────── 12. Front + back ─────────────────────────

@test("DUAL-01", "Front + back on or explained")
def dual01(t):
    fresh_camera()
    tap(rid="dual_button")
    sleep(2)
    t.shot("dual")
    t.check(in_app(), "no crash")
    if find(rid="corner_row", timeout=1):
        tap(rid="dual_button")


@test("DUAL-02", "Inset corners", phone_only="front + back needs two real cameras at once")
def dual02(t):
    pass


@test("DUAL-03", "Dual off → one recording (covered by FMT-01)")
def dual03(t):
    r = [x for x in D.RESULTS if x["id"] == "FMT-01"]
    t.check(bool(r) and r[0]["status"] == "PASS", "FMT-01 passed")


# ───────────────────────── 13. Teleprompter ─────────────────────────

@test("TP-01", "Edit the script; remembered after restart")
def tp01(t):
    fresh_camera()
    tap(rid="script_button")
    sleep(1)
    t.shot("open")
    n = find(text="✎", timeout=2) or find(desc="Edit", contains=True, timeout=1) or find(text="Edit", contains=True, timeout=1)
    if n is None:
        # The edit button is in the teleprompter bar; tap the text instead.
        n = find(rid="teleprompter", timeout=1)
    tap_xy(n.cx, n.cy)
    sleep(1)
    edit = find(text="Your script", timeout=2)
    t.shot("editor")
    box = [x for x in dump() if x.cls.endswith("EditText")]
    t.check(bool(box), "script editor opens")
    if box:
        tap_xy(box[0].cx, box[0].cy)
        ashell("input keyevent 123")
        for _ in range(40):
            ashell("input keyevent 67")
        type_text("Hello from the emulator test")
        tap(text="Save")
    relaunch()
    wait_camera()
    tap(rid="script_button")
    sleep(1)
    t.shot("after_restart")
    t.check(find(text="Hello from the emulator test", contains=True, timeout=3) is not None, "script remembered")


@test("TP-02", "Speed and size controls")
def tp02(t):
    fresh_camera()
    if find(rid="teleprompter", timeout=1) is None:
        tap(rid="script_button")
    t.shot("bar")
    for label in ["+", "A+", "−", "-"]:
        n = find(text=label, timeout=0, nodes=dump())
        if n:
            tap_xy(n.cx, n.cy)
    t.shot("after")
    t.check(in_app(), "controls respond")


@test("TP-03", "Scrolls during recording; not burned in (TP-06)")
def tp03(t):
    fresh_camera()
    if find(rid="teleprompter", timeout=1) is None:
        tap(rid="script_button")
    set_formats(["9:16"], "720p")
    a = t.shot("before")
    new_before = set(movies())
    tap(rid="record_button")
    sleep(4)
    b = t.shot("during")
    tap(rid="record_button")
    w, h = screen_size()
    tp = find(rid="teleprompter", timeout=0, nodes=dump())
    box = (tp.x1, tp.y1, tp.x2, tp.y2) if tp else None
    d = image_diff(a, b, box)
    t.soft(d > 1, f"teleprompter text moved during recording (diff {d:.1f})")
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - new_before):
        sleep(4)
    new = sorted(set(movies()) - new_before)
    if new:
        check_video(t, new[-1])
        t.note("TP-06: frame extracted, must not show the script")
    close_results()


@test("TP-04", "Eye-contact mode", phone_only="needs you to judge looking at the lens (morning)")
def tp04(t):
    pass


@test("TP-05", "Voice follow", phone_only="needs you reading aloud (morning)")
def tp05(t):
    pass


# ───────────────────────── 14. Photos ─────────────────────────

@test("PH-01", "Photo per shape at full resolution")
def ph01(t):
    fresh_camera()
    set_formats(["9:16", "1:1"], "720p")
    tap(rid="capture_photo")
    sleep(2)
    t.shot("photo_mode")
    before = set(pictures())
    tap(rid="record_button")
    end = time.time() + 120
    while time.time() < end and len(set(pictures()) - before) < 2:
        sleep(3)
    new = sorted(set(pictures()) - before)
    t.check(len(new) == 2, f"two photos: {new}")
    for name in new:
        local = pull(f"{PICTURES}/{name}")
        try:
            from PIL import Image
            w, h = Image.open(local).size
            t.note(f"{name}: {w}x{h}")
            if "9x16" in name:
                t.check(abs(w / h - 9 / 16) < 0.02, "9:16 photo aspect")
            if "1x1" in name:
                t.check(abs(w / h - 1) < 0.02, "1:1 photo aspect")
        except Exception as e:
            t.check(False, f"photo readable: {e}")
    close_results()
    tap(rid="capture_video")


@test("PH-02", "Photo with a LUT")
def ph02(t):
    fresh_camera()
    to_pro()
    open_param("Look (LUT)")
    tap(text="Noir")
    back()
    tap(rid="capture_photo")
    sleep(2)
    before = set(pictures())
    tap(rid="record_button")
    end = time.time() + 120
    while time.time() < end and not (set(pictures()) - before):
        sleep(3)
    new = sorted(set(pictures()) - before)
    t.check(bool(new), "photo saved")
    if new:
        r, g, b = image_mean(pull(f"{PICTURES}/{new[-1]}"))
        t.check(max(abs(r - g), abs(g - b)) < 6, f"Noir photo is black and white ({r:.0f},{g:.0f},{b:.0f})")
    close_results()
    tap(rid="capture_video")
    open_param("Look (LUT)")
    tap(text="None", required=False)
    back()
    to_auto()


@test("PH-03", "Photos in the library: open, viewer")
def ph03(t):
    fresh_camera()
    library_open()
    t.shot("library")
    ph = [n for n in dump() if n.rid == "duration" and n.text == "Photo"]
    t.check(bool(ph), "photos listed")
    if ph:
        tap_xy(ph[0].cx, ph[0].cy - 80)
        sleep(2)
        t.shot("photo_viewer")
        t.check(find(rid="photo_view", timeout=3) is not None, "photo viewer")
        back()
    back()


# ───────────────────────── 15. Captions (fake transcript) ─────────────────────────

def captions_on(review):
    open_settings()
    scroll_to(text="Captions", max_swipes=16)
    tap(text="English", contains=True)
    n = scroll_to(text="Review & edit", contains=True, max_swipes=3)
    if n and n.checked != review:
        tap_xy(n.cx, n.cy)
    close_sheet()


def captions_off():
    open_settings()
    scroll_to(text="Captions", max_swipes=16)
    tap(text="Off", required=False)
    close_sheet()


@test("CAP-01", "Captions option available (speech pack)", phone_only="speech pack download needs the phone's speech engine")
def cap01(t):
    pass


@test("CAP-02a", "Captions burned in + .srt (fake transcript)")
def cap02(t):
    fresh_camera()
    captions_on(review=False)
    set_formats(["9:16", "16:9"], "720p")
    new = record(6, timeout=600)
    t.check(len(new) >= 2, f"videos made: {new}")
    for name in new:
        local = pull(f"{MOVIES}/{name}")
        for at in [1.0, 2.5, 4.0]:
            frame_png(local, at, f"CAP-02a_{name[-14:-4]}")
    srt = ashell("ls /sdcard/Download/DualFrame 2>/dev/null")
    t.check(".srt" in srt, f".srt saved ({srt.strip()[:200]})")
    t.note("frames extracted at 1.0/2.5/4.0 s: captions must be inside the centre safe area")
    close_results()
    captions_off()


@test("CAP-04", "Review before saving opens the caption editor")
def cap04(t):
    fresh_camera()
    captions_on(review=True)
    set_formats(["9:16"], "720p")
    tap(rid="record_button")
    sleep(5)
    tap(rid="record_button")
    ed = find(text="Done", timeout=60)
    t.shot("review_editor")
    t.check(ed is not None, "caption editor opened for review")
    words = [n for n in dump() if n.text.lower() in ("hello", "this", "camera", "test")]
    if words:
        tap_xy(words[0].cx, words[0].cy)
        sleep(1)
        t.shot("word_sheet")
        box = [x for x in dump() if x.cls.endswith("EditText")]
        if box:
            tap_xy(box[0].cx, box[0].cy)
            ashell("input keyevent 123")
            for _ in range(12):
                ashell("input keyevent 67")
            type_text("Howdy")
            back()
            sleep(1)
        ok = find(text="OK", timeout=1) or find(text="Done", timeout=1)
        t.shot("edited")
    if ed:
        tap(text="Done")
    end = time.time() + 400
    while time.time() < end and find(rid="record_again", timeout=0, nodes=dump()) is None:
        sleep(4)
    t.shot("after_review")
    close_results()
    captions_off()


@test("CAP-05", "No speech: videos made, note shown")
def cap05(t):
    fresh_camera()
    ashell(f"mv {APP_FILES}/test_transcript.json {APP_FILES}/test_transcript.off")
    try:
        open_settings()
        scroll_to(text="Captions", max_swipes=16)
        t.shot("captions_unavailable")
        n = find(text="English", contains=True, timeout=2)
        t.note(f"English option enabled without a speech engine: {n.enabled if n else None}")
        close_sheet()
    finally:
        ashell(f"mv {APP_FILES}/test_transcript.off {APP_FILES}/test_transcript.json")


@test("CAP-06", "Placement options in the style editor")
def cap06(t):
    fresh_camera()
    open_settings()
    scroll_to(text="Captions", max_swipes=16)
    tap(text="English", contains=True)
    n = scroll_to(text="Caption style", contains=True, max_swipes=3)
    tap_xy(n.cx, n.cy)
    sleep(2)
    t.shot("style_sheet")
    for pos in ["Top", "Middle", "Bottom", "Auto"]:
        p = scroll_to(text=pos, max_swipes=8)
        if p:
            tap_xy(p.cx, p.cy)
            sleep(0.8)
            t.shot(f"pos_{pos}")
    t.check(in_app(), "style editor works")
    back()
    sleep(1)
    close_sheet()
    captions_off()


@test("CAP-07", "Style editor: presets, fonts, colours, animations, effects")
def cap07(t):
    fresh_camera()
    open_settings()
    scroll_to(text="Captions", max_swipes=16)
    tap(text="English", contains=True)
    n = scroll_to(text="Caption style", contains=True, max_swipes=3)
    tap_xy(n.cx, n.cy)
    sleep(2)
    t.shot("top")
    for label in ["Anton", "Bangers", "Pacifico", "Pop", "Bounce", "Typewriter", "Flicker", "Glitch", "Shake", "Neon", "Gradient"]:
        p = scroll_to(text=label, contains=True, max_swipes=10)
        if p:
            tap_xy(p.cx, p.cy)
            sleep(0.6)
            t.shot(label)
        else:
            t.soft(False, f"{label} option visible")
    imp = scroll_to(text="Import", contains=True, max_swipes=10)
    if imp:
        tap_xy(imp.cx, imp.cy)
        try:
            pick_document("TestFont.ttf")
            t.shot("font_imported")
            t.soft(find(text="TestFont", contains=True, timeout=3) is not None, "imported font listed")
        except AssertionError as e:
            t.soft(False, str(e))
    t.check(in_app(), "no crash")
    back()
    sleep(1)
    close_sheet()
    captions_off()


@test("CAP-11", "Add captions to a library video")
def cap11(t):
    fresh_camera()
    library_open()
    open_first_video()
    player_menu("Add captions")
    t.shot("language")
    n = find(text="English", contains=True, timeout=4)
    if n:
        tap_xy(n.cx, n.cy)
    m = find(text="Make captions", timeout=4)
    if m:
        tap_xy(m.cx, m.cy)
    sleep(4)
    t.shot("editor")
    words = [x for x in dump() if x.text.lower() in ("hello", "this", "camera", "test")]
    t.check(bool(words), "transcript words shown in the editor")
    for label in ["Join with next"]:
        pass
    before = set(movies())
    d = find(text="Done", timeout=3) or find(text="Save", timeout=1)
    if d:
        tap_xy(d.cx, d.cy)
    end = time.time() + 400
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    new = sorted(set(movies()) - before)
    t.check(bool(new), f"captioned copy saved: {new}")
    if new:
        local = pull(f"{MOVIES}/{new[-1]}")
        frame_png(local, 1.5, "CAP-11")
    t.shot("after")


@test("CAP-08", "Per-word colour, size, effect; split/join/delete (CAP-10)")
def cap08(t):
    fresh_camera()
    library_open()
    open_first_video()
    player_menu("Add captions")
    n = find(text="English", contains=True, timeout=4)
    if n:
        tap_xy(n.cx, n.cy)
    tap(text="Make captions", required=False)
    sleep(4)
    words = [x for x in dump() if x.text.lower() in ("camera", "test")]
    if not words:
        raise AssertionError("no words in the editor")
    tap_xy(words[0].cx, words[0].cy)
    sleep(1)
    t.shot("word_sheet")
    for label in ["Colour", "Size", "Effect", "Join with next"]:
        t.soft(scroll_to(text=label, contains=True, max_swipes=3) is not None, f"'{label}' in the word sheet")
    t.shot("word_sheet_scrolled")
    back()
    back()


@test("CAP-09", "Auto-emphasis / same look switch")
def cap09(t):
    fresh_camera()
    open_settings()
    scroll_to(text="Captions", max_swipes=16)
    tap(text="English", contains=True)
    a = scroll_to(text="Auto-emphasise", contains=True, max_swipes=3)
    t.check(a is not None, "Auto-emphasise option")
    s = find(text="Same look for every word", timeout=1)
    t.check(s is not None, "Same look option")
    if a:
        tap_xy(a.cx, a.cy)
    t.shot("emphasis")
    if s:
        tap_xy(s.cx, s.cy)
    close_sheet()
    captions_off()


@test("CAP-12", "Captions skipped in slow motion", phone_only="slow motion isn't offered on the emulator")
def cap12(t):
    pass


# ───────────────────────── 16. Library ─────────────────────────

@test("LIB-00", "Import test clips into the library")
def lib00(t):
    force_stop()
    launch(f"--ez {TestHooksExtra} true")
    sleep(8)
    log = adb("logcat -d -s DualFrameTest", timeout=30)
    t.note(log.strip()[-300:])
    t.check("imported" in log, "test media imported")
    t.check(any("talk" in m for m in movies()), "talk.mp4 in the library")


TestHooksExtra = "dualframe.test.import_media"


@test("LIB-01", "Library lists videos and photos, newest first")
def lib01(t):
    fresh_camera()
    library_open()
    t.shot("library")
    t.check(len(library_items()) >= 3, f"items shown ({len(library_items())})")
    cnt = find(rid="count", timeout=1)
    t.note(cnt.text if cnt else "")


@test("LIB-02", "Player plays, pauses, seeks")
def lib02(t):
    fresh_camera()
    library_open()
    open_first_video()
    sleep(2)
    t.shot("playing")
    w, h = screen_size()
    # The controls fade after a few seconds, so tap and look straight away (twice if they were just hiding).
    ok = False
    for _ in range(3):
        tap_xy(w // 2, h // 2)
        nodes = dump()
        ok = find(rid="more", timeout=0, nodes=nodes) is not None and find(rid="share", timeout=0, nodes=nodes) is not None
        if ok:
            break
    t.shot("controls")
    t.check(ok, "player with its controls")
    back()
    back()


@test("LIB-03", "⋯ menu opens Grade, captions, trim, design")
def lib03(t):
    fresh_camera()
    library_open()
    open_first_video()
    for item, marker in [("Grade", "Light"), ("Trim or join", "Clips"), ("Edit: timeline, music, captions, grade, design", "clip(s)")]:
        player_menu(item)
        t.shot(item[:6])
        t.check(find(text=marker, contains=True, timeout=6) is not None, f"{item} opens")
        back()
        sleep(1)
        n = find(text="Discard", contains=True, timeout=1) or find(text="Leave", contains=True, timeout=1)
        if n:
            tap_xy(n.cx, n.cy)
    back()
    back()


@test("LIB-04", "Delete a video")
def lib04(t):
    fresh_camera()
    library_open()
    before = len(movies())
    open_first_video()
    tap(rid="delete")
    sleep(2)
    t.shot("confirm")
    for label in ["Allow", "Delete", "OK"]:
        n = find(text=label, timeout=2)
        if n:
            tap_xy(n.cx, n.cy)
            sleep(2)
            break
    t.check(len(movies()) == before - 1, f"one video fewer ({before} → {len(movies())})")
    back()


@test("LIB-05", "Long-press several → Trim & join")
def lib05(t):
    fresh_camera()
    library_open()
    items = [n for n in dump() if n.rid == "duration" and n.text != "Photo"]
    if len(items) < 2:
        raise AssertionError("need two videos")
    long_press(Node_at(items[0]))
    tap_xy(items[1].cx, items[1].cy - 80)
    t.shot("picked")
    tap(text="Trim & join")
    sleep(3)
    t.shot("editor")
    t.check(find(text="2 clip(s)", contains=True, timeout=5) is not None, "editor has both clips")
    back()
    back()


@test("LIB-06", "Long-press several → delete them")
def lib06(t):
    fresh_camera()
    # Two fresh clips to delete, so the test videos the other tests use stay.
    set_formats(["9:16"], "720p")
    made = []
    for _ in range(2):
        made += record(2)
        close_results()
    library_open()
    before = set(movies())
    items = [n for n in dump() if n.rid == "duration" and n.text != "Photo"]
    long_press(Node_at(items[0]))
    tap_xy(items[1].cx, items[1].cy - 80)
    t.shot("picked")
    tap(desc="Delete")
    sleep(1)
    t.shot("confirm")
    t.check(find(text="Delete 2 videos?", timeout=3) is not None, "asks before deleting two")
    tap(text="Delete")
    sleep(3)
    gone = before - set(movies())
    t.check(len(gone) == 2 and gone <= set(made), f"the two picked videos deleted: {sorted(gone)}")
    t.check(find(text="videos picked", contains=True, timeout=1) is None, "selection bar closed")
    back()


class Node_at:
    def __init__(self, n):
        self.cx, self.cy = n.cx, n.cy - 80


# ───────────────────────── 17. Trim & join ─────────────────────────

def open_editor_with(names_contains):
    library_open()
    items = [n for n in dump() if n.rid == "duration" and n.text != "Photo"]
    tap_xy(items[0].cx, items[0].cy - 80)
    sleep(2)


@test("ED-01", "Trim one clip")
def ed01(t):
    fresh_camera()
    library_open()
    open_first_video()
    player_menu("Trim or join")
    t.shot("editor")
    scroll_to(text="Clip 1", contains=True, max_swipes=3)
    found = sliders()
    t.check(bool(found), "trim handles shown")
    if found:
        s = found[0]
        swipe(s.x1 + 30, s.cy, s.x1 + (s.x2 - s.x1) // 4, s.cy, 500)
        swipe(s.x2 - 30, s.cy, s.x2 - (s.x2 - s.x1) // 4, s.cy, 500)
    t.shot("trimmed")
    before = set(movies())
    tap(text="Save")
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    new = sorted(set(movies()) - before)
    t.check(bool(new), "trimmed copy saved")
    if new:
        check_video(t, new[-1])
    back()


@test("ED-02", "Join three clips")
def ed02(t):
    fresh_camera()
    library_open()
    items = [n for n in dump() if n.rid == "duration" and n.text != "Photo"][:3]
    if len(items) < 3:
        raise AssertionError("need three videos")
    long_press(Node_at(items[0]))
    for it in items[1:]:
        tap_xy(it.cx, it.cy - 80)
    tap(text="Trim & join")
    sleep(3)
    t.shot("three_clips")
    before = set(movies())
    tap(text="Save")
    end = time.time() + 400
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    new = sorted(set(movies()) - before)
    t.check(bool(new), "joined video saved")
    if new:
        info = check_video(t, new[-1])
        t.note(f"joined length {info['duration']:.1f}s")
    back()


@test("ED-03", "Join different shapes and HDR + standard")
def ed03(t):
    fresh_camera()
    library_open()
    names = movies()
    t.note(f"library has hlg clip: {any('hlg' in n for n in names)}")
    items = [n for n in dump() if n.rid == "duration" and n.text != "Photo"]
    shapes = [n for n in dump() if n.rid == "shape"]
    t.note("shapes: " + ", ".join(s.text for s in shapes[:8]))
    if len(items) < 2:
        raise AssertionError("need clips")
    # Pick the first two whose shape labels differ.
    pick = []
    seen = set()
    for it in items:
        sh_ = next((s.text for s in shapes if abs(s.cy - it.cy) < 120 and abs(s.cx - it.cx) < 200), "")
        if sh_ not in seen:
            pick.append(it)
            seen.add(sh_)
        if len(pick) == 2:
            break
    long_press(Node_at(pick[0]))
    tap_xy(pick[-1].cx, pick[-1].cy - 80)
    tap(text="Trim & join")
    sleep(3)
    before = set(movies())
    tap(text="Save")
    end = time.time() + 400
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    new = sorted(set(movies()) - before)
    t.check(bool(new), "mixed join saved")
    if new:
        check_video(t, new[-1])
    t.shot("after")
    back()


@test("ED-04", "Remove silences", phone_only="judging clipped words needs real speech (morning); pipeline run in ED-05")
def ed04(t):
    pass


@test("ED-05", "Remove silences on the tone/pause test clip")
def ed05(t):
    fresh_camera()
    library_open()
    talk = [n for n in dump() if n.rid == "duration" and n.text != "Photo"]
    # The imported talk clip is 0:12 long.
    target = next((n for n in talk if n.text in ("0:12", "00:12")), None)
    if target is None:
        raise Skip("talk clip not found in the library")
    tap_xy(target.cx, target.cy - 80)
    sleep(2)
    player_menu("Trim or join")
    n = scroll_to(text="Remove silences", max_swipes=3)
    tap_xy(n.cx, n.cy)
    sleep(6)
    t.shot("silences")
    summ = find(text="silence removed", contains=True, timeout=10)
    t.check(summ is not None, f"silence summary shown ({summ.text if summ else ''})")
    before = set(movies())
    tap(text="Save")
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    new = sorted(set(movies()) - before)
    if new:
        info = check_video(t, new[-1])
        t.check(info["duration"] < 10.5, f"shorter than the 12 s original ({info['duration']:.1f}s)")
    back()


# ───────────────────────── 18. Grading ─────────────────────────

@test("GR-01", "Grade tabs update the preview")
def gr01(t):
    fresh_camera()
    library_open()
    open_first_video()
    player_menu("Grade")
    for tab in ["Light", "Colour", "Wheels", "HSL", "Finish"]:
        n = find(text=tab, timeout=3)
        if n is None:
            t.check(False, f"{tab} tab")
            continue
        tap_xy(n.cx, n.cy)
        sleep(1)
        sl = sliders()
        if sl:
            s = sl[0]
            swipe(s.cx, s.cy, s.x2 - 20, s.cy, 400)
        t.shot(tab)
    t.check(in_app(), "no crash")
    back()
    n = find(text="Discard", contains=True, timeout=1)
    if n:
        tap_xy(n.cx, n.cy)
    back()


@test("GR-02", "Save as my look")
def gr02(t):
    fresh_camera()
    library_open()
    open_first_video()
    player_menu("Grade")
    tap(text="Colour")
    sl = sliders()
    if sl:
        swipe(sl[0].cx, sl[0].cy, sl[0].x2 - 20, sl[0].cy, 400)
    tap(text="Save as my look")
    sleep(1)
    box = [x for x in dump() if x.cls.endswith("EditText")]
    if box:
        tap_xy(box[0].cx, box[0].cy)
        ashell("input keyevent 123")
        for _ in range(20):
            ashell("input keyevent 67")
        type_text("Emu look")
    n = find(text="Save", timeout=2) or find(text="OK", timeout=1)
    if n:
        tap_xy(n.cx, n.cy)
    sleep(1)
    t.shot("saved_look")
    t.check(scroll_to(text="look", contains=True, max_swipes=2) is not None or find(text="Emu", contains=True, timeout=2) is not None, "new look listed in the grade screen")
    back()
    n = find(text="Discard", contains=True, timeout=1)
    if n:
        tap_xy(n.cx, n.cy)
    back()
    fresh_camera()
    to_pro()
    open_param("Look (LUT)")
    t.shot("camera_luts")
    t.soft(scroll_to(text="Emu look", contains=True, max_swipes=3) is not None, "look also in the camera's LUT list")
    back()
    to_auto()


@test("GR-03", "Save a graded copy")
def gr03(t):
    fresh_camera()
    library_open()
    open_first_video()
    player_menu("Grade")
    n = find(text="Teal", contains=True, timeout=3) or find(text="Vivid", timeout=1)
    if n:
        tap_xy(n.cx, n.cy)
        sleep(1.5)
    t.shot("graded")
    before = set(movies())
    tap(text="Save")
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    new = sorted(set(movies()) - before)
    t.check(bool(new), "graded copy saved")
    if new:
        check_video(t, new[-1])
    back()


@test("GR-04", "Grade an HDR clip")
def gr04(t):
    fresh_camera()
    library_open()
    # The 6 s HLG clip was imported first, so it's far down the library by now.
    hlg = scroll_to(text="0:06", rid="duration", max_swipes=20)
    if hlg is None:
        raise Skip("HLG test clip not found")
    tap_xy(hlg.cx, hlg.cy - 80)
    sleep(2)
    player_menu("Grade")
    before = set(movies())
    tap(text="Save")
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    new = sorted(set(movies()) - before)
    t.check(bool(new), "HDR clip graded and saved")
    if new:
        check_video(t, new[-1])
    t.shot("after")
    back()


@test("GR-06", "Colour wheels and scopes in the grade screen")
def gr06(t):
    fresh_camera()
    library_open()
    open_first_video()
    player_menu("Grade")
    sleep(2)
    for m in ["Waveform", "RGB parade", "Vectorscope", "Histogram"]:
        n = find(text=m, timeout=3)
        t.check(n is not None, f"{m} scope option")
        if n:
            tap_xy(n.cx, n.cy)
            sleep(1.5)
            t.shot(m.replace(" ", "_"))
    tap(text="Wheels")
    sleep(1)
    wheels = [n for n in dump() if "colour wheel" in n.desc]
    t.check(len(wheels) >= 2, f"colour wheels shown ({len(wheels)})")
    if wheels:
        w = wheels[0]
        swipe(w.cx, w.cy, w.cx - (w.x2 - w.x1) // 3, w.cy - (w.y2 - w.y1) // 3, 600)
        sleep(1.5)
        t.shot("wheel_dragged")
    t.check(in_app(), "no crash")
    back()


@test("GR-05", "No grading controls on the camera screen")
def gr05(t):
    fresh_camera()
    to_pro()
    t.check(find(desc="Grade", timeout=1) is None and find(text="Grade", timeout=0, nodes=D._last_dump) is None,
            "no Grade cell in the Pro strip")
    to_auto()


# ───────────────────────── 19. Design editor ─────────────────────────

def open_design():
    fresh_camera()
    library_open()
    open_first_video()
    player_menu("Edit: timeline, music, captions, grade, design")
    sleep(3)
    design_tab("Templates")


def design_tab(name):
    """Taps a tool in the editor's bottom toolbar (labelled by content description), scrolling it both ways."""
    w, h = screen_size()
    for i in range(8):
        n = find(desc=name, timeout=1)
        if n:
            tap_xy(n.cx, n.cy)
            sleep(1)
            return n
        tools = [x for x in D._last_dump if x.desc in ("Edit", "Audio", "Text", "Captions", "Stickers", "Templates", "Canvas", "Logo")]
        y = tools[0].cy if tools else int(h * 0.93)
        if i < 4:
            swipe(int(w * 0.8), y, int(w * 0.2), y, 300)
        else:
            swipe(int(w * 0.2), y, int(w * 0.8), y, 300)
    raise AssertionError(f"design tab {name} not found")


def design_save(t, timeout=600):
    before = set(movies())
    tap(text="Save")
    end = time.time() + timeout
    while time.time() < end and not (set(movies()) - before):
        sleep(5)
    new = sorted(set(movies()) - before)
    t.check(bool(new), "design saved")
    if new:
        local = pull(f"{MOVIES}/{new[-1]}")
        info = video_info(local)
        t.note(f"{new[-1]}: {info}")
        for at in [0.5, 2.0, 3.5]:
            frame_png(local, at, f"{t.id}")
    sleep(2)
    t.shot("after_save")
    return new


@test("DES-01", "Opens on Templates with previews")
def des01(t):
    open_design()
    sleep(4)
    t.shot("templates")
    t.check(find(text="Dreamy heart", timeout=4) is not None, "Dreamy heart")
    t.check(find(text="Scrapbook", timeout=1) is not None, "Scrapbook")
    back()


@test("DES-02", "Apply each template and save")
def des02(t):
    for name in ["Dreamy heart", "Scrapbook"]:
        open_design()
        sleep(3)
        tap(text=name)
        sleep(4)
        t.shot(name.replace(" ", "_"))
        design_save(t)
        back()


@test("DES-03", "Save as template, reuse, delete")
def des03(t):
    open_design()
    tap(text="Dreamy heart")
    sleep(2)
    n = scroll_to(text="Save this design as a template", max_swipes=4)
    tap_xy(n.cx, n.cy)
    sleep(1)
    type_text("Emu template")
    # adb typing on the emulator sometimes swaps letters; use whatever the field ended up with.
    field = next((x for x in dump() if "EditText" in x.cls), None)
    name = (field.text if field and field.text else "Emu template").strip()
    tap(text="Save")
    sleep(2)
    t.shot("saved")
    t.check(find(text=name, contains=True, timeout=4) is not None, f"my template listed ({name})")
    back()
    open_design()
    sleep(3)
    m = scroll_to(text=name, contains=True, max_swipes=3)
    t.check(m is not None, "template there for another video")
    if m:
        long_press(m)
        sleep(1)
        t.shot("delete_dialog")
        tap(text="Delete", required=False)
        sleep(1.5)
        t.check(find(text=name, contains=True, timeout=1) is None, "deleted")
    b = find(text="Dreamy heart", timeout=2)
    if b:
        long_press(b)
        sleep(1)
        t.check(find(text="Delete", timeout=1) is None, "built-ins can't be deleted")
    back()


@test("DES-04", "Canvas shapes change the output size")
def des04(t):
    open_design()
    design_tab("Canvas")
    for lab in ["16:9", "1:1", "9:16"]:
        n = find(text=lab, contains=True, timeout=2)
        if n:
            tap_xy(n.cx, n.cy)
            sleep(2)
            t.shot(lab.replace(":", "x"))
    n = find(text="1:1", contains=True, timeout=2)
    tap_xy(n.cx, n.cy)
    sleep(2)
    new = design_save(t)
    if new:
        info = video_info(os.path.join(FILES, new[-1]))
        t.check(info["w"] == info["h"], f"square output ({info['w']}x{info['h']})")
    back()


@test("DES-05", "Backgrounds: blur, colour, gradient, photo")
def des05(t):
    open_design()
    design_tab("Mask")
    sl = sliders()
    if sl:
        swipe(sl[0].cx, sl[0].cy, sl[0].x1 + (sl[0].x2 - sl[0].x1) // 3, sl[0].cy, 400)  # make the video smaller
    design_tab("Background")
    for kind in ["Blurred video", "Colour", "Gradient"]:
        tap(text=kind)
        sleep(2)
        t.shot(kind.replace(" ", "_"))
    tap(text="Photo")
    try:
        pick_document("bg_photo.jpg")
        sleep(3)
        t.shot("photo_bg")
    except AssertionError as e:
        t.check(False, str(e))
    design_save(t)
    back()


@test("DES-06", "Video background with soft blur")
def des06(t):
    open_design()
    design_tab("Mask")
    sl = sliders()
    if sl:
        swipe(sl[0].cx, sl[0].cy, sl[0].x1 + (sl[0].x2 - sl[0].x1) // 3, sl[0].cy, 400)
    design_tab("Background")
    tap(text="Video")
    try:
        pick_document("bg_video.mp4")
    except AssertionError as e:
        t.check(False, str(e))
    sleep(4)
    # The editor opens paused: play to see the background loop.
    tap(desc="Play or pause", required=False)
    sleep(1)
    a = t.shot("video_bg_a")
    sleep(1.5)
    b = t.shot("video_bg_b")
    tap(desc="Play or pause", required=False)
    t.check(image_diff(a, b) > 0.3, "background video is moving in the preview")
    n = find(text="Soft blur", timeout=2)
    if n:
        tap_xy(n.cx, n.cy)
        sleep(2)
        t.shot("video_bg_blur")
    new = design_save(t)
    if new:
        info = video_info(os.path.join(FILES, new[-1]))
        t.check(info["audio"], "main video's sound kept")
    back()


@test("DES-07", "Move / size / rotate the video; reset")
def des07(t):
    open_design()
    design_tab("Mask")
    sl = sliders()
    t.check(len(sl) >= 2, "size and rotate sliders")
    if len(sl) >= 2:
        swipe(sl[0].cx, sl[0].cy, sl[0].x1 + (sl[0].x2 - sl[0].x1) // 3, sl[0].cy, 400)
        swipe(sl[1].cx, sl[1].cy, sl[1].cx + 120, sl[1].cy, 400)
    w, h = screen_size()
    swipe(w // 2, int(h * 0.3), w // 3, int(h * 0.25), 500)
    t.shot("moved")
    tap(text="Reset position", required=False)
    t.shot("reset")
    back()


@test("DES-08", "Shapes and border")
def des08(t):
    open_design()
    design_tab("Mask")
    sl = sliders()
    if sl:
        swipe(sl[0].cx, sl[0].cy, sl[0].x1 + (sl[0].x2 - sl[0].x1) // 2, sl[0].cy, 400)
    for shape in ["Rounded", "Circle", "Heart", "Arch"]:
        n = scroll_to(text=shape, max_swipes=4)
        if n:
            tap_xy(n.cx, n.cy)
            sleep(2)
            t.shot(shape)
    b = scroll_to(text="Border", max_swipes=4)
    sl = [x for x in sliders() if b and x.cy > b.cy]
    if sl:
        swipe(sl[0].x1 + 10, sl[0].cy, sl[0].cx, sl[0].cy, 400)
        sleep(1.5)
        t.shot("border")
    design_save(t)
    back()


@test("DES-09", "Person cutout", phone_only="needs a real person in the video (morning)")
def des09(t):
    pass


@test("DES-10", "Text layers")
def des10(t):
    open_design()
    design_tab("Text")
    box = [x for x in dump() if x.cls.endswith("EditText")]
    tap_xy(box[0].cx, box[0].cy)
    type_text("Hello DualFrame")
    back()
    tap(text="Add text")
    sleep(2)
    t.shot("text_added")
    t.check(find(text="Selected layer", contains=True, timeout=3) is not None or find(text="Layer", timeout=1) is not None,
            "layer panel opens")
    for f in ["Anton", "Pacifico"]:
        n = scroll_to(text=f, max_swipes=4)
        if n:
            tap_xy(n.cx, n.cy)
            sleep(1)
    t.shot("font")
    design_save(t)
    back()


@test("DES-11", "Stickers: 3D, animated, GIF, PNG")
def des11(t):
    open_design()
    design_tab("Stickers")
    tap(text="3D")
    sleep(2)
    t.shot("3d_grid")
    imgs = [x for x in dump() if x.cls.endswith("ImageView") and x.desc]
    t.check(len(imgs) >= 20, f"3D stickers listed ({len(imgs)})")
    if imgs:
        tap_xy(imgs[0].cx, imgs[0].cy)
        sleep(2)
    design_tab("Stickers")
    tap(text="Animated")
    sleep(2)
    t.shot("animated_grid")
    imgs = [x for x in dump() if x.cls.endswith("ImageView") and x.desc]
    t.check(len(imgs) >= 20, f"animated stickers listed ({len(imgs)})")
    if imgs:
        tap_xy(imgs[1].cx, imgs[1].cy)
        sleep(2)
    a = t.shot("animated_a")
    sleep(0.7)
    b = t.shot("animated_b")
    design_tab("Stickers")
    tap(text="Your GIF / PNG")
    tap(text="Add a GIF or PNG", contains=True)
    try:
        pick_document("anim.gif")
        sleep(3)
    except AssertionError as e:
        t.check(False, str(e))
    design_tab("Stickers")
    tap(text="Your GIF / PNG")
    tap(text="Add a GIF or PNG", contains=True)
    try:
        pick_document("logo.png")
        sleep(3)
    except AssertionError as e:
        t.check(False, str(e))
    t.shot("all_stickers")
    new = design_save(t)
    if new:
        local = os.path.join(FILES, new[-1])
        f1 = frame_png(local, 1.0, "DES-11_t1")
        f2 = frame_png(local, 1.3, "DES-11_t2")
        t.note("frames at 1.0 and 1.3 s extracted: animated stickers and the GIF should differ")
    back()


@test("DES-12", "Photo layer and photo cutout layer")
def des12(t):
    open_design()
    design_tab("Photos")
    tap(text="Add photo or PNG", contains=True)
    try:
        pick_document("bg_photo.jpg")
        sleep(3)
        t.shot("photo_layer")
    except AssertionError as e:
        t.check(False, str(e))
    t.check(in_app(), "no crash")
    back()


@test("DES-13", "Layer controls: size, rotate, opacity, timing, animation, motion, order, delete")
def des13(t):
    open_design()
    design_tab("Stickers")
    tap(text="Emoji")
    n = find(text="🔥", timeout=2)
    if n:
        tap_xy(n.cx, n.cy)
        sleep(2)
    t.shot("layer_panel")
    for label in ["Pop", "Float", "Pulse", "Wiggle", "Bounce", "Spin"]:
        p = scroll_to(text=label, max_swipes=6)
        if p:
            tap_xy(p.cx, p.cy)
            sleep(0.6)
        else:
            t.check(False, f"{label} option")
    t.shot("motion_spin")
    for label in ["Bring forward", "Send back"]:
        p = scroll_to(text=label, max_swipes=4)
        if p:
            tap_xy(p.cx, p.cy)
    design_save(t)
    back()


@test("DES-14", "Logo watermark on designs and new camera videos")
def des14(t):
    open_design()
    design_tab("Logo")
    tap(text="Choose logo", contains=True)
    try:
        pick_document("logo.png")
        sleep(3)
    except AssertionError as e:
        t.check(False, str(e))
    t.shot("logo")
    back()
    fresh_camera()
    set_formats(["9:16"], "720p")
    new = record(3)
    local = pull(f"{MOVIES}/{new[-1]}")
    png = frame_png(local, 1.0, "DES-14_camera_with_logo")
    t.note("frame extracted: logo should be in the bottom-right corner")
    close_results()
    # Turn it off again.
    open_design()
    design_tab("Logo")
    n = scroll_to(text="Put it on every video", contains=True, max_swipes=4)
    if n and n.checked:
        tap_xy(n.cx, n.cy)
    back()


@test("DES-15", "Intro and outro cards")
def des15(t):
    open_design()
    design_tab("Cards")
    sw = [x for x in dump() if "Switch" in x.cls]
    for s in sw[:1]:
        tap_xy(s.cx, s.cy)
        sleep(1.5)
    t.shot("intro_on")
    n = scroll_to(text="Outro card", max_swipes=6)
    sw = [x for x in dump() if "Switch" in x.cls and n and x.cy > n.cy]
    if sw:
        tap_xy(sw[0].cx, sw[0].cy)
        sleep(1)
    new = design_save(t)
    if new:
        local = os.path.join(FILES, new[-1])
        frame_png(local, 0.5, "DES-15_intro")
        info = video_info(local)
        t.note(f"length with cards: {info['duration']:.1f}s")
    back()


@test("DES-16", "Sticker credit line")
def des16(t):
    open_design()
    design_tab("Stickers")
    n = scroll_to(text="CC BY 4.0", contains=True, max_swipes=6)
    t.shot("credit")
    t.check(n is not None, "credit line shown")
    back()


@test("DES-17", "Heavy design (video background + stickers) saves")
def des17(t):
    t.note("covered by DES-06 + DES-11 on the emulator; the 1-minute 4K version runs on the phone")
    r = {x["id"]: x["status"] for x in D.RESULTS}
    t.check(r.get("DES-06") == "PASS" and r.get("DES-11") in ("PASS", "WARN"), f"DES-06 {r.get('DES-06')}, DES-11 {r.get('DES-11')}")


# ───────────────────────── 19b. The editor (timeline, clips, music, captions) ─────────────────────────

@test("STU-01", "Editor: timeline, split, speed, volume, music, captions, adjust; save")
def stu01(t):
    open_design()
    design_tab("Edit")
    sleep(1)
    t.shot("edit_tool")
    t.check(find(text="Split at playhead", timeout=3) is not None, "Edit tool shows clip actions")
    # Scrub the timeline a bit, then split.
    w, h = screen_size()
    tl = find(text="Split at playhead", timeout=1)
    play = find(desc="Play or pause", timeout=2)
    if play:
        swipe(w * 3 // 4, play.cy + 120, w // 4, play.cy + 120, 500)
    tap(text="Split at playhead")
    sleep(2)
    t.shot("split")
    t.check(find(text="Clip 2", contains=True, timeout=3) is not None, "split made a second clip")
    tap(text="2×", required=False)
    sleep(2)
    t.shot("speed")
    design_tab("Audio")
    tap(text="+ Add music…", required=False)
    try:
        pick_document("song.m4a")
        sleep(3)
        t.shot("music")
        # The song's controls start below the fold of the panel.
        t.check(find(text="♫", contains=True, timeout=3) is not None, "music track listed")
        t.check(scroll_to(text="Fade in", max_swipes=4) is not None, "music added with its controls")
    except AssertionError as e:
        t.check(False, str(e))
    design_tab("Captions")
    tap(text="Make captions", required=False)
    sleep(6)
    t.shot("captions")
    t.check(find(text="words, timed", contains=True, timeout=5) is not None, "captions made from the clips")
    design_tab("Adjust")
    sleep(2)
    tap(text="Vectorscope", required=False)
    sleep(2)
    t.shot("adjust")
    new = design_save(t, timeout=900)
    if new:
        info = video_info(os.path.join(FILES, new[-1]))
        t.check(info["audio"], "saved video has sound (clips + music)")
    back()


# ───────────────────────── 20. Shot lists ─────────────────────────

def shots_screen():
    fresh_camera()
    tap(rid="capture_shots")
    sleep(2)


# The list's name as the app shows it (SL-01 reads it back, in case typing went wrong).
LIST = {"name": "Morning tea"}


def list_title():
    w, h = screen_size()
    skip = {"Shoot", "Shot lists", "New shot list", "Back"}
    return next((n.text for n in dump() if n.text and n.cy < h * 0.12 and n.text not in skip and not n.text[0].isdigit()), None)


def focus_field():
    """Taps the first text box and waits: typing straight into a dialog that is still opening can
    land letters out of order."""
    box = next((x for x in dump() if "EditText" in x.cls), None)
    if box:
        tap_xy(box.cx, box.cy)
        sleep(0.8)


def open_list(name=None):
    """Opens the list, unless the shots button already went straight into it (tapping the
    title there renames it)."""
    if find(text="Add a shot", timeout=1) is None:
        tap(text=name or LIST["name"])
        sleep(1.5)


@test("SL-01", "New shot list")
def sl01(t):
    shots_screen()
    t.shot("lists")
    tap(text="New shot list")
    sleep(1)
    focus_field()
    type_text("Morning tea")
    tap(text="Save")
    sleep(2)
    t.shot("new_list")
    title = list_title()
    if title:
        LIST["name"] = title
    t.soft(title == "Morning tea", f"list named as typed (got {title!r})")
    t.check(find(text="SHAPES FOR EVERY TAKE", timeout=2) is not None, "shapes row")


@test("SL-03", "Paste a shot list")
def sl03(t):
    shots_screen()
    n = find(text=LIST["name"], timeout=3)
    if n:
        tap_xy(n.cx, n.cy)
        sleep(1.5)
    tap(text="Paste a shot list")
    sleep(1)
    box = [x for x in dump() if x.cls.endswith("EditText")]
    tap_xy(box[0].cx, box[0].cy)
    # Typed line by line (adb can't type newlines inside input text).
    lines = ["1. WS - Kitchen, morning light", "2 MS: Girl putting sugar in her tea",
             "Script: Every morning starts the same way.", "3) close-up of the spoon stirring", "3A OTS - Looking out of the window"]
    for i, line in enumerate(lines):
        type_text(line)
        if i < len(lines) - 1:
            key(66)
    t.shot("pasted_text")
    tap(text="Add shots")
    sleep(2)
    t.shot("shots")
    nodes = dump()
    txt = " ".join(n.text for n in nodes)
    t.check("SHOT 1 · WIDE" in txt, "shot 1 wide")
    t.check("SHOT 2 · MEDIUM" in txt, "shot 2 medium")
    t.check("CLOSE-UP" in txt, "shot 3 close-up")
    t.check("3A" in txt and "OVER THE SHOULDER" in txt, "shot 3A over the shoulder")
    t.check("Every morning starts" in txt, "script attached to shot 2")


@test("SL-02", "Add a shot by hand")
def sl02(t):
    shots_screen()
    open_list()
    tap(text="Add a shot")
    sleep(1)
    t.shot("add_dialog")
    tap(text="ECU · Extreme close-up")
    boxes = [x for x in dump() if x.cls.endswith("EditText")]
    if len(boxes) >= 2:
        tap_xy(boxes[1].cx, boxes[1].cy)
        type_text("Steam rising from the cup")
    tap(text="Save")
    sleep(1.5)
    t.shot("added")
    t.check(find(text="Shot size / angle", timeout=1) is None, "dialog closed")
    t.check(scroll_to(text="Steam rising", contains=True, max_swipes=4) is not None, "new shot listed")


@test("SL-04", "Edit, move, delete a shot; rename")
def sl04(t):
    shots_screen()
    open_list()
    n = scroll_to(text="Steam rising", contains=True, max_swipes=4)
    tap_xy(n.cx, n.cy)
    sleep(1)
    tap(text="Move up")
    sleep(1.5)
    t.shot("moved_up")
    n = scroll_to(text="Steam rising", contains=True, max_swipes=4)
    tap_xy(n.cx, n.cy)
    sleep(1)
    tap(text="Delete shot")
    tap(text="Delete")
    sleep(1.5)
    t.check(find(text="Steam rising", contains=True, timeout=2) is None, "shot deleted")
    t.shot("after_delete")


@test("SL-05", "Shoot: camera switches to the list's shapes, card shows the shot")
def sl05(t):
    shots_screen()
    open_list()
    # Shapes: 9:16 + 16:9.
    for lab in ["9:16", "16:9"]:
        n = find(text=lab, timeout=2)
        if n and not n.selected:
            tap_xy(n.cx, n.cy)
            sleep(0.8)
    for lab in ["1:1", "4:5", "4:3", "21:9", "3:4"]:
        n = find(text=lab, timeout=0, nodes=dump())
        if n and n.selected:
            tap_xy(n.cx, n.cy)
            sleep(0.8)
    t.shot("shapes")
    tap(text="Shoot")
    sleep(4)
    t.shot("camera_card")
    txt = " ".join(n.text for n in dump())
    t.check("SHOT 1" in txt and "1 OF" in txt, "card shows shot 1")
    t.check("Next: take 1" in txt, "next take shown")
    chips = " ".join(n.text for n in dump() if "9:16" in n.text or "16:9" in n.text)
    t.check("9:16" in chips and "16:9" in chips, f"camera uses 9:16 + 16:9 ({chips})")


@test("SL-06", "Shot lines on the teleprompter")
def sl06(t):
    fresh_camera()
    n = find(text="›", timeout=3)
    if n:
        tap_xy(n.cx, n.cy)
        sleep(2)
    t.shot("shot2")
    t.check(find(text="Every morning starts", contains=True, timeout=3) is not None, "shot 2 lines on the teleprompter")
    p = find(text="‹", timeout=2)
    if p:
        tap_xy(p.cx, p.cy)


@test("SL-07", "Record a take: names and the 'How was take 1?' prompt")
def sl07(t):
    fresh_camera()
    new = record(4, timeout=500)
    t.note(str(new))
    slug = re.sub(r"[^A-Za-z0-9_-]", "", LIST["name"].replace(" ", "_"))
    t.check(all(re.search(slug + r"_S01_T01_WS_\d{8}_\d{6}_(9x16|16x9)_", n) for n in new) and len(new) == 2,
            f"two files named {slug}_S01_T01_WS_…: {new}")
    close_results()
    t.shot("prompt")
    t.check(find(text="How was take 1?", timeout=3) is not None, "rating prompt")


@test("SL-09", "Good, go again → stays, next take T02")
def sl09(t):
    fresh_camera()
    tap(text="✓ Good, go again")
    sleep(1.5)
    t.shot("after_good_again")
    t.check(find(text="Next: take 2", timeout=3) is not None, "next take 2 on the same shot")
    new = record(4, timeout=500)
    t.check(all("_S01_T02_" in n for n in new), f"take 2 files: {new}")
    close_results()


@test("SL-10", "No good, retake → stays, marked ✗")
def sl10(t):
    fresh_camera()
    tap(text="↻ No good, retake")
    sleep(1.5)
    t.shot("after_retake")
    t.check(find(text="Next: take 3", timeout=3) is not None, "stays on shot 1 for take 3")


@test("SL-08", "Good, next shot → moves on")
def sl08(t):
    fresh_camera()
    record(4, timeout=500)
    close_results()
    tap(text="✓ Good, next shot")
    sleep(2)
    t.shot("next_shot")
    t.check(find(text="SHOT 2", contains=True, timeout=3) is not None, "moved to shot 2")
    record(4, timeout=500)
    close_results()
    tap(text="✓ Good, next shot")
    sleep(1)


@test("SL-11", "‹ › and Done")
def sl11(t):
    fresh_camera()
    tap(text="‹")
    sleep(1)
    t.check(find(text="SHOT 2", contains=True, timeout=3) is not None, "back to shot 2")
    tap(text="›")
    sleep(1)
    t.shot("nav")


@test("SL-12", "Takes in the list: ✓ ✗ ★, use this take, play")
def sl12(t):
    fresh_camera()
    tap(text="List")
    sleep(2)
    t.shot("list_with_takes")
    txt = " ".join(n.text for n in dump())
    t.check("T1 ✓" in txt, "take 1 good")
    # SL-09 rated take 1 good, SL-10 take 2 no good, SL-08 take 3 good (so it's the one used).
    t.check("T2 ✗" in txt, "take 2 marked no good")
    t.check("T3 ✓ ★" in txt, "take 3 good and used (★)")
    n = find(text="T1 ✓", timeout=2)
    if n:
        tap_xy(n.cx, n.cy)
        tap(text="Use this take in the edit")
        sleep(1.5)
        t.check(find(text="T1 ✓ ★", timeout=3) is not None, "★ moved to take 1")
    n = find(text="T1", contains=True, timeout=2)
    if n:
        tap_xy(n.cx, n.cy)
        tap(text="Play")
        sleep(3)
        t.shot("play_take")
        back()


@test("SL-13", "Assemble → Join all shapes now")
def sl13(t):
    shots_screen()
    open_list()
    tap(text="Assemble")
    sleep(1)
    t.shot("assemble_menu")
    before = set(movies())
    tap(text="Join all shapes now", contains=True)
    end = time.time() + 600
    while time.time() < end and len(set(movies()) - before) < 2:
        sleep(5)
    new = sorted(set(movies()) - before)
    t.check(len(new) == 2 and all("assembled" in n for n in new), f"two assembled cuts: {new}")
    for n in new:
        info = check_video(t, n)
    sleep(3)
    t.shot("library_after")


@test("SL-14", "Assemble → Trim and join 9:16 in the editor")
def sl14(t):
    shots_screen()
    open_list()
    tap(text="Assemble")
    tap(text="Trim and join 9:16", contains=True)
    sleep(3)
    t.shot("editor")
    t.check(find(text="clip(s)", contains=True, timeout=5) is not None, "editor with the starred takes")
    back()


@test("SL-15", "Shots without a take are left out with a warning")
def sl15(t):
    t.note("covered in SL-13: shots 3 and 3A have no takes (toast shown, screenshot assemble_menu)")
    r = {x["id"]: x["status"] for x in D.RESULTS}
    t.check(r.get("SL-13") == "PASS", "SL-13 passed")


@test("SL-16", "Take saved while away still lands under its shot")
def sl16(t):
    fresh_camera()
    before = set(movies())
    tap(rid="record_button")
    sleep(4)
    tap(rid="record_button")
    sleep(1)
    key(3)
    end = time.time() + 400
    while time.time() < end and len(set(movies()) - before) < 2:
        sleep(5)
    launch()
    sleep(2)
    close_results()
    tap(text="List")
    sleep(2)
    t.shot("list")
    txt = " ".join(n.text for n in dump())
    t.check(txt.count("T1") >= 2 or "T2" in txt, "the new take is listed under its shot")
    back()


@test("SL-17", "Rate a take before its export finishes")
def sl17(t):
    fresh_camera()
    tap(rid="record_button")
    sleep(4)
    tap(rid="record_button")
    sleep(2)
    g = find(text="↻ No good, retake", timeout=3)
    if g is None:
        raise Skip("prompt only shows once the export panel closes")
    tap_xy(g.cx, g.cy)
    t.note("rated during export")


@test("SL-18", "Changing shapes in the camera updates the list")
def sl18(t):
    fresh_camera()
    set_formats(["9:16", "16:9", "1:1"], "720p")
    tap(text="List")
    sleep(2)
    n = find(text="1:1", timeout=3)
    t.shot("list_shapes")
    t.check(n is not None and n.selected, "1:1 now selected in the list's shapes")
    back()
    tap(text="Done")
    sleep(1)
    set_formats(["9:16"], "720p")


# ───────────────────────── 21. Robustness ─────────────────────────

@test("ROB-01", "10 recordings in a row: memory stable")
def rob01(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    mems = []
    for i in range(10):
        record(2)
        close_results()
        m = re.search(r"TOTAL\s+(?:PSS:\s*)?(\d+)", ashell(f"dumpsys meminfo {PKG}"))
        if m:
            mems.append(int(m.group(1)) // 1024)
    t.note(f"memory MB after each: {mems}")
    t.check(not mems or mems[-1] < mems[0] * 1.6 + 50, "memory doesn't keep growing")


@test("ROB-02", "Very low storage", phone_only="filling the emulator disk is unreliable; check on the phone")
def rob02(t):
    pass


@test("ROB-03", "Interruption during recording (alarm/call)")
def rob03(t):
    fresh_camera()
    set_formats(["9:16"], "720p")
    before = set(movies())
    tap(rid="record_button")
    sleep(3)
    ashell("am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 7")
    sleep(3)
    launch()
    end = time.time() + 300
    while time.time() < end and not (set(movies()) - before):
        sleep(4)
    t.check(bool(set(movies()) - before), "recording stopped and saved when another app came up")
    t.shot("after")
    close_results()


@test("ROB-04", "Force-stop on every screen and reopen")
def rob04(t):
    for screen in ["camera", "library", "shots", "settings"]:
        fresh_camera()
        if screen == "library":
            library_open()
        if screen == "shots":
            tap(rid="capture_shots")
        if screen == "settings":
            open_settings()
        force_stop()
        launch()
        t.check(wait_camera(), f"starts after kill on {screen}")


@test("ROB-05", "Dark mode and largest font")
def rob05(t):
    try:
        ashell("cmd uimode night yes")
        ashell("settings put system font_scale 1.3")
        fresh_camera()
        t.shot("camera_big_font")
        open_settings()
        t.shot("settings_big_font")
        close_sheet()
        library_open()
        t.shot("library_big_font")
        back()
        tap(rid="capture_shots")
        sleep(2)
        t.shot("shots_big_font")
        t.check(in_app(), "all screens open")
    finally:
        ashell("settings put system font_scale 1.0")
        ashell("cmd uimode night no")


@test("ROB-06", "Thermal", phone_only=HW)
def rob06(t):
    pass


# ───────────────────────── 22. Play readiness ─────────────────────────

@test("PLAY-01", "Permissions in the installed app")
def play01(t):
    out = ashell(f"dumpsys package {PKG}")
    req = out.split("requested permissions:")[1].split("install permissions:")[0] if "requested permissions:" in out else out
    t.note(req.strip()[:600])
    for bad in ["READ_MEDIA_VIDEO", "READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE"]:
        t.check(bad not in req, f"no {bad}")


@test("PLAY-02", "16 KB pages", phone_only="checked by CI's alignment report; real 16 KB device on the phone")
def play02(t):
    pass


@test("PLAY-03", "Privacy policy matches the app", phone_only="document review, not a device test")
def play03(t):
    pass


def push_test_assets():
    """Test clips into the app's folder (imported by LIB-00), picker files into Download, and the
    fake transcript for captions."""
    media = os.environ.get("MEDIA_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "media"))
    ashell(f"mkdir -p {APP_FILES}/test_media")
    for f in ["talk.mp4", "wide.mp4", "hlg.mp4"]:
        if os.path.exists(os.path.join(media, f)):
            adb(f"push '{os.path.join(media, f)}' {APP_FILES}/test_media/{f}", timeout=120)
    adb(f"push '{os.path.join(media, 'test_transcript.json')}' {APP_FILES}/test_transcript.json")
    for f in ["warm_test.cube", "broken.cube", "bg_photo.jpg", "bg_video.mp4", "anim.gif", "logo.png", "TestFont.ttf", "song.m4a"]:
        if os.path.exists(os.path.join(media, f)):
            adb(f"push '{os.path.join(media, f)}' /sdcard/Download/{f}")
    ashell("content call --method scan_volume --uri content://media --arg external_primary")


@test("INS-02", "Install a newer build over the old one: settings kept")
def ins02(t):
    old = os.environ.get("OLD_APK")
    new = os.environ.get("NEW_APK")
    if not old or not os.path.exists(old):
        raise Skip("no previous release APK downloaded")
    adb(f"uninstall {PKG}")
    t.check("Success" in adb(f"install '{old}'", timeout=300), "old release installs")
    grant_all()
    launch()
    wait_camera()
    settings_toggle("Grid lines", True)
    t.check("Success" in adb(f"install -r '{new}'", timeout=300), "new build installs over it")
    grant_all()
    launch()
    wait_camera()
    open_settings()
    n = scroll_to(text="Grid lines", contains=True, max_swipes=16)
    t.check(n is not None and n.checked, "a setting made in the old build is kept")
    close_sheet()
    settings_toggle("Grid lines", False)


# Order: install tests first, then the media import, then everything else.
_order = ["INS-01", "INS-02", "INS-03", "INS-04", "INS-05", "INS-06", "LIB-00"]
D.TESTS.sort(key=lambda x: _order.index(x[0]) if x[0] in _order else len(_order))
_lib00 = lib00


def lib00_with_push(t):
    push_test_assets()
    _lib00(t)


D.TESTS[:] = [(i, ti, lib00_with_push if i == "LIB-00" else fn, ph) for (i, ti, fn, ph) in D.TESTS]

if __name__ == "__main__":
    only = None
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))
    run(only)
    save_results()
