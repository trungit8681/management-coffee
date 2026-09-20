param(
    [string]$IdentityUrl = 'http://localhost:8080',
    [string]$GatewayUrl = 'http://localhost:8000'
)
$ErrorActionPreference = 'Stop'
if (-not $env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME -or -not $env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD) {
    throw 'Set IDENTITY_BOOTSTRAP_ADMIN_USERNAME and IDENTITY_BOOTSTRAP_ADMIN_PASSWORD for local smoke test.'
}
$loginBody = @{ identifier = $env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME; password = $env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD; deviceId = [guid]::NewGuid().ToString(); deviceName = 'local-smoke' } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$IdentityUrl/api/v1/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$branch = [guid]::NewGuid().ToString()
$sku = 'SMOKE-' + ([guid]::NewGuid().ToString('N'))
$productBody = @{ sku = $sku; name = 'Smoke coffee'; category = 'DRINK'; variantCode = 'M'; variantName = 'Medium' } | ConvertTo-Json
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$product = Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/catalog/products" -Headers $headers -ContentType 'application/json' -Body $productBody
if (-not $product.variantId) { throw 'Product creation did not return variantId' }
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$priceBody = @{ branchId = $branch; channel = 'POS'; unitPriceVnd = 18000 } | ConvertTo-Json
$price = Invoke-RestMethod -Method Put -Uri "$GatewayUrl/api/v1/catalog/variants/$($product.variantId)/prices" -Headers $headers -ContentType 'application/json' -Body $priceBody
if ($price.version -ne 1) { throw 'Price version mismatch' }
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$orderBody = @{ branchId = $branch; channel = 'POS'; items = @(@{ variantId = $product.variantId; quantity = 2 }) } | ConvertTo-Json -Depth 5
$order = Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/orders" -Headers $headers -ContentType 'application/json' -Body $orderBody
if ($order.status -ne 'AWAITING_CASH' -or $order.totalVnd -ne 36000) { throw 'Order was not priced and locked' }
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$insufficient = @{ orderId = $order.id; cashReceivedVnd = 35999 } | ConvertTo-Json
try {
    Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/payments/cash" -Headers $headers -ContentType 'application/json' -Body $insufficient | Out-Null
    throw 'Insufficient cash was accepted'
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 400) { throw }
}
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$cashBody = @{ orderId = $order.id; cashReceivedVnd = 40000 } | ConvertTo-Json
$paid = Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/payments/cash" -Headers $headers -ContentType 'application/json' -Body $cashBody
if (-not $paid.orderConfirmed -or $paid.receipt.changeVnd -ne 4000) { throw 'Cash payment or order confirmation failed' }
$replay = Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/payments/cash" -Headers $headers -ContentType 'application/json' -Body $cashBody
if ($replay.receipt.id -ne $paid.receipt.id) { throw 'Duplicate cash command created another receipt' }
$confirmed = Invoke-RestMethod -Method Get -Uri "$GatewayUrl/api/v1/orders/$($order.id)" -Headers $headers
if ($confirmed.status -ne 'CONFIRMED' -or $confirmed.paymentStatus -ne 'PAID') { throw 'Order was not confirmed after cash payment' }
Write-Output "PASS POS cash smoke: order=$($order.id), total=36000, change=4000, replay=same receipt"
