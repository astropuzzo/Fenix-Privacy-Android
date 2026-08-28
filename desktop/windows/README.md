# Windows install helper

Once `Fenix-Privacy-Desktop.xpi` has been Mozilla-signed, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\Install-FenixPrivacy.ps1 -XpiPath .\Fenix-Privacy-Desktop.xpi
```

Firefox still shows its normal extension-install confirmation. This is intentional; a regular Windows Firefox installation does not allow a self-distributed unsigned add-on to bypass Mozilla signing.

The released EXE wraps this same XPI, so it includes the same URL-bar shield and Privacy Studio features. XPI signing and Windows Authenticode are independent: `desktop-signing.json` in each release records both states.
