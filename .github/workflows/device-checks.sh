#!/usr/bin/env bash
set -euo pipefail
pkg=jp.xiaqui.artifact.store.preview
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
test_class=jp.xiaqui.artifact.PostPhaseTest
import_test_class=jp.xiaqui.artifact.RepoImportFlowTest
instrument() {
  local method=$1
  shift
  adb shell am instrument -w -e class "$test_class#$method" "$@" "$runner" | tee "$RUNNER_TEMP/inst.log"
  grep -Fq 'OK (1 test)' "$RUNNER_TEMP/inst.log"
}
import_instrument() {
  local method=$1
  adb shell am instrument -w -e class "$import_test_class#$method" -e restartCheck true "$runner" | tee "$RUNNER_TEMP/import-inst.log"
  grep -Fq 'OK (1 test)' "$RUNNER_TEMP/import-inst.log"
}
gradle connectedDebugAndroidTest
# Gradle may uninstall the target and instrumentation after connected checks.
gradle installDebug installDebugAndroidTest
instrument processSeed
adb shell am force-stop "$pkg"
instrument processRead -e restartCheck true
instrument processSeed
import_instrument seed
adb reboot
timeout 180 adb wait-for-device
for i in {1..90}; do
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then break; fi
  sleep 2
done
adb shell input keyevent 82
instrument processRead -e restartCheck true
import_instrument reopened
adb shell wm size 480x800
adb shell wm density 240
adb shell settings put system font_scale 1.3
instrument sevenRowsAndDetails
adb exec-out run-as "$pkg" cat files/seven-rows.png > seven-rows.png
test -s seven-rows.png

# On the same virtual device, exercise the actual fixed-key update over the 0.4.2 Release.
adb uninstall "$pkg"
adb install old-release.apk
adb shell run-as "$pkg" mkdir -p files/apks
adb shell run-as "$pkg" touch files/apks/upgrade-sentinel
adb install -r delivery/ReleaseStore-preview.apk
adb shell run-as "$pkg" ls files/apks/upgrade-sentinel
adb shell dumpsys package "$pkg" | grep 'versionCode=12 '
