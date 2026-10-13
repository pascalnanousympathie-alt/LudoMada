#!/usr/bin/env python3
"""Release-blocking static exposure checks for LudoMada's Android client.

Checks a narrow Android permission/component policy and scans source/APK bytes for
well-known embedded credential signatures. Passing is evidence only for these checks;
it is not a claim that an APK is completely malware-free.
"""
from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "android-app/app/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",  # backend API
    "android.permission.VIBRATE",   # gameplay haptic feedback
}
PATTERNS = {
    "PEM_PRIVATE_KEY": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "AWS_ACCESS_KEY_ID": re.compile(rb"AKIA[0-9A-Z]{16}"),
    "STRIPE_SECRET_KEY": re.compile(rb"sk_(?:live|test)_[A-Za-z0-9]{16,}"),
    "OPENAI_SECRET_KEY": re.compile(rb"sk-proj-[A-Za-z0-9_-]{20,}"),
    "SUPABASE_SECRET_KEY": re.compile(rb"sb_secret_[A-Za-z0-9_-]{12,}"),
    "GITHUB_TOKEN": re.compile(rb"gh[pousr]_[A-Za-z0-9]{20,}"),
    "GITHUB_FINE_GRAINED_TOKEN": re.compile(rb"github_pat_[A-Za-z0-9_]{20,}"),
    "GOOGLE_SERVICE_ACCOUNT_PRIVATE_KEY": re.compile(
        rb'"private_key"\s*:\s*"-----BEGIN (?:RSA )?PRIVATE KEY-----'
    ),
}
SOURCE_SUFFIXES = {".kt", ".java", ".xml", ".gradle", ".kts", ".properties", ".json"}
FAILURES: list[str] = []


def inspect_bytes(label: str, data: bytes) -> None:
    for name, pattern in PATTERNS.items():
        if pattern.search(data):
            # Never include matching bytes in the log.
            FAILURES.append(f"{label}: potential embedded credential pattern {name}")


def audit_manifest() -> None:
    if not MANIFEST.is_file():
        FAILURES.append("Android manifest is missing")
        return
    try:
        root = ET.parse(MANIFEST).getroot()
    except ET.ParseError as exc:
        FAILURES.append(f"Android manifest parse failed: {exc}")
        return

    permissions = set()
    for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
        for item in root.findall(tag):
            name = item.get(ANDROID + "name")
            if name:
                permissions.add(name)
    unexpected = sorted(permissions - ALLOWED_PERMISSIONS)
    if unexpected:
        FAILURES.append("Unexpected Android permissions: " + ", ".join(unexpected))

    application = root.find("application")
    if application is None:
        FAILURES.append("Android application element is missing")
        return
    if application.get(ANDROID + "usesCleartextTraffic", "true").lower() != "false":
        FAILURES.append("android:usesCleartextTraffic must be explicitly false")
    if application.get(ANDROID + "allowBackup", "true").lower() != "false":
        FAILURES.append("android:allowBackup must be explicitly false")

    activities = [
        item for item in application
        if item.tag in ("activity", "activity-alias")
    ]
    launchers = []
    exported = []
    for activity in activities:
        name = activity.get(ANDROID + "name", "")
        filters = activity.findall("intent-filter")
        if filters and activity.get(ANDROID + "exported") is None:
            FAILURES.append(f"{name or 'activity'} has intent-filter but no explicit android:exported")
        is_launcher = any(
            any(a.get(ANDROID + "name") == "android.intent.action.MAIN" for a in f.findall("action"))
            and any(a.get(ANDROID + "name") == "android.intent.category.LAUNCHER" for a in f.findall("category"))
            for f in filters
        )
        if is_launcher:
            launchers.append(activity)
        if activity.get(ANDROID + "exported", "false").lower() == "true":
            exported.append((name, activity, filters, is_launcher))

    if len(launchers) != 1:
        FAILURES.append(f"Expected exactly one launcher activity; found {len(launchers)}")
    elif launchers[0].get(ANDROID + "exported", "false").lower() != "true":
        FAILURES.append("Launcher activity must explicitly declare android:exported=true")

    for name, activity, filters, is_launcher in exported:
        if is_launcher and name.endswith(".MainActivity"):
            continue
        # WalletConnect callback is intentionally exported, but only via its custom URI.
        if name.endswith(".ReownWalletActivity"):
            actions = {
                a.get(ANDROID + "name")
                for f in filters
                for a in f.findall("action")
            }
            categories = {
                a.get(ANDROID + "name")
                for f in filters
                for a in f.findall("category")
            }
            data = [
                d for f in filters for d in f.findall("data")
            ]
            if (
                "android.intent.action.VIEW" not in actions
                or "android.intent.category.DEFAULT" not in categories
                or "android.intent.category.BROWSABLE" not in categories
                or not any(d.get(ANDROID + "scheme") == "ludomada"
                           and d.get(ANDROID + "host") == "walletconnect" for d in data)
            ):
                FAILURES.append("Exported ReownWalletActivity must be restricted to ludomada://walletconnect")
            continue
        FAILURES.append(f"Unexpected exported activity: {name}")


def audit_sources() -> int:
    checked = 0
    source_root = ROOT / "android-app/app/src"
    candidates = [
        p for p in source_root.rglob("*")
        if p.is_file() and p.suffix.lower() in SOURCE_SUFFIXES
    ]
    candidates.extend(
        p for p in [
            ROOT / "android-app/app/build.gradle.kts",
            ROOT / "android-app/build.gradle.kts",
            ROOT / "android-app/settings.gradle.kts",
            ROOT / "android-app/gradle.properties",
        ] if p.is_file()
    )
    for path in sorted(set(candidates)):
        try:
            inspect_bytes(str(path.relative_to(ROOT)), path.read_bytes())
            checked += 1
        except OSError as exc:
            FAILURES.append(f"Cannot read source file {path}: {exc}")
    return checked


def audit_apk(apk: Path) -> int:
    if not apk.is_file():
        FAILURES.append(f"APK missing: {apk}")
        return 0
    checked = 0
    try:
        with zipfile.ZipFile(apk) as archive:
            for item in archive.infolist():
                if item.is_dir() or item.file_size > 80_000_000:
                    continue
                inspect_bytes(f"APK!/{item.filename}", archive.read(item))
                checked += 1
    except (OSError, zipfile.BadZipFile) as exc:
        FAILURES.append(f"Cannot inspect APK contents: {exc}")
    return checked


def main() -> int:
    apk = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else None
    audit_manifest()
    source_count = audit_sources()
    apk_count = audit_apk(apk) if apk else 0
    print(f"Android source text files inspected: {source_count}")
    if apk:
        print(f"APK archive entries inspected: {apk_count}")
    if FAILURES:
        print("ANDROID_SECURITY_AUDIT=FAIL")
        for failure in sorted(set(FAILURES)):
            print(f"FAIL: {failure}")
        return 1
    print("Android manifest: permission allowlist, cleartext disabled, backups disabled, exported components restricted")
    print("Credential-pattern scan: no known embedded-key signature found")
    print("ANDROID_SECURITY_AUDIT=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
