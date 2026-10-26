# AntiPorn - Official Rotated Release Signer
# Signs the release APK using the official custom key and links it to the legacy AOSP testkey via Signature Scheme v3 Lineage.

$buildToolsDir = (Get-ChildItem -Path "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Filter apksigner.bat -Recurse | Select-Object -First 1).DirectoryName
$apksigner = Join-Path $buildToolsDir "apksigner.bat"

$projectRoot = Split-Path -Parent $PSScriptRoot
$oldKeystore = Join-Path $projectRoot "app\keystore\testkey.p12"
$newKeystore = Join-Path $projectRoot "app\keystore\antiporn-release.jks"
$lineageFile = Join-Path $projectRoot "app\keystore\signing_lineage.bin"
$inputApk    = Join-Path $projectRoot "app\build\outputs\apk\release\app-release.apk"
$outputApk   = Join-Path $projectRoot "app\build\outputs\apk\release\app-release-rotated.apk"

$newKeyPass = $env:RELEASE_KEYSTORE_PASSWORD
if ([string]::IsNullOrWhiteSpace($newKeyPass)) {
    $secPass = Read-Host -Prompt "Enter password for antiporn-release.jks" -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secPass)
    $newKeyPass = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
}

if (-not (Test-Path $lineageFile)) {
    Write-Host "[1/3] Generating Signing Certificate Lineage proof..." -ForegroundColor Cyan
    & $apksigner rotate `
        --out $lineageFile `
        --old-signer --ks $oldKeystore --ks-key-alias "androiddebugkey" --ks-pass "pass:android" --key-pass "pass:android" `
        --new-signer --ks $newKeystore --ks-key-alias "antiporn" --ks-pass "pass:$newKeyPass" --key-pass "pass:$newKeyPass"
}

Write-Host "[2/3] Signing APK with v2/v3 Lineage Rotation..." -ForegroundColor Cyan
& $apksigner sign `
    --ks $oldKeystore --ks-key-alias "androiddebugkey" --ks-pass "pass:android" --key-pass "pass:android" `
    --next-signer `
    --ks $newKeystore --ks-key-alias "antiporn" --ks-pass "pass:$newKeyPass" --key-pass "pass:$newKeyPass" `
    --lineage $lineageFile `
    --out $outputApk `
    $inputApk

Write-Host "[3/3] Verifying APK Signature..." -ForegroundColor Cyan
& $apksigner verify --verbose --print-certs $outputApk

Write-Host "`nSUCCESS! Rotated APK generated at: $outputApk" -ForegroundColor Green
