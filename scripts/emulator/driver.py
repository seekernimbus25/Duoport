#!/usr/bin/env python3
"""
DualFrame emulator test driver.

Runs the parts of docs/TEST_PLAN.md that an emulator can check, over adb: taps through the
app with UI Automator dumps, takes screenshots, watches the crash log, and checks saved
videos with ffprobe. Writes out/results.json, out/REPORT.md and out/shots/*.png.

Helpers and the test runner; the tests themselves are in tests.py (the entry point):
    python3 tests.py [--only ID,ID]
"""
import json
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PKG = "com.dualframe.camera"
MAIN = f"{PKG}/.MainActivity"
OUT = os.path.abspath(os.environ.get("OUT_DIR", "out"))
SHOTS = os.path.join(OUT, "shots")
FILES = os.path.join(OUT, "files")
MOVIES = "/sdcard/Movies/DualFrame"
PICTURES = "/sdcard/Pictures/DualFrame"
APP_FILES = f"/sdcard/Android/data/{PKG}/files"

os.makedirs(SHOTS, exist_ok=True)
os.makedirs(FILES, exist_ok=True)


# region adb helpers

def sh(cmd, timeout=120, check=False):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"{cmd}: {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout


def adb(args, timeout=120, check=False):
    return sh(f"adb {args}", timeout=timeout, check=check)


def ashell(args, timeout=120):
    return adb(f"shell {args}", timeout=timeout)


def sleep(s):
    time.sleep(s)


class Node:
    def __init__(self, e):
        self.text = e.get("text") or ""
        self.desc = e.get("content-desc") or ""
        self.rid = (e.get("resource-id") or "").split("/")[-1]
        self.cls = e.get("class") or ""
        self.pkg = e.get("package") or ""
        self.clickable = e.get("clickable") == "true"
        self.enabled = e.get("enabled") == "true"
        self.checked = e.get("checked") == "true"
        self.selected = e.get("selected") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", e.get("bounds") or "[0,0][0,0]")
        self.x1, self.y1, self.x2, self.y2 = map(int, m.groups())

    @property
    def cx(self):
        return (self.x1 + self.x2) // 2

    @property
    def cy(self):
        return (self.y1 + self.y2) // 2

    @property
    def visible(self):
        return self.x2 > self.x1 and self.y2 > self.y1

    def __repr__(self):
        return f"<{self.rid or self.cls} '{self.text or self.desc}' [{self.x1},{self.y1},{self.x2},{self.y2}]>"


_last_dump = []


def dump(retries=4):
    global _last_dump
    for _ in range(retries):
        out = ashell("uiautomator dump /sdcard/ui.xml", timeout=40)
        if "dumped" in out:
            xml = ashell("cat /sdcard/ui.xml", timeout=20)
            try:
                root = ET.fromstring(xml[xml.find("<"):])
                _last_dump = [Node(e) for e in root.iter("node")]
            except ET.ParseError:
                sleep(1)
                continue
            # A system "isn't responding" dialog (often the emulator's launcher) hides the app.
            if any("isn't responding" in n.text or "keeps stopping" in n.text for n in _last_dump):
                wait = next((n for n in _last_dump if n.text in ("Wait", "Close app")), None)
                if wait is not None:
                    ashell(f"input tap {wait.cx} {wait.cy}")
                    sleep(1)
                    continue
            return _last_dump
        sleep(1)
    _last_dump = []
    return _last_dump


def screen_size():
    m = re.search(r"(\d+)x(\d+)", ashell("wm size").splitlines()[-1])
    return int(m.group(1)), int(m.group(2))


def density():
    m = re.search(r"(\d+)", ashell("wm density").splitlines()[-1])
    return int(m.group(1))


def match(n, text=None, desc=None, rid=None, contains=False):
    def eq(a, b):
        if b is None:
            return True
        # Case-blind: section headers are drawn in capitals (textAllCaps) and dump that way.
        return (b.lower() in a.lower()) if contains else a.lower() == b.lower()
    return eq(n.text, text) and eq(n.desc, desc) and (rid is None or n.rid == rid)


def find(text=None, desc=None, rid=None, contains=False, timeout=6, nodes=None):
    end = time.time() + timeout
    while True:
        for n in (nodes if nodes is not None else dump()):
            if n.visible and match(n, text, desc, rid, contains):
                return n
        if nodes is not None or time.time() > end:
            return None
        sleep(0.7)


def find_all(text=None, desc=None, rid=None, contains=False):
    return [n for n in dump() if n.visible and match(n, text, desc, rid, contains)]


