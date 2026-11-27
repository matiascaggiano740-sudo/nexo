#!/usr/bin/env bash
# Diagnostic-only instrumented run. No app or test source is changed.
set -uo pipefail

SERIAL="${ANDROID_SERIAL:-emulator-${EMULATOR_PORT:-5554}}"
ADB=(adb -s "$SERIAL")
DIAG="app/build/diagnostics"
mkdir -p "$DIAG/tests"

printf 'diagnostic_bash=%s\n' "$BASH_VERSION" | tee "$DIAG/bash-version.txt"
printf 'bash_executable=%s\n' "$BASH" | tee -a "$DIAG/bash-version.txt"
"$BASH" --version | head -n 1 | tee -a "$DIAG/bash-version.txt"
printf 'serial=%s\n' "$SERIAL" > "$DIAG/initial-device-state.txt"
adb devices -l >> "$DIAG/initial-device-state.txt" 2>&1 || true
if ! "${ADB[@]}" get-state >> "$DIAG/initial-device-state.txt" 2>&1; then
  echo "ERROR: ADB device $SERIAL is not connected before diagnostics." | tee -a "$DIAG/initial-device-state.txt" >&2
  exit 2
fi

{
  echo "serial=$SERIAL"
  echo "captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo "diagnostic_bash=$BASH_VERSION"
  echo "host_nproc=$(nproc 2>&1 || true)"
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
  "${ADB[@]}" shell getprop init.svc.vold 2>&1 || true
  echo "--- shared storage mount ---"
  "${ADB[@]}" shell dumpsys mount 2>&1 || true
  "${ADB[@]}" shell df -h /sdcard 2>&1 || true
} > "$DIAG/device-resources.txt"

