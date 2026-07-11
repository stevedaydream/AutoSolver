@echo off
REM ============================================================
REM  AutoDora one-click release
REM  Usage:  release.bat <version>     e.g.  release.bat 1.1
REM
REM  Steps: bump versionName/versionCode -> assembleRelease
REM         -> git commit + tag -> push -> GitHub release + APK
REM  Requires: GitHub CLI (winget install GitHub.cli; gh auth login)
REM ============================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\release.ps1" %*
exit /b %ERRORLEVEL%
