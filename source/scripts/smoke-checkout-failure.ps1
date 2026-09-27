param([string]$IdentityUrl='http://localhost:8080',[string]$GatewayUrl='http://localhost:8000')
$ErrorActionPreference='Stop'
if(-not $env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME -or -not $env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD){throw 'Set bootstrap admin credentials'}
$login=Invoke-RestMethod -Method Post -Uri "$IdentityUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{identifier=$env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME;password=$env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD;deviceId=[guid]::NewGuid();deviceName='checkout-failure-smoke'}|ConvertTo-Json)
$headers=@{Authorization="Bearer $($login.accessToken)"}
function Post-Idem($uri,$body){$headers['Idempotency-Key']=[guid]::NewGuid().ToString();Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 8)}
$branch=[guid]::NewGuid().ToString()
$a=Post-Idem "$GatewayUrl/api/v1/inventory/ingredients" @{code='FAIL-A-'+[guid]::NewGuid().ToString('N');name='Failure ingredient A';unit='g'}
$b=Post-Idem "$GatewayUrl/api/v1/inventory/ingredients" @{code='FAIL-B-'+[guid]::NewGuid().ToString('N');name='Failure ingredient B';unit='g'}
$ordered=@($a.id,$b.id)|Sort-Object
$funded=$ordered[0];$empty=$ordered[1]
Post-Idem "$GatewayUrl/api/v1/inventory/receipts" @{branchId=$branch;ingredientId=$funded;quantity=100;referenceId=[guid]::NewGuid().ToString()}|Out-Null
$product=Post-Idem "$GatewayUrl/api/v1/catalog/products" @{sku='FAIL-'+[guid]::NewGuid().ToString('N');name='Failure coffee';category='DRINK';variantCode='M';variantName='Medium'}
$headers['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-RestMethod -Method Put -Uri "$GatewayUrl/api/v1/catalog/variants/$($product.variantId)/prices" -Headers $headers -ContentType 'application/json' -Body (@{branchId=$branch;channel='POS';unitPriceVnd=18000}|ConvertTo-Json)|Out-Null
$headers['Idempotency-Key']=[guid]::NewGuid().ToString()
Invoke-RestMethod -Method Put -Uri "$GatewayUrl/api/v1/catalog/variants/$($product.variantId)/recipe" -Headers $headers -ContentType 'application/json' -Body (@{ingredients=@(@{ingredientId=$a.id;quantity=20},@{ingredientId=$b.id;quantity=20})}|ConvertTo-Json -Depth 8)|Out-Null
$order=Post-Idem "$GatewayUrl/api/v1/orders" @{branchId=$branch;channel='POS';items=@(@{variantId=$product.variantId;quantity=2})}
$checkoutKey=[guid]::NewGuid().ToString();$headers['Idempotency-Key']=$checkoutKey
try {
  Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/orders/$($order.id)/checkout-cash" -Headers $headers -ContentType 'application/json' -Body (@{points=0;cashReceivedVnd=40000}|ConvertTo-Json)|Out-Null
  throw 'Checkout unexpectedly succeeded with insufficient stock'
} catch {
  if($_.Exception.Response.StatusCode.value__ -ne 409){throw}
}
$fundedBalance=Invoke-RestMethod -Uri "$GatewayUrl/api/v1/inventory/branches/$branch/ingredients/$funded/balance" -Headers $headers
$emptyBalance=Invoke-RestMethod -Uri "$GatewayUrl/api/v1/inventory/branches/$branch/ingredients/$empty/balance" -Headers $headers
$current=Invoke-RestMethod -Uri "$GatewayUrl/api/v1/orders/$($order.id)" -Headers $headers
if($fundedBalance.quantity -ne 100 -or $emptyBalance.quantity -ne 0){throw 'Partial stock deduction was not compensated'}
if($current.paymentStatus -ne 'PENDING_CASH'){throw 'Failed checkout recorded payment'}
Write-Output "PASS checkout failure compensation: order=$($order.id), restoredStock=100, payment=PENDING_CASH"
