param([string]$IdentityUrl='http://localhost:8080',[string]$GatewayUrl='http://localhost:8000')
$ErrorActionPreference='Stop'
if (-not $env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME -or -not $env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD) { throw 'Identity local test credentials required' }
$login=Invoke-RestMethod -Method Post -Uri "$IdentityUrl/api/v1/auth/login" -ContentType 'application/json' -Body (@{identifier=$env:IDENTITY_BOOTSTRAP_ADMIN_USERNAME;password=$env:IDENTITY_BOOTSTRAP_ADMIN_PASSWORD;deviceId=[guid]::NewGuid().ToString();deviceName='operations-smoke'}|ConvertTo-Json)
$headers=@{Authorization="Bearer $($login.accessToken)"}
function New-Key { $headers['Idempotency-Key']=[guid]::NewGuid().ToString() }
function Post-Json($path,$body) {
  try { Invoke-RestMethod -Method Post -Uri "$GatewayUrl$path" -Headers $headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 8) }
  catch { throw "POST $path failed: $($_.Exception.Message)" }
}
function Expect-Status($path,$body,$status) {
  try { Invoke-RestMethod -Method Post -Uri "$GatewayUrl$path" -Headers $headers -ContentType 'application/json' -Body ($body|ConvertTo-Json -Depth 8) | Out-Null; throw "Expected HTTP $status" }
  catch { if ($_.Exception.Response.StatusCode.value__ -ne $status) { throw } }
}
$branch=[guid]::NewGuid().ToString()
New-Key
$ingredient=Post-Json '/api/v1/inventory/ingredients' @{code="SMOKE-$([guid]::NewGuid().ToString('N'))";name='Coffee beans';unit='g'}
New-Key
$receiptBody=@{branchId=$branch;ingredientId=$ingredient.id;quantity=100;referenceId=[guid]::NewGuid().ToString()}
$receipt=Post-Json '/api/v1/inventory/receipts' $receiptBody
$receiptKey=$headers['Idempotency-Key']
$headers['Idempotency-Key']=$receiptKey
$receiptReplay=Post-Json '/api/v1/inventory/receipts' $receiptBody
if ($receiptReplay.id -ne $receipt.id) { throw 'Duplicate stock receipt created another movement' }
New-Key
$deduct=Post-Json '/api/v1/inventory/deductions' @{branchId=$branch;ingredientId=$ingredient.id;quantity=30;referenceId=[guid]::NewGuid().ToString()}
$balance=Invoke-RestMethod -Uri "$GatewayUrl/api/v1/inventory/branches/$branch/ingredients/$($ingredient.id)/balance" -Headers $headers
if ($balance.quantity -ne 70) { throw 'Stock balance mismatch' }
New-Key
Expect-Status '/api/v1/inventory/deductions' @{branchId=$branch;ingredientId=$ingredient.id;quantity=71;referenceId=[guid]::NewGuid().ToString()} 409

New-Key
$supplier=Post-Json '/api/v1/procurement/suppliers' @{code="SUP-$([guid]::NewGuid().ToString('N'))";name='Smoke supplier'}
New-Key
$po=Post-Json '/api/v1/procurement/purchase-orders' @{branchId=$branch;supplierId=$supplier.id;ingredientId=$ingredient.id;quantity=50;unitCostVnd=200}
$headers['If-Match']='0'
$approved=Post-Json "/api/v1/procurement/purchase-orders/$($po.id)/approve" @{}
$headers.Remove('If-Match')
if ($approved.status -ne 'APPROVED') { throw 'Purchase order approval failed' }

New-Key
$customer=Post-Json '/api/v1/loyalty/customers' @{}
New-Key
$credit=Post-Json '/api/v1/loyalty/credits' @{customerId=$customer.id;points=100;referenceId=[guid]::NewGuid().ToString()}
$pointOrder=[guid]::NewGuid().ToString()
$reserved=Post-Json '/api/v1/loyalty/reservations' @{customerId=$customer.id;orderId=$pointOrder;points=80}
Expect-Status '/api/v1/loyalty/reservations' @{customerId=$customer.id;orderId=[guid]::NewGuid().ToString();points=30} 409
$committed=Post-Json "/api/v1/loyalty/reservations/$pointOrder/commit" @{}
$wallet=Invoke-RestMethod -Uri "$GatewayUrl/api/v1/loyalty/customers/$($customer.id)/wallet" -Headers $headers
if ($committed.status -ne 'COMMITTED' -or $wallet.availablePoints -ne 20 -or $wallet.reservedPoints -ne 0) { throw 'Point ledger mismatch' }