"${ADB[@]}" logcat -c >/dev/null 2>&1 || true
"${ADB[@]}" logcat -v threadtime > "$DIAG/emulator-logcat.txt" 2>&1 &
LOGCAT_PID=$!
cleanup() {
  kill "$LOGCAT_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

adb_online() {
  local state_file="$1"
  if ! "${ADB[@]}" get-state > "$state_file" 2>&1; then
    return 1
  fi
  grep -qx 'device' "$state_file"
}

capture_host_device_list() {
  local out="$1"
  mkdir -p "$out"
  {
    echo "captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    adb devices -l 2>&1 || true
  } > "$out/adb-devices.txt"
  if adb_online "$out/adb-get-state.txt"; then
    echo "adb_state=online" >> "$out/adb-get-state.txt"
    "${ADB[@]}" shell getprop sys.boot_completed > "$out/sys-boot-completed.txt" 2>&1 || true
    "${ADB[@]}" shell cat /proc/uptime > "$out/uptime.txt" 2>&1 || true
    "${ADB[@]}" shell cat /proc/meminfo > "$out/meminfo.txt" 2>&1 || true
    "${ADB[@]}" shell cat /sys/devices/system/cpu/online > "$out/cpu-online.txt" 2>&1 || true
    "${ADB[@]}" shell dumpsys activity activities > "$out/activity-foreground.txt" 2>&1 || true
    "${ADB[@]}" shell dumpsys activity top > "$out/activity-top.txt" 2>&1 || true
    "${ADB[@]}" shell dumpsys activity lastanr > "$out/activity-lastanr.txt" 2>&1 || true
    "${ADB[@]}" shell dumpsys activity exit-info com.nexo.launcher > "$out/activity-exit-info-nexo.txt" 2>&1 || true
    "${ADB[@]}" shell dumpsys window windows > "$out/window-windows.txt" 2>&1 || true
    "${ADB[@]}" shell dumpsys cpuinfo > "$out/cpuinfo.txt" 2>&1 || true
    "${ADB[@]}" shell dumpsys mount > "$out/mount.txt" 2>&1 || true
    "${ADB[@]}" shell ls -lt /data/anr > "$out/anr-list.txt" 2>&1 || true
    "${ADB[@]}" shell df -h /sdcard > "$out/storage-df.txt" 2>&1 || true
    return 0
  fi
  echo "adb_state=unavailable" >> "$out/adb-get-state.txt"
  return 1
}

capture_state() {
  local out="$1" label="$2" pid="${3:-}"
  mkdir -p "$out"
  {
    echo "label=$label"
    echo "captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "nexo_pid=${pid:-not-running}"
  } > "$out/capture-info.txt"
  capture_host_device_list "$out"
}

capture_target() {
  local method="$1" out="$2" target="$3" pid="$4" actual
  actual="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  printf 'SIGQUIT requested_at=%s target=%ss pid=%s\n' "$actual" "$target" "$pid" > "$out/thread-dump-${target}s-request.txt"
  "${ADB[@]}" shell kill -3 "$pid" >> "$out/thread-dump-${target}s-request.txt" 2>&1 || true
  "${ADB[@]}" logcat -d -v threadtime > "$out/logcat-after-${target}s-dump.txt" 2>&1 || true
  capture_state "$out/state-${target}s" "${method}-${target}s" "$pid"
}

monitor_test() {
  local method="$1" out="$2" stopfile="$3"
  local pid="" launch_time="" now elapsed target
  local done_2=0 done_5=0 done_10=0
  local -a targets=(2 5 10)
  local -a capture_pids=()
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
        case "$target" in
          2) [[ "$done_2" -eq 0 ]] || continue ;;
          5) [[ "$done_5" -eq 0 ]] || continue ;;
          10) [[ "$done_10" -eq 0 ]] || continue ;;
        esac
        if (( elapsed >= target )); then
          case "$target" in
            2) done_2=1 ;;
            5) done_5=1 ;;
            10) done_10=1 ;;
          esac
          printf 'target=%ss elapsed_observed=%ss at=%s pid=%s\n' "$target" "$elapsed" "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$pid" | tee -a "$out/timing.txt"
          capture_target "$method" "$out" "$target" "$pid" &
          capture_pids+=("$!")
        fi
      done
    fi
    sleep 0.2
  done
  for target in "${targets[@]}"; do
    case "$target" in
      2) [[ "$done_2" -eq 1 ]] || echo "not-captured: test ended before target 2s after app process detection" >> "$out/timing.txt" ;;
      5) [[ "$done_5" -eq 1 ]] || echo "not-captured: test ended before target 5s after app process detection" >> "$out/timing.txt" ;;
      10) [[ "$done_10" -eq 1 ]] || echo "not-captured: test ended before target 10s after app process detection" >> "$out/timing.txt" ;;
    esac
  done
  local capture_pid
  for capture_pid in "${capture_pids[@]}"; do
    wait "$capture_pid" || true
  done
}

