# Project status

Last audited: 2026-08-28

## Upstream baseline

- Firefox for Android public Release: 154.0.1
- Source tag: `FIREFOX_154_0_1_RELEASE`
- Source revision: `9cd094dbc3eac5df87a24e7a871e52880cb8cd42`

## Implemented

- Native pre-write history suppression for blacklisted domains and URL/search keywords.
- Optional regex, case-sensitive matching, whole-word matching and URL decoding.
- Native address-bar shield button with current-site status and contextual actions; no omnibox command syntax.
- Per-tab and child-tab protection, next-navigation protection and 15-minute/session global shields.
- Delayed `FORGET_AFTER` and next-start `FORGET_ON_RESTART` rules while the matching page remains usable.
- `closeTab` applies only to tabs restored at the next Firefox start, never to a live tab.
- Page-title detection with immediate exact-URL history/metadata removal.
- Firefox recent-search/history-metadata suppression.
- Startup + 15-minute scrub of matching local and Sync-imported history.
- Manual full cleanup from Settings > Privacy & security > Private history rules.
- Cookies, logins, cache, downloads and site data remain untouched unless a destructive option is explicitly enabled.
- Aggregate-only privacy counters; no matched URLs are stored in diagnostics.
- Password-encrypted rule backup through files or cross-device QR codes; the passphrase is never embedded in the QR.
- Separate app identity (`io.github.astropuzzo.fenixprivacy`) so official Firefox can coexist.
- Upstream Mozilla Account / Firefox Sync implementation retained.
- Mozilla telemetry and crash reporting disabled in the custom release build.
- Stable-channel verifier using Firefox for Android public release notes and offered date.
- GitHub Actions build, release signing, APK signature verification and public certificate pin.
- Built-in release checker/downloader with SHA-256 validation and Android Package Installer handoff.

## QA completed in this workspace

- Python patch/release tools compile.
- Fifteen automated patch, parity and release-tool tests pass.
- Desktop matcher/runtime tests pass, including delayed cleanup and event-page session-state recovery.
- Mozilla `web-ext` lint passes with zero errors, warnings or notices.
- The unsigned XPI builds successfully and passes archive-integrity verification.
- XML overlays parse successfully.
- GitHub Actions YAML parses successfully.
- The complete overlay and toolbar patch apply to the exact `FIREFOX_154_0_1_RELEASE` source files.
- Stable and legacy `HistoryMetadataMiddleware` patch anchors are tested.
- Signing JKS fingerprint matches `SIGNING_CERT_SHA256`.
- Repository secret scan confirms no keystore payload or signing passwords are present.

## Still gated on CI/device validation

A full Firefox Android release build is intentionally not claimed here: this workspace does not contain the complete Mozilla source/toolchain checkout. The pull-request workflow is the compilation gate and produces an unsigned ARM64 smoke-test APK; the main-branch workflow signs, verifies and publishes the production APK.

Android requires user confirmation for normal APK updates; a non-root/non-device-owner app cannot silently install its own update.

## Firefox Desktop / Windows

- WebExtension source added under `desktop/firefox-extension`.
- Manifest V3, Firefox 142+; declares `data_collection_permissions.required = ["none"]` and optional consent for synced rule data.
- History URL/domain/query matching plus title matching through `tabs.onUpdated`.
- Address-bar page action and toolbar popup expose the same contextual controls as Android.
- Process/session-only tab state uses Firefox `storage.session`, never durable local storage.
- Startup and periodic history scrubbing implement immediate, delayed and next-restart retention.
- Rule sync through Firefox `storage.sync` on desktop; local fallback if unavailable.
- The Windows EXE bundles the same XPI, so Privacy Studio 3 controls are present in the desktop app installer too.
- `desktop-extension.yml` lints/builds on every desktop change, stamps a monotonically increasing 3.0 version, requires unlisted AMO signing for every main-branch release, verifies the returned XPI signature block, and never publishes an unsigned XPI release.
- Authenticode signing is supported and verified when the separate Windows certificate secrets are configured; the published signing manifest reports the EXE status truthfully when they are absent.
