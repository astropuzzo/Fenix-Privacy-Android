# Fenix Privacy for Android

A maintainable Firefox-for-Android downstream build with **native selective history suppression**.

The goal is simple: keep normal Firefox history for everything except domains, words/phrases or regular expressions you choose. Matching navigation data is blocked or scrubbed without requiring Private Browsing for every visit.

[![Android production build](https://github.com/astropuzzo/Fenix-Privacy-Android/actions/workflows/sync-build-release.yml/badge.svg?branch=main)](https://github.com/astropuzzo/Fenix-Privacy-Android/actions/workflows/sync-build-release.yml)
[![Desktop production build](https://github.com/astropuzzo/Fenix-Privacy-Android/actions/workflows/desktop-extension.yml/badge.svg?branch=main)](https://github.com/astropuzzo/Fenix-Privacy-Android/actions/workflows/desktop-extension.yml)
[![Production downloads](https://img.shields.io/badge/downloads-production-2ea44f)](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/tag/production-downloads)

## Production downloads

The rolling [Production Downloads release](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/tag/production-downloads) is refreshed only after successful production builds:

- [Android ARM64 APK](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/download/production-downloads/Fenix-Privacy-Android.apk)
- [Windows installer EXE](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/download/production-downloads/Fenix-Privacy-Desktop-Installer.exe)
- [Mozilla-signed Firefox Desktop XPI](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/download/production-downloads/Fenix-Privacy-Desktop.xpi) for Windows, macOS and Linux
- [SHA-256 checksums](https://github.com/astropuzzo/Fenix-Privacy-Android/releases/download/production-downloads/SHA256SUMS.txt)

There is no separate macOS/Linux package: Firefox uses the same Mozilla-signed XPI on all three desktop systems.
The release index reports Android, Mozilla XPI and Windows Authenticode signature state separately; an EXE is never described as Authenticode-signed unless CI verified it.

## Features

- **Privacy Studio 3.0:** visual rule builder with allowlist exceptions, profiles, expirations, path/query/title matchers and exact URL rules.
- **Contextual shield button:** the Android address bar and Firefox Desktop URL bar expose current-page status and one-tap rule actions; no command syntax is required.
- **Homepage-only rules:** block everything except a clean site root, or collapse internal visits so history records only the homepage.
- **Quick rules:** create a rule from Android's share sheet, the address-bar shield, Firefox Desktop's URL-bar shield or the page context menu.
- **Temporary shields:** block all history for 15 minutes, one hour or the current app session; shield one tab and its children, or arm only the next navigation.
- **Delayed forgetting:** keep matching history until the next Firefox start or for a chosen retention period without closing or disrupting the live page.
- **Private tester and cleanup preview:** decisions and aggregate counts are shown without storing or listing tested/matching URLs.
- Domain blacklist: exact domains and all subdomains.
- Keyword/phrase blacklist: URL, decoded search query and page-title matching.
- Optional regular expressions, case sensitivity and whole-word matching.
- Pre-write suppression: URL/domain matches are rejected before Places `recordVisit()`.
- Recent-search protection: matching Firefox history metadata/search terms are suppressed too.
- Sync scrubber: scrubs matching records at startup and every 15 minutes if they arrive from another Firefox through Sync.
- One-tap cleanup of existing matching local history.
- Device-local privacy counter: aggregate totals for pre-write blocks, title/search scrubs and startup/Sync cleanup. URLs, titles, queries and rules are never stored in the counter.
- Dashboard: aggregate today/week/total/collapse counters, milestones, live shield state and an optional Android Quick Settings tile.
- **Cookies and logins stay saved by default.** Site data, sessions, cache, downloads and tabs are untouched unless an optional action is explicitly enabled on one visual rule.
- **Synced Android private-password tier:** each credential can be marked private independently, stays out of ordinary lists and Android Autofill, and is released only for the current site after a fresh strong biometric. Its privacy marker travels inside the same encrypted Firefox Password Sync record, so reinstall and device replacement restore both together.
- AES-256-GCM `.fprules` bundles and local encrypted QR transfer move rules between Android and Desktop without including history, counters or the passphrase; Desktop can also push/pull the encrypted bundle through Firefox Sync.
- Fresh class-3/strong biometric protection for the Android rule screen and password access; the device PIN is not accepted.
- Automatic and on-demand privacy integrity self-tests after Mozilla updates.
- Separate Android package: `io.github.astropuzzo.fenixprivacy`, so official Firefox can stay installed.
- Firefox Accounts/Sync code retained from upstream Fenix.
- Mozilla telemetry/crash reporting disabled for the custom build.
- Built-in updater: checks GitHub releases, automatically downloads a newer APK, verifies SHA-256, then hands it to Android for the required installation confirmation.
- Stable-upstream CI: tracks Firefox stable release tags, not Nightly commits.

See [Privacy Studio 3.0](docs/PRIVACY_STUDIO_V3.md) for feature parity, matcher semantics and privacy guarantees.
See [Password privacy tiers](docs/PASSWORD_PRIVACY.md) for setup, daily use, recovery, migration, threat model and the explicit native-Desktop limit.

## Firefox Desktop (Windows, macOS and Linux)

Password privacy is enforced by the native Android build. The Desktop deliverable below is a WebExtension for selective-history protection; Firefox does not expose native saved passwords or `about:logins` filtering to WebExtensions. It therefore does not claim to hide synchronized private credentials on Desktop. A separately maintained privileged Firefox Desktop build is required for that guarantee.

This repository also contains **Fenix Privacy Desktop**, a Firefox WebExtension under [`desktop/firefox-extension`](desktop/firefox-extension). It mirrors the selective-history behavior on Firefox for Windows, macOS and Linux:

- domain/subdomain, keyword/phrase and regular-expression rules;
- visual allow/block/collapse/delayed-forgetting rules, profiles, inherited per-tab mode and one-shot navigation protection;
- encrypted Android-compatible file/QR import/export and encrypted Firefox Sync transport;
- URL-bar shield button and popup, aggregate dashboard, cleanup preview, conflict warnings and integrity self-test;
- immediate deletion on history/navigation events plus title-based cleanup after page load;
- full-history scrub on demand, at startup and every 15+ minutes;
- rules local by default, with opt-in Firefox `storage.sync` after Firefox's built-in consent;
- no browsing data transmitted to a developer-controlled server;
- GitHub Actions builds an unsigned test XPI for pull requests, while every main-branch desktop release requires an **unlisted Mozilla-signed XPI** using `AMO_API_KEY` / `AMO_API_SECRET`. Unsigned XPI publication is blocked. If `WINDOWS_SIGNING_PFX_BASE64` and `WINDOWS_SIGNING_PFX_PASSWORD` are configured, CI also Authenticode-signs and verifies the installer; otherwise the release metadata states plainly that the EXE is unsigned. Signed desktop releases publish `desktop-updates.json`, and the rolling production release exposes the XPI plus the Windows installer in one stable location.

Firefox Desktop requires Mozilla signing for normal permanent installation. See [`desktop/firefox-extension/README.md`](desktop/firefox-extension/README.md) and [`desktop/windows`](desktop/windows).

## How the auto-update pipeline works

Every day GitHub Actions checks Mozilla's `FIREFOX_*_RELEASE` tags and verifies the candidate against the public Firefox for Android Release Notes/date. If a newer Release-channel version exists, it fetches that exact Firefox source, applies `scripts/apply_fenix_privacy.py`, builds an optimized ARM64 release APK, signs it with the repository's private Actions secrets, verifies the signature, generates `update.json`, and publishes both files as the latest GitHub Release.

If Mozilla changes an integration point and the patch no longer applies exactly, CI fails **before** a release is created. This deliberately favors no update over a broken privacy build.

The Android check runs every day at **04:23 UTC** and can also be started manually. A new Mozilla stable tag is recorded in `UPSTREAM_REF` only after the ARM64 APK, signing certificate, package identity, Gecko libraries, update manifest and GitHub Release have all passed.

Desktop is a Mozilla-signed WebExtension rather than a forked Firefox binary, so it is rebuilt when the extension changes, not for every Firefox source tag. The Windows EXE is an installer for that same XPI: all Privacy Studio 3.0 behavior is therefore included in the EXE path too. The same signed XPI works on Windows, macOS and Linux; the rolling production release always keeps it beside the newest Android build.

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
