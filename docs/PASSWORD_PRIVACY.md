# Password privacy tiers

## What is implemented

Fenix Privacy for Android has an origin-bound, per-credential privacy tier. It does not create a
visible vault, a hidden-site list, a private-credential count or a synthetic password record.

The classification travels inside the existing Firefox login record. Firefox Password Sync
therefore encrypts, uploads, merges and restores the password and its privacy status together when
Password Sync is enabled.

| Data | Before strong biometric | Recovery |
| --- | --- | --- |
| Standard credentials | No origin, username, password or count is read or rendered | Firefox Accounts Password Sync |
| Private credentials | Omitted from ordinary lists, searches and external Android Autofill | Firefox Accounts Password Sync, including the private marker |
| History rules | Rule details remain behind strong biometric | Encrypted `.fprules` export/import |
| Desktop native password list | Not controlled by the current WebExtension | Requires a privileged/native Firefox Desktop build |

Firefox for Android does not synchronize WebExtension `storage.sync` data with the user's Mozilla
account. Password privacy therefore uses the native Password Sync record, not extension storage.
See Mozilla's
[`storage.sync` documentation](https://developer.mozilla.org/docs/Mozilla/Add-ons/WebExtensions/API/storage/sync).

## Set up and manage passwords

1. Sign in to the Mozilla account in Fenix Privacy and enable **Passwords** in Sync.
2. Open **Settings → Private history rules** and pass a fresh class-3/strong biometric.
3. Open the neutral **Saved passwords** row.
4. Select a credential and choose **Use private access**. The same menu can return it to standard
   access, edit it or delete it.
5. Wait for Firefox Sync, or use **Sync now**, before uninstalling or moving to another Fenix Privacy
   Android installation.

The normal password screen never shows private records. The neutral management row has no private
word, badge, domain or count before biometric authentication. Android screenshots and Recents
previews are blocked while sensitive management data is visible.

The preview release stored password classification on visual history rules. The first successful
open of **Saved passwords** migrates every matching form credential into its own synchronized login
record and removes the old flags only after the writes succeed.

## Use a password on a website

1. Focus the website's login field.
2. Firefox always shows one neutral **Unlock saved passwords** action. It is shown even when the
   current site has no saved login, so its presence reveals nothing.
3. Tap it, then pass a fresh strong biometric, to retrieve only standard credentials for the current
   origin.
4. Long-press the same neutral action, then pass a fresh strong biometric, to retrieve only private
   credentials for the current origin.
5. If the requested tier has no matching credential, the prompt closes without revealing a count.

The device PIN is never accepted. Every new fill request starts locked; the Android Autofill
authentication cache is disabled.

## Security invariants

- Gecko receives only a transient, metadata-free action before authentication.
- Real records are queried only after authentication and only for the current origin.
- Standard and private result sets are mutually exclusive.
- Internal private metadata is stripped before an authenticated credential reaches Gecko.
- Private records are filtered from the ordinary Saved Logins UI and Android Autofill search.
- Website password updates preserve the synchronized private marker.
- Privacy Studio re-locks when left and uses Android `FLAG_SECURE` while sensitive data is visible.
- Migration never clears preview flags before the corresponding login updates succeed.

## Reinstall and device replacement

Install Fenix Privacy, sign in to the same Mozilla account, enable Password Sync and let the first
sync finish. Both the credentials and their private status return because they are one Sync record.
No separate `.fprules` file is required for the new password classification. A `.fprules` backup
is still needed for history rules and for preview-era flags that have not yet been migrated.

## Limits

- An official or otherwise unmodified Firefox client ignores the Fenix Privacy marker and can still
  display the synchronized credential. Use the private tier only on Fenix Privacy clients until a
  native Desktop build exists.
- The current Desktop deliverable is a WebExtension. Mozilla does not expose native saved passwords
  or `about:logins` filtering to WebExtensions, and it does not expose a portable strong-biometric
  unlock API. The extension therefore does not pretend to protect Desktop native passwords.
- Form-based website credentials are supported. HTTP authentication-dialog credentials cannot
  carry this compatible marker and fail closed instead of being mislabeled as private.
- The design does not defend against a rooted/compromised OS, an attacker whose biometric is
  enrolled as strong, or a page after the user deliberately fills a credential.

A real Desktop equivalent requires a separately built and maintained privileged Firefox Desktop
fork; an XPI cannot honestly provide the same guarantee.
