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
