<#
.SYNOPSIS
    Generates an Android Enterprise OOBE QR Provisioning JSON payload with URL-safe Base64 SHA-256 Checksum.
.DESCRIPTION
    Extracts the SHA-256 certificate fingerprint from a signed APK or Keystore,
    converts it into the required URL-safe Base64 format, builds the standardized
    Android Enterprise payload JSON, prints it to the console, and saves it to a file.
.EXAMPLE
    .\scripts\generate_qr_payload.ps1 -DownloadUrl "https://my-server.com/app.apk" -WifiSsid "MyHome" -WifiPassword "Secret123"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$ApkPath = "app\build\outputs\apk\release\app-release.apk",

    [Parameter(Mandatory = $false)]
    [string]$KeystorePath = "app\keystore\testkey.p12",

    [Parameter(Mandatory = $false)]
    [string]$KeystorePassword = "android",

    [Parameter(Mandatory = $false)]
    [string]$DownloadUrl = "https://example.com/downloads/antiporn-release.apk",

    [Parameter(Mandatory = $false)]
    [string]$WifiSsid = "Home_WiFi_Network",

    [Parameter(Mandatory = $false)]
    [string]$WifiPassword = "YourStrongWiFiPassword",

    [Parameter(Mandatory = $false)]
    [ValidateSet("WPA", "WEP", "NONE")]
    [string]$WifiSecurity = "WPA",

    [Parameter(Mandatory = $false)]
    [string]$OutputFile = "provisioning_payload.json"
)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  ISMAH Android Enterprise QR Provisioning Generator      " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$hexDigest = $null

# 1. Attempt extraction from APK if exists
if (Test-Path $ApkPath) {
    Write-Host "[1/3] Extracting certificate SHA-256 digest from APK: $ApkPath" -ForegroundColor Green
    
    # Try apksigner first
    try {
        $apkSignerOutput = & apksigner verify --print-certs $ApkPath
        $match = $apkSignerOutput | Select-String 'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9\s]+)'
        if ($match) {
            $hexDigest = ($match.Matches.Groups[1].Value -replace '\s+', '').Trim()
        }
    } catch {
        Write-Verbose "apksigner not found or failed, attempting keytool fallback..."
    }

    # Fallback to keytool if apksigner did not yield result
    if (-not $hexDigest) {
        try {
            $keytoolOutput = & keytool -printcert -jarfile $ApkPath
            $match = $keytoolOutput | Select-String 'SHA-?256:\s*([a-fA-F0-9:]+)'
            if ($match) {
                $hexDigest = ($match.Matches.Groups[1].Value -replace ':', '' -replace '\s+', '').Trim()
            }
        } catch {
            Write-Verbose "keytool jarfile extraction failed: $($_.Exception.Message)"
        }
    }
}

# 2. Fallback to Keystore if APK extraction did not run or succeed
if (-not $hexDigest) {
    if (Test-Path $KeystorePath) {
        Write-Host "[1/3] Extracting certificate SHA-256 digest from Keystore: $KeystorePath" -ForegroundColor Yellow
        try {
            $keytoolOutput = & keytool -list -v -keystore $KeystorePath -storepass $KeystorePassword
            $match = $keytoolOutput | Select-String 'SHA-?256:\s*([a-fA-F0-9:]+)'
            if ($match) {
                $hexDigest = ($match.Matches.Groups[1].Value -replace ':', '' -replace '\s+', '').Trim()
            }
        } catch {
            Write-Host "Failed to read keystore using keytool: $($_.Exception.Message)" -ForegroundColor Red
        }
    } else {
        Write-Host "Neither APK ($ApkPath) nor Keystore ($KeystorePath) could be found!" -ForegroundColor Red
        return
    }
}

if (-not $hexDigest) {
    Write-Host "Could not extract SHA-256 certificate digest from available artifacts." -ForegroundColor Red
    return
}

Write-Host "   -> Raw SHA-256 Hex: $hexDigest" -ForegroundColor Gray

# 3. Convert Hex digest to URL-safe Base64 without padding (Android Enterprise standard)
$bytes = [byte[]]@($hexDigest -split '(?<=\G.{2})' | Where-Object { $_ } | ForEach-Object { [Convert]::ToByte($_, 16) })
$standardBase64 = [Convert]::ToBase64String($bytes)
$urlSafeChecksum = $standardBase64.Replace('+', '-').Replace('/', '_').TrimEnd('=')

Write-Host "[2/3] URL-Safe Base64 Checksum Calculated:" -ForegroundColor Green
Write-Host "   -> $urlSafeChecksum" -ForegroundColor White -BackgroundColor DarkGreen

# 4. Assemble the Android Enterprise Provisioning Payload
Write-Host "[3/3] Generating Provisioning JSON Payload..." -ForegroundColor Green

$payload = [ordered]@{
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME" = "com.drmohammed.antiporn/.AdminReceiver"
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME" = "com.drmohammed.antiporn"
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION" = $DownloadUrl
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM" = $urlSafeChecksum
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED" = $true
    "android.app.extra.PROVISIONING_LOCALE" = "ar_SA"
    "android.app.extra.PROVISIONING_TIME_ZONE" = "Asia/Riyadh"
    "android.app.extra.PROVISIONING_WIFI_SSID" = $WifiSsid
    "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE" = $WifiSecurity
    "android.app.extra.PROVISIONING_WIFI_PASSWORD" = $WifiPassword
    "android.app.extra.PROVISIONING_WIFI_HIDDEN" = $false
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION" = $false
    "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE" = @{
        "provisioning_source" = "qr_oobe"
    }
}

$jsonText = $payload | ConvertTo-Json -Depth 4

# Save JSON file
$jsonText | Out-File -FilePath $OutputFile -Encoding utf8
Write-Host "`nSuccessfully saved JSON payload to: $OutputFile" -ForegroundColor Cyan

Write-Host "`n------------------- COPY-PASTE FOR QR CODE -------------------" -ForegroundColor Yellow
Write-Host $jsonText -ForegroundColor White
Write-Host "--------------------------------------------------------------" -ForegroundColor Yellow
Write-Host "Next Step: Copy the JSON block above into your QR code generator (e.g. qr-code-generator.com)." -ForegroundColor Green
