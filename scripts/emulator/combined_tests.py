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
    enter("Shot title","HeroCloseup");click("Add shot")
    see("HeroCloseup",True,20)
    click("HeroCloseup",True);see("Record this shot in Duoport")
def shot_persist():
    click("Edit camera",True)
    click("Save planned shot offline")
    D.relaunch();click("Projects");click("CinemaIdeaAlpha")
    see("OpeningScene",True);click("HeroCloseup",True)
    see("Record this shot in Duoport")
def cancel():
    click("Record this shot in Duoport")
    see("Film a Studio shot",timeout=16)
    click("Cancel")
    see("Camera cancelled",True,timeout=16)
    see("Recorded takes: 0")
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
    click("Wrap");see("Production wrap")
    see("Export production metadata JSON")
def settings():
    click("AI");see("Sign in under Settings",True)
    click("Settings");see("Your Studio account")
    see("Test connection");see("Export offline backup JSON")
def main():
    print("Emulator OS",D.ashell("getprop ro.build.version.release"),flush=True)
    print(D.adb("install -r -g "+os.environ["NEW_APK"],timeout=180,check=True))
    D.crash_log_clear()
    for id,title,fn in [
        ("COMB-01","Single-package Studio launcher",first),
        ("COMB-02","Offline idea persists after app kill",idea),
        ("COMB-03","Idea to project, scene and shot",plans),
        ("COMB-04","Planned shot editor persists",shot_persist),
        ("COMB-05","Internal capture cancel creates no take",cancel),
        ("COMB-06","Embedded Duoport camera opens",camera),
        ("COMB-07","Treatment approval and production wrap",workflow),
        ("COMB-08","Unauthenticated AI gated and settings available",settings),
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
