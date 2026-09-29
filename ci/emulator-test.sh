#!/usr/bin/env bash
# Phase-1 emulator smoke test for Stop Apps.
# Runs on a GitHub Actions emulator (AOSP/ Google APIs image, NOT MIUI):
# installs the APK, grants usage-stats, enables the accessibility service,
# launches the app, screenshots it, and checks logcat for crashes.
# NOTE: this validates install/launch/service-binding end-to-end on AOSP.
# It cannot reproduce MIUI-specific Settings UI behavior.
set -u

APK="${1:?usage: emulator-test.sh <apk>}"
OUT="ci/out"
mkdir -p "$OUT"

PKG="com.stopapps.app"
SVC="com.stopapps.app/com.stopapps.app.accessibility.StopAccessService"
MAIN="$PKG/com.stopapps.app.MainActivity"

echo "=== installing $APK ==="
adb install -r "$APK"

echo "=== granting usage-stats access ==="
adb shell appops set "$PKG" GET_USAGE_STATS allow

echo "=== enabling accessibility service ==="
adb shell settings put secure enabled_accessibility_services "$SVC"
adb shell settings put secure accessibility_enabled 1
sleep 3
adb shell settings get secure enabled_accessibility_services | tee "$OUT/accessibility.txt"
if ! grep -q "StopAccessService" "$OUT/accessibility.txt"; then
  echo "ACCESSIBILITY SERVICE NOT ENABLED"; exit 1
fi

echo "=== launching app ==="
adb shell am start -n "$MAIN"
sleep 8
adb shell screencap -p /sdcard/stopapps-home.png
adb pull /sdcard/stopapps-home.png "$OUT/" || true

echo "=== logcat ==="
adb logcat -d > "$OUT/logcat-full.txt" || true
grep -iE "stopapps|StopAccess|ForceStop" "$OUT/logcat-full.txt" | tail -120 > "$OUT/logcat-stopapps.txt" || true

echo "=== crash check ==="
if grep -qE "FATAL EXCEPTION.*$PKG" "$OUT/logcat-full.txt"; then
  echo "APP CRASH DETECTED — see ci/out/logcat-full.txt"
  exit 1
fi

echo "EMULATOR TEST OK"
