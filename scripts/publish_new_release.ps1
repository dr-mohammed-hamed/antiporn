<#
.SYNOPSIS
    All-in-One Automated Release Builder & Cloudflare R2 Publisher for AntiPorn.
.DESCRIPTION
    1. Bumps versionCode and versionName in build.gradle.kts.
    2. Builds production release APK via Gradle.
    3. Signs APK with official v2/v3 Lineage Rotation.
    4. Updates version.json with new metadata.
    5. Uploads and overwrites antiporn-release.apk and version.json on Cloudflare R2.
.EXAMPLE
    .\scripts\publish_new_release.ps1 -NewVersionCode 2 -NewVersionName "1.1" -ReleaseNotes "Bug fixes and performance improvements"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [int]$NewVersionCode,

    [Parameter(Mandatory = $false)]
    [string]$NewVersionName,

    [Parameter(Mandatory = $false)]
    [string]$ReleaseNotes = "Performance improvements and stability fixes"
)

$ErrorActionPreference = "Stop"

$localPropsFile = Join-Path $PSScriptRoot "..\local.properties"
$localProps = @{}
if (Test-Path $localPropsFile) {
    Get-Content $localPropsFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            $localProps[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  AntiPorn - All-in-One Automated Release & Deployer      " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$gradleFile = "app\build.gradle.kts"
$versionJsonFile = "version.json"

# 1. Bump version in build.gradle.kts if provided
if ($NewVersionCode -gt 0 -or -not [string]::IsNullOrWhiteSpace($NewVersionName)) {
    Write-Host "[1/5] Updating version numbers in $gradleFile..." -ForegroundColor Green
    $gradleContent = Get-Content $gradleFile -Raw
    if ($NewVersionCode -gt 0) {
        $gradleContent = $gradleContent -replace 'versionCode\s*=\s*\d+', "versionCode = $NewVersionCode"
        Write-Host "   -> Set versionCode = $NewVersionCode" -ForegroundColor Gray
    }
    if (-not [string]::IsNullOrWhiteSpace($NewVersionName)) {
        $gradleContent = $gradleContent -replace 'versionName\s*=\s*"[^"]+"', "versionName = `"$NewVersionName`""
        Write-Host "   -> Set versionName = `"$NewVersionName`"" -ForegroundColor Gray
    }
    Set-Content -Path $gradleFile -Value $gradleContent -Encoding utf8
}

# 2. Build Release APK with Gradle
Write-Host "[2/5] Compiling and packaging release APK via Gradle..." -ForegroundColor Green
& .\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Host "Gradle build failed!" -ForegroundColor Red
    exit 1
}

# 3. Sign APK with v2/v3 Lineage Rotation
Write-Host "[3/5] Signing APK with official v2/v3 Lineage Rotation..." -ForegroundColor Green
if ([string]::IsNullOrWhiteSpace($env:RELEASE_KEYSTORE_PASSWORD)) {
    if ($localProps.ContainsKey("release.keystore.password")) {
        $env:RELEASE_KEYSTORE_PASSWORD = $localProps["release.keystore.password"]
    }
}
if ([string]::IsNullOrWhiteSpace($env:RELEASE_KEYSTORE_PASSWORD)) {
    $secPass = Read-Host -Prompt "Enter password for antiporn-release.jks" -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secPass)
    $env:RELEASE_KEYSTORE_PASSWORD = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}
& powershell -ExecutionPolicy Bypass -File scripts\sign_rotated_release.ps1
if ($LASTEXITCODE -ne 0) {
    Write-Host "Signing failed!" -ForegroundColor Red
    exit 1
}

$rotatedApk = "app\build\outputs\apk\release\app-release-rotated.apk"
$apkSizeBytes = (Get-Item $rotatedApk).Length
$apkSizeMb = [math]::Round($apkSizeBytes / 1MB, 2)

# 4. Update version.json
Write-Host "[4/5] Updating $versionJsonFile..." -ForegroundColor Green
if (Test-Path $versionJsonFile) {
    $vData = Get-Content $versionJsonFile -Raw | ConvertFrom-Json
    if ($NewVersionCode -gt 0) { $vData.versionCode = $NewVersionCode }
    if (-not [string]::IsNullOrWhiteSpace($NewVersionName)) { $vData.versionName = $NewVersionName }
    $vData.releaseNotes = $ReleaseNotes
    $vData.fileSizeBytes = $apkSizeBytes
    $vData.releaseDate = (Get-Date -Format "yyyy-MM-dd")
    $vData | ConvertTo-Json -Depth 4 | Set-Content -Path $versionJsonFile -Encoding utf8
}

# 5. Upload & Overwrite on Cloudflare R2
Write-Host "[5/5] Deploying and replacing release in Cloudflare R2..." -ForegroundColor Green
& powershell -ExecutionPolicy Bypass -File scripts\deploy_r2_distribution.ps1
if ($LASTEXITCODE -ne 0) {
    Write-Host "Upload to R2 failed!" -ForegroundColor Red
    exit 1
}

$r2BaseUrl = $env:CLOUDFLARE_R2_PUBLIC_URL
if ([string]::IsNullOrWhiteSpace($r2BaseUrl) -and $localProps.ContainsKey("ota.base.url")) {
    $r2BaseUrl = $localProps["ota.base.url"]
}
if ([string]::IsNullOrWhiteSpace($r2BaseUrl)) {
    $r2BaseUrl = "https://your-r2-bucket.r2.dev"
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "  SUCCESS! NEW RELEASE PUBLISHED & DEPLOYED TO R2!        " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "APK Size:    $apkSizeMb MB" -ForegroundColor White
Write-Host "Public Link: $r2BaseUrl/antiporn-release.apk" -ForegroundColor Cyan
Write-Host "OTA Meta:    $r2BaseUrl/version.json" -ForegroundColor Cyan
Write-Host "Note: Existing users will download this update, and the QR code points to it automatically!" -ForegroundColor Yellow
