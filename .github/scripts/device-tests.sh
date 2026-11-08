#!/usr/bin/env bash
# Runs the instrumented tests on the booted emulator and collects screenshots and logs.
# Android kills the app when "All files access" changes, so the tests run in two passes:
#   1) everything without it   2) tests marked @NeedsAllFilesAccess, after granting it.
set -u
OUT=test-output
PKG=com.titipong.safmulticopy
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
NEEDS="$PKG.NeedsAllFilesAccess"
mkdir -p "$OUT"

# Emulator noise: keep "X isn't responding" dialogs (e.g. the launcher) from covering the app.
adb shell settings put global hide_error_dialogs 1 || true
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1 || true
adb shell pm disable-user --user 0 com.android.launcher3 >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
sleep 10

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c || true

echo "===== Pass 1: without All files access =====" | tee "$OUT/instrument.txt"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE default
adb shell am instrument -w -e notAnnotation "$NEEDS" "$RUNNER" 2>&1 | tee -a "$OUT/instrument.txt"
PASS1=$(grep -c '^OK (' "$OUT/instrument.txt")

echo "===== Pass 2: with All files access =====" | tee -a "$OUT/instrument.txt"
adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
adb shell am instrument -w -e annotation "$NEEDS" "$RUNNER" 2>&1 | tee -a "$OUT/instrument.txt"
OKS=$(grep -c '^OK (' "$OUT/instrument.txt")

mkdir -p "$OUT/test-screenshots"
adb exec-out run-as "$PKG" tar cf - files/test-screenshots 2>/dev/null | tar xf - -C "$OUT" 2>/dev/null \
  && mv "$OUT"/files/test-screenshots/* "$OUT/test-screenshots/" 2>/dev/null || true
adb exec-out screencap -p > "$OUT/last-screen.png" || true
adb logcat -d > "$OUT/logcat.txt" || true

[ "$PASS1" -ge 1 ] && [ "$OKS" -ge 2 ]
