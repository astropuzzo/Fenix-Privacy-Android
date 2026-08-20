#ifndef MyAppVersion
  #define MyAppVersion "0.0.0"
#endif

#ifndef XpiSource
  #error XpiSource must be provided by the build workflow.
#endif

#ifndef OutputBase
  #define OutputBase "Fenix-Privacy-Desktop-Installer"
#endif

#define MyAppName "Fenix Privacy Desktop"

[Setup]
AppId={{8E5BA863-6C44-4E86-9C3D-2B331C692FE7}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher=astropuzzo
DefaultDirName={localappdata}\Fenix Privacy Desktop
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\out
OutputBaseFilename={#OutputBase}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
Uninstallable=yes

[Files]
Source: "{#XpiSource}"; DestDir: "{app}"; DestName: "Fenix-Privacy-Desktop.xpi"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Fenix Privacy Desktop"; Filename: "{code:GetFirefoxPath}"; Parameters: """{app}\Fenix-Privacy-Desktop.xpi"""

[Run]
Filename: "{code:GetFirefoxPath}"; Parameters: """{app}\Fenix-Privacy-Desktop.xpi"""; Description: "Open Fenix Privacy in Firefox"; Flags: nowait postinstall skipifsilent

[Code]
function GetFirefoxPath(Param: String): String;
var
  Path: String;
begin
  Path := '';

  if IsWin64 then
  begin
    RegQueryStringValue(HKLM64,
      'SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\firefox.exe',
      '', Path);
  end;

  if Path = '' then
  begin
    RegQueryStringValue(HKLM,
      'SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\firefox.exe',
      '', Path);
  end;

  if Path = '' then
  begin
    RegQueryStringValue(HKCU,
      'SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\firefox.exe',
      '', Path);
  end;

  if Path = '' then
  begin
    Path := ExpandConstant('{autopf}\Mozilla Firefox\firefox.exe');
    if not FileExists(Path) then
      Path := '';
  end;

  if Path = '' then
  begin
    MsgBox(
      'Firefox was not found. Install Firefox first, then run this installer again.',
      mbError, MB_OK);
    Path := 'firefox.exe';
  end;

  Result := Path;
end;
