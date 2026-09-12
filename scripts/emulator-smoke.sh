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
echo "Starting instrumentation smoke tests..."
log_file="${RUNNER_TEMP:-.}/ludomada-instrumentation.log"
set +e
adb_timeout 330s adb shell am instrument -w -r "$TEST_RUNNER" >"$log_file" 2>&1
instrumentation_rc=$?
cat "$log_file"
set -e
if grep -q "shortMsg=Process crashed\|shortMsg=Process exited" "$log_file"; then
  echo "Instrumentation reported a process crash/exit." >&2
  adb_timeout 30s adb logcat -d -t 300 || true
  exit 1
fi
if ! grep -Eq "(^|[[:space:]])OK \([0-9]+ test|INSTRUMENTATION_CODE: 0" "$log_file"; then
  echo "Instrumentation did not report a successful test result." >&2
  adb_timeout 30s adb logcat -d -t 300 || true
  exit 1
fi
exit "$instrumentation_rc"
