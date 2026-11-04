#!/usr/bin/env python3
"""Проверяет новую стабильную GeckoView и обновляет проект под неё.

Что делает:
  1. Берёт текущую версию из `val geckoVersion = "..."` в app/build.gradle.kts.
  2. Читает maven-metadata.xml с maven.mozilla.org и выбирает самую свежую стабильную сборку
     (формат MAJOR.MINOR.BUILDID, сравнивается по числам, а не строкой).
  3. Если она новее, скачивает AAR, читает minCompileSdk / minCompileMinorSdk и при необходимости
     поднимает compileSdk в build.gradle.kts и платформу SDK в .github/workflows/*.yml.
  4. Пишет в GITHUB_OUTPUT: updated, old, new, compile_sdk.

Без сети и без сторонних пакетов, только стандартная библиотека Python 3.
Запуск локально: python3 scripts/geckoview_update.py [--dry-run] [--metadata FILE]
"""
from __future__ import annotations

import argparse
import io
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"
WORKFLOWS = ROOT / ".github" / "workflows"
MAVEN = "https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview"

VERSION_RE = re.compile(r'val geckoVersion = "([^"]+)"')
COMPILE_RE = re.compile(r"compileSdk \{ version = release\((\d+)\)(?: \{ minorApiLevel = (\d+) \})? \}")
STABLE_RE = re.compile(r"^(\d+)\.(\d+)(?:\.(\d+))?\.(\d{14})$")


def parse_version(v: str) -> tuple[int, ...] | None:
    """159.0.20261102144711 -> (159, 0, 0, 20261102144711); 159.0.1.2026... -> (159, 0, 1, ...). Не стабильная -> None."""
    m = STABLE_RE.match(v.strip())
    if not m:
        return None
    major, minor, patch, build = m.groups()
    return int(major), int(minor), int(patch or 0), int(build)


def latest_stable(versions: list[str]) -> str | None:
    parsed = [(parse_version(v), v) for v in versions]
    parsed = [(p, v) for p, v in parsed if p is not None]
    return max(parsed)[1] if parsed else None


def versions_from_metadata(xml_text: str) -> list[str]:
    root = ET.fromstring(xml_text)
    return [v.text.strip() for v in root.iter("version") if v.text]


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "hrips-geckoview-update"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def aar_compile_sdk(aar: bytes) -> tuple[int, int]:
    with zipfile.ZipFile(io.BytesIO(aar)) as z:
        props = z.read("META-INF/com/android/build/gradle/aar-metadata.properties").decode()
    values = dict(line.split("=", 1) for line in props.splitlines() if "=" in line)
    return int(values.get("minCompileSdk", "0")), int(values.get("minCompileMinorSdk", "0"))


def bump_compile_sdk(gradle_text: str, need: tuple[int, int]) -> tuple[str, tuple[int, int]]:
    m = COMPILE_RE.search(gradle_text)
    if not m:
        raise SystemExit("compileSdk в build.gradle.kts не найден в ожидаемом виде")
    current = (int(m.group(1)), int(m.group(2) or 0))
    if current >= need:
        return gradle_text, current
    major, minor = need
    repl = f"compileSdk {{ version = release({major}) {{ minorApiLevel = {minor} }} }}" if minor else f"compileSdk {{ version = release({major}) }}"
    return gradle_text[: m.start()] + repl + gradle_text[m.end():], need


def platform_name(sdk: tuple[int, int]) -> str:
    return f"android-{sdk[0]}.{sdk[1]}" if sdk[1] else f"android-{sdk[0]}"


def bump_workflows(sdk: tuple[int, int], dry: bool) -> None:
    name = platform_name(sdk)
    for wf in sorted(WORKFLOWS.glob("*.yml")):
        text = wf.read_text(encoding="utf-8")
        new = re.sub(r'"platforms;android-[0-9.]+"', f'"platforms;{name}"', text)
        new = re.sub(r"Android SDK platform [0-9.]+", f"Android SDK platform {name.removeprefix('android-')}", new)
        if new != text and not dry:
            wf.write_text(new, encoding="utf-8")


def output(**values: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    lines = "".join(f"{k}={v}\n" for k, v in values.items())
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(lines)
    sys.stdout.write(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="ничего не менять, только показать")
    ap.add_argument("--metadata", help="локальный maven-metadata.xml вместо сети (для проверки)")
    args = ap.parse_args()

    gradle = GRADLE.read_text(encoding="utf-8")
    m = VERSION_RE.search(gradle)
    if not m:
        raise SystemExit("geckoVersion в build.gradle.kts не найден")
    current = m.group(1)

    xml_text = Path(args.metadata).read_text() if args.metadata else fetch(f"{MAVEN}/maven-metadata.xml").decode()
    latest = latest_stable(versions_from_metadata(xml_text))
    cur_parsed = parse_version(current)
    if latest is None or (cur_parsed is not None and parse_version(latest) <= cur_parsed):
        output(updated="false", old=current, new=current, compile_sdk="")
        return 0

    need = (0, 0)
    if not args.metadata:
        need = aar_compile_sdk(fetch(f"{MAVEN}/{latest}/geckoview-{latest}.aar"))
    gradle = gradle[: m.start(1)] + latest + gradle[m.end(1):]
    gradle, sdk = bump_compile_sdk(gradle, need)
    if not args.dry_run:
        GRADLE.write_text(gradle, encoding="utf-8")
    bump_workflows(sdk, args.dry_run)
    output(updated="true", old=current, new=latest, compile_sdk=platform_name(sdk))
    return 0


if __name__ == "__main__":
    sys.exit(main())
