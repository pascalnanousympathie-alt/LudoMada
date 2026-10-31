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
screenshot_name="ludomada-startup-offline-api-$api_level.png"
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

# Capture the app-private screenshot while the debug APK is still installed.
# Gradle removes the target APK after connectedDebugAndroidTest completes, so
# do not postpone adb run-as until after Gradle exits.
evidence_dir="$GITHUB_WORKSPACE/android-app/app/build/ramus-evidence"
additional_output_root="$GITHUB_WORKSPACE/android-app/app/build/outputs/connected_android_test_additional_output"
mkdir -p "$evidence_dir"
screenshot_local="$evidence_dir/ludomada-startup-offline.png"
screenshot_tmp="$evidence_dir/.ludomada-startup-offline.png.tmp"
rm -f "$screenshot_local" "$screenshot_tmp"

validate_png() {
  python3 - "$1" <<'PY'
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
}

(
  for attempt in $(seq 1 600); do
    if timeout 4s "$ADB" -s "$DEVICE" exec-out run-as com.ludomadagascar cat "$screenshot_remote" > "$screenshot_tmp" 2>/dev/null; then
      if [[ -s "$screenshot_tmp" ]]; then
        mv -- "$screenshot_tmp" "$screenshot_local"
        echo "ANDROID_SURFACE_SCREENSHOT_CAPTURED_BEFORE_TEARDOWN"
        exit 0
      fi
    fi
    rm -f "$screenshot_tmp"
    sleep 1
  done
  echo "ANDROID_SURFACE_SCREENSHOT_WATCH_TIMEOUT"
  exit 1
) &
screenshot_watcher_pid=$!

cd android-app
set +e
echo "INSTRUMENTATION_TARGET=com.ludomadagascar.StartupUiTest (all @Test methods)"
timeout --signal=TERM --kill-after=15s 10m ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ludomadagascar.StartupUiTest \
  --no-daemon --no-parallel -Dorg.gradle.workers.max=2 --console=plain --stacktrace
test_status=$?
set -e

if [[ "$test_status" -ne 0 ]]; then
  kill "$screenshot_watcher_pid" 2>/dev/null || true
  wait "$screenshot_watcher_pid" 2>/dev/null || true
  rm -f "$screenshot_tmp"
  echo "CONNECTED_ANDROID_TEST_DIAGNOSTICS: Gradle exit code $test_status"
  "$ADB" devices -l || true
  timeout 8s "$ADB" -s "$DEVICE" shell getprop sys.boot_completed || true
  timeout 8s "$ADB" -s "$DEVICE" shell service check package || true
  timeout 8s "$ADB" -s "$DEVICE" shell dumpsys activity activities | tail -n 100 || true
  timeout 8s "$ADB" -s "$DEVICE" logcat -d -t 2500 -s AndroidRuntime:E ActivityManager:E TestRunner:E AndroidJUnitRunner:E || true
  exit "$test_status"
fi

# The watcher reads the app-private file before Gradle's post-test uninstall.
screenshot_source_kind=""
if wait "$screenshot_watcher_pid" && [[ -s "$screenshot_local" ]] && validate_png "$screenshot_local"; then
  screenshot_source_kind="app-private-run-as-before-teardown"
else
  rm -f "$screenshot_local" "$screenshot_tmp"
  echo "ANDROID_SURFACE_SCREENSHOT_RUN_AS_WATCH_FAILED: checking UTP output fallback"
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
  echo "ANDROID_SURFACE_SCREENSHOT_PULL_FAILED: pre-teardown run-as and UTP outputs unavailable"
  exit 1
fi
echo "ANDROID_SURFACE_SCREENSHOT_SOURCE=$screenshot_source_kind"
echo "API_LEVEL=$api_level"
echo "ANDROID_SURFACE_SCREENSHOT_CAPTURE_PASS"
