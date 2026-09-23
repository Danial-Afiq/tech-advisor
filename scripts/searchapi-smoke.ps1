param([string]$BaseUrl = 'http://localhost:18087')
$ErrorActionPreference = 'Stop'
$credential = Get-Credential -UserName 'demo-admin' -Message 'Local ingestion-demo admin password'
$pair = 'demo-admin:' + $credential.GetNetworkCredential().Password
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair)) }
$sessionInfo = Invoke-RestMethod "$BaseUrl/api/admin/ingestion/session" -Headers $headers -SessionVariable webSession
$sources = Invoke-RestMethod "$BaseUrl/api/admin/ingestion/sources" -Headers $headers -WebSession $webSession
$source = $sources | Where-Object sourceId -eq 'searchapi-google-product-reviews'
if (-not $source.enabled) { throw 'Enable searchapi-google-product-reviews and restart the backend.' }
if ($source.nextAllowedAt -and [DateTimeOffset]::Parse($source.nextAllowedAt) -gt [DateTimeOffset]::UtcNow) {
    throw "Source cooldown is active until $($source.nextAllowedAt). Retry then."
}
$headers[$sessionInfo.csrfHeader] = $sessionInfo.csrfToken
$headers['Idempotency-Key'] = [Guid]::NewGuid().ToString()
$body = @{ sources = @('searchapi-google-product-reviews'); reason = 'Local SearchAPI review smoke test' } | ConvertTo-Json
$run = Invoke-RestMethod "$BaseUrl/api/admin/ingestion/runs" -Method Post -Headers $headers -WebSession $webSession -ContentType 'application/json' -Body $body
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(2)
while ($run.status -in @('ACCEPTED', 'RUNNING')) {
    if ([DateTimeOffset]::UtcNow -gt $deadline) { throw "Polling timed out; inspect run $($run.runId) through the admin API." }
    Start-Sleep -Seconds 2
    $run = Invoke-RestMethod "$BaseUrl/api/admin/ingestion/runs/$($run.runId)" -Headers $headers -WebSession $webSession
}
$run | ConvertTo-Json -Depth 12
if ($run.status -ne 'SUCCESS') { throw "Ingestion finished with status $($run.status); inspect sanitized errors above." }
