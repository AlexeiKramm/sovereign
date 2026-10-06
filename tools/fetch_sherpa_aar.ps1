# Fetch the pinned sherpa-onnx Android runtime (AAR) into app/libs/ for Windows PowerShell.
# Usage: .\tools\fetch_sherpa_aar.ps1 [-Force]

param (
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$Version = "1.13.8"
$ExpectedSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
$Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${Version}/sherpa-onnx-${Version}.aar"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$DestDir = Join-Path $RepoRoot "app\libs"
$Dest = Join-Path $DestDir "sherpa-onnx-${Version}.aar"

if ($Force -and (Test-Path $Dest)) {
    Remove-Item -Force $Dest
}

if (Test-Path $Dest) {
    $Hash = (Get-FileHash -Path $Dest -Algorithm SHA256).Hash.ToLower()
    if ($Hash -eq $ExpectedSha256) {
        Write-Host "ok: $Dest already present and verified (sherpa-onnx $Version)"
        exit 0
    }
}

if (-not (Test-Path $DestDir)) {
    New-Item -ItemType Directory -Path $DestDir | Out-Null
}

$PartFile = "$Dest.part"
Write-Host "Downloading sherpa-onnx $Version (~48 MB) -> $Dest..."
Invoke-WebRequest -Uri $Url -OutFile $PartFile

$DownloadedHash = (Get-FileHash -Path $PartFile -Algorithm SHA256).Hash.ToLower()
if ($DownloadedHash -ne $ExpectedSha256) {
    Remove-Item -Force $PartFile
    Write-Error "FAIL: SHA-256 mismatch for $Dest. Expected: $ExpectedSha256, Actual: $DownloadedHash"
    exit 1
}

Move-Item -Force -Path $PartFile -Destination $Dest
Write-Host "ok: $Dest verified ($($ExpectedSha256.Substring(0, 16))...)"
