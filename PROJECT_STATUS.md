# Project status

Last audited: 2026-08-24

## Upstream baseline

- Firefox for Android public Release: 153.0.4
- Source tag: `FIREFOX_153_0_4_RELEASE`
- Source revision: `c178247e1dfea52241a6b18b18cf3a00f8da935c`

## Implemented

- Native pre-write history suppression for blacklisted domains and URL/search keywords.
- Optional regex, case-sensitive matching, whole-word matching and URL decoding.
- Page-title detection with immediate exact-URL history/metadata removal.
- Firefox recent-search/history-metadata suppression.
- Startup + 15-minute scrub of matching local and Sync-imported history.
- Manual full cleanup from Settings > Privacy & security > Private history rules.
- Separate app identity (`io.github.astropuzzo.fenixprivacy`) so official Firefox can coexist.
- Upstream Mozilla Account / Firefox Sync implementation retained.
- Mozilla telemetry and crash reporting disabled in the custom release build.
- Stable-channel verifier using Firefox for Android public release notes and offered date.
- GitHub Actions build, release signing, APK signature verification and public certificate pin.
- Built-in release checker/downloader with SHA-256 validation and Android Package Installer handoff.

## QA completed in this workspace

- Python patch/release tools compile.
- Seven automated Android patch/release tests pass.
- XML overlays parse successfully.
- GitHub Actions YAML parses successfully.
- Stable and legacy `HistoryMetadataMiddleware` patch anchors are tested.
- Signing JKS fingerprint matches `SIGNING_CERT_SHA256`.
- Repository secret scan confirms no keystore payload or signing passwords are present.

## Still gated on CI/device validation

A full Firefox Android release build is intentionally not claimed here: this workspace does not contain the complete Mozilla source/toolchain checkout. The GitHub workflow is the compilation gate. The first published APK should be smoke-tested on a device for sign-in/Sync, history suppression, cleanup and self-update before treating the build as production-proven.

Android requires user confirmation for normal APK updates; a non-root/non-device-owner app cannot silently install its own update.

## Firefox Desktop / Windows

- WebExtension source added under `desktop/firefox-extension`.
- Manifest V3, Firefox 140+; declares `data_collection_permissions.required = ["none"]` and optional consent for synced rule data.
- History URL/domain/query matching plus title matching through `tabs.onUpdated`.
- Startup and periodic history scrubbing.
- Rule sync through Firefox `storage.sync` on desktop; local fallback if unavailable.
- `desktop-extension.yml` lints/builds on every desktop change, stamps a monotonically increasing version, requires unlisted AMO signing for every main-branch release, verifies the returned XPI signature block, and never publishes an unsigned release.
