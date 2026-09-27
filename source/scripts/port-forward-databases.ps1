param(
  [ValidateSet('all', 'identity', 'organization', 'catalog', 'inventory', 'procurement', 'order', 'payment', 'loyalty', 'promotion', 'fulfillment', 'notification')]
  [string]$Database = 'all'
)

$ErrorActionPreference = 'Stop'

$connections = [ordered]@{
  identity     = @{ Service = 'identity-db';     Port = 15432; Database = 'identity';     User = 'identity_app';     PasswordKey = 'IDENTITY_DB_PASSWORD' }
  organization = @{ Service = 'organization-db'; Port = 15433; Database = 'organization'; User = 'organization_app'; PasswordKey = 'ORGANIZATION_DB_PASSWORD' }
  catalog      = @{ Service = 'catalog-db';      Port = 15434; Database = 'catalog';      User = 'catalog_app';      PasswordKey = 'CATALOG_DB_PASSWORD' }
  inventory    = @{ Service = 'inventory-db';    Port = 15435; Database = 'inventory';    User = 'inventory_app';    PasswordKey = 'INVENTORY_DB_PASSWORD' }
  procurement  = @{ Service = 'procurement-db';  Port = 15436; Database = 'procurement';  User = 'procurement_app';  PasswordKey = 'PROCUREMENT_DB_PASSWORD' }
  order        = @{ Service = 'order-db';        Port = 15437; Database = 'orders';       User = 'order_app';        PasswordKey = 'ORDER_DB_PASSWORD' }
  payment      = @{ Service = 'payment-db';      Port = 15438; Database = 'payment';      User = 'payment_app';      PasswordKey = 'PAYMENT_DB_PASSWORD' }
  loyalty      = @{ Service = 'loyalty-db';      Port = 15439; Database = 'loyalty';      User = 'loyalty_app';      PasswordKey = 'LOYALTY_DB_PASSWORD' }
  promotion    = @{ Service = 'promotion-db';    Port = 15440; Database = 'promotion';    User = 'promotion_app';    PasswordKey = 'PROMOTION_DB_PASSWORD' }
  fulfillment  = @{ Service = 'fulfillment-db';  Port = 15441; Database = 'fulfillment';  User = 'fulfillment_app';  PasswordKey = 'FULFILLMENT_DB_PASSWORD' }
  notification = @{ Service = 'notification-db'; Port = 15442; Database = 'notification'; User = 'notification_app'; PasswordKey = 'NOTIFICATION_DB_PASSWORD' }
}

if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
  throw 'kubectl was not found in PATH.'
}

kubectl get namespace coffee-platform --request-timeout=10s | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw 'Cannot access namespace coffee-platform. Check the docker-desktop context.'
}

$selected = if ($Database -eq 'all') { @($connections.Keys) } else { @($Database) }
$jobs = @()

try {
  foreach ($name in $selected) {
    $config = $connections[$name]
    $arguments = @(
      '-n', 'coffee-platform',
      'port-forward', "service/$($config.Service)",
      "$($config.Port):5432"
    )
    $job = Start-Job -Name "coffee-db-$name" -ScriptBlock {
      param([string[]]$KubectlArguments)
      & kubectl @KubectlArguments
    } -ArgumentList (,$arguments)
    $jobs += $job
  }

  Start-Sleep -Seconds 2
  $failed = @($jobs | Where-Object { $_.State -eq 'Failed' })
  if ($failed.Count -gt 0) {
    $failed | Receive-Job
    throw 'One or more kubectl port-forward jobs failed.'
  }

  Write-Host ''
  Write-Host 'Coffee PostgreSQL connections are available while this terminal remains open:'
  Write-Host ''
  foreach ($name in $selected) {
    $config = $connections[$name]
    Write-Host ("{0,-13} host=localhost port={1} database={2} user={3} password-key={4}" -f `
      $name, $config.Port, $config.Database, $config.User, $config.PasswordKey)
  }
  Write-Host ''
  Write-Host 'Press Ctrl+C to stop all port-forwards.'

  while ($true) {
    Start-Sleep -Seconds 2
    $stopped = @($jobs | Where-Object { $_.State -notin @('Running', 'NotStarted') })
    if ($stopped.Count -gt 0) {
      $stopped | Receive-Job
      throw 'A kubectl port-forward job stopped unexpectedly.'
    }
  }
}
finally {
  $jobs | Stop-Job -ErrorAction SilentlyContinue
  $jobs | Remove-Job -Force -ErrorAction SilentlyContinue
}