methods=(
  drawerSearchFiltersAsYouTypeAndKeepsPartialRowsLeftAligned
  homeSearchIsCompactCenteredAboveTheDock
  folderPreviewUsesNineAlignedIconsAndFolderEditingHasNoStaticLabel
  homeEditUsesLivePocoStyleOverlayAndNoDoubleTapLock
  doubleTapEmptyHomeCellDoesNotTriggerLockFeature
  activityAlwaysRequestsTheLiveAndroidWallpaper
  savedHomeQuoteCanBeRemovedWithoutReturningAsDefault
  appsCanBeRemovedFromCreatedPageWithoutChangingHome
  appCanBeRemovedFromHomeWithoutUninstallingIt
  widgetPickerUsesNexoFullScreenLayoutWithSearchAndProviderPreview
  longPressingHomeAppEntersPocoEditModeWithoutLaunchingIt
  swipeUpOnHomeOpensTheAppDrawer
  swipeDownAtTopOfDrawerClosesIt
  emptyPageCanBeDeletedFromLiveEditMode
  longPressDragMovesAnAppIntoAnEmptyHomeCellAndPersistsIt
  longPressDragMovesAnAppAcrossPagesAndPersistsSelection
  longPressDragMovesAnAppFromHomeToDockAndPersistsBothLocations
  populatedPageDeletionRequiresConfirmation
  widgetCanBeRemovedFromLiveEditWithoutRemovingItsProviderApp
  deletingPageWithWidgetConfirmsAndRemovesItsPlacement
  appDrawerHasNoRedundantCloseButton
  systemBackClosesTheAppDrawer
  newPageStaysEmptyAndPagesCanBeSelectedIndependently
  iconPackSelectionIsReachableFromHomeEdit
  updateCheckerIsReachableFromNexoSettings
  androidCanResolveNexoAsAHomeActivityWithoutChangingTheDefault
)
printf 'method,gradle_exit_code,junit_result,test_count,failures,errors,skipped,execution_state\n' > "$DIAG/test-summary.csv"
attempted_tests=0
printf '%s\n' "$attempted_tests" > "$DIAG/attempted-test-commands.txt"
overall_status=0
stop_reason=""

if ! adb_online "$DIAG/pre-build-adb-state.txt"; then
  echo "ERROR: device disconnected before diagnostics." >&2
  exit 2
fi

echo "Building the debug app and instrumented APK before isolated test invocations."
build_status=1
attempt=1
while (( attempt <= 3 )); do
  gradle assembleDebug assembleDebugAndroidTest --no-daemon > "$DIAG/assemble-debug-and-tests-attempt-$attempt.log" 2>&1
  build_status=$?
  if (( build_status == 0 )); then
    cp "$DIAG/assemble-debug-and-tests-attempt-$attempt.log" "$DIAG/assemble-debug-and-tests.log"
    break
  fi
  echo "Gradle build attempt $attempt failed with exit $build_status; retrying after a short backoff."
  sleep $((attempt * 10))
  attempt=$((attempt + 1))
done
printf '%s\n' "$build_status" > "$DIAG/assemble-debug-and-tests-exit-code.txt"
if (( build_status != 0 )); then
  echo "Gradle assembleDebug/assembleDebugAndroidTest failed; tests were not started."
  exit "$build_status"
fi
if [[ ! -f app/build/outputs/apk/debug/app-debug.apk || ! -f app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk ]]; then
  echo "ERROR: expected debug APK or instrumentation APK is missing." | tee "$DIAG/missing-test-apk.txt" >&2
  exit 3
fi

mark_not_run() {
  local first="$1" reason="$2" after_first="${3:-0}" method found=0
  for method in "${methods[@]}"; do
    if [[ "$method" == "$first" ]]; then
      found=1
      if [[ "$after_first" -eq 1 ]]; then found=2; continue; fi
    fi
    if [[ "$found" -eq 1 || "$found" -eq 2 ]]; then
      printf '%s,%s,%s,0,0,0,0,NOT_RUN:%s\n' "$method" "NA" "UNKNOWN" "$reason" >> "$DIAG/test-summary.csv"
      mkdir -p "$DIAG/tests/$method"
      printf 'not_run_reason=%s\n' "$reason" > "$DIAG/tests/$method/not-run.txt"
    fi
  done
}

