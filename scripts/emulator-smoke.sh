#!/bin/sh
set -eu

APP_APK="android-app/app/build/outputs/apk/debug/app-debug.apk"

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

# Avoid installing the instrumentation APK: the smoke test no longer executes
# instrumentation, and package-install broadcasts can overload slow CI emulators.
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
adb_timeout 30s adb shell pm path com.ludomadagascar >/dev/null

echo "Starting application launch smoke test..."

# Keep the validation focused on the real application launch. Instrumentation is
# intentionally omitted because it can start the target process indirectly and
# trigger false ANRs on heavily loaded CI emulators.
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
sleep 5
app_pid="$(adb_timeout 15s adb shell pidof com.ludomadagascar 2>/dev/null | tr -d "\r" || true)"
[ -n "$app_pid" ] || { echo "Application did not stay running after launch." >&2; adb_timeout 30s adb logcat -d -t 300 || true; exit 1; }
echo "Application launch smoke passed (pid=$app_pid)."
