@echo off
chcp 65001 > nul
rem ==============================================================================
rem سكريبت تشغيل النسخ الاحتياطي الشامل لمشروع "حصن" (Hisn / AntiPorn)
rem ==============================================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\create_backup.ps1"
echo.
pause
