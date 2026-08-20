# Fenix Privacy for Android

A maintainable Firefox-for-Android downstream build with **native selective history suppression**.

The goal is simple: keep normal Firefox history for everything except domains, words/phrases or regular expressions you choose. Matching navigation data is blocked or scrubbed without requiring Private Browsing for every visit.

## Features

- Domain blacklist: exact domains and all subdomains.
- Keyword/phrase blacklist: URL, decoded search query and page-title matching.
- Optional regular expressions, case sensitivity and whole-word matching.
- Pre-write suppression: URL/domain matches are rejected before Places `recordVisit()`.
- Recent-search protection: matching Firefox history metadata/search terms are suppressed too.
- Sync scrubber: scrubs matching records at startup and every 15 minutes if they arrive from another Firefox through Sync.
- One-tap cleanup of existing matching local history.
- Separate Android package: `io.github.astropuzzo.fenixprivacy`, so official Firefox can stay installed.
- Firefox Accounts/Sync code retained from upstream Fenix.
- Mozilla telemetry/crash reporting disabled for the custom build.
- Built-in updater: checks GitHub releases, automatically downloads a newer APK, verifies SHA-256, then hands it to Android for the required installation confirmation.
- Stable-upstream CI: tracks Firefox stable release tags, not Nightly commits.

## Firefox Desktop for Windows

This repository also contains **Fenix Privacy Desktop**, a Firefox WebExtension under [`desktop/firefox-extension`](desktop/firefox-extension). It mirrors the selective-history behavior on Windows Firefox:

- domain/subdomain, keyword/phrase and regular-expression rules;
- immediate deletion on history/navigation events plus title-based cleanup after page load;
- full-history scrub on demand, at startup and every 15+ minutes;
- rules local by default, with opt-in Firefox `storage.sync` after Firefox's built-in consent;
- no browsing data transmitted to a developer-controlled server;
- GitHub Actions builds an unsigned test XPI automatically and can request an **unlisted Mozilla-signed XPI** when `AMO_API_KEY` / `AMO_API_SECRET` are configured.

Firefox Desktop requires Mozilla signing for normal permanent installation. See [`desktop/firefox-extension/README.md`](desktop/firefox-extension/README.md) and [`desktop/windows`](desktop/windows).

## How the auto-update pipeline works

Every day GitHub Actions checks Mozilla's `FIREFOX_*_RELEASE` tags and verifies the candidate against the public Firefox for Android Release Notes/date. If a newer Release-channel version exists, it fetches that exact Firefox source, applies `scripts/apply_fenix_privacy.py`, builds an optimized universal release APK, signs it with the repository's private Actions secrets, verifies the signature, generates `update.json`, and publishes both files as the latest GitHub Release.

If Mozilla changes an integration point and the patch no longer applies exactly, CI fails **before** a release is created. This deliberately favors no update over a broken privacy build.

## Initial repository setup

1. Add the four Android signing secrets described in [`docs/SIGNING.md`](docs/SIGNING.md).
2. Run **Sync Firefox, build and release** once from Actions.
3. Install `Fenix-Privacy-universal.apk` from the resulting Release and allow “Install unknown apps” for Fenix Privacy when Android asks.
4. For a permanently installable Windows add-on, add `AMO_API_KEY` and `AMO_API_SECRET` and run **Build Firefox Desktop extension**; the workflow requests an unlisted Mozilla signature and publishes the signed XPI.

After that, stable Firefox Android updates are handled by the workflow and the installed app's update checker.

## Upstream currently pinned

- Release: `FIREFOX_153_0_4_RELEASE`
- Revision: `c178247e1dfea52241a6b18b18cf3a00f8da935c`

These files are changed by CI only after a successful signed release.

## Important privacy scope

The rules protect **Firefox browsing history and Firefox recent-search/history metadata** in the Android build, and Firefox browsing history in the desktop extension. They do not erase DNS logs, network/provider logs, search-engine account history, keyboard history, screenshots, downloads, bookmarks, open tabs, or history independently stored by another app/device.

## License and branding

Downstream source changes are under MPL-2.0. The app is branded **Fenix Privacy**, not Firefox, and uses a separate Android application ID. Mozilla Firefox remains the upstream project.
