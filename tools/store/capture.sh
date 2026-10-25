#!/usr/bin/env bash
# Captures the Play listing art from the real game (spec section 13) on a connected emulator or device
# with a GPU: phone and tablet screenshots and the feature graphic, then adds captions.
#   tools/store/capture.sh            # every set
#   tools/store/capture.sh phone      # one set: phone, tablet7, tablet10 or feature
# The display size is set by the capture test (wm size) and reset afterwards.
set -euo pipefail
cd "$(dirname "$0")/../.."
SETS=${*:-phone tablet7 tablet10 feature}
./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null
RAW=build/store-raw
mkdir -p "$RAW"
for set in $SETS; do
  echo "Capturing $set"
  adb shell am instrument -w -e store "$set" -e class com.hatchslide.app.StoreCaptureTest \
    com.hatchslide.game.test/androidx.test.runner.AndroidJUnitRunner
  rm -rf "${RAW:?}/$set"
  adb pull "/sdcard/Android/data/com.hatchslide.game/files/store/$set" "$RAW/" >/dev/null
done
python3 tools/store/compose_store_art.py "$RAW" store
