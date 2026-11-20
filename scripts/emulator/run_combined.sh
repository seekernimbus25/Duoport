#!/usr/bin/env bash
# Run true Android instrumentation + UI Automator acceptance in the SAME emulator.
# Neither suite masks a failure in the other.
set -uo pipefail
mkdir -p scripts/emulator/combined_out
set +e
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace 2>&1 | tee scripts/emulator/combined_out/instrumentation.log
GRADLE_STATUS=${PIPESTATUS[0]}
python3 -u scripts/emulator/combined_tests.py 2>&1 | tee scripts/emulator/combined_out/ui-driver.log
UI_STATUS=${PIPESTATUS[0]}
set -e
if [ -d app/build/outputs/androidTest-results/connected ]; then
  cp -r app/build/outputs/androidTest-results/connected scripts/emulator/combined_out/instrumentation-xml
fi
if [ -d app/build/reports/androidTests/connected ]; then
  cp -r app/build/reports/androidTests/connected scripts/emulator/combined_out/instrumentation-html
fi
adb logcat -d -b crash > scripts/emulator/combined_out/full-crash-log.txt || true
echo "Instrumentation exit: $GRADLE_STATUS, UI acceptance exit: $UI_STATUS"
test "$GRADLE_STATUS" -eq 0 && test "$UI_STATUS" -eq 0
