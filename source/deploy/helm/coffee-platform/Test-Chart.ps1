param([string]$HelmImage='alpine/helm:3.17.3',[string]$KubeconformImage='ghcr.io/yannh/kubeconform:v0.6.7')
$ErrorActionPreference='Stop'
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$chart='source/deploy/helm/coffee-platform'
docker run --rm -v "${repo}:/workspace" -w /workspace $HelmImage lint $chart --strict
if($LASTEXITCODE -ne 0){throw 'helm lint failed'}
docker run --rm -v "${repo}:/workspace" -w /workspace $HelmImage template coffee $chart |
  docker run --rm -i $KubeconformImage -strict -summary
if($LASTEXITCODE -ne 0){throw 'kubeconform failed'}
Write-Output 'PASS Helm chart lint and Kubernetes schema validation.'

