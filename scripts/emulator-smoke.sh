#!/bin/sh
set -eu

APP_APK="android-app/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="android-app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
TEST_RUNNER="com.ludomadagascar.test/androidx.test.runner.AndroidJUnitRunner"

adb start-server || true
adb wait-for-device

booted=0
for i in $(seq 1 120); do
  state="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [ "$state" = "1" ]; then
    booted=1
    break
  fi
  sleep 2
done
[ "$booted" = "1" ]

adb shell input keyevent 82 || true

install_with_retry() {
  apk="$1"
  for attempt in 1 2 3; do
    if adb install --no-streaming -r "$apk"; then
      return 0
    fi
    adb wait-for-device
    sleep 5
  done
  return 1
}

install_with_retry "$APP_APK"
install_with_retry "$TEST_APK"
adb shell am instrument -w -r "$TEST_RUNNER"
