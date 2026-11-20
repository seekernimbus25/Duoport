#!/usr/bin/env python3
"""Real-device-UI emulator acceptance for the single installed Studio + Duoport APK."""
import json,os,sys,time,traceback
sys.path.insert(0,os.path.dirname(__file__))
import driver as D
PKG="com.dualframe.camera"
D.MAIN=PKG+"/app.filmmaking.studio.MainActivity"
OUT=os.environ.get("OUT_DIR",os.path.join(os.path.dirname(__file__),"combined_out"))
os.makedirs(OUT,exist_ok=True)
D.OUT=OUT;D.SHOTS=os.path.join(OUT,"shots");D.FILES=os.path.join(OUT,"files")
os.makedirs(D.SHOTS,exist_ok=True);os.makedirs(D.FILES,exist_ok=True)
rows=[]
def see(v,contains=False,timeout=12):
    x=D.find(text=v,contains=contains,timeout=timeout)
    if x is None: raise AssertionError(f"Missing {v}: "+repr([n.text for n in D.dump() if n.text][:35]))
    return x
def click(v,contains=False,timeout=12):
    n=see(v,contains,timeout);D.tap_xy(n.cx,n.cy)
def scroll_click(label,contains=False):
    node=D.find(text=label,contains=contains,timeout=2)
    w,h=D.screen_size()
    # Just off screen: nudge gently towards it (a full swipe can fling past a nearby target).
    for _ in range(4):
        if node is None or h*0.07<=node.cy<=h*0.93: break
        if node.cy>h*0.93: D.swipe(w//2,int(h*0.70),w//2,int(h*0.45),700)
        else: D.swipe(w//2,int(h*0.45),w//2,int(h*0.70),700)
        D.sleep(0.5);node=D.find(text=label,contains=contains,timeout=1)
    if node is None or not (h*0.07<=node.cy<=h*0.93):
        node=None
        # Slow quarter-screen steps can't fling past the target; search down, then back up.
        for up in (False,True):
            for _ in range(16):
                n=D.find(text=label,contains=contains,timeout=0,nodes=D.dump())
                if n is not None and h*0.07<=n.cy<=h*0.93: node=n;break
                if up: D.swipe(w//2,int(h*0.40),w//2,int(h*0.65),700)
                else: D.swipe(w//2,int(h*0.65),w//2,int(h*0.40),700)
                D.sleep(0.4)
            if node is not None: break
    if node is None:
        raise AssertionError("Cannot reach "+label+": "+repr([n.text for n in D.dump() if n.text][:30]))
    D.tap_xy(node.cx,node.cy)
def scroll_back_to(label,contains=False,max_swipes=10):
    """Content moves DOWN (finger swipes down) until an element above the viewport shows."""
    w,h=D.screen_size()
    for _ in range(max_swipes+1):
        n=D.find(text=label,contains=contains,timeout=0,nodes=D.dump())
        if n is not None and h*0.07<=n.cy<=h*0.93: return n
        D.swipe(w//2,int(h*0.35),w//2,int(h*0.85),400)
    return D.find(text=label,contains=contains,timeout=1)
def enter(label,value):
    n=see(label);D.tap_xy(n.cx,n.cy);D.type_text(value)
def safe():
    crash=D.crashes()
    assert not crash,"Fatal exception "+crash[-1500:]
    assert not D.anr(),"App not responding"
def case(id,title,fn):
    r={"id":id,"title":title}
    try:
        fn();safe();r["result"]="PASS"
    except Exception as e:
        r["result"]="FAIL";r["error"]=str(e);r["trace"]=traceback.format_exc(limit=6)
    try:r["screenshot"]=D.screenshot(id)
    except Exception as e:r["screenshotError"]=str(e)
    print(id,r["result"],r.get("error",""),flush=True);rows.append(r)
def first():
    launcher=D.ashell(f"cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p {PKG}")
    assert "app.filmmaking.studio.MainActivity" in launcher,launcher
    D.ashell(f"pm clear {PKG}")
    D.ashell(f"pm grant {PKG} android.permission.CAMERA")
    D.ashell(f"pm grant {PKG} android.permission.RECORD_AUDIO")
    D.launch()
    see("From thought to shot",timeout=35);see("Open Duoport camera")
def idea():
    click("Capture an idea")
    enter("Capture an idea, in your own words","CinemaIdeaAlpha")
    click("Save offline")
    see("CinemaIdeaAlpha",timeout=18)
    D.relaunch();click("Ideas");see("CinemaIdeaAlpha")
def plans():
    click("Develop as film")
    click("Projects");click("CinemaIdeaAlpha")
    see("Scenes and shot list")
    enter("Scene name","OpeningScene");click("Add scene")
    see("OpeningScene",True,20)
    enter("Shot title","HeroCloseup");scroll_click("Add shot")
    see("HeroCloseup",True,20)
    scroll_click("HeroCloseup",True);see("Record this shot in Duoport")
def shot_persist():
    click("Edit camera",True)
    # The editor is long: the Save action is below the initial viewport.
    scroll_click("Save planned shot offline")
    D.relaunch();click("Projects");click("CinemaIdeaAlpha")
    see("OpeningScene",True);scroll_click("HeroCloseup",True)
    assert D.scroll_to(text="Record this shot in Duoport",max_swipes=10),"Shot was not persisted after relaunch"
def cancel():
    # Start independently, so an earlier editor failure cannot mask this test.
    D.relaunch();click("Projects");click("CinemaIdeaAlpha")
    scroll_click("HeroCloseup",True)
    scroll_click("Record this shot in Duoport")
    see("Film a Studio shot",timeout=16)
    click("Cancel")
    see("Camera cancelled",True,timeout=16)
    assert D.scroll_to(text="Recorded takes: 0",max_swipes=8),"Cancelled recording incorrectly created a take"
def camera():
    click("Home");click("Open Duoport camera")
    for _ in range(3):
        n=D.find(text="While using the app",contains=True,timeout=1)
        if n:D.tap_xy(n.cx,n.cy)
    assert D.find(rid="record_button",timeout=30),"Embedded Duoport camera did not open"
    D.ashell("input keyevent 4");D.sleep(1)
def workflow():
    click("Projects");click("CinemaIdeaAlpha")
    click("Open Develop",True)
    see("+ Treatment")
    click("+ Treatment");see("Add Treatment")
    enter("Title","TreatmentOne");enter("Premise","Lightandmemory")
    click("Save offline");see("TreatmentOne",timeout=20)
    click("Approve");see("approved",True)
    tab=see("Shoot")
    w,h=D.screen_size()
    D.swipe(min(w-110,tab.cx+160),tab.cy,max(110,w//4),tab.cy,420)
    click("Wrap")
    see("Production wrap")
    see("Export production metadata JSON")
def settings():
    click("AI");see("Sign in under Settings",True)
    click("Settings");see("Your Studio account")
    see("Test connection")
    # Crash-diagnostics settings push storage actions below the fold.
    assert D.scroll_to(text="Export offline backup JSON",max_swipes=10),"Backup action not reachable"
    assert D.scroll_to(text="Import backup",contains=True,max_swipes=4),"Restore action not reachable"
def test_real_recording():
    # Positive camera handoff, real emulator MediaStore save and take import.
    D.relaunch()
    click("Projects");click("CinemaIdeaAlpha")
    scroll_click("HeroCloseup",True)
    click("Record this shot in Duoport")
    click("Open Duoport",timeout=15)
    assert D.wait_camera(timeout=30),"Studio capture did not open Duoport camera"
    newfiles=D.record(seconds=3,timeout=210)
    assert newfiles,"The emulator camera produced no saved video"
    D.close_results()
    D.ashell("input keyevent 4");D.sleep(2)
    if D.find(rid="record_button",timeout=1):
        D.ashell("input keyevent 4");D.sleep(2)
    see("imported",contains=True,timeout=25)
    # The takes list sits below the external-camera section: scroll, then assert real data.
    count=D.scroll_to(text="Recorded takes:",contains=True,max_swipes=12)
    assert count,"Recorded takes section not reachable"
    assert count.text.strip()!="Recorded takes: 0","Recording not imported into Studio: "+count.text
    assert D.scroll_to(text="Take 1",contains=True,max_swipes=6),"Take 1 card not shown"

def test_external_camera_filenames():
    # Real UI: log first filename, infer prefix + padded next number, persist offline.
    D.relaunch()
    click("Projects");click("CinemaIdeaAlpha")
    scroll_click("HeroCloseup",True)
    entry=D.scroll_to(text="Camera filename (first entry)",max_swipes=10)
    assert entry,"External camera filename field not visible"
    D.tap_xy(entry.cx,entry.cy)
    D.type_text("DJI_0045.MP4")
    scroll_click("Log external camera take")
    see("External camera filename saved",contains=True,timeout=20)
    assert D.scroll_to(text="DJI_0045.MP4",max_swipes=10),"Logged DJI_0045.MP4 card not shown"
    # The number field sits ABOVE the logged cards we just scrolled to.
    assert scroll_back_to("File number only (suffix)"), "Next-number field missing"
    see("Filename: DJI_0046.MP4",timeout=10)
    scroll_click("Log external camera take")
    see("External camera filename saved",contains=True,timeout=20)
    assert D.scroll_to(text="DJI_0046.MP4",max_swipes=10),"Logged DJI_0046.MP4 card not shown"
    D.relaunch()
    click("Projects");click("CinemaIdeaAlpha")
    scroll_click("HeroCloseup",True)
    assert D.scroll_to(text="DJI_0045.MP4",max_swipes=10),"First filename not saved"
    assert D.scroll_to(text="DJI_0046.MP4",max_swipes=10),"Second filename not saved"

def test_backend_health_and_backup():
    # Fresh start: Settings keeps its scroll position from the previous case otherwise.
    D.relaunch();click("Settings")
    click("Test connection")
    see("Server: ready",contains=True,timeout=20)
    scroll_click("Export offline backup JSON")
    D.sleep(2)
    assert "com.google.android.documentsui" in D.current_package() or D.find(text="SAVE",contains=True,timeout=1), "Backup picker did not open"
    D.ashell("input keyevent 4");D.sleep(1)

def open_workspace(tab_name):
    D.relaunch();click("Projects");click("CinemaIdeaAlpha")
    click("Open Develop",True)
    tab=see("Shoot")
    if tab_name=="Wrap":
        w,h=D.screen_size()
        D.swipe(min(w-110,tab.cx+160),tab.cy,max(110,w//4),tab.cy,420)
    click(tab_name)

def test_shoot_mode():
    open_workspace("Shoot")
    click("Enter Shoot Mode",True)
    see("Shoot Mode",timeout=15)
    see("HeroCloseup",contains=True)
    scroll_click("Log room tone take")
    see("Room tone take logged",contains=True,timeout=15)
    assert D.scroll_to(text="roomTone",contains=True,max_swipes=6),"Room tone take not shown as the last take"
    scroll_click("Exit Shoot Mode")
    # Back on the same workspace tab (Shoot), not reset to Develop.
    see("Enter Shoot Mode",contains=True,timeout=10)

def test_wrap_readiness_exports():
    open_workspace("Wrap")
    see("Production wrap",timeout=15)
    assert D.scroll_to(text="Ready for editorial handoff",contains=True,max_swipes=10),"Readiness levels missing"
    # Not verified: the DJI names were logged but their footage was never imported.
    assert D.find(text="✗ Ready for editorial handoff",timeout=2) or D.find(text="✗ Actual media verified",timeout=2),"Readiness wrongly claims handoff"
    D.relaunch();open_workspace("Wrap")
    scroll_click("Markdown")
    D.sleep(2)
    assert "com.google.android.documentsui" in D.current_package() or D.find(text="SAVE",contains=True,timeout=1),"Markdown export picker did not open"
    D.ashell("input keyevent 4");D.sleep(1)

def test_sync_gating_and_camera_profile():
    D.relaunch();click("Settings")
    assert D.scroll_to(text="Cloud sync",max_swipes=10),"Cloud sync section missing"
    see("Sign in to copy",contains=True)
    scroll_click("Read this phone's camera capabilities")
    see("Camera profile read",contains=True,timeout=20)
    assert D.scroll_to(text="verified (device-reported)",contains=True,max_swipes=6),"Profile not labelled device-reported"

def by_desc(desc,timeout=12):
    n=D.find(desc=desc,contains=True,timeout=timeout)
    if n is None: raise AssertionError("Missing element "+desc+": "+repr([x.text for x in D.dump() if x.text][:30]))
    return n
def reach_desc(desc,max_swipes=10):
    w,h=D.screen_size()
    for _ in range(max_swipes+1):
        n=D.find(desc=desc,contains=True,timeout=0,nodes=D.dump())
        if n is not None and h*0.12<=n.cy<=h*0.88: return n
        D.swipe(w//2,int(h*0.75),w//2,int(h*0.40),400)
    raise AssertionError("Cannot reach "+desc)

def test_storyboard_sketch():
    open_workspace("Visual")
    see("Storyboards",timeout=15)
    scroll_click("Sketch")
    pad=by_desc("Sketch area")
    # A real finger stroke across the frame.
    D.swipe(pad.x1+int((pad.x2-pad.x1)*0.2),pad.y1+int((pad.y2-pad.y1)*0.3),pad.x1+int((pad.x2-pad.x1)*0.8),pad.y1+int((pad.y2-pad.y1)*0.7),700)
    click("Save sketch")
    see("Sketch saved",contains=True,timeout=15)
    assert D.scroll_to(text="· drawing",contains=True,max_swipes=6),"Sketch version not listed"
    D.relaunch();open_workspace("Visual")
    assert D.scroll_to(text="· drawing",contains=True,max_swipes=6),"Sketch not persisted"

def test_diagram_canvas_drag():
    open_workspace("Visual")
    n=D.scroll_to(text="New diagram name",max_swipes=10)
    assert n,"Diagram form missing"
    D.tap_xy(n.cx,n.cy);D.type_text("BlockingOne")
    scroll_click("Create diagram")
    scroll_click("+ Element")
    see("Add element")
    enter("Label","CamA")
    click("Add")
    assert D.scroll_to(text="“CamA” at 5.0m, 4.0m",contains=True,max_swipes=8),"Element not added at 5,4"
    canvas=reach_desc("Diagram canvas, 1 elements")
    cw=canvas.x2-canvas.x1
    # Element sits at the canvas centre (5 m of 10, 4 m of 8): drag it 2 m right.
    D.swipe(canvas.cx,canvas.cy,canvas.cx+int(cw*0.2),canvas.cy,900)
    D.sleep(2)
    assert D.find(text="“CamA” at 5.0m",contains=True,timeout=2) is None,"Drag did not move the element"
    assert D.scroll_to(text="“CamA” at",contains=True,max_swipes=4),"Element lost after drag"

def test_beat_drag_reorder():
    open_workspace("Story")
    for name in ("BeatOne","BeatTwo"):
        scroll_click("+ Visual beat")
        see("Add Visual beat")
        enter("Title",name)
        click("Save offline")
        see(name,timeout=15)
    D.relaunch();open_workspace("Story")
    first=see("1. BeatOne");second=see("2. BeatTwo")
    handle=by_desc("Drag BeatOne")
    D.swipe(handle.cx,handle.cy,handle.cx,handle.cy+int((second.cy-first.cy)*1.3),1200)
    D.sleep(2)
    see("1. BeatTwo",timeout=10);see("2. BeatOne")
    D.relaunch();open_workspace("Story")
    see("1. BeatTwo",timeout=12)

def test_creative_dna_preferences():
    D.relaunch();click("AI");click("Creative DNA")
    see("Explicit preferences",timeout=12)
    see("Sign in under Settings to analyse your archive.")
    enter("e.g. I prefer long, observational takes","PreferStillFrames")
    click("Add preference")
    see("PreferStillFrames",timeout=12)
    D.relaunch();click("AI");click("Creative DNA")
    see("PreferStillFrames",timeout=12)
    see("1 explicit preference(s)",contains=True)

def test_sentry_opt_in_event():
    D.relaunch();click("Settings")
    row=D.scroll_to(text="Share crash reports",max_swipes=14)
    assert row,"Crash diagnostics section missing"
    if D.find(text="Crash reporting is not connected yet",contains=True,timeout=1):
        raise AssertionError("APK built without STUDIO_SENTRY_DSN")
    w,h=D.screen_size()
    D.tap_xy(w-90,row.cy)   # the consent switch sits at the end of the row
    see("Crash reporting enabled",contains=True,timeout=10)
    scroll_click("Send safe diagnostic test event")
    see("Diagnostic test event queued",contains=True,timeout=10)
    D.sleep(20)  # let the SDK deliver before the emulator shuts down

def test_reference_board_inputs():
    # Offline-safe checks of the three ways in: nothing copied, a non-web link, and the phone picker opening.
    open_workspace("Visual")
    see("Reference board",timeout=15)
    scroll_click("Paste")
    see("The clipboard",contains=True,timeout=10)
    scroll_click("From a link")
    see("Image from a link")
    enter("Link to an image or a web page","ftp://example.com/still.jpg")
    click("Fetch image")
    see("Only web links",contains=True,timeout=10)
    scroll_click("From this phone")
    time.sleep(3)
    if not D.find(text="Reference board",timeout=2): D.back(force=True)
    assert D.in_app(),"App left the foreground after the picker"
    see("Reference board",timeout=10)

def test_google_sign_in_ui():
    """Continue with Google exists; never crashes; without a client ID it says so plainly."""
    D.relaunch();click("Settings")
    btn=D.scroll_to(text="Continue with Google",max_swipes=14)
    assert btn,"Continue with Google button missing"
    # The dump reports the label inside a disabled Compose button as enabled, so read the note instead.
    # The note sits right under the button; scrolling to look for it can push the button off screen.
    if D.find(text="set up in this build",contains=True,timeout=2):
        return
    btn=scroll_back_to("Continue with Google",max_swipes=4)
    assert btn,"Continue with Google button not on screen to tap"
    D.tap_xy(btn.cx,btn.cy)
    # The CI emulator has no Google account: Credential Manager must end in a plain message.
    outcomes=["No Google account","Google Play services","cancelled","Google sign-in failed","doesn't support","interrupted"]
    end=time.time()+60; seen=[]; focus=""
    while time.time()<end:
        nodes=D.dump()
        if any(D.find(text=o,contains=True,nodes=nodes) for o in outcomes): break
        focus=D.current_package()
        seen=[n.text for n in nodes if n.visible and n.text][:25]
        # Google's own sheet or add-account screen is another app's window: Back dismisses it (= cancelled).
        if PKG not in focus and time.time()>end-50: D.ashell("input keyevent 4")
        time.sleep(2)
    else:
        raise AssertionError("Google sign-in gave no message. Focus: "+" ".join(focus.split())[:200]+" | screen: "+" / ".join(seen))
    assert "studio" in D.ashell("dumpsys activity activities | grep -m1 ResumedActivity").lower() or D.find(text="Settings",timeout=3),"app left the foreground"

def main():
    print("Emulator OS",D.ashell("getprop ro.build.version.release"),flush=True)
    print(D.adb("install -r -g "+os.environ["NEW_APK"],timeout=180,check=True))
    # Disable on-screen IMEs so scripted inputs do not cover save buttons.
    for ime in D.ashell("ime list -s -a").splitlines():
        ime=ime.strip()
        if ime and "/" in ime:
            D.ashell("ime disable "+ime)
    D.ashell("settings put secure show_ime_with_hard_keyboard 0")
    D.crash_log_clear()
    for id,title,fn in [
        ("COMB-01","Single-package Studio launcher",first),
        ("COMB-02","Offline idea persists after app kill",idea),
        ("COMB-03","Idea to project, scene and shot",plans),
        ("COMB-04","Planned shot editor persists",shot_persist),
        ("COMB-05","Internal capture cancel creates no take",cancel),
        ("COMB-06","Embedded Duoport camera opens",camera),
        ("COMB-09","Emulator-recorded media returns to Studio",test_real_recording),
        ("COMB-07","Treatment approval and production wrap",workflow),
        ("COMB-08","Unauthenticated AI gated and settings available",settings),
        ("COMB-10","AWS health and Android backup document picker",test_backend_health_and_backup),
        ("COMB-11","DJI filenames auto-increment and persist offline",test_external_camera_filenames),
        ("COMB-12","Shoot Mode logs a room-tone take offline",test_shoot_mode),
        ("COMB-13","Wrap readiness never claims unimported footage; Markdown export",test_wrap_readiness_exports),
        ("COMB-14","Cloud sync gated when signed out; device camera profile verified",test_sync_gating_and_camera_profile),
        ("COMB-15","Storyboard sketch by finger persists as a drawing version",test_storyboard_sketch),
        ("COMB-16","Diagram canvas: drag moves an element in metres",test_diagram_canvas_drag),
        ("COMB-17","Beat board drag reorder persists",test_beat_drag_reorder),
        ("COMB-18","Creative DNA explicit preference persists offline",test_creative_dna_preferences),
        ("COMB-19","Opt-in Sentry diagnostic test event is sent",test_sentry_opt_in_event),
        ("COMB-20","Continue with Google is present and fails safely without an account",test_google_sign_in_ui),
        ("COMB-21","Reference board: paste, link and phone inputs fail safely offline",test_reference_board_inputs),
    ]:case(id,title,fn)
    with open(os.path.join(OUT,"results.json"),"w") as f:json.dump(rows,f,indent=2)
    report=["# Combined Android Emulator Acceptance","","| Test | Status | Result |","|---|---|---|"]
    for r in rows:report.append("| "+r["id"]+" | "+r["result"]+" | "+r.get("error",r["title"]).replace("|","/").replace("\n"," ")[:280]+" |")
    report.extend(["","Emulator evidence only. Real capture quality, paid AI requests, cross-device sync and full feature parity are not certified."])
    open(os.path.join(OUT,"REPORT.md"),"w").write("\n".join(report)+"\n")
    open(os.path.join(OUT,"crash_buffer.txt"),"w").write(D.crashes())
    print("\n".join(report),flush=True)
    return int(any(r["result"]!="PASS" for r in rows))
if __name__=="__main__":sys.exit(main())
