# Phone test session 48 (Pixel 9a, build 183)

| Test | Result | Notes |
|---|---|---|
| CAM-10 | ✅ (diagnostic) | Info line: Standard → "Steady: standard", Enhanced → "Steady: enhanced" (recording drops to 1200×1600). The camera applies both; the walk comparison is still for the tester. |
| PROF-01 | ✅ log / ❌ → fix pushed for Rec.709 | Same scene: Standard T008 blacks 29 / avg 69 / highs 117; DF-Log T009 69 / 107 / 151 (flat, shadows lifted; was darker than Standard before). DF-Log + Rec.709 T010 104 avg, barely different from log. Fix: filmic (ACES-fit) conversion at 0.3 exposure, simulated on T009: 38 / 79 / 130. Re-test next build. |
| DES-09 | ❌ → fix pushed | Fine edges preview: playback died at 0:03 (Media3 1.5.1 NoSuchElementException in VideoFrameRenderControl.dropFrame) before any mask; loading the matte blocked the player thread. Fix: the live preview segments on its own thread and shows the newest mask; saving stays frame-exact. Save not yet tried. |
| FOL-02 | ✅ | T014 (tester): upright for ~2.5 s, phone turned, upright again from ~3 s in both cuts; 9:16 zooms in while sideways, 16:9 shows the wider room. Only the half second of the turn itself is tilted. (Build 80's T036 stayed on its side.) |
| EYE-01 | ❌ → fix pushed | T012 (tester): "not working at all". Measured with the face landmark model at 4 fps: looking at the lens (first 1.5 s) openness 0.40, iris -0.044 below the corner line; reading ~0.35 openness, iris within 0.02 of calibration. Reading mostly lowered the upper lids, so the iris-only shift was ~2 px, invisible. Fix: the warp now lifts the upper lid and iris back to the calibrated opening (6-8 px on T012 in a PC simulation); logs "Eye contact on" and what it measures every 30 frames. Whether build 183's effect ran can't be told (logs rolled over). |
| CIN-01, EYE-02, DES-10 | not run yet | |
