# Phone test session 48 (Pixel 9a, build 183)

| Test | Result | Notes |
|---|---|---|
| CAM-10 | ✅ (diagnostic) | Info line: Standard → "Steady: standard", Enhanced → "Steady: enhanced" (recording drops to 1200×1600). The camera applies both; the walk comparison is still for the tester. |
| PROF-01 | ✅ log / ❌ → fix pushed for Rec.709 | Same scene: Standard T008 blacks 29 / avg 69 / highs 117; DF-Log T009 69 / 107 / 151 (flat, shadows lifted; was darker than Standard before). DF-Log + Rec.709 T010 104 avg, barely different from log. Fix: filmic (ACES-fit) conversion at 0.3 exposure, simulated on T009: 38 / 79 / 130. Re-test next build. |
| DES-09 | ❌ → fix pushed | Fine edges preview: playback died at 0:03 (Media3 1.5.1 NoSuchElementException in VideoFrameRenderControl.dropFrame) before any mask; loading the matte blocked the player thread. Fix: the live preview segments on its own thread and shows the newest mask; saving stays frame-exact. Save not yet tried. |
| CIN-01, FOL-02, EYE-01/02, DES-10 | not run yet | |
