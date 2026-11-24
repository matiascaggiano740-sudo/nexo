#!/usr/bin/env bash
# Diagnostic-only instrumented run. No app or test source is changed.
set -uo pipefail

SERIAL="${ANDROID_SERIAL:-emulator-${EMULATOR_PORT:-5554}}"
ADB=(adb -s "$SERIAL")
DIAG="app/build/diagnostics"
mkdir -p "$DIAG/tests"

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "No se encuentra el emulador en $SERIAL" >&2
  exit 2
fi

{
  echo "serial=$SERIAL"
  echo "captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "--- online CPU count ---"
  "${ADB[@]}" shell nproc 2>&1 || true
  echo "--- online CPU mask ---"
  "${ADB[@]}" shell cat /sys/devices/system/cpu/online 2>&1 || true
  echo "--- CPU info ---"
  "${ADB[@]}" shell cat /proc/cpuinfo 2>&1 || true
  echo "--- memory info ---"
  "${ADB[@]}" shell cat /proc/meminfo 2>&1 || true
  echo "--- emulator/system properties ---"
  "${ADB[@]}" shell getprop ro.build.version.sdk 2>&1 || true
  "${ADB[@]}" shell getprop ro.hardware 2>&1 || true
} > "$DIAG/device-resources.txt"

# Keep one continuous transcript. The per-test buffers are cleared separately below.
"${ADB[@]}" logcat -c >/dev/null 2>&1 || true
"${ADB[@]}" logcat -v threadtime > "$DIAG/emulator-logcat.txt" 2>&1 &
LOGCAT_PID=$!

