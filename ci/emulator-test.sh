#!/usr/bin/env bash
# Phase-2 emulator test for Stop Apps.
# Runs on a GitHub Actions emulator (AOSP / Google APIs image, NOT MIUI):
# installs the APK, grants usage-stats, enables the accessibility service,
# launches the app, then:
#   2a. taps "Select all" and verifies N apps get selected, then "Clear";
#   2b. searches for one real app (Chrome/Gmail/Maps/YouTube), selects it,
#       taps "Stop 1 apps" and verifies the ForceStopEngine automation
#       trace ([dbg] lines) in logcat through to the run-finished marker.
# The harness tolerates slow emulator boot and a wedged system_server
# ("Process system isn't responding" — common on software-emulated runners):
# every adb call that can block runs under `timeout`, a persistent system ANR
# is escalated from "Wait" to "Close app" (soft reboot) and, if the framework
# still won't come back, to a full `adb reboot`; the app is then relaunched
# and polling continues. It performs REAL crash detection: any FATAL
# EXCEPTION whose "Process:" line is our package fails the run.
# NOTE: this validates the full automation pipeline end-to-end on AOSP
# Settings. It cannot reproduce MIUI-specific Settings UI behavior.
#
# RETRY CONTRACT with the workflow: on success the script writes
# $OUT/TEST_OK. On environmental failure (adb lost / device never usable)
# it writes $OUT/RETRYABLE and exits 75 (EX_TEMPFAIL); the workflow then
# boots a FRESH emulator and retries the test phase once. A genuine test
# failure (app crash, engine never engaged) exits 1 with no marker, and
# the workflow fails honestly after at most 2 attempts.
set -u

APK="${1:?usage: emulator-test.sh <apk>}"
# OUT_DIR is set by CI for the retry attempt (fresh emulator) so its
# diagnostics don't clobber the first attempt's.
OUT="${OUT_DIR:-ci/out}"
mkdir -p "$OUT"

PKG="com.stopapps.app"
SVC="com.stopapps.app/com.stopapps.app.accessibility.StopAccessService"
MAIN="$PKG/com.stopapps.app.MainActivity"

# Any nonzero exit with a dead adb device (exit 224 / offline / missing)
# is environmental, not a test result: leave a RETRYABLE marker so the
# workflow boots a fresh emulator and tries once more instead of failing.
on_exit() {
  local code=$?
  if [ "$code" -ne 0 ] && [ ! -f "$OUT/TEST_OK" ] \
     && [ "$(timeout 20 adb get-state 2>/dev/null | tr -d '\r')" != "device" ]; then
    echo "device lost at exit (code $code) - marking run RETRYABLE"
    touch "$OUT/RETRYABLE"
  fi
}
trap on_exit EXIT

# adb that can never hang forever. A wedged system_server makes plain adb
# block indefinitely (notably `uiautomator dump`), which turns every poll
# loop glacial — the script's own timeouts stop being enforced. 120s is
# generous for a healthy-but-slow software emulator (dumps take ~2-5s).
tadb() { timeout 120 adb "$@"; }

