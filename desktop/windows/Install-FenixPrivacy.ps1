param(
    [Parameter(Mandatory=$true)]
    [string]$XpiPath
)

$ErrorActionPreference = "Stop"
$resolved = (Resolve-Path $XpiPath).Path
$firefoxCandidates = @(
    "$env:ProgramFiles\Mozilla Firefox\firefox.exe",
    "${env:ProgramFiles(x86)}\Mozilla Firefox\firefox.exe"
)
$firefox = $firefoxCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $firefox) { throw "Firefox was not found in the standard Program Files locations." }

$uri = [System.Uri]::new($resolved).AbsoluteUri
Write-Host "Opening the signed XPI in Firefox. Firefox will ask you to confirm installation."
Start-Process -FilePath $firefox -ArgumentList @("-new-tab", $uri)
