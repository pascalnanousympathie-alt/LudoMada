#!/usr/bin/env bash
set -euo pipefail

cd "${GITHUB_WORKSPACE:-$(pwd)}"
ADB="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools/adb"
DEVICE="emulator-5554"

# Record the emulator API for evidence naming and diagnostics. The screenshot
# itself is stored in app-private files/ and retrieved with adb run-as.
api_level="$(timeout 8s "$ADB" -s "$DEVICE" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
if [[ ! "$api_level" =~ ^[0-9]+$ ]]; then
  echo "ANDROID_API_LEVEL_UNAVAILABLE: could not determine emulator API level"
  exit 1
fi
screenshot_name="ludomada-startup-offline.png"
screenshot_remote="files/$screenshot_name"

"$ADB" start-server >/dev/null 2>&1 || true
ready=0

# android-emulator-runner currently invokes each newline in its script field as
# a separate shell process. This script is invoked as one command so loops and
# recovery logic retain their shell state.
for attempt in $(seq 1 120); do
  devices="$("$ADB" devices 2>/dev/null | awk -v dev="$DEVICE" '$1 == dev {print $2; exit}' || true)"

  if [[ "$devices" == "device" ]]; then
    boot="$(timeout 8s "$ADB" -s "$DEVICE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    package_service="$(timeout 8s "$ADB" -s "$DEVICE" shell service check package 2>/dev/null || true)"
    if [[ "$boot" == "1" ]] && grep -q "found" <<<"$package_service"; then
      ready=1
      break
    fi
  elif [[ "$devices" == "offline" ]]; then
    # A freshly booted software-backed AVD may register before the shell is ready.
    # Nudge the transport occasionally but do not restart adb on every poll.
    if (( attempt % 10 == 0 )); then
      "$ADB" reconnect offline >/dev/null 2>&1 || true
    fi
    if (( attempt % 30 == 0 )); then
      "$ADB" kill-server >/dev/null 2>&1 || true
      "$ADB" start-server >/dev/null 2>&1 || true
    fi
  fi
  sleep 2
done

if [[ "$ready" != "1" ]]; then
  echo "EMULATOR_BOOT_DIAGNOSTICS: Android system or package manager did not become ready after 240 seconds"
  "$ADB" devices -l || true
  timeout 8s "$ADB" -s "$DEVICE" shell getprop sys.boot_completed || true
  timeout 8s "$ADB" -s "$DEVICE" shell service list | grep -i package || true
  timeout 8s "$ADB" -s "$DEVICE" logcat -d -t 1500 | tail -n 250 || true
  exit 1
fi

"$ADB" -s "$DEVICE" shell settings put global window_animation_scale 0 || true
"$ADB" -s "$DEVICE" shell settings put global transition_animation_scale 0 || true
"$ADB" -s "$DEVICE" shell settings put global animator_duration_scale 0 || true

cd android-app
set +e
echo "INSTRUMENTATION_TARGET=com.ludomadagascar.StartupUiTest (all @Test methods)"
timeout --signal=TERM --kill-after=15s 10m ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ludomadagascar.StartupUiTest \
  --no-daemon --no-parallel -Dorg.gradle.workers.max=2 --console=plain --stacktrace
test_status=$?
set -e

if [[ "$test_status" -ne 0 ]]; then
  echo "CONNECTED_ANDROID_TEST_DIAGNOSTICS: Gradle exit code $test_status"
  "$ADB" devices -l || true
  timeout 8s "$ADB" -s "$DEVICE" shell getprop sys.boot_completed || true
  timeout 8s "$ADB" -s "$DEVICE" shell service check package || true
  timeout 8s "$ADB" -s "$DEVICE" shell dumpsys activity activities | tail -n 100 || true
  timeout 8s "$ADB" -s "$DEVICE" logcat -d -t 2500 -s AndroidRuntime:E ActivityManager:E TestRunner:E AndroidJUnitRunner:E || true
  exit "$test_status"
fi

# Preserve the actual SurfaceView frame captured by UiDevice. GLSurfaceView
# renders into a separate Surface, so keep this device screenshot as an
# independent visual artifact alongside Ramus screenshots.
evidence_dir="$GITHUB_WORKSPACE/android-app/app/build/ramus-evidence"
additional_output_root="$GITHUB_WORKSPACE/android-app/app/build/outputs/connected_android_test_additional_output"
mkdir -p "$evidence_dir"

# Read the app-private screenshot through run-as. Debug APKs are debuggable
# on both API 30 and API 27, and this avoids public-storage permission variance.
screenshot_local="$evidence_dir/ludomada-startup-offline.png"
screenshot_source_kind=""
if timeout 15s "$ADB" -s "$DEVICE" exec-out run-as com.ludomadagascar cat "$screenshot_remote" > "$screenshot_local" && validate_png "$screenshot_local"; then
  screenshot_source_kind="app-private-run-as"
else
  rm -f "$screenshot_local"
  echo "ANDROID_SURFACE_SCREENSHOT_RUN_AS_FAILED: checking UTP output fallback"
  screenshot_source=""
  if [[ -d "$additional_output_root" ]]; then
    screenshot_source="$(find "$additional_output_root" -type f -name "$screenshot_name" -size +1k -print -quit)"
  fi
  if [[ -n "$screenshot_source" ]]; then
    cp -- "$screenshot_source" "$screenshot_local"
    if validate_png "$screenshot_local"; then
      screenshot_source_kind="utp"
    else
      rm -f "$screenshot_local"
    fi
  fi
fi

if [[ -z "$screenshot_source_kind" ]]; then
  timeout 8s "$ADB" -s "$DEVICE" shell run-as com.ludomadagascar ls -la files || true
  if [[ -d "$additional_output_root" ]]; then
    find "$additional_output_root" -maxdepth 8 -type f -printf '%p %s bytes\n' | head -n 80 || true
  else
    echo "UTP additional output directory is missing: $additional_output_root"
  fi
  echo "ANDROID_SURFACE_SCREENSHOT_PULL_FAILED: app-private run-as and UTP outputs unavailable"
  exit 1
fi
echo "ANDROID_SURFACE_SCREENSHOT_SOURCE=$screenshot_source_kind"
echo "API_LEVEL=$api_level"
echo "ANDROID_SURFACE_SCREENSHOT_CAPTURE_PASS"
