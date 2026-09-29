#!/usr/bin/env bash
# Phase-2 emulator test for Stop Apps.
# Runs on a GitHub Actions emulator (AOSP / Google APIs image, NOT MIUI):
# installs the APK, grants usage-stats, enables the accessibility service,
# launches the app, then drives a REAL stop-all run by tapping the UI
# (Select all -> Stop N apps) and verifies the ForceStopEngine automation
# trace ([dbg] lines) in logcat through to the run-finished marker.
# The harness tolerates slow emulator boot (system ANR dialogs are
# dismissed, tap targets are polled) and performs REAL crash detection:
# any FATAL EXCEPTION whose "Process:" line is our package fails the run.
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

# ---------- UI automation helpers ----------

# Dump the current UI hierarchy into $OUT/ui-dump.xml.
ui_dump() {
  adb shell uiautomator dump /data/local/tmp/ui.xml > /dev/null 2>&1
  adb pull /data/local/tmp/ui.xml "$OUT/ui-dump.xml" > /dev/null 2>&1
}

# Print "x y" (center) of the first node whose text matches the regex.
find_node() {
  python3 - "$1" "$OUT/ui-dump.xml" <<'EOF'
import sys, re, xml.etree.ElementTree as ET
pat, path = sys.argv[1], sys.argv[2]
try:
    tree = ET.parse(path)
except Exception:
    sys.exit(1)
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
}

# Single tap attempt on a node matching the regex. Returns 1 if absent.
tap_node() {
  local pattern="$1" desc="$2"
  ui_dump
  local bounds
  if ! bounds=$(find_node "$pattern"); then
    return 1
  fi
  echo "tapping '$desc' at $bounds"
  # shellcheck disable=SC2086
  adb shell input tap $bounds
}

# True when logcat holds a FATAL EXCEPTION for our package.
app_crashed() {
  adb logcat -d -t 4000 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -q "Process: $PKG"
}

# Save our app's crash stack trace for the artifacts.
save_crash() {
  adb logcat -d 2>/dev/null | grep -B2 -A30 "FATAL EXCEPTION" > "$OUT/app-crash.txt" || true
}

# Dismiss system "X isn't responding" ANR dialogs by tapping "Wait".
# Fails (returns 1) if the ANR is for OUR app.
dismiss_system_dialogs() {
  ui_dump
  grep -q "isn't responding" "$OUT/ui-dump.xml" || return 0
  local title
  title=$(grep -o 'text="[^"]*isn'"'"'t responding"' "$OUT/ui-dump.xml" | head -1)
  echo "system ANR dialog: $title"
  if [[ "$title" == *"Stop Apps"* ]]; then
    echo "APP ANR — our app is not responding"
    return 1
  fi
  local bounds
  if bounds=$(find_node "^Wait$"); then
    echo "tapping 'Wait' at $bounds"
    # shellcheck disable=SC2086
    adb shell input tap $bounds
    sleep 3
  fi
  return 0
}

# Poll for a tap target up to timeout_s, dismissing system dialogs.
wait_and_tap() {
  local pattern="$1" desc="$2" timeout_s="$3"
  local tries=$((timeout_s / 5))
  for _ in $(seq 1 "$tries"); do
    if app_crashed; then
      echo "APP CRASHED (logcat)"
      save_crash
      return 1
    fi
    dismiss_system_dialogs || return 1
    if tap_node "$pattern" "$desc"; then
      return 0
    fi
    sleep 5
  done
  echo "TAP TARGET NOT FOUND after ${timeout_s}s: $desc (see ui-dump.xml)"
  return 1
}

# ---------- test ----------

echo "=== launching app ==="
adb shell am start -n "$MAIN"
sleep 8
adb shell screencap -p /data/local/tmp/stopapps-home.png
adb pull /data/local/tmp/stopapps-home.png "$OUT/" || true

echo "=== launch health check ==="
if app_crashed; then
  echo "APP CRASHED ON LAUNCH"
  save_crash
  exit 1
fi
if [ -z "$(adb shell pidof "$PKG" 2>/dev/null)" ]; then
  echo "APP PROCESS NOT RUNNING after launch"
  exit 1
fi
echo "app process is alive"

echo "=== phase 2: driving a real stop-all run via UI taps ==="
wait_and_tap "^Select all$" "Select all" 120 || { echo "SELECT-ALL TAP FAILED"; exit 1; }
sleep 3
wait_and_tap "^Stop [0-9]+ apps$" "Stop N apps" 60 || { echo "STOP TAP FAILED"; exit 1; }

echo "=== waiting for the run to finish (up to 8 min) ==="
FOUND=""
for _ in $(seq 1 48); do
  sleep 10
  if app_crashed; then
    echo "APP CRASHED DURING RUN"
    save_crash
    exit 1
  fi
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

echo "=== final crash check ==="
if app_crashed; then
  echo "APP CRASH DETECTED — see ci/out/app-crash.txt"
  save_crash
  exit 1
fi

echo "EMULATOR TEST OK"
