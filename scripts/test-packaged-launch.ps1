param(
    [Parameter(Mandatory = $true)]
    [string]$ApplicationDirectory
)

$ErrorActionPreference = "Stop"
$directory = (Resolve-Path $ApplicationDirectory).Path
$executable = Join-Path $directory "OrderFlow.exe"
if (-not (Test-Path $executable)) { throw "Packaged OrderFlow.exe not found in $directory" }
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw "Port 8080 is already in use; close the existing app before the packaged smoke check."
}

$first = $null
$second = $null
try {
    $first = Start-Process -FilePath $executable -WorkingDirectory $directory -PassThru
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:8080/api/health" -TimeoutSec 1
            if ($health.status -eq "UP" -and $health.application -eq "OrderFlow") {
                $ready = $true
                break
            }
        } catch [System.Net.WebException] {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $ready) { throw "Packaged launcher did not reach OrderFlow health readiness within 30 seconds." }
    $listener = Get-NetTCPConnection -LocalPort 8080 -State Listen
    $serverProcess = Get-Process -Id $listener.OwningProcess
    if ($serverProcess.Path -ne $executable) {
        throw "The health responder is not owned by the packaged executable under test."
    }
    $serverPid = $serverProcess.Id
    $page = Invoke-WebRequest "http://127.0.0.1:8080/" -UseBasicParsing -TimeoutSec 5
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch 'id="root"') {
        throw "Packaged frontend was not served."
    }
    $snapshot = Invoke-RestMethod "http://127.0.0.1:8080/api/market/snapshot" -TimeoutSec 5
    if ($snapshot.bestBidTicks -le 0 -or $snapshot.bestAskTicks -le $snapshot.bestBidTicks) {
        throw "The packaged engine did not provide seeded market depth."
    }

    $second = Start-Process -FilePath $executable -WorkingDirectory $directory -PassThru
    if (-not $second.WaitForExit(20000)) { throw "Second launcher did not reuse the existing instance and exit." }
    $second.Refresh()
    if ($second.ExitCode -ne 0) { throw "Second launcher exited with error $($second.ExitCode)." }
    $listenerAfter = Get-NetTCPConnection -LocalPort 8080 -State Listen
    if ($listenerAfter.OwningProcess -ne $serverPid) { throw "Second launch replaced the original backend." }
    Write-Output "PASS: packaged EXE served health, frontend, and seeded depth; second launch exited 0 and reused backend PID $serverPid."
} finally {
    if ($first) {
        try {
            Invoke-WebRequest "http://127.0.0.1:8080/api/shutdown" -Method POST `
                -Headers @{ "X-OrderFlow-Action" = "quit" } -UseBasicParsing -TimeoutSec 5 | Out-Null
        } catch {
            Write-Warning "Could not request shutdown: $($_.Exception.Message)"
        }
        if (-not $first.WaitForExit(15000)) {
            Stop-Process -Id $first.Id -ErrorAction SilentlyContinue
            Write-Warning "Packaged launcher did not exit after shutdown."
        }
    }
    if ($second -and -not $second.HasExited) { Stop-Process -Id $second.Id -ErrorAction SilentlyContinue }
}
