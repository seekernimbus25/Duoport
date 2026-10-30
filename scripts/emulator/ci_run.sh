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
adb shell wm dismiss-keyguard
adb install -r -g "$NEW_APK" | tee "$OUT_DIR/install.txt"
ONLY_ARGS=()
if [ -n "${ONLY:-}" ]; then ONLY_ARGS=(--only "$ONLY"); fi
timeout "${TEST_MINUTES:-260}m" python3 -u tests.py "${ONLY_ARGS[@]}" 2>&1 | tee "$OUT_DIR/driver.log"
adb logcat -d -b crash > "$OUT_DIR/crash_buffer.txt" || true
adb logcat -d -t 3000 > "$OUT_DIR/logcat_tail.txt" || true
exit 0
