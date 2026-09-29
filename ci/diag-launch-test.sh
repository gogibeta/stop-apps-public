#!/usr/bin/env bash
# Minimal launch/crash test for a specific APK (e.g. the 1.3.1-diag build
# the user installed on their phone).
# Installs the APK, launches the main activity, waits, then checks:
#   1. no FATAL EXCEPTION for our package in logcat (real check: the
#      package name appears on the "Process:" line after FATAL EXCEPTION)
#   2. the app process is still alive
# Any failure saves the crash trace and exits 1.
set -u

APK="${1:?usage: diag-launch-test.sh <apk>}"
OUT="ci/out"
mkdir -p "$OUT"
PKG="com.stopapps.app"
MAIN="$PKG/com.stopapps.app.MainActivity"

echo "=== [diag] installing $APK ==="
adb install -r "$APK"

echo "=== [diag] launching ==="
adb shell am start -n "$MAIN"
sleep 15
adb shell screencap -p /data/local/tmp/diag-launch.png
adb pull /data/local/tmp/diag-launch.png "$OUT/" > /dev/null 2>&1 || true

echo "=== [diag] crash check ==="
if adb logcat -d -t 4000 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -q "Process: $PKG"; then
  echo "[diag] APP CRASHED ON LAUNCH"
  adb logcat -d 2>/dev/null | grep -B2 -A30 "FATAL EXCEPTION" > "$OUT/diag-crash.txt" || true
  grep -B2 -A30 "FATAL EXCEPTION" "$OUT/diag-crash.txt" | head -50 || true
  exit 1
fi
if [ -z "$(adb shell pidof "$PKG" 2>/dev/null)" ]; then
  echo "[diag] APP PROCESS NOT RUNNING after launch"
  exit 1
fi
echo "[diag] LAUNCH OK — no crash, process alive"
adb uninstall "$PKG" > /dev/null 2>&1 || true
