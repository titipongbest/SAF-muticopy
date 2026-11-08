#!/usr/bin/env bash
# Runs the instrumented tests on the booted emulator and collects screenshots and logs.
set -u
OUT=test-output
PKG=com.titipong.safmulticopy
mkdir -p "$OUT"

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c || true

adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/instrument.txt"

adb pull "/sdcard/Android/data/$PKG/files/test-screenshots" "$OUT/" || true
adb exec-out screencap -p > "$OUT/last-screen.png" || true
adb logcat -d > "$OUT/logcat.txt" || true

grep -q '^OK (' "$OUT/instrument.txt"
