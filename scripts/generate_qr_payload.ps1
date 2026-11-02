<#
.SYNOPSIS
    Generates an Android Enterprise OOBE QR Provisioning JSON payload with URL-safe Base64 SHA-256 Checksum and QR Code image.
.DESCRIPTION
    Extracts the SHA-256 certificate fingerprint from a signed APK or Keystore,
    converts it into the required URL-safe Base64 format, builds the standardized
    Android Enterprise payload JSON, prints it to the console, saves it to a file,
    and optionally renders a ready-to-scan QR code PNG image.
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
    [string]$WifiSsid = $null,

    [Parameter(Mandatory = $false)]
    [string]$WifiPassword = $null,

    [Parameter(Mandatory = $false)]
    [ValidateSet("WPA", "WEP", "NONE")]
    [string]$WifiSecurity = "WPA",

    [Parameter(Mandatory = $false)]
    [string]$OutputFile = "provisioning_payload.json",

    [Parameter(Mandatory = $false)]
    [string]$QrImageFile = "provisioning_qr.png"
)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  ISMAH Android Enterprise QR Provisioning Generator      " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$hexDigest = $null

# Locate apksigner if available in PATH or Android SDK build-tools
$apksignerExe = $null
if (Get-Command apksigner -ErrorAction SilentlyContinue) {
    $apksignerExe = "apksigner"
} elseif (Test-Path "$env:LOCALAPPDATA\Android\Sdk\build-tools") {
    $found = Get-ChildItem -Path "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Filter apksigner.bat -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) {
        $apksignerExe = $found.FullName
    }
}

# 1. Attempt extraction from APK if exists
if (Test-Path $ApkPath) {
    Write-Host "[1/3] Extracting certificate SHA-256 digest from APK: $ApkPath" -ForegroundColor Green
    
    # Try apksigner first
    if ($apksignerExe) {
        try {
            $apkSignerOutput = & $apksignerExe verify --verbose --print-certs $ApkPath
            $match = $apkSignerOutput | Select-String 'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9\s]+)'
            if ($match) {
                $hexDigest = ($match.Matches.Groups[1].Value -replace '\s+', '').Trim()
                Write-Host "   -> Extracted from APK using apksigner ($apksignerExe)" -ForegroundColor Gray
            }
        } catch {
            Write-Verbose "apksigner failed, attempting keytool fallback..."
        }
    }

    # Fallback to keytool if apksigner did not yield result
    if (-not $hexDigest) {
        try {
            $keytoolOutput = & keytool -printcert -jarfile $ApkPath
            $match = $keytoolOutput | Select-String 'SHA-?256:\s*([a-fA-F0-9:]+)'
            if ($match) {
                $hexDigest = ($match.Matches.Groups[1].Value -replace ':', '' -replace '\s+', '').Trim()
                Write-Host "   -> Extracted from APK using keytool" -ForegroundColor Gray
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
    "android.app.extra.PROVISIONING_LOCALE" = "ar_EG"
    "android.app.extra.PROVISIONING_TIME_ZONE" = "Africa/Cairo"
}

if (-not [string]::IsNullOrWhiteSpace($WifiSsid)) {
    $payload["android.app.extra.PROVISIONING_WIFI_SSID"] = $WifiSsid
    $payload["android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"] = $WifiSecurity
    $payload["android.app.extra.PROVISIONING_WIFI_PASSWORD"] = $WifiPassword
    $payload["android.app.extra.PROVISIONING_WIFI_HIDDEN"] = $false
}

$payload["android.app.extra.PROVISIONING_SKIP_ENCRYPTION"] = $false
$payload["android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"] = @{
    "provisioning_source" = "qr_oobe"
}

$jsonText = $payload | ConvertTo-Json -Depth 4

# Save JSON file
$jsonText | Out-File -FilePath $OutputFile -Encoding utf8
Write-Host "`nSuccessfully saved JSON payload to: $OutputFile" -ForegroundColor Cyan

# Attempt QR code image generation if python is installed
try {
    $pythonCmd = Get-Command python -ErrorAction SilentlyContinue
    if ($pythonCmd) {
        $pyScript = @"
import sys, json, qrcode
with open(r'$OutputFile', 'r', encoding='utf-8-sig') as f:
    data = json.load(f)
compact_json = json.dumps(data, separators=(',', ':'))
qr = qrcode.QRCode(box_size=10, border=2)
qr.add_data(compact_json)
qr.make(fit=True)
img = qr.make_image(fill_color='black', back_color='white')
img.save(r'$QrImageFile')
print('Generated QR image at $QrImageFile')
"@
        & python -c $pyScript
        if (Test-Path $QrImageFile) {
            Write-Host "QR Code image rendered successfully: $QrImageFile" -ForegroundColor Green
        }
    }
} catch {
    Write-Verbose "Python QR code image generation skipped: $($_.Exception.Message)"
}

Write-Host "`n------------------- COPY-PASTE FOR QR CODE -------------------" -ForegroundColor Yellow
Write-Host $jsonText -ForegroundColor White
Write-Host "--------------------------------------------------------------" -ForegroundColor Yellow
Write-Host "Next Step: You can scan $QrImageFile directly or copy the JSON block above into any QR generator." -ForegroundColor Green
