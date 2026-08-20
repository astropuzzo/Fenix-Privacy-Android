# Fenix Privacy Desktop

Firefox Desktop WebExtension for selective history suppression. It removes matching entries for domains, keywords/phrases, decoded URL/query content, page titles and regular expressions.

## Privacy model

The extension does not transmit browsing data or rules to a developer-controlled server. Rules stay local by default. The user can explicitly enable rule synchronization; Firefox then presents its built-in data-transmission consent for the rule data (which can contain domains/search terms/settings) and, only after consent, the extension stores rule preferences in `storage.sync` so Firefox Sync can synchronize them between desktop profiles when Add-ons sync is enabled. Firefox for Android does not currently synchronize WebExtension `storage.sync`; the Android build in this repository therefore has its own native rule store.

## Permissions

- `history`: inspect and delete matching history entries.
- `tabs`: inspect top-level tab URL/title changes so title-only rules are caught after page load.
- `webNavigation`: react as soon as a top-level navigation is committed.
- `storage`: store rules and statistics; uses `storage.sync` with a local fallback.
- `alarms`: periodic scrub to catch entries arriving through Firefox Sync.

## Install on Windows

A normal Firefox Release build requires Mozilla-signed add-ons. The GitHub workflow can create an unlisted signed XPI once repository secrets `AMO_API_KEY` and `AMO_API_SECRET` are configured. The signed XPI contains a self-distribution `update_url`; when this repository is public, Firefox reads `desktop-updates.json` and automatically installs later signed releases with higher versions. Until then, the unsigned XPI is suitable for temporary testing via `about:debugging` → **This Firefox** → **Load Temporary Add-on** → select `manifest.json` or the unsigned XPI.
