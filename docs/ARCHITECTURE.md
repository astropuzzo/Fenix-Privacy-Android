# Architecture

This repository is a small downstream patch layer rather than a permanent copy of Mozilla's huge monorepo. CI discovers the latest **stable** Firefox release tag, fetches that exact source revision, applies the native Fenix Privacy patch, builds, signs and publishes an APK.

## Privacy path

1. `PrivateHistoryDelegate` replaces Android Components' normal history delegate in Fenix.
2. Domain and URL/search-query matches are rejected before `recordVisit()` reaches Places.
3. Page-title matches are removed as soon as the title becomes known.
4. `HistoryMetadataMiddleware` is guarded so blocked search terms are not added to Firefox's separate recent-search metadata.
5. `PrivateHistoryMaintenanceWorker` scrubs matching local/remote-synced history and metadata at startup and every 15 minutes, covering entries imported by Firefox Sync from another device.
6. A manual cleanup command in Settings scans existing data with the same rules.

## Updates

CI follows `FIREFOX_*_RELEASE` tags but only accepts a tag after Mozilla's public Firefox for Android release-notes page confirms that version has reached the Release channel; this avoids picking pre-created release-engineering tags. A release is published only after the downstream patch applies, the build succeeds, the APK is signed, and `apksigner verify` succeeds.

The app checks `releases/latest/download/update.json` every 12 hours, downloads a newer signed APK with Android `DownloadManager`, verifies its SHA-256, and offers it to Android's package installer. Android still requires user confirmation for installation unless the device grants privileged/device-owner capabilities.

## Identity and Sync

The app uses the distinct package `io.github.astropuzzo.fenixprivacy`, so it can coexist with official Firefox. The Mozilla `sharedUserId` used by official release builds is removed. Firefox Accounts/Sync code remains upstream Fenix code; the production FxA configuration is not replaced by this patch.

Telemetry and Mozilla crash reporting are disabled in the custom release build.
