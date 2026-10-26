# Local build WITHOUT touching the system drive.
#
# Why this script exists (two Windows-specific traps):
#
# 1. Path encoding. Gradle launches its test worker with an @argfile whose classpath
#    contains project paths. On Windows those argfiles are written in the platform
#    encoding, and the JVM reads them as UTF-8, so ANY non-ASCII character in the
#    project path makes `:app:testDebugUnitTest` die with
#    "ClassNotFoundException: worker.org.gradle.internal.worker.GradleWorkerMain".
#    Fix: when the repo path is not pure ASCII, build through a directory junction
#    (D:\yg-build\src -> <repo>\android) so the JVM only ever sees ASCII paths.
#    A junction needs no admin rights and copies nothing.
#
# 2. Cache location. Everything (GRADLE_USER_HOME, TMP, TEMP) is redirected into
#    .cache/ or the ASCII work root, so no build artifact lands on the system drive.
#
# PowerShell 5.1 reads .ps1 as ANSI, so this script is intentionally ASCII-only.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File tools\build-local.ps1 -Task ":app:assembleDebug"
#   powershell -ExecutionPolicy Bypass -File tools\build-local.ps1 -Task ":app:testDebugUnitTest"

param(
    [string]$Task = ":app:assembleDebug",
    [string]$WorkRoot = "D:\yg-build",
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $root '.cache'
$gradleHome = Join-Path $cache 'gradle-home'
New-Item -ItemType Directory -Force -Path $gradleHome | Out-Null

# --- 1. seed the Gradle user home (one time) ---
$sourceCaches = Join-Path $env:USERPROFILE '.gradle\caches\modules-2'
$targetCaches = Join-Path $gradleHome 'caches\modules-2'
if (-not (Test-Path -LiteralPath $targetCaches) -and (Test-Path -LiteralPath $sourceCaches)) {
    Write-Host "[build-local] seeding dependency cache (about 1GB, one time)"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $targetCaches) | Out-Null
    robocopy $sourceCaches $targetCaches /E /NFL /NDL /NJH /NJS /NP | Out-Null
}

$sourceDists = Join-Path $env:USERPROFILE '.gradle\wrapper\dists'
$targetDists = Join-Path $gradleHome 'wrapper\dists'
if (-not (Test-Path -LiteralPath $targetDists) -and (Test-Path -LiteralPath $sourceDists)) {
    Write-Host "[build-local] seeding gradle distribution"
    New-Item -ItemType Directory -Force -Path $targetDists | Out-Null
    robocopy $sourceDists $targetDists /E /NFL /NDL /NJH /NJS /NP | Out-Null
}

# --- 2. Android SDK ---
if (-not $env:ANDROID_HOME) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
        (Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'platforms')) {
            $env:ANDROID_HOME = $candidate
            break
        }
    }
}
if (-not $env:ANDROID_HOME) { throw 'Android SDK not found; set ANDROID_HOME' }
Write-Host "[build-local] ANDROID_HOME = $env:ANDROID_HOME"

# --- 3. pick an ASCII path for the JVM ---
$isAscii = ($root -notmatch '[^\x00-\x7F]')
if ($isAscii) {
    $buildDir = Join-Path $root 'android'
    $buildGradleHome = $gradleHome
    $tmpDir = Join-Path $cache 'tmp'
    Write-Host "[build-local] project path is ASCII, building in place"
} else {
    Write-Host "[build-local] project path contains non-ASCII characters"
    Write-Host "[build-local] using ASCII junction root: $WorkRoot (nothing is copied)"
    New-Item -ItemType Directory -Force -Path $WorkRoot | Out-Null

    $ghLink = Join-Path $WorkRoot 'gh'
    $srcLink = Join-Path $WorkRoot 'src'
    if (-not (Test-Path -LiteralPath $ghLink)) {
        cmd /c mklink /J "$ghLink" "$gradleHome" | Out-Null
    }
    if (-not (Test-Path -LiteralPath $srcLink)) {
        cmd /c mklink /J "$srcLink" (Join-Path $root 'android') | Out-Null
    }
    # Stale junction (target moved) -> mklink fails silently, so verify.
    if (-not (Test-Path -LiteralPath (Join-Path $ghLink 'wrapper'))) {
        throw "Cannot create ASCII junction at $ghLink. Remove $WorkRoot and retry."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $srcLink 'settings.gradle.kts'))) {
        throw "Cannot create ASCII junction at $srcLink. Remove $WorkRoot and retry."
    }

    $buildDir = $srcLink
    $buildGradleHome = $ghLink
    $tmpDir = Join-Path $WorkRoot 'tmp'
}

New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$env:GRADLE_USER_HOME = $buildGradleHome
$env:TMP = $tmpDir
$env:TEMP = $tmpDir

# --- 4. run ---
$gradle = Get-ChildItem -Path (Join-Path $buildGradleHome 'wrapper\dists') -Recurse -Filter 'gradle.bat' |
    Select-Object -First 1
if (-not $gradle) { throw "gradle.bat not found under $buildGradleHome" }

Push-Location $buildDir
try {
    $offlineFlag = @()
    if ($Offline) { $offlineFlag = @('--offline') }
    Write-Host "[build-local] GRADLE_USER_HOME = $buildGradleHome"
    Write-Host "[build-local] TMP = $tmpDir"
    Write-Host "[build-local] running: $($gradle.FullName) $offlineFlag $Task"
    & $gradle.FullName @offlineFlag $Task --stacktrace
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
