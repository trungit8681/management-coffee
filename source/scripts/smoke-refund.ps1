param([string]$IdentityUrl='http://localhost:8080',[string]$GatewayUrl='http://localhost:8000')
$ErrorActionPreference='Stop'
if (-not $env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME -or -not $env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD) { throw 'Identity local test credentials required' }
$login=Invoke-RestMethod -Method Post -Uri "$IdentityUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{identifier=$env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME;password=$env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD;deviceId=[guid]::NewGuid().ToString();deviceName='refund-smoke'}|ConvertTo-Json)
$headers=@{Authorization="Bearer $($login.accessToken)"}
function New-Key { $headers['Idempotency-Key']=[guid]::NewGuid().ToString() }
function Post-Json($path,$body) { Invoke-RestMethod -Method Post -Uri "$GatewayUrl$path" -Headers $headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 8) }
$branch=[guid]::NewGuid().ToString()
New-Key
$product=Post-Json '/api/v1/catalog/products' @{sku="REFUND-$([guid]::NewGuid().ToString('N'))";name='Refund coffee';category='DRINK';variantCode='M';variantName='Medium'}
New-Key
$null=Invoke-RestMethod -Method Put -Uri "$GatewayUrl/api/v1/catalog/variants/$($product.variantId)/prices" -Headers $headers -ContentType 'application/json' -Body (@{branchId=$branch;channel='POS';unitPriceVnd=19000}|ConvertTo-Json)
New-Key
$order=Post-Json '/api/v1/orders' @{branchId=$branch;channel='POS';items=@(@{variantId=$product.variantId;quantity=1})}
New-Key
$paid=Post-Json '/api/v1/payments/cash' @{orderId=$order.id;cashReceivedVnd=20000}
if (-not $paid.orderConfirmed) { throw 'Payment confirmation failed' }
$headers['If-Match']='1'
$cancel=Post-Json "/api/v1/orders/$($order.id)/cancel" @{reason='Customer requested cancellation'}
$headers.Remove('If-Match') | Out-Null
if ($cancel.status -ne 'REFUND_PENDING') { throw 'Paid order was not put into refund pending state' }
New-Key
$refundBody=@{orderId=$order.id;reason='Cash returned to customer'}
$refunded=Post-Json '/api/v1/payments/cash/refunds' $refundBody
if (-not $refunded.orderRefunded -or $refunded.refund.amountVnd -ne 19000) { throw 'Cash refund failed' }
$replay=Post-Json '/api/v1/payments/cash/refunds' $refundBody
if ($replay.refund.id -ne $refunded.refund.id) { throw 'Duplicate cash refund created another record' }
$final=Invoke-RestMethod -Uri "$GatewayUrl/api/v1/orders/$($order.id)" -Headers $headers
if ($final.status -ne 'REFUNDED' -or $final.paymentStatus -ne 'REFUNDED') { throw 'Order was not marked refunded' }
Write-Output "PASS cash refund smoke: order=$($order.id), amount=19000, replay=same refund"
