# Local Android build on Windows

The local builder uses MozillaBuild and a dedicated working directory. Its
first run discovers the latest stable Firefox release, fetches that tag,
applies the Fenix Privacy overlay, installs Mozilla's Android toolchains, and
builds installable release-optimized APKs with the standard Android debug key.

## Requirements

- Windows 10 or 11
- [MozillaBuild](https://ftp.mozilla.org/pub/mozilla/libraries/win32/MozillaBuildSetup-Latest.exe)
- Git
- About 60 GB of free disk space for the first build

The default source and cache directory is `F:\Fenix-Privacy-Android-Local`.
Use `-BuildRoot` to put it on another drive.

## Prepare or update and build

Run this from the repository in PowerShell:

```powershell
.\scripts\build_android_local.ps1
```

The online command discovers and fetches the latest stable
`FIREFOX_*_RELEASE` tag. To build an exact release instead:

```powershell
.\scripts\build_android_local.ps1 -Ref FIREFOX_154_0_RELEASE
```

## Rebuild offline

After one successful online build, disconnecting the machine is supported:

```powershell
.\scripts\build_android_local.ps1 -Offline
```

Offline mode uses the prepared source, Mozilla artifact cache, Android SDK,
JDK, Gradle distribution, and Gradle dependency cache. It does not fetch a new
Firefox release. Run the online command again when an upstream update is
wanted.

Use `-OutputDir` to select where APKs are copied:

```powershell
.\scripts\build_android_local.ps1 -Offline -OutputDir C:\Fenix-APKs
```

For most physical Android devices, use the `arm64-v8a` APK. The `universal`
APK is larger but contains all supported native architectures.

These local APKs use the Android debug certificate and are suitable for local
installation and testing. GitHub releases use the separate production signing
key described in [SIGNING.md](SIGNING.md); Android will not update one signing
identity with APKs signed by the other.
