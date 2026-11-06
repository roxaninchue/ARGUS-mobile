#!/bin/bash
# 0.52.2 ARGUS Spotlight check (local, not the release gate), run inside the emulator by .github/workflows/home-intro-check.yml
# (replaces run-intro.sh / HomeIntroCheck, which played the old Home's entry sequence): installs the debug app + test APK,
# runs ArgusSpotlightCheck (pull the notch down / tap the scrim / tap + Back / pull the handle up, three rounds, real touches,
# 50 ms main-thread monitor, limit 700 ms; round 1 is a warm-up) and pulls its screenshots.
set -uo pipefail
OUT=spot-out; mkdir -p "$OUT"; PKG=com.blaze.assistant
adb install -r -g app/build/outputs/apk/debug/app-debug.apk > "$OUT/install.txt" 2>&1
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$OUT/install.txt" 2>&1
# the panel raises the keyboard like on a phone (the emulator's hardware keyboard would otherwise hide it)
adb shell settings put secure show_ime_with_hard_keyboard 1 || true
adb logcat -c
timeout 900 adb shell am instrument -w -r -e class $PKG.smoke.ArgusSpotlightCheck $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrument.txt" 2>&1 || true
adb pull /sdcard/Android/data/$PKG/files/argus-spotlight "$OUT/shots" > /dev/null 2>&1 || echo "no screenshots" > "$OUT/no-shots.txt"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
{ echo "# ARGUS Spotlight check"; grep -E "OK \(|FAILURES|Tests run|shortMsg|stack=" "$OUT/instrument.txt" | head -20
  grep "BlazeArgusSpot" "$OUT/logcat.txt" | sed 's/^.*BlazeArgusSpot: /  /'
  echo "- files: $(ls "$OUT" | tr '\n' ' ') shots: $(ls "$OUT/shots" 2>/dev/null | wc -l)"; } > "$OUT/SUMMARY.md"
cat "$OUT/SUMMARY.md"
grep -q "^OK (1 test)" "$OUT/instrument.txt"
