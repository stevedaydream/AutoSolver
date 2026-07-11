param(
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if ($Version -notmatch '^\d+(\.\d+)*$') {
    Write-Host "[ERROR] Version must look like 1.1 or 1.2.3 (got: $Version)"
    exit 1
}

# --- Preconditions -------------------------------------------------
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host "[ERROR] GitHub CLI (gh) not found."
    Write-Host "        Install:  winget install GitHub.cli"
    Write-Host "        Then:     gh auth login"
    exit 1
}
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] gh is not authenticated. Run: gh auth login"
    exit 1
}
if (git status --porcelain) {
    Write-Host "[ERROR] Working tree is not clean. Commit or stash first."
    exit 1
}
if (git tag -l "v$Version") {
    Write-Host "[ERROR] Tag v$Version already exists."
    exit 1
}

# --- 1. Bump version in app/build.gradle.kts -----------------------
$gradle = Join-Path $repoRoot "app\build.gradle.kts"
$content = Get-Content $gradle -Raw
$m = [regex]::Match($content, 'versionCode = (\d+)')
if (-not $m.Success) { Write-Host "[ERROR] versionCode not found in build.gradle.kts"; exit 1 }
$newCode = [int]$m.Groups[1].Value + 1
$content = $content -replace 'versionCode = \d+', "versionCode = $newCode"
$content = $content -replace 'versionName = "[^"]*"', "versionName = `"$Version`""
[System.IO.File]::WriteAllText($gradle, $content, (New-Object System.Text.UTF8Encoding $false))
Write-Host "[1/5] Bumped version: versionName=$Version versionCode=$newCode"

# --- 2. Build release APK (AGP needs JDK 17; use Android Studio JBR) ---
$jbr = "C:\Program Files\Android\Android Studio\jbr"
if (Test-Path "$jbr\bin\java.exe") { $env:JAVA_HOME = $jbr }
& .\gradlew.bat :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Build failed. Version bump was NOT committed;"
    Write-Host "        run 'git checkout app/build.gradle.kts' to undo it."
    exit 1
}
$apk = Join-Path $repoRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) {
    Write-Host "[ERROR] APK not found: $apk"
    exit 1
}
$namedApk = Join-Path $repoRoot "app\build\outputs\apk\release\AutoDora-v$Version.apk"
Copy-Item $apk $namedApk -Force
Write-Host "[2/5] Built $namedApk"

# --- 3. Commit + tag ------------------------------------------------
git add app/build.gradle.kts
git commit -m "release: v$Version"
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] git commit failed"; exit 1 }
git tag "v$Version"
Write-Host "[3/5] Committed and tagged v$Version"

# --- 4. Push --------------------------------------------------------
git push origin main
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] git push failed"; exit 1 }
git push origin "v$Version"
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] git push tag failed"; exit 1 }
Write-Host "[4/5] Pushed main + tag to origin"

# --- 5. GitHub release (in-app updater reads /releases/latest) ------
gh release create "v$Version" $namedApk --title "AutoDora v$Version" --generate-notes
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] gh release create failed"; exit 1 }
Write-Host "[5/5] Release v$Version published. In-app updater will now offer it."
