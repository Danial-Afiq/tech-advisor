param(
    [string]$BaseUrl = 'http://localhost:18087',
    [System.Management.Automation.PSCredential]$AdminCredential
)
$ErrorActionPreference = 'Stop'
if (-not $AdminCredential) {
    $AdminCredential = Get-Credential -Message 'Tech Advisor administrator credentials'
}
$loginBody = @{
    email = $AdminCredential.UserName.Trim()
    password = $AdminCredential.GetNetworkCredential().Password
} | ConvertTo-Json
$login = Invoke-RestMethod "$BaseUrl/api/auth/admin/login" -Method Post -ContentType 'application/json' -Body $loginBody
$loginBody = $null
$headers = @{ Authorization = "$($login.tokenType) $($login.token)" }
$null = Invoke-RestMethod "$BaseUrl/api/admin/ingestion/session" -Headers $headers
$sources = Invoke-RestMethod "$BaseUrl/api/admin/ingestion/sources" -Headers $headers
$source = $sources | Where-Object sourceId -eq 'searchapi-google-product-reviews'
if (-not $source.enabled) { throw 'Enable searchapi-google-product-reviews and restart the backend.' }
if ($source.nextAllowedAt -and [DateTimeOffset]::Parse($source.nextAllowedAt) -gt [DateTimeOffset]::UtcNow) {
    throw "Source cooldown is active until $($source.nextAllowedAt). Retry then."
}
$headers['Idempotency-Key'] = [Guid]::NewGuid().ToString()
$body = @{ sources = @('searchapi-google-product-reviews'); reason = 'Local SearchAPI review smoke test' } | ConvertTo-Json
$run = Invoke-RestMethod "$BaseUrl/api/admin/ingestion/runs" -Method Post -Headers $headers -ContentType 'application/json' -Body $body
$deadline = [DateTimeOffset]::UtcNow.AddMinutes(2)
while ($run.status -in @('ACCEPTED', 'RUNNING')) {
    if ([DateTimeOffset]::UtcNow -gt $deadline) { throw "Polling timed out; inspect run $($run.runId) through the admin API." }
    Start-Sleep -Seconds 2
    $run = Invoke-RestMethod "$BaseUrl/api/admin/ingestion/runs/$($run.runId)" -Headers $headers
}
$run | ConvertTo-Json -Depth 12
if ($run.status -ne 'SUCCESS') { throw "Ingestion finished with status $($run.status); inspect sanitized errors above." }
