# Fenix Privacy Desktop

Firefox Desktop WebExtension for selective history suppression. It removes matching entries for domains, keywords/phrases, decoded URL/query content, page titles and regular expressions.

Version 3.0 adds a native URL-bar shield with current-page status and quick actions, visual allow/block/collapse/delayed-forgetting rules, profiles, exact exceptions, inherited per-tab and next-navigation shields, conflict warnings, aggregate diagnostics, optional per-rule actions, integrity checks, and AES-256-GCM file/QR bundles compatible with Android.

## Privacy model

The extension does not transmit browsing data or rules to a developer-controlled server. Rules stay local by default. The user can explicitly push a password-encrypted bundle to `storage.sync`; Firefox first presents its built-in data-transmission consent. The passphrase is never stored or synchronized. Android imports and exports the same `.fprules` format, because native Fenix settings cannot directly read WebExtension `storage.sync`.

Cookies, logins, sessions, cache and downloads remain untouched unless an optional action is explicitly enabled on one visual rule and the corresponding Firefox permission is granted. A `closeTab` option affects matching restored tabs only at the next Firefox start; it never closes the page being used.

## Permissions

- `history`: inspect and delete matching history entries.
- `tabs`: inspect top-level tab URL/title changes so title-only rules are caught after page load.
- `webNavigation`: react as soon as a top-level navigation is committed.
- `storage`: store rules and statistics; uses `storage.sync` with a local fallback.
- `alarms`: periodic scrub to catch entries arriving through Firefox Sync.
- `contextMenus`: quick rules from the current page.

The `page_action` shield is shown inside Firefox's URL bar. Click it for page/site/retention controls; no omnibox keyword or command parser is installed.
Per-tab, child-tab, next-navigation and session shields use Firefox's memory-only `storage.session`, so Manifest V3 event-page suspension does not disable them and closing Firefox clears them.

`cookies`, `browsingData`, download-list access and all-site host access are optional. Firefox requests them only when the user enables the matching destructive rule action.

## Install on Windows

A normal Firefox Release build requires Mozilla-signed add-ons. Main-branch releases therefore fail unless repository secrets `AMO_API_KEY` (JWT issuer) and `AMO_API_SECRET` (complete JWT secret) produce an unlisted Mozilla-signed XPI. The workflow verifies that AMO returned a signature block before publishing. Unsigned XPIs exist only as pull-request test artifacts and can be loaded temporarily through `about:debugging`; they are never published as normal releases. The signed XPI contains a self-distribution `update_url`; when this repository is public, Firefox reads `desktop-updates.json` and automatically installs later signed versions.

<!-- CI validation trigger: 2026-08-20T18:59+02:00 -->
