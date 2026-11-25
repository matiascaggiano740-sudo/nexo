#!/usr/bin/env bash
set -euo pipefail

ARTIFACTS="$GITHUB_WORKSPACE/ota-e2e-artifacts"
mkdir -p "$ARTIFACTS"
APK="$GITHUB_WORKSPACE/app/build/outputs/ota-fixtures/ota-candidate-release.apk"
OLD="$GITHUB_WORKSPACE/app/build/outputs/ota-fixtures/nexo-test-base-v999.apk"
TEST_APK="$GITHUB_WORKSPACE/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"
SIGNED_TEST_APK="$GITHUB_WORKSPACE/app/build/outputs/ota-fixtures/app-release-androidTest-signed.apk"
APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n 1)"

adb install "$OLD" | tee "$ARTIFACTS/install-base.txt"
"$APKSIGNER" sign --ks "$GITHUB_WORKSPACE/nexo-release-final.p12" --ks-type PKCS12 \
  --ks-pass env:NEXO_STORE_PASSWORD --key-pass env:NEXO_KEY_PASSWORD --ks-key-alias nexo \
  --out "$SIGNED_TEST_APK" "$TEST_APK"
test_cert="$("$APKSIGNER" verify --print-certs "$SIGNED_TEST_APK" | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1 | tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]')"
test "$test_cert" = "$EXPECTED_CERT_SHA256"
"$APKSIGNER" verify --verbose "$SIGNED_TEST_APK" | tee "$ARTIFACTS/instrumentation-apk-signature.txt"
adb install "$SIGNED_TEST_APK" | tee "$ARTIFACTS/install-instrumentation.txt"
adb shell appops set com.nexo.launcher REQUEST_INSTALL_PACKAGES allow
adb shell pm grant com.nexo.launcher android.permission.REQUEST_INSTALL_PACKAGES >/dev/null 2>&1 || true

set +e
first="$(adb shell am instrument -w -e class 'com.nexo.launcher.OtaEndToEndTest#realUpdaterRejectsBadHashThenOpensAndroidInstallerForVerifiedRelease' com.nexo.launcher.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
first_status=$?
set -e
printf '%s\n' "$first" | tee "$ARTIFACTS/ota-ui-instrumentation.txt"
test "$first_status" -eq 0
echo "$first" | grep -Fq 'OK (1 test)'
adb logcat -d -s NexoOtaE2E:I > "$ARTIFACTS/ota-flow-logcat.txt" 2>&1

adb shell dumpsys activity activities > "$ARTIFACTS/installer-activity-before-confirm.txt"
grep -E 'mResumedActivity|topResumedActivity' "$ARTIFACTS/installer-activity-before-confirm.txt" | tee "$ARTIFACTS/installer-top-activity.txt"
grep -Eq 'com\.android\.packageinstaller|com\.google\.android\.packageinstaller|com\.android\.permissioncontroller' "$ARTIFACTS/installer-top-activity.txt"

# Confirm only inside the disposable CI emulator, through Android's own installer UI.
installed=0
confirmed=0
for attempt in $(seq 1 8); do
  adb shell uiautomator dump /sdcard/nexo-installer-window.xml >/dev/null 2>&1 || true
  xml="$(adb shell cat /sdcard/nexo-installer-window.xml 2>/dev/null | tr -d '\r')"
  printf '%s\n' "$xml" > "$ARTIFACTS/installer-ui-attempt-$attempt.xml"
  bounds="$(printf '%s' "$xml" | python3 -c 'import sys,xml.etree.ElementTree as E; s=sys.stdin.read();
try:
 r=E.fromstring(s)
 for n in r.iter("node"):
  if n.get("clickable")=="true" and n.get("text","").strip().lower() in {"install","update"}:
   print(n.get("bounds","")); break
except Exception: pass')"
  if [[ -n "$bounds" ]]; then
    read -r x y < <(python3 - "$bounds" <<'PY'
import re,sys
m=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',sys.argv[1])
if not m: raise SystemExit(1)
a,b,c,d=map(int,m.groups());print((a+c)//2,(b+d)//2)
PY
)
    echo "Installer confirmation button bounds=$bounds center=$x,$y" | tee "$ARTIFACTS/installer-confirmation.txt"
    adb shell input tap "$x" "$y"
    confirmed=1
    break
  fi
  sleep 2
done
test "$confirmed" -eq 1 || { echo 'Could not find the Install/Update confirmation control in Android PackageInstaller UI.' >&2; exit 1; }

echo 'Waiting for Android PackageInstaller to install versionCode=1000.' | tee -a "$ARTIFACTS/installer-confirmation.txt"
for attempt in $(seq 1 60); do
  adb shell dumpsys package com.nexo.launcher > "$ARTIFACTS/installed-package-dump.txt" 2>&1 || true
  if grep -Eq 'versionCode=1000([[:space:]]|$)' "$ARTIFACTS/installed-package-dump.txt"; then installed=1; break; fi
  sleep 2
done
test "$installed" -eq 1 || { echo 'Android installer did not complete the OTA update.' >&2; exit 1; }
echo 'Android PackageInstaller installed versionCode=1000.' | tee -a "$ARTIFACTS/installer-confirmation.txt"

adb shell dumpsys activity activities > "$ARTIFACTS/installer-activity-after-install.txt" || true
# Run a fresh instrumented process against the updated app after Android killed the old activity.
set +e
post="$(adb shell am instrument -w -e class 'com.nexo.launcher.OtaPostInstallVerificationTest#installedCandidateHasVersionAndUserPreferencesSurvived' com.nexo.launcher.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
post_status=$?
set -e
printf '%s\n' "$post" | tee "$ARTIFACTS/post-install-instrumentation.txt"
test "$post_status" -eq 0
echo "$post" | grep -Fq 'OK (1 test)'
adb logcat -d -s NexoOtaE2E:I > "$ARTIFACTS/ota-flow-logcat-final.txt" 2>&1
echo 'OTA E2E PASS: manifest error surfaced; bad hash rejected; verified APK handed to Android installer; signed update installed; preferences preserved.' | tee "$ARTIFACTS/ota-e2e-result.txt"