New-Key
$voucherCode="V-$([guid]::NewGuid().ToString('N'))"
$voucher=Post-Json '/api/v1/promotions/vouchers' @{branchId=$branch;code=$voucherCode;discountVnd=5000;minTotalVnd=10000;usageLimit=1;expiresAt=[DateTime]::UtcNow.AddDays(1).ToString('o')}
$voucherOrder=[guid]::NewGuid().ToString()
$reservation=Post-Json '/api/v1/promotions/reservations' @{branchId=$branch;orderId=$voucherOrder;code=$voucherCode;totalVnd=18000}
Expect-Status '/api/v1/promotions/reservations' @{branchId=$branch;orderId=[guid]::NewGuid().ToString();code=$voucherCode;totalVnd=18000} 409
$voucherCommitted=Post-Json "/api/v1/promotions/reservations/$voucherOrder/commit" @{}
if ($voucherCommitted.status -ne 'COMMITTED') { throw 'Voucher commit failed' }

$notifyCode="NOTICE-$([guid]::NewGuid().ToString('N'))"
New-Key
$template=Post-Json '/api/v1/notifications/templates' @{code=$notifyCode;body='Order {number} ready'}
$recipient=[guid]::NewGuid().ToString()
New-Key
$queued=Post-Json '/api/v1/notifications' @{branchId=$branch;recipientId=$recipient;referenceId=[guid]::NewGuid().ToString();templateCode=$notifyCode;variables=@{number='42'}}
Start-Sleep -Seconds 3
$inbox=Invoke-RestMethod -Uri "$GatewayUrl/api/v1/notifications/inbox/$recipient" -Headers $headers
if (@($inbox).Count -lt 1 -or @($inbox)[0].rendered_body -ne 'Order 42 ready') { throw 'Notification worker did not deliver inbox message' }

$sku="DELIVERY-$([guid]::NewGuid().ToString('N'))"
New-Key
$product=Post-Json '/api/v1/catalog/products' @{sku=$sku;name='Delivery coffee';category='DRINK';variantCode='M';variantName='Medium'}
New-Key
$price=Invoke-RestMethod -Method Put -Uri "$GatewayUrl/api/v1/catalog/variants/$($product.variantId)/prices" -Headers $headers -ContentType 'application/json' -Body (@{branchId=$branch;channel='DELIVERY';unitPriceVnd=25000}|ConvertTo-Json)
New-Key
$order=Post-Json '/api/v1/orders' @{branchId=$branch;channel='DELIVERY';items=@(@{variantId=$product.variantId;quantity=1})}
if ($order.status -ne 'CONFIRMED' -or $order.paymentStatus -ne 'PENDING_CASH') { throw 'Delivery order state invalid' }
New-Key
$delivery=Post-Json '/api/v1/fulfillment/deliveries' @{orderId=$order.id;branchId=$branch;address='Local smoke test address'}
$headers['If-Match']='0'
$assigned=Post-Json "/api/v1/fulfillment/deliveries/$($delivery.id)/assign" @{driverId=[guid]::NewGuid().ToString()}
$headers.Remove('If-Match')
Expect-Status "/api/v1/fulfillment/deliveries/$($delivery.id)/complete" @{} 409
New-Key
$payment=Post-Json '/api/v1/payments/cash' @{orderId=$order.id;cashReceivedVnd=25000}
$completed=Post-Json "/api/v1/fulfillment/deliveries/$($delivery.id)/complete" @{}
if ($completed.status -ne 'DELIVERED' -or -not $payment.orderConfirmed) { throw 'Delivery did not complete after cash collection' }
Write-Output 'PASS operations smoke: stock, purchase order, points, voucher, inbox, delivery cash handover'
