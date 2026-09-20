param([string]$IdentityUrl='http://localhost:8080',[string]$GatewayUrl='http://localhost:8000')
$ErrorActionPreference='Stop'
if (-not $env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME -or -not $env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD) { throw 'Identity local test credentials required' }
$login=Invoke-RestMethod -Method Post -Uri "$IdentityUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{identifier=$env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME;password=$env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD;deviceId=[guid]::NewGuid().ToString();deviceName='concurrency-smoke'}|ConvertTo-Json)
$token=$login.accessToken
$headers=@{Authorization="Bearer $token";'Idempotency-Key'=[guid]::NewGuid().ToString()}
$branch=[guid]::NewGuid().ToString()
$ingredient=Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/inventory/ingredients" -Headers $headers -ContentType 'application/json' -Body (@{code="CONCURRENT-$([guid]::NewGuid().ToString('N'))";name='Test ingredient';unit='g'}|ConvertTo-Json)
$headers['Idempotency-Key']=[guid]::NewGuid().ToString()
$null=Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/inventory/receipts" -Headers $headers -ContentType 'application/json' -Body (@{branchId=$branch;ingredientId=$ingredient.id;quantity=1;referenceId=[guid]::NewGuid().ToString()}|ConvertTo-Json)
$jobs=@()
for($i=0;$i -lt 2;$i++) {
  $body=@{branchId=$branch;ingredientId=$ingredient.id;quantity=1;referenceId=[guid]::NewGuid().ToString()}|ConvertTo-Json
  $key=[guid]::NewGuid().ToString()
  $jobs+=Start-Job -ArgumentList "$GatewayUrl/api/v1/inventory/deductions",$token,$key,$body -ScriptBlock {
    param($url,$access,$idem,$json)
    Start-Sleep -Seconds 2
    try { (Invoke-WebRequest -UseBasicParsing -Method Post -Uri $url -Headers @{Authorization="Bearer $access";'Idempotency-Key'=$idem} -ContentType 'application/json' -Body $json).StatusCode }
    catch { $_.Exception.Response.StatusCode.value__ }
  }
}
$null=$jobs|Wait-Job
$codes=@($jobs|Receive-Job)
$jobs|Remove-Job
if (@($codes|Where-Object { $_ -eq 201 }).Count -ne 1 -or @($codes|Where-Object { $_ -eq 409 }).Count -ne 1) { throw "Concurrent deductions did not serialize: $($codes -join ',')" }
$balance=Invoke-RestMethod -Uri "$GatewayUrl/api/v1/inventory/branches/$branch/ingredients/$($ingredient.id)/balance" -Headers $headers
if ($balance.quantity -ne 0) { throw 'Concurrent deduction left wrong stock balance' }
Write-Output 'PASS stock concurrency: one deduction accepted, one rejected, balance zero'
