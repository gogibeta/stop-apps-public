#!/usr/bin/env bash
# Phase-2 emulator test for Stop Apps.
# Runs on a GitHub Actions emulator (AOSP / Google APIs image, NOT MIUI):
# installs the APK, grants usage-stats, enables the accessibility service,
# launches the app, then drives a REAL stop-all run by tapping the UI
# (Select all -> Stop N apps) and verifies the ForceStopEngine automation
# trace ([dbg] lines) in logcat through to the run-finished marker.
# NOTE: this validates the full automation pipeline end-to-end on AOSP
# Settings. It cannot reproduce MIUI-specific Settings UI behavior.
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

echo "=== granting notification permission (avoid runtime dialog) ==="
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

echo "=== launching app ==="
adb shell am start -n "$MAIN"
sleep 8
adb shell screencap -p /data/local/tmp/stopapps-home.png
adb pull /data/local/tmp/stopapps-home.png "$OUT/" || true

echo "=== phase 2: driving a real stop-all run via UI taps ==="
# Tap the center of the first UI node whose text matches the given regex.
tap_node() {
  local pattern="$1" desc="$2"
  adb shell uiautomator dump /data/local/tmp/ui.xml > /dev/null 2>&1
  adb pull /data/local/tmp/ui.xml /tmp/ui.xml > /dev/null 2>&1
  local bounds
  bounds=$(python3 - "$pattern" <<'EOF'
import sys, re, xml.etree.ElementTree as ET
pat = sys.argv[1]
tree = ET.parse('/tmp/ui.xml')
for node in tree.iter('node'):
    t = node.get('text') or ''
    if re.search(pat, t):
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds') or '')
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
EOF
)
  if [ -z "$bounds" ]; then echo "TAP TARGET NOT FOUND: $desc"; return 1; fi
  echo "tapping '$desc' at $bounds"
  # shellcheck disable=SC2086
  adb shell input tap $bounds
}

sleep 5
tap_node "^Select all$" "Select all" || { echo "SELECT-ALL TAP FAILED"; exit 1; }
sleep 3
tap_node "^Stop [0-9]+ apps$" "Stop N apps" || { echo "STOP TAP FAILED"; exit 1; }

echo "=== waiting for the run to finish (up to 8 min) ==="
FOUND=""
for _ in $(seq 1 48); do
  sleep 10
  if adb logcat -d -t 4000 2>/dev/null | grep -q "\[dbg\] run finished"; then FOUND=1; break; fi
done
if [ -z "$FOUND" ]; then
  echo "RUN DID NOT FINISH IN TIME"
  adb logcat -d -t 4000 > "$OUT/logcat-full.txt" || true
  exit 1
fi

echo "=== collecting stop-run diagnostics ==="
adb logcat -d > "$OUT/logcat-full.txt" || true
grep -iE "stopapps|StopAccess|ForceStop" "$OUT/logcat-full.txt" | tail -120 > "$OUT/logcat-stopapps.txt" || true
grep -a "StopApps" "$OUT/logcat-full.txt" > "$OUT/stop-run-dbg.txt" || true
adb shell screencap -p /data/local/tmp/stopapps-after.png
adb pull /data/local/tmp/stopapps-after.png "$OUT/" || true

DBG=$(grep -ac "\[dbg\]" "$OUT/stop-run-dbg.txt" || true)
STOPPED=$(grep -ac "Stopped " "$OUT/stop-run-dbg.txt" || true)
COULDNOT=$(grep -ac "Could not stop" "$OUT/stop-run-dbg.txt" || true)
echo "engine [dbg] lines : $DBG"
echo "apps stopped       : $STOPPED"
echo "apps could-not-stop: $COULDNOT"
grep -a "\[dbg\] run finished" "$OUT/stop-run-dbg.txt" | tail -1
if [ "$DBG" -eq 0 ]; then echo "ENGINE NEVER ENGAGED (no [dbg] lines)"; exit 1; fi

echo "=== crash check ==="
if grep -qE "FATAL EXCEPTION.*$PKG" "$OUT/logcat-full.txt"; then
  echo "APP CRASH DETECTED — see ci/out/logcat-full.txt"
  exit 1
fi

echo "EMULATOR TEST OK"
