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

adb pull "/sdcard/Android/data/$PKG/files/test-screenshots" "$OUT/" || true
adb exec-out screencap -p > "$OUT/last-screen.png" || true
adb logcat -d > "$OUT/logcat.txt" || true

[ "$PASS1" -ge 1 ] && [ "$OKS" -ge 2 ]
