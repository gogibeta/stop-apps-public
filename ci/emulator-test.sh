#!/usr/bin/env bash
# Phase-2 emulator test for Stop Apps.
# Runs on a GitHub Actions emulator (AOSP / Google APIs image, NOT MIUI):
# installs the APK, grants usage-stats, enables the accessibility service,
# launches the app, then:
#   2a. taps "Select all" and verifies N apps get selected, then "Clear";
#   2b. searches for one real app (Chrome/Gmail/Maps/YouTube), selects it,
#       taps "Stop 1 apps" and verifies the ForceStopEngine automation
#       trace ([dbg] lines) in logcat through to the run-finished marker.
# The harness tolerates slow emulator boot (system ANR dialogs are
# dismissed — "Close app" as a last resort — and tap targets are polled)
# and performs REAL crash detection: any FATAL EXCEPTION whose "Process:"
# line is our package fails the run.
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

# Print the text of the first node matching the regex (for reading labels).
node_text() {
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
        print(t)
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

ANR_COUNT=0

# Dismiss system "X isn't responding" ANR dialogs. Fails (returns 1) if the
# ANR is for OUR app. If "Wait" doesn't clear a system ANR after ~8 tries,
# taps "Close app" to kill the wedged system process (it restarts).
dismiss_system_dialogs() {
  ui_dump
  grep -q "isn't responding" "$OUT/ui-dump.xml" || { ANR_COUNT=0; return 0; }
  local title
  title=$(grep -o 'text="[^"]*isn'"'"'t responding"' "$OUT/ui-dump.xml" | head -1)
  echo "system ANR dialog: $title"
  if [[ "$title" == *"Stop Apps"* ]]; then
    echo "APP ANR — our app is not responding"
    return 1
  fi
  ANR_COUNT=$((ANR_COUNT + 1))
  local bounds btn="Wait" bpat="^Wait$"
  if [ "$ANR_COUNT" -ge 8 ]; then
    btn="Close app"; bpat="^Close app$"; ANR_COUNT=0
  fi
  if bounds=$(find_node "$bpat"); then
    echo "tapping '$btn' at $bounds"
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

# Poll for a node whose text matches; prints its text. Returns 1 on timeout.
wait_for_text() {
  local pattern="$1" timeout_s="$2"
  local tries=$((timeout_s / 5))
  for _ in $(seq 1 "$tries"); do
    dismiss_system_dialogs || return 1
    ui_dump
    local t
    if t=$(node_text "$pattern"); then
      echo "$t"
      return 0
    fi
    sleep 5
  done
  return 1
}

screenshot() {
  adb shell screencap -p "/data/local/tmp/$1"
  adb pull "/data/local/tmp/$1" "$OUT/" > /dev/null 2>&1 || true
}

# ---------- test ----------

echo "=== launching app ==="
adb shell am start -n "$MAIN"
sleep 8
screenshot "stopapps-home.png"

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

echo "=== phase 2a: Select-all UI test ==="
# Switch to the "All" tab (maximal list) and wait until the app list has
# actually loaded before touching "Select all". On the slow software
# emulator the list can take minutes to populate, and tapping "Select all"
# on an empty list selects nothing, leaving the button at "Stop apps".
wait_and_tap "^All$" "All filter chip" 120 || { echo "ALL-CHIP TAP FAILED"; exit 1; }
# NOTE: "Settings" (com.android.settings) is deliberately NEVER in the list —
# AutoWhitelist.SYSTEM_PACKAGES excludes it — so wait for a label that is
# guaranteed on the Google-APIs emulator image instead.
wait_for_text "^(Chrome|Gmail|YouTube|Maps)$" 300 > /dev/null || { echo "APP LIST NEVER LOADED"; exit 1; }
echo "app list loaded"
STOPLABEL=""
for _ in 1 2 3; do
  wait_and_tap "^Select all$" "Select all" 120 || { echo "SELECT-ALL TAP FAILED"; exit 1; }
  if STOPLABEL=$(wait_for_text "^Stop [0-9]+ apps$" 60); then break; fi
  echo "stop button not up yet, retrying Select all"
done
if [ -z "$STOPLABEL" ]; then echo "STOP-BUTTON NEVER APPEARED"; exit 1; fi
N=$(echo "$STOPLABEL" | grep -o "[0-9][0-9]*")
echo "select-all -> '$STOPLABEL' (N=$N)"
if [ -z "$N" ] || [ "$N" -eq 0 ]; then
  echo "SELECT ALL SELECTED ZERO APPS"
  exit 1
fi
screenshot "stopapps-selected.png"
wait_and_tap "^Clear$" "Clear" 60 || { echo "CLEAR TAP FAILED"; exit 1; }
echo "select-all/clear UI OK"

echo "=== phase 2b: real single-app stop run ==="
# Use the "All" tab so the target app is listed regardless of running state.
wait_and_tap "^All$" "All filter chip" 60 || echo "WARN: All chip not found, continuing"
# Tap the search field and try candidates until one is listed.
TARGET=""
for CAND in Chrome Gmail Maps YouTube; do
  wait_and_tap "^Search apps" "search field" 60 || { echo "SEARCH FIELD NOT FOUND"; exit 1; }
  # Clear any previous query, then type the candidate.
  for _ in $(seq 1 30); do adb shell input keyevent 67; done
  adb shell input text "$CAND"
  sleep 3
  if wait_for_text "^${CAND}$" 30 > /dev/null; then
    TARGET="$CAND"
    echo "target app found: $TARGET"
    break
  fi
  echo "candidate '$CAND' not listed, trying next"
done
if [ -z "$TARGET" ]; then
  echo "NO TARGET APP FOUND"
  exit 1
fi
wait_and_tap "^${TARGET}$" "target app row" 60 || { echo "TARGET ROW TAP FAILED"; exit 1; }
STOPLABEL=$(wait_for_text "^Stop 1 apps$" 60) || { echo "STOP-1 BUTTON NEVER APPEARED"; exit 1; }
echo "selected 1 app -> '$STOPLABEL'"
wait_and_tap "^Stop 1 apps$" "Stop 1 app" 60 || { echo "STOP TAP FAILED"; exit 1; }

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
screenshot "stopapps-after.png"

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