for method in "${methods[@]}"; do
  out="$DIAG/tests/$method"
  mkdir -p "$out"
  echo "=== Test individual: $method ==="
  if ! adb_online "$out/pre-test-adb-state.txt"; then
    echo "Device disconnected before $method; capturing host/device state and stopping the sequence." | tee "$out/device-disconnected.txt"
    capture_host_device_list "$out/failure-device-state" || true
    "${ADB[@]}" logcat -d -v threadtime > "$out/failure-logcat.txt" 2>&1 || true
    stop_reason="DEVICE_DISCONNECTED_BEFORE_TEST_$method"
    overall_status=1
    mark_not_run "$method" "$stop_reason"
    break
  fi

  # connectedDebugAndroidTest uninstalls test APKs after every invocation. Reinstall
  # the debug app package before pm clear so the preference reset is actually run.
  "${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk > "$out/install-debug-app.txt" 2>&1
  install_status=$?
  printf '%s\n' "$install_status" > "$out/install-debug-app-exit-code.txt"
  if (( install_status != 0 )); then
    capture_host_device_list "$out/failure-device-state" || true
    "${ADB[@]}" logcat -d -v threadtime > "$out/failure-logcat.txt" 2>&1 || true
    printf '%s,%s,%s,0,0,0,0,INSTALL_FAILED\n' "$method" "$install_status" "UNKNOWN" >> "$DIAG/test-summary.csv"
    stop_reason="DEBUG_APP_INSTALL_FAILED_$method"
    overall_status=1
    mark_not_run "$method" "$stop_reason" 1
    break
  fi

  "${ADB[@]}" logcat -c >/dev/null 2>&1 || true
  if ! adb_online "$out/pre-pm-clear-adb-state.txt"; then
    capture_host_device_list "$out/failure-device-state" || true
    "${ADB[@]}" logcat -d -v threadtime > "$out/failure-logcat.txt" 2>&1 || true
    printf '%s,%s,%s,0,0,0,0,DEVICE_DISCONNECTED\n' "$method" "NA" "UNKNOWN" >> "$DIAG/test-summary.csv"
    stop_reason="DEVICE_DISCONNECTED_BEFORE_PM_CLEAR_$method"
    overall_status=1
    mark_not_run "$method" "$stop_reason" 1
    break
  fi
  "${ADB[@]}" shell pm clear com.nexo.launcher > "$out/pm-clear.txt" 2>&1
  pm_clear_status=$?
  if (( pm_clear_status != 0 )) || ! grep -q '^Success' "$out/pm-clear.txt"; then
    echo "pm clear did not return Success; this test and the rest of the sequence are not run." | tee -a "$out/pm-clear.txt"
    capture_host_device_list "$out/failure-device-state" || true
    "${ADB[@]}" logcat -d -v threadtime > "$out/failure-logcat.txt" 2>&1 || true
    printf '%s,%s,%s,0,0,0,0,CLEAR_FAILED\n' "$method" "$pm_clear_status" "UNKNOWN" >> "$DIAG/test-summary.csv"
    stop_reason="PM_CLEAR_FAILED_$method"
    overall_status=1
    mark_not_run "$method" "$stop_reason" 1
    break
  fi
  echo "pm clear confirmed: Success" >> "$out/pm-clear.txt"

  # UTP writes additional test output under shared storage. Probe that path before
  # the invocation and preserve the error if Android's emulated-storage FUSE is down.
  "${ADB[@]}" shell 'mkdir -p /sdcard/Android/media/com.nexo.launcher/additional_test_output && test -d /sdcard/Android/media/com.nexo.launcher/additional_test_output' > "$out/shared-storage-preflight.txt" 2>&1
  storage_status=$?
  if (( storage_status != 0 )); then
    echo "Shared-storage preflight failed; stopping to preserve diagnostics." | tee -a "$out/shared-storage-preflight.txt"
    capture_host_device_list "$out/failure-device-state" || true
    "${ADB[@]}" logcat -d -v threadtime > "$out/failure-logcat.txt" 2>&1 || true
    printf '%s,%s,%s,0,0,0,0,STORAGE_UNAVAILABLE\n' "$method" "NA" "UNKNOWN" >> "$DIAG/test-summary.csv"
    stop_reason="SHARED_STORAGE_UNAVAILABLE_$method"
    overall_status=1
    mark_not_run "$method" "$stop_reason" 1
    break
  fi

  rm -rf app/build/outputs/androidTest-results/connected/debug app/build/reports/androidTests/connected/debug
  mkdir -p app/build/outputs/androidTest-results/connected/debug app/build/reports/androidTests/connected/debug
  capture_state "$out/before-test" "before-$method"
  "${ADB[@]}" shell dumpsys gfxinfo com.nexo.launcher reset > "$out/gfxinfo-reset.txt" 2>&1 || true
  "${ADB[@]}" shell atrace --async_start -c -b 32768 am wm view sched gfx > "$out/atrace-start.txt" 2>&1 || true
  stopfile="$out/monitor.stop"
  rm -f "$stopfile"
  monitor_test "$method" "$out" "$stopfile" &
  monitor_pid=$!

  attempted_tests=$((attempted_tests + 1))
  printf '%s\n' "$attempted_tests" > "$DIAG/attempted-test-commands.txt"
  gradle connectedDebugAndroidTest --no-daemon \
    "-Pandroid.testInstrumentationRunnerArguments.class=com.nexo.launcher.HomeNavigationTest#$method" \
    > "$out/gradle-output.txt" 2>&1
  test_status=$?
  printf '%s\n' "$test_status" > "$out/gradle-exit-code.txt"
  "${ADB[@]}" shell atrace --async_stop > "$out/system-trace.atrace" 2> "$out/atrace-stop.txt" || true
  touch "$stopfile"
  wait "$monitor_pid" || true

  mkdir -p "$out/androidTest-results" "$out/androidTest-report"
  if [[ -d app/build/outputs/androidTest-results/connected/debug ]]; then
    cp -R app/build/outputs/androidTest-results/connected/debug/. "$out/androidTest-results/" 2>/dev/null || true
  fi
  if [[ -d app/build/reports/androidTests/connected/debug ]]; then
    cp -R app/build/reports/androidTests/connected/debug/. "$out/androidTest-report/" 2>/dev/null || true
  fi
  "${ADB[@]}" logcat -d -v threadtime > "$out/logcat-after-test.txt" 2>&1 || true
  capture_host_device_list "$out/after-test-device-state"; device_status=$?
  capture_state "$out/after-test" "after-$method"
  "${ADB[@]}" shell dumpsys gfxinfo com.nexo.launcher framestats > "$out/gfxinfo-framestats.txt" 2>&1 || true
  if (( device_status != 0 )); then
    echo "ADB device unavailable after instrumentation." > "$out/device-health-failure.txt"
  elif ! "${ADB[@]}" shell 'test -d /sdcard/Android/media' > "$out/shared-storage-postflight.txt" 2>&1; then
    echo "Android shared storage is unavailable after instrumentation." > "$out/device-health-failure.txt"
  fi
  if grep -R -F -q 'Transport endpoint is not connected' "$out/androidTest-results" 2>/dev/null; then
    echo "UTP reported Transport endpoint is not connected while accessing Android shared storage." > "$out/device-health-failure.txt"
  fi
  if grep -R -F -q 'Process crashed.' "$out/androidTest-results" 2>/dev/null; then
    echo "UTP reported instrumentation Process crashed; stopping further instrumentation attempts." > "$out/instrumentation-failure.txt"
  fi

  python3 - "$out" "$method" "$test_status" "$DIAG/test-summary.csv" <<'PY'
