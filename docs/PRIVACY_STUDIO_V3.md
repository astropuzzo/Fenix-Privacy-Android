# Privacy Studio 3.0

Privacy Studio 3.0 makes the selective-history engine available directly where a page is used. Android adds a native address-bar shield; Firefox Desktop adds a URL-bar `page_action`. Neither platform installs an omnibox keyword or stores command text.

## Contextual controls

| Control | Android | Firefox Desktop |
|---|---|---|
| Current-page state | Native address-bar shield and dialog | URL-bar icon and popup |
| Never save this site | `DOMAIN` block rule | Same portable rule |
| Keep only homepage | `DOMAIN_EXCEPT_ROOT` block rule | Same portable rule |
| Allow exact page | `EXACT_URL` allow rule | Same portable rule |
| Forget after 24 hours | `FORGET_AFTER` with retention | Same portable rule |
| Forget on restart | `FORGET_ON_RESTART` | Same portable rule |
| Shield tab and children | Process-only tab state | Session-only tab state |
| Shield next navigation | One-shot process state | One-shot session state |
| Global temporary mode | 15 minutes, one hour, app session | 15 minutes, one hour, Firefox session |

Long-pressing the Android shield toggles tab-and-child protection. Pages remain open and usable. Tab state and one-shot URLs live in process memory on Android and Firefox `storage.session` on Desktop; they are never written to disk and disappear when the tab/browser session ends.

## Retention semantics

- `BLOCK` and `COLLAPSE_TO_ROOT` prevent or remove the original history entry immediately.
- `FORGET_AFTER` records the visit normally, then makes it eligible for aggregate cleanup after the configured period.
- `FORGET_ON_RESTART` records the visit for the current run and removes it at the next startup or an explicit manual cleanup.
- `closeTab` never closes a live tab. It only removes a matching restored tab during the next startup pass.
- Cookies, logins, site data, cache and downloads remain intact unless their separate destructive option is explicitly enabled.

## Portable rules and encrypted transfer

Android and Desktop use the action/matcher contract in [`../shared/privacy-rule-schema.json`](../shared/privacy-rule-schema.json) and synthetic parity fixtures in [`../shared/rule-fixtures.json`](../shared/rule-fixtures.json). AES-256-GCM `.fprules` files and QR codes contain only an encrypted rule bundle. The passphrase, counters and browsing history are never encoded. QR generation and scanning happen locally.

Allow rules have priority over ordinary visual and legacy rules. Global temporary and tab protection intentionally override allow rules because the user has asked to protect the whole current scope.

## Privacy invariants

- Aggregate counters never contain URLs, titles, search terms or rule text.
- The tester and cleanup preview return decisions/counts only.
- Matching pages stay usable; history policy is independent from cookie/login retention.
- Optional site-data actions require explicit per-rule consent and platform permissions.
- Encrypted transfer uses PBKDF2-HMAC-SHA256 with 210,000 iterations, AES-256-GCM, and a fresh salt and IV.
- Rule-builder conflict and duplicate warnings are computed locally.
