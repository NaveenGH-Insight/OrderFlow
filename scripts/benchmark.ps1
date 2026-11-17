$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    mvn -B -Dtest=MatchingEngineBenchmarkTest test
    if ($LASTEXITCODE -ne 0) { throw "Matching engine benchmark failed." }
} finally {
    Pop-Location
}
