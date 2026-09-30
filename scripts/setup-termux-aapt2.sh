#!/data/data/com.termux/files/usr/bin/bash
set -eu

QEMU_X86_64="/data/data/com.termux/files/usr/bin/qemu-x86_64"
SYSROOT="/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian-x86/rootfs"
WRAPPER_DIR="$HOME/.local/bin"
STATE_DIR="$HOME/.local/share/ludomada"
AAPT2_PATH_FILE="$STATE_DIR/aapt2.path"
LAUNCHER="$WRAPPER_DIR/aapt2"

test -x "$QEMU_X86_64"
test -x "$SYSROOT/lib64/ld-linux-x86-64.so.2"

SDK_ROOT="${ANDROID_SDK_ROOT:-}"
if [ -z "$SDK_ROOT" ]; then SDK_ROOT="${ANDROID_HOME:-}"; fi
if [ -z "$SDK_ROOT" ]; then SDK_ROOT="$HOME/android-sdk"; fi
BUILD_TOOLS="$SDK_ROOT/build-tools/36.0.0"
BUILD_TOOLS_URL="https://dl.google.com/android/repository/build-tools_r36_linux.zip"
BUILD_TOOLS_SHA1="b0b6376977657e8ad9b969bacf4093601da2c6fb"
BUILD_TOOLS_ARCHIVE="$HOME/.cache/ludomada/build-tools_r36_linux.zip"

if [ ! -x "$BUILD_TOOLS/aapt2" ] || [ ! -x "$BUILD_TOOLS/d8" ] || [ ! -f "$BUILD_TOOLS/source.properties" ]; then
  mkdir -p "$HOME/.cache/ludomada" "$SDK_ROOT/build-tools"
  if [ ! -s "$BUILD_TOOLS_ARCHIVE" ] || [ "$(sha1sum "$BUILD_TOOLS_ARCHIVE" 2>/dev/null | awk '{print $1}')" != "$BUILD_TOOLS_SHA1" ]; then
    rm -f "$BUILD_TOOLS_ARCHIVE"
    curl -L --fail --retry 3 --retry-delay 2 -o "$BUILD_TOOLS_ARCHIVE.tmp" "$BUILD_TOOLS_URL"
    test "$(sha1sum "$BUILD_TOOLS_ARCHIVE.tmp" | awk '{print $1}')" = "$BUILD_TOOLS_SHA1"
    mv "$BUILD_TOOLS_ARCHIVE.tmp" "$BUILD_TOOLS_ARCHIVE"
  fi
  rm -rf "$SDK_ROOT/build-tools/.36.0.0.extract"
  mkdir -p "$SDK_ROOT/build-tools/.36.0.0.extract"
  unzip -q "$BUILD_TOOLS_ARCHIVE" -d "$SDK_ROOT/build-tools/.36.0.0.extract"
  src="$(find "$SDK_ROOT/build-tools/.36.0.0.extract" -type f -name aapt2 -perm -u+x -print -quit)"
  test -n "$src"
  srcdir="$(dirname "$src")"
  rm -rf "$BUILD_TOOLS"
  mv "$srcdir" "$BUILD_TOOLS"
  rm -rf "$SDK_ROOT/build-tools/.36.0.0.extract"
fi

test -f "$BUILD_TOOLS/source.properties"

mkdir -p "$WRAPPER_DIR" "$STATE_DIR"

AAPT2=""
if [ -s "$AAPT2_PATH_FILE" ]; then
  CACHED_AAPT2="$(head -n 1 "$AAPT2_PATH_FILE")"
  if [ -x "$CACHED_AAPT2" ]; then
    AAPT2="$CACHED_AAPT2"
  fi
fi

if [ -z "$AAPT2" ]; then
  AAPT2="$(find "$HOME/.gradle/caches" -type f -path '*/transformed/aapt2-*-linux/aapt2' -perm -u+x -print 2>/dev/null | sort | tail -n 1)"
fi

if [ -z "$AAPT2" ] || [ ! -x "$AAPT2" ]; then
  echo "AGP AAPT2 binary not found in Gradle cache" >&2
  exit 2
fi

printf '%s\n' "$AAPT2" > "$AAPT2_PATH_FILE"

cat > "$STATE_DIR/aapt2_launcher.c" <<'EOF'
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int read_path(char *out, size_t n) {
    const char *home = getenv("HOME");
    if (!home) return 0;
    char file[4096];
    snprintf(file, sizeof(file), "%s/.local/share/ludomada/aapt2.path", home);
    FILE *f = fopen(file, "r");
    if (!f) return 0;
    if (!fgets(out, n, f)) { fclose(f); return 0; }    fclose(f);
    out[strcspn(out, "\r\n")] = 0;
    return out[0] != 0;
}

int main(int argc, char **argv) {
    char aapt2[4096];
    if (!read_path(aapt2, sizeof(aapt2))) return 2;

    char *args[1024];
    if (argc + 4 >= 1024) return 3;
    args[0] = "/data/data/com.termux/files/usr/bin/qemu-x86_64";
    args[1] = "-L";
    args[2] = "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian-x86/rootfs";
    args[3] = aapt2;
    for (int i = 1; i < argc; ++i) args[i + 3] = argv[i];
    args[argc + 3] = NULL;
    execv(args[0], args);
    return 127;
}
EOF

/data/data/com.termux/files/usr/bin/clang -O2 -fPIE -pie   "$STATE_DIR/aapt2_launcher.c" -o "$LAUNCHER"
chmod +x "$LAUNCHER"

"$LAUNCHER" version
if [ -n "${GITHUB_ENV:-}" ]; then
  printf 'AAPT2_WRAPPER=%s\n' "$LAUNCHER" >> "$GITHUB_ENV"
fi
