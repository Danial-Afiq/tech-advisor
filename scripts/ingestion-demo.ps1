param(
    [string]$Database = 'techadvisor_ingestion_demo',
    [int]$Port = 18087
)
$ErrorActionPreference = 'Stop'
if (-not $Database.EndsWith('_demo')) { throw 'Use a dedicated database ending in _demo.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { '' }
if (-not $javaPath -or -not (Test-Path -LiteralPath $javaPath)) { throw 'Set JAVA_HOME to the Java 21 JDK directory (the Oracle PATH shim spawns a separate process).' }
$jarPath = Join-Path $projectRoot 'backend\target\backend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build backend with mvnw package first.' }
$names = @('POSTGRES_DB','SPRING_PROFILES_ACTIVE','INGESTION_DEMO_PASSWORD','INGESTION_SCHEDULING_ENABLED','INGESTION_ANCHOR')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$env:POSTGRES_DB = $Database
$env:SPRING_PROFILES_ACTIVE = 'ingestion-demo'
if (-not $env:INGESTION_DEMO_PASSWORD) { $env:INGESTION_DEMO_PASSWORD = [guid]::NewGuid().ToString('N') }
$env:INGESTION_SCHEDULING_ENABLED = 'false'
$env:INGESTION_ANCHOR = '2026-09-17T05:00:00Z'
$basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('demo-admin:' + $env:INGESTION_DEMO_PASSWORD))
$headers = @{ Authorization = 'Basic ' + $basic }
$base = "http://127.0.0.1:$Port"
$backendProcess = $null

function Start-DemoBackend {
    $process = Start-Process -FilePath $javaPath -ArgumentList @('-jar', ('"{0}"' -f $jarPath), "--server.port=$Port", '--logging.level.root=WARN', '--debug=false') `
        -WorkingDirectory (Join-Path $projectRoot 'backend') -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $projectRoot 'backend\target\ingestion-demo-server.log') `
        -RedirectStandardError (Join-Path $projectRoot 'backend\target\ingestion-demo-server-error.log')
    try {
        for ($attempt = 0; $attempt -lt 90; $attempt++) {
            if ($process.HasExited) { throw 'Demo backend exited. Inspect backend/target/ingestion-demo-server*.log.' }
            try {
                $health = Invoke-RestMethod "$base/actuator/health" -TimeoutSec 2
                if ($health.status -eq 'UP') { return $process }
            } catch { }
            Start-Sleep -Milliseconds 500
        }
        throw 'Demo backend startup timed out.'
    } catch { if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }; throw }
}

try {
    # Refuse an occupied port so this script never calls or stops someone else's server.
    $probe = [Net.Sockets.TcpClient]::new()
    try { $probe.Connect('127.0.0.1', $Port); throw "Port $Port is already occupied." }
    catch [Net.Sockets.SocketException] { }
    finally { $probe.Dispose() }
    $backendProcess = Start-DemoBackend
    $identity = Invoke-RestMethod "$base/api/admin/ingestion/session" -Headers $headers -SessionVariable session
    $headers[$identity.csrfHeader] = $identity.csrfToken
    $before = Invoke-RestMethod "$base/api/admin/ingestion/schedule" -Headers $headers -WebSession $session
    $results = @()
    foreach ($sourceSet in @(@('simulated-release'), @('simulated-release','simulated-failure'))) {
        $headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
        $body = @{ sources = @($sourceSet); reason = 'Mid-cycle release demonstration' } | ConvertTo-Json
        $run = Invoke-RestMethod "$base/api/admin/ingestion/runs" -Method Post -Headers $headers -WebSession $session -ContentType 'application/json' -Body $body
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            $run = Invoke-RestMethod "$base/api/admin/ingestion/runs/$($run.runId)" -Headers $headers -WebSession $session
            if ($run.finishedAt) { break }
            Start-Sleep -Milliseconds 250
        }
        if (-not $run.finishedAt) { throw 'Run did not finish.' }
        $results += $run
    }
    if ($results[0].status -ne 'SUCCESS' -or $results[0].processedPayloadCount -ne 3) { throw 'Success demo failed.' }
    if ($results[1].status -ne 'PARTIAL_FAILURE' -or $results[1].errorStackCount -ne 1) { throw 'Failure demo failed.' }
    $after = Invoke-RestMethod "$base/api/admin/ingestion/schedule" -Headers $headers -WebSession $session
    if ($before.nextScheduledAt -ne $after.nextScheduledAt) { throw 'Manual run changed the schedule.' }
    Stop-Process -Id $backendProcess.Id -Force
    $backendProcess.WaitForExit()
    $stillRunning = $true
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try { $null = Invoke-RestMethod "$base/actuator/health" -TimeoutSec 1 }
        catch { $stillRunning = $false; break }
        Start-Sleep -Milliseconds 250
    }
    if ($stillRunning) { throw 'Old backend still answers after stop; restart verification cannot continue.' }
    $backendProcess = Start-DemoBackend
    foreach ($run in $results) {
        $persisted = Invoke-RestMethod "$base/api/admin/ingestion/runs/$($run.runId)" -Headers $headers
        if ($persisted.status -ne $run.status -or $persisted.processedPayloadCount -ne $run.processedPayloadCount) {
            throw 'Persisted result changed after restart.'
        }
    }
    $artifact = @{ description = 'Actual local simulated runs, retrieved again after backend restart';
        database = $Database; restartVerified = $true; scheduleUnchanged = $true; runs = $results }
    $outputDirectory = Join-Path $projectRoot 'docs\examples'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $artifact | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $outputDirectory 'ingestion-demo-results.json') -Encoding UTF8
    $results | Select-Object runId, status, startedAt, finishedAt, processedPayloadCount, errorStackCount | Format-Table
    Write-Output 'Verified persisted results after restart; manual runs preserved the schedule.'
} finally {
    if ($backendProcess -and -not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id -Force }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