import csv, sys, xml.etree.ElementTree as ET
from pathlib import Path
out, method, gradle_exit, summary = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), Path(sys.argv[4])
files = sorted((out / 'androidTest-results').rglob('TEST-*.xml'))
parsed = []
parse_errors = []
for file in files:
    try:
        root = ET.parse(file).getroot()
        parsed.append((file, root, list(root.iter('testcase'))))
    except (ET.ParseError, OSError) as exc:
        parse_errors.append(f'{file}: {exc}')
all_cases = [case for _, _, cases in parsed for case in cases]
matching = [case for case in all_cases if case.attrib.get('name') == method and case.attrib.get('classname') == 'com.nexo.launcher.HomeNavigationTest']
failures = [case.find('failure') for case in all_cases if case.find('failure') is not None]
errors = [case.find('error') for case in all_cases if case.find('error') is not None]
skipped = [case.find('skipped') for case in all_cases if case.find('skipped') is not None]
valid_one = len(files) == 1 and len(parsed) == 1 and len(all_cases) == 1 and len(matching) == 1
if not valid_one:
    result = 'UNKNOWN'
elif failures or errors:
    result = 'FAIL'
elif skipped:
    result = 'SKIPPED'
elif gradle_exit == 0:
    result = 'PASS'
else:
    result = 'UNKNOWN'
