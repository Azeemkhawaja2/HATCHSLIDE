#!/usr/bin/env bash
# 10,000-event monkey run (spec section 12): installs a build on the connected device or emulator and
# fails on any crash or ANR. Usage: tools/verify/monkey.sh [path/to/app.apk]
set -euo pipefail
cd "$(dirname "$0")/../.."
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}
PACKAGE=com.hatchslide.game
LOG=build/reports/monkey.log
mkdir -p build/reports
adb install -r -g "$APK" >/dev/null
adb shell am force-stop "$PACKAGE"
# Touch, motion, navigation and trackball events; no system keys, so the run stays in the game.
adb shell monkey -p "$PACKAGE" -s 2026 --throttle 40 --pct-syskeys 0 --pct-appswitch 2 \
  --monitor-native-crashes -v -v 10000 > "$LOG" 2>&1 || true
if grep -qE "// CRASH|// NOT RESPONDING|ANR in $PACKAGE|FATAL EXCEPTION" "$LOG"; then
  echo "Monkey found a crash or ANR; see $LOG" >&2
  grep -nE "// CRASH|// NOT RESPONDING|ANR in|FATAL EXCEPTION" "$LOG" | head -20 >&2
  exit 1
fi
grep -q "Monkey finished" "$LOG" || { echo "Monkey did not finish; see $LOG" >&2; exit 1; }
echo "Monkey: 10,000 events, no crash or ANR ($LOG)"
