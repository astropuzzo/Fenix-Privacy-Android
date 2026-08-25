# Fenix Privacy for Android

A maintainable Firefox-for-Android downstream build with **native selective history suppression**.

The goal is simple: keep normal Firefox history for everything except domains, words/phrases or regular expressions you choose. Matching navigation data is blocked or scrubbed without requiring Private Browsing for every visit.

[![Android production build](https://github.com/astropuzzo/Fenix-Privacy-Android/actions/workflows/sync-build-release.yml/badge.svg?branch=main)](https://github.com/astropuzzo/Fenix-Privacy-Android/actions/workflows/sync-build-release.yml)
[![Desktop production build](https://github.com/astropuzzo/Fenix-Privacy-Android/actions/workflows/desktop-extension.yml/badge.svg?branch=main)](https://github.com/astropuzzo/Fenix-Privacy-Android/actions/workflows/desktop-extension.yml)
[![Production downloads](https://img.shields.io/badge/downloads-production-2ea44f)](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/tag/production-downloads)

## Production downloads

The rolling [Production Downloads release](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/tag/production-downloads) is refreshed only after successful signed builds:

- [Android ARM64 APK](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/download/production-downloads/Fenix-Privacy-Android.apk)
- [Windows installer EXE](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/download/production-downloads/Fenix-Privacy-Desktop-Installer.exe)
- [Mozilla-signed Firefox Desktop XPI](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/download/production-downloads/Fenix-Privacy-Desktop.xpi) for Windows, macOS and Linux
- [SHA-256 checksums](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/download/production-downloads/SHA256SUMS.txt)

There is no separate macOS/Linux package: Firefox uses the same Mozilla-signed XPI on all three desktop systems.

## Features

- Domain blacklist: exact domains and all subdomains.
- Keyword/phrase blacklist: URL, decoded search query and page-title matching.
- Optional regular expressions, case sensitivity and whole-word matching.
- Pre-write suppression: URL/domain matches are rejected before Places `recordVisit()`.
- Recent-search protection: matching Firefox history metadata/search terms are suppressed too.
- Sync scrubber: scrubs matching records at startup and every 15 minutes if they arrive from another Firefox through Sync.
- One-tap cleanup of existing matching local history.
- Device-local privacy counter: aggregate totals for pre-write blocks, title/search scrubs and startup/Sync cleanup. URLs, titles, queries and rules are never stored in the counter.
- Cookies, site data, login sessions and cache are deliberately left untouched, so protected sites can remain signed in.
- Separate Android package: `io.github.astropuzzo.fenixprivacy`, so official Firefox can stay installed.
- Firefox Accounts/Sync code retained from upstream Fenix.
- Mozilla telemetry/crash reporting disabled for the custom build.
- Built-in updater: checks GitHub releases, automatically downloads a newer APK, verifies SHA-256, then hands it to Android for the required installation confirmation.
- Stable-upstream CI: tracks Firefox stable release tags, not Nightly commits.

## Firefox Desktop (Windows, macOS and Linux)

This repository also contains **Fenix Privacy Desktop**, a Firefox WebExtension under [`desktop/firefox-extension`](desktop/firefox-extension). It mirrors the selective-history behavior on Firefox for Windows, macOS and Linux:

- domain/subdomain, keyword/phrase and regular-expression rules;
- immediate deletion on history/navigation events plus title-based cleanup after page load;
- full-history scrub on demand, at startup and every 15+ minutes;
- rules local by default, with opt-in Firefox `storage.sync` after Firefox's built-in consent;
- no browsing data transmitted to a developer-controlled server;
- GitHub Actions builds an unsigned test XPI for pull requests, while every main-branch desktop release requires an **unlisted Mozilla-signed XPI** using `AMO_API_KEY` / `AMO_API_SECRET`. Unsigned release publication is blocked. Signed desktop releases publish `desktop-updates.json`, and the rolling production release exposes the signed XPI plus the Windows installer in one stable location. Firefox self-update works once this repository is public.

Firefox Desktop requires Mozilla signing for normal permanent installation. See [`desktop/firefox-extension/README.md`](desktop/firefox-extension/README.md) and [`desktop/windows`](desktop/windows).

## How the auto-update pipeline works

Every day GitHub Actions checks Mozilla's `FIREFOX_*_RELEASE` tags and verifies the candidate against the public Firefox for Android Release Notes/date. If a newer Release-channel version exists, it fetches that exact Firefox source, applies `scripts/apply_fenix_privacy.py`, builds an optimized ARM64 release APK, signs it with the repository's private Actions secrets, verifies the signature, generates `update.json`, and publishes both files as the latest GitHub Release.

If Mozilla changes an integration point and the patch no longer applies exactly, CI fails **before** a release is created. This deliberately favors no update over a broken privacy build.

The Android check runs every day at **04:23 UTC** and can also be started manually. A new Mozilla stable tag is recorded in `UPSTREAM_REF` only after the ARM64 APK, signing certificate, package identity, Gecko libraries, update manifest and GitHub Release have all passed.

Desktop is a Mozilla-signed WebExtension rather than a forked Firefox binary, so it is rebuilt when the extension changes, not for every Firefox source tag. The same signed XPI works on Windows, macOS and Linux; the rolling production release always keeps it beside the newest Android build.

## Initial repository setup

1. Create a public repository named `Fenix-Privacy-Android` under the GitHub account that will publish releases.
2. Upload this repository contents to `main`.
3. Add the four signing secrets described in [`docs/SIGNING.md`](docs/SIGNING.md).
4. Run **Build Android APK** once from Actions.
5. Install `Fenix-Privacy-Android.apk` from the resulting Release and allow “Install unknown apps” for Fenix Privacy when Android asks.

After that, stable Firefox updates are handled by the workflow and the installed app's update checker.

## Upstream currently pinned

The live source tag and exact revision are recorded in [`UPSTREAM_REF`](UPSTREAM_REF) and [`UPSTREAM_REVISION`](UPSTREAM_REVISION). CI changes them only after the matching signed APK has been verified and published.

## Important privacy scope

The rules protect **Firefox browsing history and Firefox recent-search/history metadata** in this build. They do not erase DNS logs, network/provider logs, search-engine account history, keyboard history, screenshots, downloads, bookmarks, open tabs, or history independently stored by another app/device.

Cookie and session retention is intentional: history protection does not clear cookies, logins, cache or other site data. The privacy counter stores only aggregate numbers in the app's private local preferences; resetting it clears only those numbers.

## License and branding

Downstream source changes are under MPL-2.0. The app is branded **Fenix Privacy**, not Firefox, and uses a separate Android application ID. Mozilla Firefox remains the upstream project.
