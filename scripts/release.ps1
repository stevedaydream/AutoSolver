param(
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = "Stop"

function Get-ReleaseNotes {
    $previousEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
        $tags = git tag --merged HEAD --sort=-version:refname
        if ($LASTEXITCODE -ne 0) { throw '無法讀取版本標籤。' }
        $previousTag = $tags | Where-Object { $_ -match '^v\d+(\.\d+)*$' } | Select-Object -First 1
        $range = if ($previousTag) { "$previousTag..HEAD" } else { 'HEAD' }
        $commits = git -c i18n.logOutputEncoding=utf-8 log --reverse '--format=- %s (%h)%n%w(0,2,2)%b' $range
        if ($LASTEXITCODE -ne 0) { throw '無法讀取 commit 更新內容。' }
        $summary = if ($commits) { ($commits -join "`n").TrimEnd() } else { '本次沒有新增 commit。' }
        "## 更新內容`n`n$summary`n"
    } finally {
        [Console]::OutputEncoding = $previousEncoding
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

if ($Version -notmatch '^\d+(\.\d+)*$') {
    Write-Host "[ERROR] Version must look like 1.1 or 1.2.3 (got: $Version)"
    exit 1
}

# 發版前先確認分支與 JDK，避免改完版本才發現環境不符。
$branch = git branch --show-current
if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') {
    Write-Host "[ERROR] 請切換到 main 分支後再發版（目前：$branch）。"
    exit 1
}

$jdkCandidates = @(
    $env:JAVA_HOME,
    'C:\Program Files\Java\jdk-17',
    'C:\Program Files\Android\Android Studio\jbr'
) | Where-Object { $_ } | Select-Object -Unique
$jdk = $jdkCandidates | Where-Object {
    $java = Join-Path $_ 'bin\java.exe'
    $release = Join-Path $_ 'release'
    (Test-Path $java) -and (Test-Path $release) -and
        ((Get-Content $release -Raw) -match '(?m)^JAVA_VERSION="17(?:\.|\")')
} | Select-Object -First 1
if (-not $jdk) {
    Write-Host '[ERROR] 找不到 JDK 17，請將 JAVA_HOME 指向 JDK 17 後重試。'
    exit 1
}
$env:JAVA_HOME = $jdk
Write-Host "使用 JDK 17：$jdk"

# 檢查 GitHub 登入、工作目錄與版本標籤。
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

# 在版本提交之前收集內容；首次發版包含全部歷史。
$releaseNotes = Get-ReleaseNotes

# --- 1. Bump version in app/build.gradle.kts -----------------------
$gradle = Join-Path $repoRoot "app\build.gradle.kts"
$content = Get-Content $gradle -Raw -Encoding UTF8
$m = [regex]::Match($content, 'versionCode = (\d+)')
if (-not $m.Success) { Write-Host "[ERROR] versionCode not found in build.gradle.kts"; exit 1 }
$newCode = [int]$m.Groups[1].Value + 1
$content = $content -replace 'versionCode = \d+', "versionCode = $newCode"
$content = $content -replace 'versionName = "[^"]*"', "versionName = `"$Version`""
[System.IO.File]::WriteAllText($gradle, $content, (New-Object System.Text.UTF8Encoding $false))
Write-Host "[1/5] Bumped version: versionName=$Version versionCode=$newCode"

# 先執行單元測試，再建置發佈 APK。
& .\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --console=plain
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
if ($LASTEXITCODE -ne 0) { Write-Host '[ERROR] 暫存版本變更失敗。'; exit 1 }
git commit -m "chore(release): 發佈 v$Version"
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] git commit failed"; exit 1 }
git tag "v$Version"
if ($LASTEXITCODE -ne 0) { Write-Host '[ERROR] 建立版本標籤失敗。'; exit 1 }
Write-Host "[3/5] Committed and tagged v$Version"

# --- 4. Push --------------------------------------------------------
git push origin main
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] git push failed"; exit 1 }
git push origin "v$Version"
if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] git push tag failed"; exit 1 }
Write-Host "[4/5] Pushed main + tag to origin"

# --- 5. GitHub release (in-app updater reads /releases/latest) ------
$notesFile = [System.IO.Path]::GetTempFileName()
try {
    [System.IO.File]::WriteAllText($notesFile, $releaseNotes, (New-Object System.Text.UTF8Encoding $false))
    gh release create "v$Version" $namedApk --title "AutoDora v$Version" --notes-file $notesFile
    $releaseExitCode = $LASTEXITCODE
} finally {
    Remove-Item -LiteralPath $notesFile -Force
}
if ($releaseExitCode -ne 0) { Write-Host "[ERROR] gh release create failed"; exit 1 }
Write-Host "[5/5] Release v$Version published. In-app updater will now offer it."
