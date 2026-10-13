#!/usr/bin/env python3
"""Narrow credential-signature scan for Android production source and APK archives."""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "android-app/app/src/main"
SOURCE_SUFFIXES = {".kt", ".java", ".xml", ".gradle", ".kts", ".properties", ".json"}
PATTERNS = {
    "PEM_PRIVATE_KEY": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "AWS_ACCESS_KEY_ID": re.compile(rb"AKIA[0-9A-Z]{16}"),
    "STRIPE_SECRET_KEY": re.compile(rb"sk_(?:live|test)_[A-Za-z0-9]{16,}"),
    "OPENAI_SECRET_KEY": re.compile(rb"sk-proj-[A-Za-z0-9_-]{20,}"),
    "SUPABASE_SECRET_KEY": re.compile(rb"sb_secret_[A-Za-z0-9_-]{12,}"),
    "GITHUB_TOKEN": re.compile(rb"gh[pousr]_[A-Za-z0-9]{20,}"),
    "GITHUB_FINE_GRAINED_TOKEN": re.compile(rb"github_pat_[A-Za-z0-9_]{20,}"),
    "SLACK_TOKEN": re.compile(rb"xox[baprs]-[A-Za-z0-9-]{10,}"),
}
FAILURES: list[str] = []
CHECKED_SOURCE = 0
CHECKED_ARCHIVE = 0


def inspect(label: str, data: bytes) -> None:
    for name, pattern in PATTERNS.items():
        if pattern.search(data):
            # Intentionally never print matching bytes or secret values.
            FAILURES.append(f"{label}: potential embedded credential pattern {name}")


def scan_sources() -> None:
    global CHECKED_SOURCE
    if not SOURCE_ROOT.is_dir():
        FAILURES.append("Android production source root missing")
        return
    for path in sorted(SOURCE_ROOT.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in SOURCE_SUFFIXES:
            continue
        try:
            inspect(str(path.relative_to(ROOT)), path.read_bytes())
            CHECKED_SOURCE += 1
        except OSError as exc:
            FAILURES.append(f"Cannot read {path.relative_to(ROOT)}: {exc}")
    for path in (
        ROOT / "android-app/app/build.gradle.kts",
        ROOT / "android-app/build.gradle.kts",
        ROOT / "android-app/settings.gradle.kts",
        ROOT / "android-app/gradle.properties",
    ):
        if path.is_file():
            try:
                inspect(str(path.relative_to(ROOT)), path.read_bytes())
                CHECKED_SOURCE += 1
            except OSError as exc:
                FAILURES.append(f"Cannot read {path.relative_to(ROOT)}: {exc}")


def scan_apk(apk: Path) -> None:
    global CHECKED_ARCHIVE
    if not apk.is_file():
        FAILURES.append(f"APK missing: {apk}")
        return
    try:
        with zipfile.ZipFile(apk) as archive:
            for item in archive.infolist():
                if item.is_dir() or item.file_size > 80_000_000:
                    continue
                inspect(f"{apk.name}!/{item.filename}", archive.read(item))
                CHECKED_ARCHIVE += 1
    except (OSError, zipfile.BadZipFile) as exc:
        FAILURES.append(f"Cannot read APK archive {apk.name}: {exc}")


def main() -> int:
    scan_sources()
    for arg in sys.argv[1:]:
        scan_apk(Path(arg).resolve())
    print(f"Android production source files inspected: {CHECKED_SOURCE}")
    if sys.argv[1:]:
        print(f"APK archive entries inspected: {CHECKED_ARCHIVE}")
    if FAILURES:
        print("ANDROID_CREDENTIAL_SCAN=FAIL")
        for failure in sorted(set(FAILURES)):
            print("FAIL:", failure)
        return 1
    print("ANDROID_CREDENTIAL_SCAN=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
