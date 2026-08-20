#!/usr/bin/env python3
"""Select the newest publicly released stable Firefox tag.

Git tags are created during release engineering before a build necessarily reaches
Firefox for Android's Release channel. With --verify-android-release we therefore
require Mozilla's public Android release-notes page to confirm that version and a
Release-channel date that is not in the future.
"""
from __future__ import annotations

import argparse
from datetime import date, datetime
from html import unescape
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

TAG_RE = re.compile(r"refs/tags/(FIREFOX_(\d+)_(\d+)(?:_(\d+))?_RELEASE)$")
OFFERED_RE = re.compile(
    r"Version\s+([0-9.]+)\s*,?\s*first offered to Release channel users on\s+"
    r"([A-Za-z]+\s+\d{1,2},\s+\d{4})",
    re.IGNORECASE,
)


def parse_candidates(lines: list[str]) -> list[tuple[tuple[int, int, int], str, str]]:
    candidates: list[tuple[tuple[int, int, int], str, str]] = []
    for line in lines:
        if line.endswith("^{}\n"):
            continue
        parts = line.strip().split()
        if len(parts) != 2:
            continue
        match = TAG_RE.fullmatch(parts[1])
        if not match:
            continue
        key = tuple(int(x or 0) for x in match.groups()[1:])
        candidates.append((key, match.group(1), parts[0]))
    return sorted(candidates, key=lambda item: item[0], reverse=True)


def version_from_key(key: tuple[int, int, int]) -> str:
    major, minor, patch = key
    return f"{major}.{minor}.{patch}" if patch else f"{major}.{minor}"


def release_page_confirms(version: str, body: str, today: date) -> bool:
    text = unescape(re.sub(r"<[^>]+>", " ", body))
    text = re.sub(r"\s+", " ", text)
    for match in OFFERED_RE.finditer(text):
        page_version, offered_raw = match.groups()
        if page_version != version:
            continue
        offered = datetime.strptime(offered_raw, "%B %d, %Y").date()
        return offered <= today
    return False


def is_public_android_release(version: str, today: date) -> bool:
    url = f"https://www.firefox.com/en-US/firefox/android/{version}/releasenotes/"
    request = Request(url, headers={"User-Agent": "Fenix-Privacy-Release-Bot/1.0"})
    try:
        with urlopen(request, timeout=20) as response:
            body = response.read().decode("utf-8", errors="replace")
    except HTTPError as exc:
        if exc.code in (404, 410):
            return False
        raise SystemExit(f"Mozilla release-note check failed with HTTP {exc.code}: {url}") from exc
    except URLError as exc:
        raise SystemExit(f"Mozilla release-note check failed: {exc.reason}") from exc
    return release_page_confirms(version, body, today)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify-android-release", action="store_true")
    parser.add_argument("--today", type=date.fromisoformat, default=date.today())
    args = parser.parse_args()

    candidates = parse_candidates(list(sys.stdin))
    if not candidates:
        raise SystemExit("No stable Firefox release tag found")

    if not args.verify_android_release:
        key, ref, sha = candidates[0]
        print(f"{ref} {sha}")
        return

    for key, ref, sha in candidates:
        version = version_from_key(key)
        if is_public_android_release(version, args.today):
            print(f"{ref} {sha}")
            return

    raise SystemExit("No publicly released Firefox for Android tag found")


if __name__ == "__main__":
    main()
