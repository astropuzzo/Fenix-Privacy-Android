# Password privacy tiers

## Status

The Android fork contains the first native implementation of an origin-bound password gate.
It is intentionally fail-closed and does not describe the current Desktop WebExtension as a
password-manager protection: a WebExtension cannot filter Firefox's native `about:logins` UI.

Password records and their privacy classification have different recovery properties today:

| Data | Android protection | Recovery today |
| --- | --- | --- |
| Saved username/password | Firefox login database; never returned to Gecko before authentication | Firefox Accounts password Sync, when enabled |
| `protectLogin` rule flag | App-private rules, hidden behind a fresh strong biometric | AES-256-GCM `.fprules` export/import |
| Desktop native password list | Not controlled by this WebExtension | Requires a privileged/native Firefox Desktop implementation |

Firefox for Android implements the WebExtension `storage.sync` API as device-local storage; it
does not synchronize that data with a Mozilla account. Consequently, the repository must not
claim that Desktop extension storage is an Android recovery channel. See Mozilla's
[`storage.sync` documentation](https://developer.mozilla.org/docs/Mozilla/Add-ons/WebExtensions/API/storage/sync).

## Android user flow

1. Add or edit an ordinary Privacy Studio rule and enable **Protect matching saved passwords**.
   There is no separately named vault or visible private-password section.
2. When a login field is focused, Firefox displays one generic **Unlock saved passwords** action.
   Before authentication it contains no real origin, username, password or credential count.
3. Tap that action and pass a fresh class-3/strong biometric to use ordinary credentials for the
   current site.
4. Long-press the same action and pass the same fresh biometric to use protected credentials for
   the current site. A zero-result lookup simply closes the prompt, so the pre-authentication UI
   does not reveal whether a protected record exists.

The normal Saved Logins list and Android's external autofill search omit protected records. The
Privacy Studio rule screen itself requires a fresh strong biometric and does not accept the device
PIN. Leaving the screen locks it again.

## Security invariants

- Gecko receives only a transient neutral action until authentication succeeds.
- The real lookup is performed after authentication and only for the current origin.
- A normal tap and a long-press use mutually exclusive standard/private filters.
- The authentication cache in Android Autofill is disabled; every new fill request starts locked.
- Strong biometric availability is checked explicitly. Missing enrollment or hardware fails
  closed; there is no PIN or unsecured-warning bypass.
- Protected records are filtered from the ordinary Saved Logins UI and external autofill search.
- Legacy history domain/keyword lists never hide passwords after an upgrade. Only an explicit
  `protectLogin` flag on an active visual rule can classify a login as protected.

## Limits that remain

- Firefox Sync still sends the underlying login to every signed-in Firefox client. An official or
  otherwise unmodified Firefox client can display it because it does not understand this privacy
  tier. End-to-end password Sync is recovery, not cross-client concealment.
- Android rule classification is not yet restored automatically from a Mozilla account. Exporting
  an encrypted `.fprules` bundle is currently required before uninstalling. The bundle includes the
  `protectLogin` flag but never includes usernames or passwords.
- The Desktop deliverable is a WebExtension, not a privileged Firefox fork. It preserves the rule
  flag in encrypted bundles but cannot hide native saved logins or demand OS authentication for
  `about:logins`.
- This design does not defend against a rooted/compromised OS, an attacker whose biometric is
  enrolled as strong, or a page that receives a credential after the user deliberately fills it.

## Release criteria for complete cross-device support

The feature should be called fully synchronized only after both of these land:

1. An end-to-end encrypted rules engine registered with Android's Firefox Accounts/App Services
   Sync stack, with merge, deletion-tombstone and reinstall tests. A WebExtension
   `storage.sync` shim is not sufficient on Android.
2. A privileged/native Firefox Desktop integration that applies the same pre-authentication gate,
   ordinary-list filter and current-origin private gesture. Until then, the Desktop checkbox is
   labeled as an Android classification flag rather than a Desktop protection claim.
