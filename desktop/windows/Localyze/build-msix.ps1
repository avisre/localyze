# build-msix.ps1 — Package Localyze.ai as a signed MSIX.
#
# Usage:
#   $env:LOCALYZE_CERT_PFX = "C:\path\to\localyze-codesign.pfx"
#   $env:LOCALYZE_CERT_PASSWORD = "<pfx password>"   # optional; prompted otherwise
#   pwsh -File build-msix.ps1                        # default: Release / win-x64
#   pwsh -File build-msix.ps1 -Configuration Release -Rid win-arm64
#
# What it does:
#   1. dotnet publish -c $Configuration -r $Rid --self-contained true
#   2. Copies the published output into a staging tree, drops Package.appxmanifest
#      and Assets/ next to the binaries.
#   3. MakeAppx pack -d <staging> -p <out>.msix
#   4. signtool sign /fd SHA256 /a /f $LOCALYZE_CERT_PFX /p $LOCALYZE_CERT_PASSWORD <out>.msix
#
# Requirements (must be on PATH or under the standard Windows Kits dir):
#   - dotnet SDK 9
#   - MakeAppx.exe and signtool.exe (Windows 10/11 SDK, e.g.
#     "C:\Program Files (x86)\Windows Kits\10\bin\10.0.22621.0\x64\")
#
# Signing certificate:
#   The Subject CN of the cert MUST match the Publisher in Package.appxmanifest
#   ("CN=Localyze" by default). Update the manifest first if your cert uses a
#   different DN, otherwise the MSIX will install but Windows will reject the
#   signature with APPX_E_INVALID_PUBLISHER.

[CmdletBinding()]
param(
    [string]$Configuration = "Release",
    [string]$Rid           = "win-x64",
    [string]$OutDir        = "dist",
    [string]$PackageName   = "Localyze"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$here       = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectDir = $here
$stagingDir = Join-Path $here "obj/msix-stage/$Rid"
$publishDir = Join-Path $here "bin/$Configuration/net9.0-windows10.0.22621.0/$Rid/publish"
$distDir    = Join-Path $here $OutDir
$msixPath   = Join-Path $distDir "$PackageName-$Rid.msix"

function Find-SdkTool {
    param([string]$Name)
    # Try PATH first, then walk the standard Windows 10/11 SDK install layout.
    $onPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $kitRoot = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
    if (-not (Test-Path $kitRoot)) {
        throw "$Name not on PATH and Windows SDK not found at $kitRoot"
    }
    $candidates = Get-ChildItem -Path $kitRoot -Directory |
        Where-Object { $_.Name -match '^10\.' } |
        Sort-Object Name -Descending
    foreach ($c in $candidates) {
        $p = Join-Path $c.FullName "x64\$Name"
        if (Test-Path $p) { return $p }
    }
    throw "$Name not found under $kitRoot"
}

Write-Host "==> dotnet publish ($Configuration, $Rid)"
dotnet publish $projectDir `
    -c $Configuration `
    -r $Rid `
    --self-contained true `
    -p:PublishReadyToRun=true `
    -p:WindowsAppSDKSelfContained=true
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed ($LASTEXITCODE)" }

Write-Host "==> Stage MSIX layout at $stagingDir"
if (Test-Path $stagingDir) { Remove-Item -Recurse -Force $stagingDir }
New-Item -ItemType Directory -Path $stagingDir | Out-Null
Copy-Item -Path (Join-Path $publishDir '*') -Destination $stagingDir -Recurse
Copy-Item -Path (Join-Path $projectDir 'Package.appxmanifest') -Destination $stagingDir -Force
$assetsSrc = Join-Path $projectDir 'Assets'
if (Test-Path $assetsSrc) {
    Copy-Item -Path $assetsSrc -Destination (Join-Path $stagingDir 'Assets') -Recurse -Force
}

Write-Host "==> MakeAppx pack -> $msixPath"
New-Item -ItemType Directory -Path $distDir -Force | Out-Null
if (Test-Path $msixPath) { Remove-Item -Force $msixPath }
$makeAppx = Find-SdkTool -Name 'MakeAppx.exe'
& $makeAppx pack /d $stagingDir /p $msixPath /o
if ($LASTEXITCODE -ne 0) { throw "MakeAppx pack failed ($LASTEXITCODE)" }

# --- Signing ---------------------------------------------------------------
$pfx = $env:LOCALYZE_CERT_PFX
if (-not $pfx) {
    Write-Warning "LOCALYZE_CERT_PFX env var is not set — produced MSIX is UNSIGNED."
    Write-Warning "Windows will refuse to install it. Set LOCALYZE_CERT_PFX and re-run to sign."
    Write-Host "Unsigned MSIX written to: $msixPath"
    return
}
if (-not (Test-Path $pfx)) {
    throw "LOCALYZE_CERT_PFX=$pfx does not exist"
}

$signtool = Find-SdkTool -Name 'signtool.exe'
$pw = $env:LOCALYZE_CERT_PASSWORD
$signArgs = @('sign', '/fd', 'SHA256', '/f', $pfx)
if ($pw) { $signArgs += @('/p', $pw) }
$signArgs += @('/tr', 'http://timestamp.digicert.com', '/td', 'SHA256', $msixPath)

Write-Host "==> signtool sign $msixPath"
& $signtool @signArgs
if ($LASTEXITCODE -ne 0) { throw "signtool sign failed ($LASTEXITCODE)" }

Write-Host "==> Done. Signed MSIX: $msixPath"