lines = [f'result={result}', f'expected_test=com.nexo.launcher.HomeNavigationTest#{method}', f'junit_files={len(files)}', f'valid_xml_files={len(parsed)}', f'xml_testcases={len(all_cases)}', f'matching_testcases={len(matching)}', f'failures={len(failures)}', f'errors={len(errors)}', f'skipped={len(skipped)}', f'gradle_exit_code={gradle_exit}']
if parse_errors:
    lines.append('xml_parse_errors=' + ' | '.join(parse_errors))
(out / 'junit-result.txt').write_text('\n'.join(lines) + '\n')
with (out / 'failure-details.txt').open('w') as dest:
    for label, nodes in (('FAILURE', failures), ('ERROR', errors)):
        for node in nodes:
            dest.write(f'[{label}] {node.attrib.get("message", "")} type={node.attrib.get("type", "")}\n')
            dest.write(''.join(node.itertext()) + '\n\n')
    for file, root, _ in parsed:
        stderr = root.find('system-err')
        if stderr is not None and (stderr.text or '').strip():
            dest.write(f'[SYSTEM-ERR] {file}\n{stderr.text}\n\n')
with summary.open('a', newline='') as f:
    csv.writer(f).writerow([method, gradle_exit, result, len(all_cases), len(failures), len(errors), len(skipped), 'EXECUTED'])
PY

  if [[ -f "$out/device-health-failure.txt" || -f "$out/instrumentation-failure.txt" ]]; then
    overall_status=1
    stop_reason="DEVICE_OR_INSTRUMENTATION_UNHEALTHY_AFTER_$method"
    echo "Stopping sequence: $(cat "$out/device-health-failure.txt" "$out/instrumentation-failure.txt" 2>/dev/null | tr '\n' ' ')"
    # One final best-effort capture after the failure; errors remain in the files.
    adb devices -l > "$out/failure-adb-devices.txt" 2>&1 || true
    "${ADB[@]}" get-state > "$out/failure-adb-get-state.txt" 2>&1 || true
    "${ADB[@]}" logcat -d -v threadtime > "$out/failure-logcat.txt" 2>&1 || true
    capture_host_device_list "$out/failure-device-state" || true
  fi
  junit_result="$(grep '^result=' "$out/junit-result.txt" | cut -d= -f2)"
  if [[ "$junit_result" != "PASS" ]]; then overall_status=1; fi
  if [[ "$junit_result" == "UNKNOWN" && -z "$stop_reason" ]]; then
    stop_reason="NO_VALID_JUNIT_RESULT_AFTER_$method"
    echo "No exactly-one valid JUnit result exists for this test; stopping subsequent instrumentation." > "$out/instrumentation-failure.txt"
  fi
  echo "=== Resultado $method: $(tr '\n' ' ' < "$out/junit-result.txt") ==="
  if [[ -n "$stop_reason" ]]; then
    mark_not_run "$method" "$stop_reason" 1
    break
  fi
done

"${ADB[@]}" logcat -d -v threadtime > "$DIAG/emulator-logcat-final-buffer.txt" 2>&1 || true
if [[ -n "$stop_reason" ]]; then
  printf 'stopped_reason=%s\n' "$stop_reason" > "$DIAG/stopped-early.txt"
fi
exit "$overall_status"
