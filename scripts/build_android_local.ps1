[CmdletBinding()]
param(
    [switch]$Offline,
    [string]$BuildRoot = 'F:\Fenix-Privacy-Android-Local',
    [string]$OutputDir,
    [string]$Ref
)

$ErrorActionPreference = 'Stop'

$mozillaBuild = 'C:\mozilla-build'
$bash = Join-Path $mozillaBuild 'msys2\usr\bin\bash.exe'
if (-not (Test-Path -LiteralPath $bash)) {
    throw 'MozillaBuild is required. Install https://ftp.mozilla.org/pub/mozilla/libraries/win32/MozillaBuildSetup-Latest.exe'
}

function ConvertTo-MsysPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Expected an absolute Windows path: $full"
    }

    $drive = $Matches[1].ToLowerInvariant()
    $tail = $Matches[2] -replace '\\', '/'
    return "/$drive/$tail"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$OutputDir = if ($OutputDir) { $OutputDir } else { Join-Path $repoRoot 'outputs' }
$script = ConvertTo-MsysPath (Join-Path $PSScriptRoot 'local_build_android.sh')
$buildRootMsys = ConvertTo-MsysPath $BuildRoot
$outputDirMsys = ConvertTo-MsysPath $OutputDir

$arguments = @(
    '-l', $script,
    '--build-root', $buildRootMsys,
    '--output-dir', $outputDirMsys
)
if ($Offline) {
    $arguments += '--offline'
}
if ($Ref) {
    $arguments += @('--ref', $Ref)
}

$env:MOZILLABUILD = "$mozillaBuild\"
$env:MSYSTEM = 'MSYS'
$env:CHERE_INVOKING = '1'

& $bash @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Local Android build failed with exit code $LASTEXITCODE"
}

Write-Host "Completed. Installable local-development APKs are in $OutputDir."
