# Security

## Release identity

All published APKs must be signed by the certificate whose SHA-256 digest is stored in `SIGNING_CERT_SHA256`. CI fails closed if the configured GitHub Actions signing key produces a different certificate.

Never commit the private JKS or its passwords. Keep the supplied signing backup offline in at least one additional secure location.

## Update chain

The app reads `update.json` from the project's latest GitHub Release, downloads the referenced APK, checks its SHA-256, and hands it to Android's Package Installer. On updates, Android additionally enforces that the APK signing identity matches the already-installed app.

## Privacy scope

Private-history rules affect Firefox local Places history and Firefox history metadata/recent searches. They do not erase records held by websites, search-provider accounts, DNS resolvers, network operators, keyboards, other applications, screenshots, downloads, bookmarks or open tabs.
