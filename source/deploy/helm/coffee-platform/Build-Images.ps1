param(
  [Parameter(Mandatory=$true)][string]$Registry,
  [string]$Tag = 'local',
  [switch]$Push
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$services=@('identity','organization','catalog','inventory','procurement','order','payment','loyalty','promotion','fulfillment','notification')
foreach($name in $services){
  $image="$Registry/$name-service`:$Tag"
  docker build --pull -t $image (Join-Path $root "services/$name-service")
  if($LASTEXITCODE -ne 0){throw "Build failed: $name-service"}
  if($Push){docker push $image;if($LASTEXITCODE -ne 0){throw "Push failed: $image"}}
}
$relay="$Registry/outbox-relay`:$Tag"
docker build --pull -t $relay (Join-Path $root 'infrastructure/outbox-relay')
if($LASTEXITCODE -ne 0){throw 'Build failed: outbox-relay'}
if($Push){docker push $relay;if($LASTEXITCODE -ne 0){throw "Push failed: $relay"}}
Write-Output "PASS images: 11 services + outbox-relay, tag=$Tag, pushed=$Push"

