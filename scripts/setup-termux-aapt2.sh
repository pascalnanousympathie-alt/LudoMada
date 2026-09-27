#!/data/data/com.termux/files/usr/bin/bash
set -eu

PROOT_DISTRO="/data/data/com.termux/files/usr/bin/proot-distro"
WRAPPER_DIR="$HOME/.local/bin"
WRAPPER="$WRAPPER_DIR/aapt2"

test -x "$PROOT_DISTRO"
"$PROOT_DISTRO" login debian-x86 -- sh -lc 'test -x /lib64/ld-linux-x86-64.so.2'

mkdir -p "$WRAPPER_DIR"
cat > "$WRAPPER" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -eu
AAPT2="$(find "$HOME/.gradle/caches" -type f -path '*/transformed/aapt2-*-linux/aapt2' -perm -u+x -print 2>/dev/null | sort | tail -n 1)"
if [ -z "$AAPT2" ]; then
  echo "AGP AAPT2 binary not found in Gradle cache" >&2
  exit 2
fi
exec /data/data/com.termux/files/usr/bin/proot-distro login debian-x86 -- "$AAPT2" "$@"
EOF
chmod +x "$WRAPPER"

"$WRAPPER" version
if [ -n "${GITHUB_ENV:-}" ]; then
  printf 'AAPT2_WRAPPER=%s\n' "$WRAPPER" >> "$GITHUB_ENV"
fi
