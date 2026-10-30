#!/usr/bin/env bash
set -euo pipefail

cd "${GITHUB_WORKSPACE:-$(pwd)}"
ADB="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools/adb"
DEVICE="emulator-5554"

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
screenshot_name="ludomada-startup-offline.png"
mkdir -p "$evidence_dir"

# Android Test Orchestrator/UTP copies this app-specific media folder to the
# host before uninstall_after_test removes the target and test APKs.
screenshot_source=""
if [[ -d "$additional_output_root" ]]; then
  screenshot_source="$(find "$additional_output_root" -type f -name "$screenshot_name" -size +1k -print -quit)"
fi
if [[ -z "$screenshot_source" ]]; then
  echo "ANDROID_SURFACE_SCREENSHOT_PULL_FAILED: UTP additional_test_output did not contain the screenshot"
  if [[ -d "$additional_output_root" ]]; then
    find "$additional_output_root" -maxdepth 8 -type f -printf '%p %s bytes\n' | head -n 80 || true
  else
    echo "UTP additional output directory is missing: $additional_output_root"
  fi
  exit 1
fi

if ! python3 - "$screenshot_source" <<'PY'
from pathlib import Path
import struct
import sys

path = Path(sys.argv[1])
data = path.read_bytes()
if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n":
    print(f"ANDROID_SURFACE_SCREENSHOT_INVALID_PNG bytes={len(data)} path={path}")
    raise SystemExit(1)
width, height = struct.unpack(">II", data[16:24])
if width < 1 or height < 1:
    print(f"ANDROID_SURFACE_SCREENSHOT_INVALID_DIMENSIONS width={width} height={height}")
    raise SystemExit(1)
print(f"ANDROID_SURFACE_PNG_VALID bytes={len(data)} size={width}x{height}")
PY
then
  echo "ANDROID_SURFACE_SCREENSHOT_PULL_FAILED: UTP output is not a valid PNG"
  exit 1
fi

cp -- "$screenshot_source" "$evidence_dir/$screenshot_name"
echo "ANDROID_SURFACE_SCREENSHOT_CAPTURE_PASS"
