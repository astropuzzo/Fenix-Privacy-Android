# Release signing

Fenix Privacy must always be updated with the same Android signing key. Never commit the `.jks` file or its passwords.

Add these GitHub Actions repository secrets:

- `ANDROID_KEYSTORE_BASE64` — base64 of the complete JKS file, on one line.
- `ANDROID_KEY_ALIAS` — key alias.
- `ANDROID_STORE_PASSWORD` — JKS store password.
- `ANDROID_KEY_PASSWORD` — private-key password.

The workflow decodes the keystore only inside the ephemeral GitHub runner, signs the universal APK with `apksigner`, verifies the signature, then deletes the temporary keystore.

Keep an offline backup of the JKS and passwords. Losing the signing key means Android will not accept future builds as updates of the installed app.

## Pinned public certificate

`SIGNING_CERT_SHA256` stores only the public SHA-256 certificate fingerprint. CI compares every signed APK against it before publishing. This file is safe to keep public; the private JKS and passwords must remain offline/GitHub-secret only.

## Mozilla Add-ons API credentials

Desktop extension signing uses Mozilla Add-ons (AMO) API credentials. Sign in
to the [AMO API credentials page](https://addons.mozilla.org/en-US/developers/addon/api/key/),
complete two-factor authentication if requested, and create or regenerate the
personal JWT credentials.

Copy the pair into GitHub repository **Settings > Secrets and variables >
Actions**:

- AMO's **JWT issuer** (the value beginning with `user:`) becomes
  `AMO_API_KEY`.
- AMO's complete **JWT secret** becomes `AMO_API_SECRET`.

Always replace both values from the same newly generated pair. Never put the
secret in a workflow file, issue, pull request, or build log. If `web-ext sign`
reports `Error decoding signature`, the stored secret is malformed or does not
match the stored key; regenerate the pair and update both repository secrets.

Do not save the visually masked JWT value containing dots: GitHub needs the
complete underlying secret. Main-branch and manual desktop releases fail closed
if either secret is missing or AMO rejects it. Unsigned XPIs are produced only
as pull-request test artifacts and are never published as installable releases.

## Optional Windows Authenticode certificate

Mozilla signs the XPI; a separate code-signing certificate is required to sign the Windows installer executable. Add both repository secrets to enable it:

- `WINDOWS_SIGNING_PFX_BASE64` — base64 of the complete code-signing PFX.
- `WINDOWS_SIGNING_PFX_PASSWORD` — PFX password.

CI decodes the PFX only on the ephemeral Windows runner, signs with SHA-256 and an RFC 3161 timestamp, verifies the resulting Authenticode chain, and deletes the PFX. If either secret is absent, the build can still publish the mandatory Mozilla-signed XPI and installer, but `desktop-signing.json` and the release notes explicitly mark the EXE as not Authenticode-signed.