def tap_xy(x, y):
    ashell(f"input tap {x} {y}")
    sleep(0.6)


def tap(text=None, desc=None, rid=None, contains=False, timeout=6, required=True):
    n = find(text, desc, rid, contains, timeout)
    if n is None:
        if required:
            raise AssertionError(f"not found: text={text} desc={desc} rid={rid}")
        return None
    tap_xy(n.cx, n.cy)
    return n


def long_press(n, ms=900):
    ashell(f"input swipe {n.cx} {n.cy} {n.cx} {n.cy} {ms}")
    sleep(0.6)


def swipe(x1, y1, x2, y2, ms=300):
    ashell(f"input swipe {x1} {y1} {x2} {y2} {ms}")
    sleep(0.6)


def scroll_to(text=None, desc=None, rid=None, contains=False, max_swipes=10, region=None):
    """Swipes up (content moves up) until the node shows. region = (x, ytop, ybottom)."""
    w, h = screen_size()
    x, top, bottom = region or (w // 2, int(h * 0.35), int(h * 0.85))
    for i in range(max_swipes + 1):
        n = find(text, desc, rid, contains, timeout=0, nodes=dump())
        # Anywhere comfortably on screen will do (not under the status or navigation bar).
        if n is not None and h * 0.07 <= n.cy <= h * 0.93:
            return n
        swipe(x, bottom, x, top, 400)
    return find(text, desc, rid, contains, timeout=1)


def scroll_tap(text=None, desc=None, rid=None, contains=False, max_swipes=10, region=None):
    n = scroll_to(text, desc, rid, contains, max_swipes, region)
    if n is None:
        raise AssertionError(f"not found after scrolling: {text or desc or rid}")
    tap_xy(n.cx, n.cy)
    return n


def back(force=False):
    """Back key. On the bare camera screen it would leave the app, so it's skipped there unless
    [force]: picking an option already closes a Pro panel, and tests often press Back after."""
    if not force:
        nodes = dump()
        rids = {n.rid for n in nodes if n.visible}
        if "record_button" in rids and not rids & {"control_panel", "result_panel", "export_panel"}:
            return
    ashell("input keyevent 4")
    sleep(0.8)


def key(code):
    ashell(f"input keyevent {code}")
    sleep(0.5)


def type_text(s):
    # adb input text needs spaces as %s and some characters escaped.
    safe = s.replace("\\", "\\\\").replace(" ", "%s").replace("'", "\\'").replace('"', '\\"') \
        .replace("&", "\\&").replace("(", "\\(").replace(")", "\\)").replace(";", "\\;").replace("<", "\\<").replace(">", "\\>")
    ashell(f"input text '{safe}'" if "'" not in s else f'input text "{safe}"')
    sleep(0.4)


def current_package():
    out = ashell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
    return out


def in_app():
    return PKG in current_package()


def launch(extra=""):
    ashell(f"am start -n {MAIN} {extra}", timeout=30)
    sleep(4)


def force_stop():
    ashell(f"am force-stop {PKG}")
    sleep(1)


def relaunch(extra=""):
    force_stop()
    launch(extra)


def grant_all():
    for p in ["CAMERA", "RECORD_AUDIO", "POST_NOTIFICATIONS"]:
        ashell(f"pm grant {PKG} android.permission.{p}")


def crash_log_clear():
    adb("logcat -b crash -c")


def crashes():
    out = adb("logcat -b crash -d", timeout=30)
    return out if PKG in out else ""


def anr():
    return find(text="isn't responding", contains=True, timeout=0, nodes=dump()) is not None


def list_files(path):
    out = ashell(f"ls -1 {path} 2>/dev/null")
    return sorted(l.strip() for l in out.splitlines() if l.strip() and "No such file" not in l)


def pull(path, name=None):
    local = os.path.join(FILES, name or os.path.basename(path))
    adb(f"pull '{path}' '{local}'", timeout=300)
    return local


def ffprobe(local):
    try:
        out = sh(f"ffprobe -v error -show_streams -show_format -of json '{local}'", timeout=60)
        return json.loads(out)
    except Exception:
        return {}


def video_info(local):
    info = ffprobe(local)
    v = next((s for s in info.get("streams", []) if s.get("codec_type") == "video"), {})
    a = next((s for s in info.get("streams", []) if s.get("codec_type") == "audio"), None)
    w, h = int(v.get("width", 0)), int(v.get("height", 0))
    rot = 0
    for sd in v.get("side_data_list", []) or []:
        if "rotation" in sd:
            rot = int(sd["rotation"])
    if (v.get("tags") or {}).get("rotate"):
        rot = int(v["tags"]["rotate"])
    if abs(rot) % 180 == 90:
        w, h = h, w
    return {
        "w": w, "h": h, "codec": v.get("codec_name"), "fps": v.get("r_frame_rate"),
        "avg_fps": v.get("avg_frame_rate"),
        "pix_fmt": v.get("pix_fmt"), "transfer": v.get("color_transfer"), "primaries": v.get("color_primaries"),
        "bitrate": int(v.get("bit_rate") or info.get("format", {}).get("bit_rate") or 0),
        "duration": float(info.get("format", {}).get("duration") or 0),
        "audio": a is not None, "streams": len(info.get("streams", [])),
    }


def frame_png(local, at=1.0, name=None):
    png = os.path.join(SHOTS, (name or os.path.basename(local)) + f"_frame{at:.0f}.png")
    sh(f"ffmpeg -y -v error -ss {at} -i '{local}' -frames:v 1 -vf scale=360:-2 '{png}'", timeout=60)
    return png if os.path.exists(png) else None


def screenshot(name):
    path = os.path.join(SHOTS, f"{name}.png")
    sh(f"adb exec-out screencap -p > '{path}'", timeout=30)
    return path


def image_diff(a, b, box=None):
    """Mean absolute pixel difference (0..255) between two screenshots, optionally in a box."""
    try:
        from PIL import Image, ImageChops, ImageStat
        ia, ib = Image.open(a).convert("L"), Image.open(b).convert("L")
        if box:
            ia, ib = ia.crop(box), ib.crop(box)
        ia, ib = ia.resize((180, 320)), ib.resize((180, 320))
        return ImageStat.Stat(ImageChops.difference(ia, ib)).mean[0]
    except Exception:
        return -1


def image_mean(path, box=None):
    try:
        from PIL import Image, ImageStat
        im = Image.open(path).convert("RGB")
        if box:
            im = im.crop(box)
        return ImageStat.Stat(im).mean
    except Exception:
        return [0, 0, 0]


# endregion

# region camera-screen helpers

def wait_camera(timeout=20):
    """Waits for the camera screen (record button) and a live preview."""
    n = find(rid="record_button", timeout=timeout)
    sleep(2)
    return n is not None


def dismiss_dialogs():
    """Closes anything that would block the camera screen (onboarding toasts, dialogs)."""
    for label in ["OK", "Got it", "Not now", "Close", "Dismiss"]:
        n = find(text=label, timeout=0, nodes=_last_dump or dump())
        if n is not None and n.pkg == PKG:
            tap_xy(n.cx, n.cy)


def preview_live(tag):
    """Two screenshots a second apart; the emulated camera moves, so a live preview differs."""
    w, h = screen_size()
    box = (0, int(h * 0.2), w, int(h * 0.6))
    # The emulated back scene drifts slowly, so look over a few seconds; a frozen preview stays at 0.
    a = screenshot(f"{tag}_live_a")
    d = 0.0
    for i in range(2):
        sleep(1.5)
        b = screenshot(f"{tag}_live_b")
        d = max(d, image_diff(a, b, box))
    return d, a


def movies():
    return [f for f in list_files(MOVIES) if f.endswith(".mp4")]


def pictures():
    return [f for f in list_files(PICTURES) if re.search(r"\.(jpe?g|png)$", f)]


def record(seconds=4, wait_export=True, timeout=420):
    """Taps record, waits, stops, and waits until the export has saved new files."""
    before = set(movies())
    tap(rid="record_button")
    sleep(seconds)
    tap(rid="record_button")
    if not wait_export:
        return []
    end = time.time() + timeout
    last_count = -1
    stable = 0
    while time.time() < end:
        sleep(4)
        nodes = dump()
        done = find(rid="result_panel", timeout=0, nodes=nodes) or find(rid="record_again", timeout=0, nodes=nodes)
        failed = find(text="Couldn", contains=True, timeout=0, nodes=nodes)
        new = sorted(set(movies()) - before)
        if failed:
            screenshot("export_failed_dialog")
            raise AssertionError("export failed dialog: " + " | ".join(n.text for n in nodes if n.text)[:400])
        if done and new:
            # Let the last file finish writing.
            if len(new) == last_count:
                stable += 1
                if stable >= 1:
                    return new
            last_count = len(new)
    raise AssertionError(f"export did not finish in {timeout}s; new files: {sorted(set(movies()) - before)}")


def close_results():
    n = find(rid="record_again", timeout=2)
    if n:
        tap_xy(n.cx, n.cy)
        sleep(1)


def open_settings():
    close_results()  # the "saved" card sits over the camera controls
    tap(rid="settings_button")
    sleep(1.5)


def close_sheet():
    back()
    sleep(1.5)


def set_formats(wanted, resolution="720p"):
    """Turns on exactly the shapes in [wanted] (labels like '9:16') at [resolution]."""
    open_settings()
    labels = ["9:16", "16:9", "4:5", "1:1", "3:4", "4:3", "21:9"]
    # Turn wanted ones on first (the sheet refuses to turn the last one off).
    for lab in wanted + [l for l in labels if l not in wanted]:
        n = scroll_to(text=f"{lab}   ·", contains=True, max_swipes=6)
        if n is None:
            continue
        want = lab in wanted
        if n.checked != want:
            tap_xy(n.cx, n.cy)
            sleep(0.4)
        if want and resolution:
            r = find(text=resolution, timeout=1, nodes=dump())
            # The resolution pills sit right under the switch.
            cands = [x for x in _last_dump if x.text == resolution and x.cy > n.cy and x.cy < n.cy + 300]
            if cands:
                tap_xy(cands[0].cx, cands[0].cy)
    close_sheet()


def settings_choice(section_text, choice_text, max_swipes=14):
    open_settings()
    scroll_to(text=section_text, max_swipes=max_swipes)
    n = scroll_to(text=choice_text, max_swipes=4)
    if n is None:
        close_sheet()
        raise AssertionError(f"setting '{choice_text}' not found under '{section_text}'")
    enabled = n.enabled
    if enabled:
        tap_xy(n.cx, n.cy)
    close_sheet()
    return enabled


def settings_toggle(title_start, on=True, max_swipes=16):
    open_settings()
    n = scroll_to(text=title_start, contains=True, max_swipes=max_swipes)
    if n is None:
        close_sheet()
        raise AssertionError(f"toggle '{title_start}' not found")
    if n.checked != on:
        tap_xy(n.cx, n.cy)
    close_sheet()


def to_pro():
    tap(rid="mode_pro")
    sleep(2)


def to_auto():
    tap(rid="mode_auto")
    sleep(2)


def open_param(title):
    """Pro strip cell by its title ('Recording profile', 'Look (LUT)', 'Frame rate'…)."""
    n = find(desc=title, timeout=4)
    if n is None:
        # The strip scrolls sideways.
        w, h = screen_size()
        strip = find(rid="pro_strip", timeout=2)
        y = strip.cy if strip else int(h * 0.15)
        for _ in range(4):
            swipe(int(w * 0.85), y, int(w * 0.15), y, 300)
            n = find(desc=title, timeout=1)
            if n:
                break
    if n is None:
        raise AssertionError(f"pro cell '{title}' not found")
    tap_xy(n.cx, n.cy)
    sleep(1)
    return n


def latest_files(names, n):
    return names[-n:]


def library_open():
    tap(rid="gallery_button")
    sleep(2.5)


def library_items():
    return find_all(rid="thumb")


def open_first_video():
    """Opens the newest non-photo item in the library."""
    items = [n for n in dump() if n.rid == "duration" and n.text and n.text != "Photo"]
    if not items:
        raise AssertionError("no videos in the library")
    tap_xy(items[0].cx, items[0].cy - 80)
    sleep(2.5)


def player_menu(item_text):
    tap(rid="more")
    sleep(1)
    tap(text=item_text)
    sleep(2.5)


def pick_document(filename, timeout=10):
    """Picks [filename] in the system file picker: searches for it and taps the first result."""
    end = time.time() + timeout
    while time.time() < end:
        nodes = dump()
        if any("documentsui" in n.pkg for n in nodes):
            break
        sleep(1)
    n = find(text=filename, timeout=2)
    if n is None:
        s = find(desc="Search", timeout=3)
        if s:
            tap_xy(s.cx, s.cy)
            sleep(1)
            type_text(filename.rsplit(".", 1)[0])
            key(66)
            sleep(3)
        n = find(text=filename, timeout=3)
        if n is None:
            # Grid view shows thumbnails without names: take the first result.
            items = [x for x in dump() if x.rid in ("item_root", "thumbnail", "icon_thumb", "icon_mime_lg", "item_icon")]
            if items:
                n = items[0]
            else:
                head = next((x for x in D_last() if x.text in ("Images", "Videos", "Documents", "Audio", "Files")), None)
                if head is not None:
                    tap_xy(head.x1 + 120, head.y2 + 150)
                    sleep(2)
                    return
    if n is None:
        screenshot(f"picker_missing_{filename}")
        raise AssertionError(f"file picker: {filename} not found")
    tap_xy(n.cx, n.cy)
    sleep(2)


def D_last():
    return _last_dump


def is_slider(n):
    return any(k in n.cls for k in ("SeekBar", "Slider", "ProgressBar")) and n.x2 - n.x1 > 200


def sliders():
    return [n for n in dump() if is_slider(n)]


# endregion

# region test framework

RESULTS = []
TESTS = []


class Skip(Exception):
    pass


class NeedsPhone(Exception):
    pass


class T:
    def __init__(self, tid):
        self.id = tid
        self.notes = []
        self.shots = []
        self.failed = False
        self.warn = False

    def shot(self, name):
        p = screenshot(f"{self.id}_{name}")
        self.shots.append(os.path.basename(p))
        return p

    def note(self, msg):
        self.notes.append(str(msg))

    def check(self, cond, msg):
        if cond:
            self.notes.append("✓ " + msg)
        else:
            self.notes.append("✗ " + msg)
            self.failed = True
        return cond

    def soft(self, cond, msg):
        if cond:
            self.notes.append("✓ " + msg)
        else:
            self.notes.append("⚠ " + msg)
            self.warn = True
        return cond


def test(tid, title, phone_only=False):
    def wrap(fn):
        TESTS.append((tid, title, fn, phone_only))
        return fn
    return wrap


def recover():
    """Puts the app back on the camera screen after a test, whatever state it's in."""
    for _ in range(3):
        if find(rid="record_button", timeout=1):
            return
        back()
    relaunch()
    grant_all()
    wait_camera()


def run(only=None):
    for tid, title, fn, phone_only in TESTS:
        if only and tid not in only:
            continue
        t = T(tid)
        status = "PASS"
        start = time.time()
        crash_log_clear()
        print(f"=== {tid} {title}", flush=True)
        try:
            if phone_only:
                raise NeedsPhone(phone_only if isinstance(phone_only, str) else "needs real camera hardware")
            fn(t)
            if t.failed:
                status = "FAIL"
            elif t.warn:
                status = "WARN"
        except NeedsPhone as e:
            status = "PHONE"
            t.note(str(e))
        except Skip as e:
            status = "SKIP"
            t.note(str(e))
        except Exception as e:
            status = "FAIL"
            t.note("error: " + str(e))
            t.note(traceback.format_exc(limit=3))
            try:
                t.shot("error")
                # What was on screen, for working out why a control wasn't found.
                with open(os.path.join(OUT, f"ui_{tid}.txt"), "w") as f:
                    for n in dump():
                        if n.visible and (n.text or n.desc or n.rid):
                            f.write(f"{n.cx},{n.cy} rid={n.rid} text={n.text!r} desc={n.desc!r} checked={n.checked}\n")
            except Exception:
                pass
        c = crashes()
        if c:
            status = "FAIL"
            t.note("CRASH:\n" + c[-3000:])
            with open(os.path.join(OUT, f"crash_{tid}.txt"), "w") as f:
                f.write(c)
        if status in ("FAIL",) or c:
            sh(f"adb logcat -d -t 600 > '{OUT}/logcat_{tid}.txt'", timeout=60)
        RESULTS.append({"id": tid, "title": title, "status": status, "notes": t.notes, "shots": t.shots,
                        "secs": round(time.time() - start, 1)})
        print(f"--- {tid}: {status}  " + " | ".join(t.notes)[:600], flush=True)
        save_results()
        if status != "PHONE":
            try:
                recover()
            except Exception as e:
                print("recover failed: " + str(e), flush=True)


def save_results():
    with open(os.path.join(OUT, "results.json"), "w") as f:
        json.dump(RESULTS, f, indent=1, ensure_ascii=False)
    counts = {}
    for r in RESULTS:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    lines = ["# Emulator test report", "", "  ".join(f"**{k}** {v}" for k, v in sorted(counts.items())), "",
             "| ID | Status | Title | Notes |", "|---|---|---|---|"]
    for r in RESULTS:
        notes = "<br>".join(n.replace("|", "/").replace("\n", " ")[:300] for n in r["notes"][:8])
        lines.append(f"| {r['id']} | {r['status']} | {r['title']} | {notes} |")
    with open(os.path.join(OUT, "REPORT.md"), "w") as f:
        f.write("\n".join(lines) + "\n")


# endregion

