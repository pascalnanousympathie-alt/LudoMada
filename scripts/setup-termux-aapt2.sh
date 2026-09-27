#!/data/data/com.termux/files/usr/bin/bash
set -eu

QEMU_X86_64="/data/data/com.termux/files/usr/bin/qemu-x86_64"
SYSROOT="/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian-x86/rootfs"
WRAPPER_DIR="$HOME/.local/bin"
WRAPPER="$WRAPPER_DIR/aapt2"

test -x "$QEMU_X86_64"
test -x "$SYSROOT/lib64/ld-linux-x86-64.so.2"

mkdir -p "$WRAPPER_DIR"
cat > "$WRAPPER" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -eu
QEMU_X86_64="/data/data/com.termux/files/usr/bin/qemu-x86_64"
SYSROOT="/data/data/com.termux/files/usr/var/lib/proot-distro/containers/debian-x86/rootfs"
AAPT2="$(find "$HOME/.gradle/caches" -type f -path '*/transformed/aapt2-*-linux/aapt2' -perm -u+x -print 2>/dev/null | sort | tail -n 1)"
if [ -z "$AAPT2" ]; then
  echo "AGP AAPT2 binary not found in Gradle cache" >&2
  exit 2
fi
exec "$QEMU_X86_64" -L "$SYSROOT" "$AAPT2" "$@"
EOF
chmod +x "$WRAPPER"

"$WRAPPER" version
if [ -n "${GITHUB_ENV:-}" ]; then
  printf 'AAPT2_WRAPPER=%s\n' "$WRAPPER" >> "$GITHUB_ENV"
fi
