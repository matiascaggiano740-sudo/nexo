#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME is required}"
: "${EXPECTED_CERT_SHA256:?EXPECTED_CERT_SHA256 is required}"
workspace="${GITHUB_WORKSPACE:-$PWD}"
old_apk="$(find "$workspace/historical-artifact" -type f -name '*.apk' -print -quit)"
new_apk="$workspace/app/build/outputs/apk/release/app-release.apk"
test -n "$old_apk" && test -s "$old_apk" || { echo 'Historical signed APK is missing from downloaded artifact.' >&2; exit 1; }
test -s "$new_apk" || { echo 'New Release APK is missing.' >&2; exit 1; }
echo "Historical APK: $old_apk"
echo "Candidate APK: $new_apk"
apksigner="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n 1)"
old_cert="$("$apksigner" verify --print-certs "$old_apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1 | tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]')"
new_cert="$("$apksigner" verify --print-certs "$new_apk" | sed -n 's/.*certificate SHA-256 digest: //p' | head -n 1 | tr -d ':[:space:]' | tr '[:lower:]' '[:upper:]')"
test "$old_cert" = "$EXPECTED_CERT_SHA256" || { echo 'Historical APK certificate mismatch.' >&2; exit 1; }
test "$new_cert" = "$EXPECTED_CERT_SHA256" || { echo 'Candidate APK certificate mismatch.' >&2; exit 1; }
adb install "$old_apk"
adb install "$workspace/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
seed_result="$(adb shell am instrument -w -e class com.nexo.launcher.UpgradePersistenceTest#seedUserDataBeforeUpgrade com.nexo.launcher.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
printf '%s\n' "$seed_result"
echo "$seed_result" | grep -Fq 'OK (1 test)'
adb install -r "$new_apk"
upgrade_result="$(adb shell am instrument -w -e class com.nexo.launcher.UpgradePersistenceTest#updatedPackageKeepsUserDataAndHasNewVersion com.nexo.launcher.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
printf '%s\n' "$upgrade_result"
echo "$upgrade_result" | grep -Fq 'OK (1 test)'
echo "Signed in-place update succeeded; user preferences survived. Certificate: $new_cert"
