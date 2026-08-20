# Windows install helper

Once `Fenix-Privacy-Desktop-Windows.xpi` has been Mozilla-signed, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\Install-FenixPrivacy.ps1 -XpiPath .\Fenix-Privacy-Desktop-Windows.xpi
```

Firefox still shows its normal extension-install confirmation. This is intentional; a regular Windows Firefox installation does not allow a self-distributed unsigned add-on to bypass Mozilla signing.