# ---------- pre-flight: make sure adb actually has a live device ----------
# The runner waits for boot, but on software emulation adb can lose the
# device right after boot (exit 224 / "offline"). Probe in stages; if the
# device never becomes usable, exit retryable so the workflow boots a
# fresh emulator instead of failing on infra.
preflight() {
  echo "=== adb pre-flight ==="
  local i state
  for i in $(seq 1 60); do
    if timeout 30 adb wait-for-device > /dev/null 2>&1; then break; fi
    sleep 10
  done
  for i in $(seq 1 60); do
    state=$(timeout 30 adb get-state 2>/dev/null | tr -d '\r')
    [ "$state" = "device" ] && break
    # nudge a half-dead adb server back to life
    timeout 30 adb reconnect > /dev/null 2>&1 || true
    sleep 10
  done
  for i in $(seq 1 60); do
    if [ "$(timeout 30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] \
       && timeout 30 adb shell pm path android > /dev/null 2>&1; then
      echo "pre-flight OK: device online, boot completed, package manager alive"
      return 0
    fi
    sleep 10
  done
  echo "PRE-FLIGHT FAILED: no usable adb device after ~30 min"
  touch "$OUT/RETRYABLE"
  exit 75
}
preflight

echo "=== installing $APK ==="
timeout 600 adb install -r "$APK"

echo "=== granting usage-stats access ==="
tadb shell appops set "$PKG" GET_USAGE_STATS allow

echo "=== enabling accessibility service ==="
tadb shell settings put secure enabled_accessibility_services "$SVC"
tadb shell settings put secure accessibility_enabled 1
sleep 3
tadb shell settings get secure enabled_accessibility_services | tee "$OUT/accessibility.txt"
if ! grep -q "StopAccessService" "$OUT/accessibility.txt"; then
  echo "ACCESSIBILITY SERVICE NOT ENABLED"; exit 1
fi

echo "=== granting notification permission (avoid runtime dialog) ==="
tadb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true

# ---------- UI automation helpers ----------

# Dump the current UI hierarchy into $OUT/ui-dump.xml.
# Returns 1 (and removes any stale dump) when the system is too wedged to
# produce one, so callers poll again instead of acting on stale coordinates.
ui_dump() {
  rm -f "$OUT/ui-dump.xml"
  tadb shell uiautomator dump /data/local/tmp/ui.xml > /dev/null 2>&1 || return 1
  tadb pull /data/local/tmp/ui.xml "$OUT/ui-dump.xml" > /dev/null 2>&1 || return 1
  [ -s "$OUT/ui-dump.xml" ] || return 1
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
  ui_dump || return 1
  local bounds
  if ! bounds=$(find_node "$pattern"); then
    return 1
  fi
  echo "tapping '$desc' at $bounds"
  # shellcheck disable=SC2086
  tadb shell input tap $bounds || return 1
}

# True when logcat holds a FATAL EXCEPTION for our package.
app_crashed() {
  tadb logcat -d -t 4000 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -q "Process: $PKG"
}

# Save our app's crash stack trace for the artifacts.
save_crash() {
  tadb logcat -d 2>/dev/null | grep -B2 -A30 "FATAL EXCEPTION" > "$OUT/app-crash.txt" || true
}

# Consecutive polls where the system looked wedged (system ANR dialog shown
# or uiautomator dump itself timing out). Escalates Wait -> Close app ->
# recover (soft reboot, else full reboot + app relaunch).
WEDGED_STREAK=0

# Bring the device back after a wedged system_server: wait for the framework
# to answer again (soft reboot follows the ANR dialog's "Close app"), else
# fall back to a full `adb reboot`; then relaunch our app (any reboot kills
# it — its a11y/usage-stats settings persist) and confirm it is alive.
# Returns 1 when the device cannot be recovered.
recover_wedged_system() {
  echo "=== recovering wedged system ==="
  local i ok=""
  for i in $(seq 1 24); do
    if timeout 45 adb shell uiautomator dump /data/local/tmp/probe.xml > /dev/null 2>&1; then
      ok=1; break
    fi
    sleep 10
  done
  if [ -z "$ok" ]; then
    echo "framework still dead -> full adb reboot"
    timeout 60 adb reboot || true
    for i in $(seq 1 90); do
      if timeout 30 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'; then
        ok=1; break
      fi
      sleep 10
    done
    sleep 30
  fi
  if [ -z "$ok" ]; then
    echo "DEVICE UNRECOVERABLE"
    return 1
  fi
  echo "framework back - relaunching app"
  tadb shell am start -n "$MAIN" > /dev/null 2>&1 || true
  sleep 10
  if [ -z "$(timeout 30 adb shell pidof "$PKG" 2>/dev/null)" ]; then
    echo "APP NOT RUNNING after recovery"
    return 1
  fi
  echo "recovered: app relaunched and alive"
  return 0
}

# Dismiss system "X isn't responding" ANR dialogs. Fails (returns 1) if the
# ANR is for OUR app. A system ANR (or a wedged dump) that persists across
# 3 consecutive polls is escalated: tap "Close app" to kill the wedged
# system process, then recover and continue polling.
dismiss_system_dialogs() {
  local bounds
  if ! ui_dump; then
    echo "WARN: ui dump timed out - system looks wedged"
    WEDGED_STREAK=$((WEDGED_STREAK + 1))
  else
    if ! grep -q "isn't responding" "$OUT/ui-dump.xml"; then
      WEDGED_STREAK=0
      return 0
    fi
    local title
    title=$(grep -o 'text="[^"]*isn'"'"'t responding"' "$OUT/ui-dump.xml" | head -1)
    echo "system ANR dialog: $title"
    if [[ "$title" == *"Stop Apps"* ]]; then
      echo "APP ANR — our app is not responding"
      return 1
    fi
    WEDGED_STREAK=$((WEDGED_STREAK + 1))
  fi
  if [ "$WEDGED_STREAK" -ge 3 ]; then
    echo "system wedged for $WEDGED_STREAK consecutive polls - tapping 'Close app'"
    ui_dump || true
    if bounds=$(find_node "^Close app$"); then
      echo "tapping 'Close app' at $bounds"
      # shellcheck disable=SC2086
      tadb shell input tap $bounds || true
      sleep 5
    fi
    WEDGED_STREAK=0
    recover_wedged_system || return 1
    return 0
  fi
  ui_dump || true
  if bounds=$(find_node "^Wait$"); then
    echo "tapping 'Wait' at $bounds (wedged streak $WEDGED_STREAK)"
    # shellcheck disable=SC2086
    tadb shell input tap $bounds || true
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
    ui_dump || { sleep 5; continue; }
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
  tadb shell screencap -p "/data/local/tmp/$1"
  tadb pull "/data/local/tmp/$1" "$OUT/" > /dev/null 2>&1 || true
}

# ---------- test ----------

echo "=== launching app ==="
tadb shell am start -n "$MAIN"
sleep 8
screenshot "stopapps-home.png"

echo "=== launch health check ==="
if app_crashed; then
  echo "APP CRASHED ON LAUNCH"
  save_crash
  exit 1
fi
if [ -z "$(timeout 30 adb shell pidof "$PKG" 2>/dev/null)" ]; then
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
  for _ in $(seq 1 30); do tadb shell input keyevent 67; done
  tadb shell input text "$CAND"
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
  if tadb logcat -d -t 4000 2>/dev/null | grep -q "\[dbg\] run finished"; then FOUND=1; break; fi
done
if [ -z "$FOUND" ]; then
  echo "RUN DID NOT FINISH IN TIME"
  tadb logcat -d -t 4000 > "$OUT/logcat-full.txt" || true
  exit 1
fi

echo "=== collecting stop-run diagnostics ==="
tadb logcat -d > "$OUT/logcat-full.txt" || true
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

touch "$OUT/TEST_OK"
echo "EMULATOR TEST OK"
