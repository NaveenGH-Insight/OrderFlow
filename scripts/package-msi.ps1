param(
    [switch]$SkipFrontend,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

foreach ($tool in @("java", "jpackage", "mvn")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Required tool '$tool' is not on PATH. Install JDK 21 (including jpackage) and Maven first."
    }
}
if (-not $SkipFrontend) {
    if (-not (Get-Command "npm" -ErrorAction SilentlyContinue)) {
        throw "Required tool 'npm' is not on PATH. Install Node.js 20+ first."
    }
    Push-Location (Join-Path $root "frontend")
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed." }
        npm test
        if ($LASTEXITCODE -ne 0) { throw "Frontend type check failed." }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw "Frontend production build failed." }
    } finally {
        Pop-Location
    }
}

Push-Location $root
try {
    if ($SkipTests) { mvn -B -DskipTests package }
    else { mvn -B verify }
    if ($LASTEXITCODE -ne 0) { throw "Maven build or tests failed." }
    if (-not (Test-Path (Join-Path $root "src\main\resources\static\index.html"))) {
        throw "The frontend build is missing. Run npm run build or omit -SkipFrontend."
    }

    $dist = Join-Path $root "dist"
    $input = Join-Path $dist "jpackage-input"
    New-Item -ItemType Directory -Force -Path $input | Out-Null
    Copy-Item (Join-Path $root "target\orderflow.jar") $input
    jpackage `
        --type msi `
        --name OrderFlow `
        --app-version 1.0.2 `
        --vendor "NaveenGH-Insight" `
        --description "Educational simulated order matching and market data terminal" `
        --input $input `
        --main-jar orderflow.jar `
        --main-class org.springframework.boot.loader.launch.JarLauncher `
        --dest $dist `
        --win-menu `
        --win-shortcut `
        --win-dir-chooser `
        --win-per-user-install `
        --win-upgrade-uuid "c613e7a5-21fb-4bdd-9384-90f1c511a684"
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed. Confirm the WiX Toolset version supported by your JDK is installed and on PATH."
    }
    $installer = Join-Path $dist "OrderFlow-1.0.2.msi"
    if (-not (Test-Path $installer)) { throw "jpackage returned success but the expected MSI was not created." }
    Write-Output "Unsigned installer created: $installer"
} finally {
    Pop-Location
}
