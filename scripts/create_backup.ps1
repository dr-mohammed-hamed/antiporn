# ==============================================================================
# سكريبت النسخ الاحتياطي الذكي والشامل لمشروع "حصن" (Hisn / AntiPorn)
# ==============================================================================
param(
    [string]$OutputDir = "D:\b\backups",
    [string]$ZipPassword = ""
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ProjectRoot = (Get-Item (Join-Path $PSScriptRoot "..")).FullName

# التحقق من وجود أداة 7-Zip في النظام
$7zCmd = $null
if (Get-Command 7z -ErrorAction SilentlyContinue) {
    $7zCmd = "7z"
} elseif (Test-Path "C:\Users\$env:USERNAME\scoop\shims\7z.exe") {
    $7zCmd = "C:\Users\$env:USERNAME\scoop\shims\7z.exe"
} elseif (Test-Path "C:\Program Files\7-Zip\7z.exe") {
    $7zCmd = "C:\Program Files\7-Zip\7z.exe"
}

# قائمة الاستبعاد للملفات والمجلدات المؤقتة
$ExcludedItems = @(
    "build",
    "app\build",
    ".gradle",
    ".kotlin",
    ".idea\caches",
    ".idea\libraries",
    "*.log",
    "*.tmp",
    "*.lock",
    "*.zip"
)

$Timestamp = Get-Date -Format "yyyy-MM-dd_HH-mm"
$ArchiveName = "Hisn_AntiPorn_Backup_$Timestamp.zip"

if (-not (Test-Path -Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$DestinationZip = Join-Path -Path $OutputDir -ChildPath $ArchiveName

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "بدء إنشاء نسخة احتياطية شاملة لمشروع: حصن (Hisn / AntiPorn)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "مسار المشروع: $ProjectRoot" -ForegroundColor Gray
Write-Host "مجلد الحفظ:   $DestinationZip" -ForegroundColor Gray

# التحقق من وجود الأصول الحيوية
Write-Host "`n[*] جاري التحقق من وجود الأصول الحيوية:" -ForegroundColor Yellow

$CheckList = @(
    "app\keystore\antiporn-release.jks",
    "app\keystore\key.properties",
    "app\keystore\signing_lineage.bin",
    "app\keystore\testkey.p12",
    ".agents\rules\KOTLIN_CONSTITUTION.md",
    ".agents\skills",
    ".specify\memory\constitution.md",
    "provisioning_payload.json",
    "provisioning_qr.png",
    "development"
)

foreach ($relPath in $CheckList) {
    $fullPath = Join-Path $ProjectRoot $relPath
    if (Test-Path $fullPath) {
        Write-Host "  [OK] $relPath" -ForegroundColor Green
    } else {
        Write-Host "  [!] تحذير: غير موجود: $relPath" -ForegroundColor Yellow
    }
}

Write-Host "`n[*] جاري الضغط واستبعاد مخلفات البناء والكاش..." -ForegroundColor Yellow
$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

if ($7zCmd) {
    $7zArgs = [System.Collections.Generic.List[string]]::new()
    $7zArgs.Add("a")
    $7zArgs.Add("-tzip")
    $7zArgs.Add("-mx=5")
    $7zArgs.Add($DestinationZip)
    $7zArgs.Add("$ProjectRoot\*")

    foreach ($item in $ExcludedItems) {
        $7zArgs.Add("-xr!$item")
    }

    if ($ZipPassword -and $ZipPassword.Trim() -ne "") {
        $7zArgs.Add("-p$ZipPassword")
        Write-Host "[*] تم تفعيل كلمة سر لتشفير الملف المضغوط." -ForegroundColor Green
    }

    $7zArgs.Add("-bso0")
    $7zArgs.Add("-bse2")

    & $7zCmd $7zArgs
} else {
    Write-Host "[i] لم يتم العثور على 7-Zip، جاري استخدام محرك النظام الأصيل..." -ForegroundColor Cyan
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $TempBackupFolder = Join-Path $env:TEMP "HisnBackup_$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $TempBackupFolder -Force | Out-Null

    try {
        $AllFiles = Get-ChildItem -Path $ProjectRoot -Recurse -Force | Where-Object { -not $_.PSIsContainer }
        foreach ($file in $AllFiles) {
            $rel = $file.FullName.Substring($ProjectRoot.Length + 1)
            $skip = $false
            foreach ($ex in $ExcludedItems) {
                if ($rel -like "$ex*" -or $rel -like "*\$ex*" -or $file.Name -like $ex) {
                    $skip = $true
                    break
                }
            }
            if (-not $skip) {
                $targetFile = Join-Path $TempBackupFolder $rel
                $targetDir = Split-Path -Path $targetFile -Parent
                if (-not (Test-Path $targetDir)) {
                    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
                }
                Copy-Item -Path $file.FullName -Destination $targetFile -Force
            }
        }
        [System.IO.Compression.ZipFile]::CreateFromDirectory($TempBackupFolder, $DestinationZip)
    } finally {
        if (Test-Path $TempBackupFolder) {
            Remove-Item -Path $TempBackupFolder -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

$Stopwatch.Stop()

if (Test-Path $DestinationZip) {
    $FileSizeMB = [math]::Round(((Get-Item $DestinationZip).Length / 1MB), 2)
    $ElapsedSec = [math]::Round($Stopwatch.Elapsed.TotalSeconds, 1)

    Write-Host "`n==========================================================" -ForegroundColor Green
    Write-Host "[+] تم إنشاء النسخة الاحتياطية بنجاح تام!" -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Green
    Write-Host "اسم الملف:     $ArchiveName" -ForegroundColor White
    Write-Host "الحجم الصافي:  $FileSizeMB ميجابايت (MB)" -ForegroundColor White
    Write-Host "الوقت المستغرق: $ElapsedSec ثانية" -ForegroundColor White
    Write-Host "المسار الكامل: $DestinationZip" -ForegroundColor Yellow
    Write-Host "----------------------------------------------------------" -ForegroundColor Gray
    Write-Host "[i] يمكنك الآن نقل هذا الملف مباشرة إلى الهارد الخارجي أو سحابة التخزين." -ForegroundColor Cyan
} else {
    Write-Host "`n[!] حدث خطأ أثناء إنشاء النسخة الاحتياطية." -ForegroundColor Red
    exit 1
}