cleanup() {
  kill "$LOGCAT_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

capture_state() {
  local out="$1" label="$2" pid="${3:-}"
  mkdir -p "$out"
  {
    echo "label=$label"
    echo "captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "nexo_pid=${pid:-not-running}"
    echo "--- foreground activity ---"
    "${ADB[@]}" shell dumpsys activity activities 2>&1 || true
  } > "$out/activity-foreground.txt"
  "${ADB[@]}" shell dumpsys activity top > "$out/activity-top.txt" 2>&1 || true
  "${ADB[@]}" shell dumpsys window windows > "$out/window-windows.txt" 2>&1 || true
  "${ADB[@]}" shell dumpsys cpuinfo > "$out/cpuinfo.txt" 2>&1 || true
  "${ADB[@]}" shell cat /proc/meminfo > "$out/meminfo.txt" 2>&1 || true
}

monitor_test() {
  local method="$1" out="$2" stopfile="$3"
  local pid="" launch_time="" now elapsed target actual
  local -a targets=(2 5 10)
  local -A done=([2]=0 [5]=0 [10]=0)
  while [[ ! -e "$stopfile" ]]; do
    if [[ -z "$pid" ]]; then
      pid="$("${ADB[@]}" shell pidof com.nexo.launcher 2>/dev/null | tr -d '\r' | awk '{print $1}')"
      if [[ -n "$pid" ]]; then
        launch_time="$(date +%s)"
        printf 'pid=%s detected_at=%s\n' "$pid" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" >> "$out/timing.txt"
      fi
    fi
    if [[ -n "$launch_time" ]]; then
      now="$(date +%s)"
      elapsed=$((now - launch_time))
      for target in "${targets[@]}"; do
        if (( elapsed >= target && done[$target] == 0 )); then
          done[$target]=1
          actual="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
          printf 'target=%ss elapsed_observed=%ss at=%s pid=%s\n' "$target" "$elapsed" "$actual" "$pid" | tee -a "$out/timing.txt"
          {
            echo "SIGQUIT request at $actual; target=${target}s; observed_elapsed=${elapsed}s; pid=$pid"
            "${ADB[@]}" shell kill -3 "$pid" 2>&1 || true
          } > "$out/thread-dump-${target}s-request.txt"
          sleep 1
          "${ADB[@]}" logcat -d -v threadtime > "$out/logcat-after-${target}s-dump.txt" 2>&1 || true
          capture_state "$out/state-${target}s" "${method}-${target}s" "$pid"
        fi
      done
    fi
    sleep 0.25
  done
  for target in "${targets[@]}"; do
    if (( done[$target] == 0 )); then
      echo "not-captured: test ended before target ${target}s after app process detection" >> "$out/timing.txt"
    fi
  done
}

methods=(
  appDrawerOpensAndClosesFromExplicitControls
  systemBackClosesTheAppDrawer
  newPageStaysEmptyAndPagesCanBeSelectedIndependently
  iconPackSelectionIsReachableFromHomeEdit
  androidCanResolveNexoAsAHomeActivityWithoutChangingTheDefault
)

printf 'method,gradle_exit_code,junit_result,test_count,failures,errors,skipped\n' > "$DIAG/test-summary.csv"
overall_status=0

echo "Installing the debug app once before isolated per-test pm clear operations."
gradle installDebug --no-daemon > "$DIAG/install-debug.log" 2>&1
install_status=$?
printf '%s\n' "$install_status" > "$DIAG/install-debug-exit-code.txt"
if (( install_status != 0 )); then
  echo "gradle installDebug failed; individual tests were not started."
  exit "$install_status"
fi

for method in "${methods[@]}"; do
  out="$DIAG/tests/$method"
  mkdir -p "$out"
  echo "=== Test individual: $method ==="
  "${ADB[@]}" logcat -c >/dev/null 2>&1 || true
  if ! "${ADB[@]}" shell pm clear com.nexo.launcher > "$out/pm-clear.txt" 2>&1 || ! grep -q '^Success' "$out/pm-clear.txt"; then
    echo "pm clear failed; this test cannot be claimed to have isolated preferences" | tee -a "$out/pm-clear.txt"
    printf '%s,%s,%s,0,0,0,0\n' "$method" "CLEAR_FAILED" "UNKNOWN" >> "$DIAG/test-summary.csv"
    overall_status=1
    continue
  fi
  rm -rf app/build/outputs/androidTest-results/connected/debug app/build/reports/androidTests/connected/debug
  mkdir -p app/build/outputs/androidTest-results/connected/debug app/build/reports/androidTests/connected/debug
  echo "Preferences cleared successfully before this isolated test." >> "$out/pm-clear.txt"
  capture_state "$out/before-test" "before-$method"
  "${ADB[@]}" shell dumpsys gfxinfo com.nexo.launcher reset > "$out/gfxinfo-reset.txt" 2>&1 || true
  "${ADB[@]}" shell atrace --async_start -c -b 32768 am wm view sched gfx > "$out/atrace-start.txt" 2>&1 || true
  stopfile="$out/monitor.stop"
  rm -f "$stopfile"
  monitor_test "$method" "$out" "$stopfile" &
  monitor_pid=$!

  gradle connectedDebugAndroidTest --no-daemon \
    "-Pandroid.testInstrumentationRunnerArguments.class=com.nexo.launcher.HomeNavigationTest#$method" \
    > "$out/gradle-output.txt" 2>&1
  test_status=$?
  printf '%s\n' "$test_status" > "$out/gradle-exit-code.txt"
  "${ADB[@]}" shell atrace --async_stop > "$out/system-trace.atrace" 2> "$out/atrace-stop.txt" || true
  touch "$stopfile"
  wait "$monitor_pid" || true
  "${ADB[@]}" logcat -d -v threadtime > "$out/logcat-after-test.txt" 2>&1 || true
  capture_state "$out/after-test" "after-$method"
  "${ADB[@]}" shell dumpsys gfxinfo com.nexo.launcher framestats > "$out/gfxinfo-framestats.txt" 2>&1 || true

  mkdir -p "$out/androidTest-results" "$out/androidTest-report"
  if [[ -d app/build/outputs/androidTest-results/connected/debug ]]; then
    cp -R app/build/outputs/androidTest-results/connected/debug/. "$out/androidTest-results/" 2>/dev/null || true
  fi
  if [[ -d app/build/reports/androidTests/connected/debug ]]; then
    cp -R app/build/reports/androidTests/connected/debug/. "$out/androidTest-report/" 2>/dev/null || true
  fi
  python3 - "$out" "$method" "$test_status" "$DIAG/test-summary.csv" <<'PY'
import csv, sys, xml.etree.ElementTree as ET
from pathlib import Path
out, method, gradle_exit, summary = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), Path(sys.argv[4])
files = list((out / 'androidTest-results').rglob('TEST-*.xml'))
tests = []
for file in files:
    try:
        root = ET.parse(file).getroot()
        tests.extend(root.iter('testcase'))
    except ET.ParseError:
        pass
matches = [t for t in tests if t.attrib.get('name') == method]
fails = sum(1 for t in tests if t.find('failure') is not None)
errors = sum(1 for t in tests if t.find('error') is not None)
skipped = sum(1 for t in tests if t.find('skipped') is not None)
if len(matches) != 1 or len(tests) != 1:
    result = 'UNKNOWN'
elif matches[0].find('failure') is not None or matches[0].find('error') is not None:
    result = 'FAIL'
elif matches[0].find('skipped') is not None:
    result = 'SKIPPED'
else:
    result = 'PASS'
(out / 'junit-result.txt').write_text(f'result={result}\nexpected_test={method}\nxml_testcases={len(tests)}\nfailures={fails}\nerrors={errors}\nskipped={skipped}\ngradle_exit_code={gradle_exit}\n')
with summary.open('a', newline='') as f:
    csv.writer(f).writerow([method, gradle_exit, result, len(tests), fails, errors, skipped])
PY
  if (( test_status != 0 )) || grep -q '^result=FAIL\|^result=UNKNOWN' "$out/junit-result.txt"; then overall_status=1; fi
  echo "=== Resultado $method: $(tr '\n' ' ' < "$out/junit-result.txt") ==="
done

"${ADB[@]}" logcat -d -v threadtime > "$DIAG/emulator-logcat-final-buffer.txt" 2>&1 || true
exit "$overall_status"
