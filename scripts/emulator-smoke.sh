#!/bin/sh
set -eu

APP_APK="android-app/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
TEST_RUNNER="com.ludomadagascar.test/androidx.test.runner.AndroidJUnitRunner"

adb_timeout() {
  timeout --foreground --kill-after=10s "$@"
}

adb_timeout 30s adb start-server || true
adb_timeout 120s adb wait-for-device

booted=0
for i in $(seq 1 120); do
  state="$(adb_timeout 15s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  if [ "$state" = "1" ]; then
    booted=1
    break
  fi
  sleep 2
done
[ "$booted" = "1" ]

# sys.boot_completed can be reported before Android framework services are stable.
# Require package + activity services to answer successfully before touching APK install/instrumentation.
framework_ready=0
for i in $(seq 1 90); do
  if adb_timeout 15s adb shell pm list packages >/dev/null 2>&1 &&
     adb_timeout 15s adb shell cmd activity get-config >/dev/null 2>&1; then
    framework_ready=$((framework_ready + 1))
    if [ "$framework_ready" -ge 3 ]; then
      break
    fi
  else
    framework_ready=0
  fi
  sleep 2
done
[ "$framework_ready" -ge 3 ] || { echo "Android framework services did not stabilize." >&2; adb_timeout 30s adb logcat -d -t 300 || true; exit 1; }

adb_timeout 30s adb shell input keyevent 82 || true

install_with_retry() {
  apk="$1"
  for attempt in 1 2 3; do
    if adb_timeout 90s adb install --no-streaming -r "$apk"; then
      return 0
    fi
    adb_timeout 120s adb wait-for-device || true
    sleep 5
  done
  return 1
}

install_with_retry "$APP_APK"
install_with_retry "$TEST_APK"

echo "Starting application launch smoke test..."

# The package-only instrumentation test does not exercise the real app and can
# itself trigger target-process startup on slow CI emulators. The real validation
# is the explicit MainActivity launch below, so keep this smoke path deterministic.
# Verify the real application launches independently after installation.
# Use an explicit activity start instead of monkey: monkey can trigger Google system
# services and ANRs on heavily loaded CI emulators unrelated to the application.
adb_timeout 60s adb shell am force-stop com.ludomadagascar || true
launch_output="$(adb_timeout 60s adb shell am start -W -n com.ludomadagascar/.MainActivity 2>&1)" || {
  echo "$launch_output"
  echo "Application activity failed to start." >&2
  adb_timeout 30s adb logcat -d -t 300 || true
  exit 1
}
echo "$launch_output"
case "$launch_output" in
  *"Status: ok"*|*"Activity: com.ludomadagascar/.MainActivity"*) : ;;
  *)
    echo "Application activity did not report a successful launch." >&2
    adb_timeout 30s adb logcat -d -t 300 || true
    exit 1
    ;;
esac
sleep 3
app_pid="$(adb_timeout 15s adb shell pidof com.ludomadagascar 2>/dev/null | tr -d "\r" || true)"
[ -n "$app_pid" ] || { echo "Application did not stay running after launch." >&2; adb_timeout 30s adb logcat -d -t 300 || true; exit 1; }
echo "Application launch smoke passed (pid=$app_pid)."
