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
home_screenshot_remote="files/ludomada-home-preview.png"

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
home_screenshot_local="$evidence_dir/ludomada-home-preview.png"
home_screenshot_tmp="$evidence_dir/.ludomada-home-preview.png.tmp"
rm -f "$screenshot_local" "$screenshot_tmp" "$home_screenshot_local" "$home_screenshot_tmp"

validate_png() {
  python3 - "$1" <<'PY'
from pathlib import Path
import struct
import sys
import zlib

path = Path(sys.argv[1])
data = path.read_bytes()
if len(data) < 1024 or data[:8] != b"\x89PNG\r\n\x1a\n":
    print(f"ANDROID_SURFACE_SCREENSHOT_INVALID_PNG bytes={len(data)} path={path}")
    raise SystemExit(1)

width, height = struct.unpack(">II", data[16:24])
if width < 1 or height < 1:
    print(f"ANDROID_SURFACE_SCREENSHOT_INVALID_DIMENSIONS width={width} height={height}")
    raise SystemExit(1)

# Reject a partial read while UiDevice.takeScreenshot() is still writing.
offset = 8
has_iend = False
while offset + 12 <= len(data):
    chunk_length = struct.unpack(">I", data[offset:offset + 4])[0]
    chunk_type = data[offset + 4:offset + 8]
    chunk_end = offset + 12 + chunk_length
    if chunk_end > len(data):
        break
    chunk_data = data[offset + 8:offset + 8 + chunk_length]
    stored_crc = struct.unpack(">I", data[offset + 8 + chunk_length:chunk_end])[0]
    if zlib.crc32(chunk_type + chunk_data) & 0xffffffff != stored_crc:
        break
    offset = chunk_end
    if chunk_type == b"IEND":
        has_iend = chunk_length == 0 and offset == len(data)
        break

if not has_iend:
    print(f"ANDROID_SURFACE_SCREENSHOT_INCOMPLETE_PNG bytes={len(data)} path={path}")
    raise SystemExit(1)
print(f"ANDROID_SURFACE_PNG_VALID bytes={len(data)} size={width}x{height}")
PY
}

capture_app_private_screenshot() {
  local remote_path="$1"
  local local_path="$2"
  local temp_path="$3"
  local label="$4"
  for attempt in $(seq 1 600); do
    if timeout 4s "$ADB" -s "$DEVICE" exec-out run-as com.ludomadagascar cat "$remote_path" > "$temp_path" 2>/dev/null; then
      # adb may read app-private output before takeScreenshot() has finished.
      # Accept only a complete PNG (>1 KB, valid chunk CRCs and terminal IEND).
      if [[ -s "$temp_path" ]] && validate_png "$temp_path" >/dev/null 2>&1; then
        mv -- "$temp_path" "$local_path"
        echo "ANDROID_\${label}_SCREENSHOT_CAPTURED_AND_VALIDATED_BEFORE_TEARDOWN"
        return 0
      fi
    fi
    rm -f "$temp_path"
    if (( attempt % 30 == 0 )); then
      echo "ANDROID_\${label}_SCREENSHOT_WAITING_FOR_COMPLETE_PNG attempt=$attempt"
    fi
    sleep 1
  done
  echo "ANDROID_\${label}_SCREENSHOT_WATCH_TIMEOUT"
  return 1
}

capture_app_private_screenshot "$screenshot_remote" "$screenshot_local" "$screenshot_tmp" "GAMEPLAY" &
screenshot_watcher_pid=$!
capture_app_private_screenshot "$home_screenshot_remote" "$home_screenshot_local" "$home_screenshot_tmp" "HOME_3D" &
home_screenshot_watcher_pid=$!

cd android-app
set +e
echo "INSTRUMENTATION_TARGET=com.ludomadagascar.StartupUiTest (all @Test methods)"
timeout --signal=TERM --kill-after=15s 10m ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ludomadagascar.StartupUiTest \
  --no-daemon --no-parallel -Dorg.gradle.workers.max=2 --console=plain --stacktrace
test_status=$?
set -e

if [[ "$test_status" -ne 0 ]]; then
  kill "$screenshot_watcher_pid" "$home_screenshot_watcher_pid" 2>/dev/null || true
  wait "$screenshot_watcher_pid" 2>/dev/null || true
  wait "$home_screenshot_watcher_pid" 2>/dev/null || true
  rm -f "$screenshot_tmp" "$home_screenshot_tmp"
  echo "CONNECTED_ANDROID_TEST_DIAGNOSTICS: Gradle exit code $test_status"
  "$ADB" devices -l || true
  timeout 8s "$ADB" -s "$DEVICE" shell getprop sys.boot_completed || true
  timeout 8s "$ADB" -s "$DEVICE" shell service check package || true
  timeout 8s "$ADB" -s "$DEVICE" shell dumpsys activity activities | tail -n 100 || true
  timeout 8s "$ADB" -s "$DEVICE" logcat -d -t 2500 -s AndroidRuntime:E ActivityManager:E TestRunner:E AndroidJUnitRunner:E || true
  exit "$test_status"
fi

# Both app-private screenshots are captured before connectedDebugAndroidTest uninstalls
# the target APK. The Home screenshot is taken only after its renderer resumes on return.
wait "$screenshot_watcher_pid" 2>/dev/null || true
wait "$home_screenshot_watcher_pid" 2>/dev/null || true

screenshot_source_kind=""
if [[ -s "$screenshot_local" ]] && validate_png "$screenshot_local"; then
  screenshot_source_kind="app-private-run-as-before-teardown"
else
  rm -f "$screenshot_local" "$screenshot_tmp"
  echo "ANDROID_GAMEPLAY_SCREENSHOT_RUN_AS_WATCH_FAILED: checking UTP output fallback"
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

home_screenshot_source_kind=""
if [[ -s "$home_screenshot_local" ]] && validate_png "$home_screenshot_local"; then
  home_screenshot_source_kind="app-private-run-as-before-teardown"
else
  rm -f "$home_screenshot_local" "$home_screenshot_tmp"
  echo "ANDROID_HOME_SCREENSHOT_RUN_AS_WATCH_FAILED: checking UTP output fallback"
  home_screenshot_source=""
  if [[ -d "$additional_output_root" ]]; then
    home_screenshot_source="$(find "$additional_output_root" -type f -name "ludomada-home-preview.png" -size +1k -print -quit)"
  fi
  if [[ -n "$home_screenshot_source" ]]; then
    cp -- "$home_screenshot_source" "$home_screenshot_local"
    if validate_png "$home_screenshot_local"; then
      home_screenshot_source_kind="utp"
    else
      rm -f "$home_screenshot_local"
    fi
  fi
fi

if [[ -z "$screenshot_source_kind" ]]; then
  echo "ANDROID_GAMEPLAY_SCREENSHOT_PULL_FAILED: pre-teardown run-as and UTP outputs unavailable"
  exit 1
fi
if [[ -z "$home_screenshot_source_kind" ]]; then
  echo "ANDROID_HOME_SCREENSHOT_PULL_FAILED: Home 3D screenshot is required evidence"
  exit 1
fi
echo "ANDROID_GAMEPLAY_SCREENSHOT_SOURCE=$screenshot_source_kind"
echo "ANDROID_HOME_SCREENSHOT_SOURCE=$home_screenshot_source_kind"
echo "API_LEVEL=$api_level"
echo "ANDROID_GAMEPLAY_AND_HOME_SCREENSHOTS_CAPTURE_PASS"
