#!/bin/bash
# Runs inside the emulator step on CI: installs the test build and runs the tests.
set -u
cd "$(dirname "$0")"
export OUT_DIR="$PWD/out"
export MEDIA_DIR="$PWD/media"
mkdir -p "$OUT_DIR"
adb wait-for-device
adb shell getprop ro.build.version.release > "$OUT_DIR/device.txt"
adb shell getprop ro.product.model >> "$OUT_DIR/device.txt"
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
# No system "isn't responding" dialogs over the app.
adb shell settings put global hide_error_dialogs 1
adb shell wm dismiss-keyguard
# No on-screen keyboard: it covers dialog buttons and autocorrects typed text. adb's key
# events still type into the focused field without one.
# (Disabling the input method alone didn't stick, so the keyboard apps are disabled.)
adb shell ime list -s -a >> "$OUT_DIR/device.txt"
for ime in $(adb shell ime list -s -a | tr -d '\r'); do
  adb shell ime disable "$ime" || true
  adb shell pm disable-user --user 0 "${ime%%/*}" || true
done
adb shell settings put secure show_ime_with_hard_keyboard 0
adb shell ime list -s >> "$OUT_DIR/device.txt"
adb install -r -g "$NEW_APK" | tee "$OUT_DIR/install.txt"
ONLY_ARGS=()
if [ -n "${ONLY:-}" ]; then ONLY_ARGS=(--only "$ONLY"); fi
timeout "${TEST_MINUTES:-260}m" python3 -u tests.py "${ONLY_ARGS[@]}" 2>&1 | tee "$OUT_DIR/driver.log"
adb logcat -d -b crash > "$OUT_DIR/crash_buffer.txt" || true
adb logcat -d -t 3000 > "$OUT_DIR/logcat_tail.txt" || true
exit 0
