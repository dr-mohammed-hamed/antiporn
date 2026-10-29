<#
.SYNOPSIS
    Automated Cloudflare R2 Distribution Deployer for AntiPorn App.
.DESCRIPTION
    Creates the dedicated R2 bucket, uploads the signed rotated release APK
    and version metadata, and outputs the provisioning endpoints.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$BucketName = "antiporn-releases",

    [Parameter(Mandatory = $false)]
    [string]$ApkSourcePath = "app\build\outputs\apk\release\app-release-rotated.apk",

    [Parameter(Mandatory = $false)]
    [string]$VersionFile = "version.json"
)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  AntiPorn - Cloudflare R2 Deployment Hub                 " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Verify Wrangler authentication
Write-Host "[1/4] Verifying Cloudflare Authentication..." -ForegroundColor Green
try {
    $whoami = wrangler whoami 2>&1
    Write-Host "   -> Logged in to Cloudflare successfully!" -ForegroundColor Gray
} catch {
    Write-Host "   -> Error: Please ensure 'wrangler login' has completed successfully." -ForegroundColor Red
    return
}

# 2. Check or Create R2 Bucket
Write-Host "[2/4] Ensuring R2 Bucket '$BucketName' exists..." -ForegroundColor Green
$buckets = wrangler r2 bucket list 2>&1
if ($buckets -match $BucketName) {
    Write-Host "   -> Bucket '$BucketName' already exists." -ForegroundColor Gray
} else {
    Write-Host "   -> Creating R2 Bucket '$BucketName'..." -ForegroundColor Yellow
    $createResult = wrangler r2 bucket create $BucketName 2>&1
    Write-Host "   -> $createResult" -ForegroundColor Gray
}

# 3. Upload release APK
if (-not (Test-Path $ApkSourcePath)) {
    Write-Host "Error: Release APK not found at $ApkSourcePath" -ForegroundColor Red
    return
}
$apkSizeMb = [math]::Round((Get-Item $ApkSourcePath).Length / 1MB, 2)
Write-Host "[3/4] Uploading $ApkSourcePath ($apkSizeMb MB) to $BucketName/antiporn-release.apk..." -ForegroundColor Green
$uploadApk = wrangler r2 object put "$BucketName/antiporn-release.apk" --file="$ApkSourcePath" --content-type="application/vnd.android.package-archive" --remote --force 2>&1
Write-Host "   -> $uploadApk" -ForegroundColor Gray

# 4. Upload version.json (OTA metadata)
if (Test-Path $VersionFile) {
    Write-Host "[4/4] Uploading $VersionFile to $BucketName/version.json..." -ForegroundColor Green
    $uploadVer = wrangler r2 object put "$BucketName/version.json" --file="$VersionFile" --content-type="application/json" --remote --force 2>&1
    Write-Host "   -> $uploadVer" -ForegroundColor Gray
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "  DEPLOYMENT COMPLETE!                                    " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "Bucket: $BucketName" -ForegroundColor White
Write-Host "APK:    antiporn-release.apk" -ForegroundColor White
Write-Host "OTA:    version.json" -ForegroundColor White
