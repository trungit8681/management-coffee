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
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$ingredientBody = @{ code = 'SMOKE-' + ([guid]::NewGuid().ToString('N')); name = 'Smoke coffee beans'; unit = 'g' } | ConvertTo-Json
$ingredient = Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/inventory/ingredients" -Headers $headers -ContentType 'application/json' -Body $ingredientBody
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
$recipeBody = @{ ingredients = @(@{ ingredientId = $ingredient.id; quantity = 20 }) } | ConvertTo-Json -Depth 5
$recipe = Invoke-RestMethod -Method Put -Uri "$GatewayUrl/api/v1/catalog/variants/$($product.variantId)/recipe" -Headers $headers -ContentType 'application/json' -Body $recipeBody
if ($recipe.version -ne 1) { throw 'Recipe version mismatch' }
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$receiptBody = @{ branchId = $branch; ingredientId = $ingredient.id; quantity = 100; referenceId = [guid]::NewGuid().ToString() } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/inventory/receipts" -Headers $headers -ContentType 'application/json' -Body $receiptBody | Out-Null
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
$checkoutKey = [guid]::NewGuid().ToString()
$checkoutBody = @{ points = 0; cashReceivedVnd = 40000 } | ConvertTo-Json
$jobs = 1..6 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($url,$token,$orderId,$key,$body)
        $h = @{ Authorization = $token; 'Idempotency-Key' = $key }
        for($attempt=0;$attempt -lt 20;$attempt++) {
            $result=Invoke-RestMethod -Method Post -Uri "$url/api/v1/orders/$orderId/checkout-cash" -Headers $h -ContentType 'application/json' -Body $body
            if($result.status -eq 'COMPLETED'){return $result}
            Start-Sleep -Milliseconds 200
        }
        throw 'Checkout runner lease did not complete in time'
    } -ArgumentList $GatewayUrl,$headers.Authorization,$order.id,$checkoutKey,$checkoutBody
}
$results = $jobs | Receive-Job -Wait -AutoRemoveJob
if (@($results).Count -ne 6 -or @($results | Select-Object -ExpandProperty paymentId -Unique).Count -ne 1) { throw 'Concurrent checkout was not idempotent' }
$replay = Invoke-RestMethod -Method Post -Uri "$GatewayUrl/api/v1/orders/$($order.id)/checkout-cash" -Headers (@{Authorization=$headers.Authorization;'Idempotency-Key'=$checkoutKey}) -ContentType 'application/json' -Body $checkoutBody
if ($replay.status -ne 'COMPLETED') { throw 'Checkout replay did not return completed saga' }
$confirmed = Invoke-RestMethod -Method Get -Uri "$GatewayUrl/api/v1/orders/$($order.id)" -Headers $headers
if ($confirmed.status -ne 'CONFIRMED' -or $confirmed.paymentStatus -ne 'PAID') { throw 'Order was not confirmed after cash payment' }
$balance = Invoke-RestMethod -Method Get -Uri "$GatewayUrl/api/v1/inventory/branches/$branch/ingredients/$($ingredient.id)/balance" -Headers $headers
if ($balance.quantity -ne 60) { throw "Concurrent checkout deducted stock more than once: $($balance.quantity)" }
Write-Output "PASS checkout saga concurrency: order=$($order.id), payment=$($replay.paymentId), stock=60, six callers=same result"
