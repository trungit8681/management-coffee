[CmdletBinding()]
param(
    [switch]$Strict
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$failures = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Add-Failure([string]$Message) { $failures.Add($Message) }
function Add-Warning([string]$Message) { $warnings.Add($Message) }

$requiredPaths = @(
    'AGENTS.md',
    'document/BangKeHoach.md',
    'document/usecase/00-use-case-tong-quan.puml',
    'document/sequence/01-dang-nhap-phan-quyen.puml',
    'document/sequence/06-pos-tao-don-thanh-toan.puml',
    '.agents/skills/coffee-backend/SKILL.md',
    '.codex/rules/project.rules'
)

foreach ($relativePath in $requiredPaths) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectRoot $relativePath))) {
        Add-Failure "Missing required project artifact: $relativePath"
    }
}

$plantUmlFiles = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'document') -Filter '*.puml' -Recurse -File
foreach ($file in $plantUmlFiles) {
    $content = Get-Content -Raw -LiteralPath $file.FullName -Encoding UTF8
    if (-not $content.TrimStart().StartsWith('@startuml') -or -not $content.TrimEnd().EndsWith('@enduml')) {
        Add-Failure "Invalid PlantUML wrapper: $($file.FullName.Substring($projectRoot.Length + 1))"
    }

    $blocks = ([regex]::Matches($content, '(?m)^\s*(alt|opt|loop|group|par|break)\b')).Count
    $ends = ([regex]::Matches($content, '(?m)^\s*end\s*$')).Count
    if ($blocks -ne $ends) {
        Add-Failure "Unbalanced PlantUML blocks: $($file.FullName.Substring($projectRoot.Length + 1)) (blocks=$blocks, ends=$ends)"
    }
}

$currentScopeFiles = @(
    'document/usecase/00-use-case-tong-quan.puml',
    'document/usecase/04-ban-hang-don-hang.puml',
    'document/sequence/03-cham-cong-mo-dong-ca.puml',
    'document/sequence/06-pos-tao-don-thanh-toan.puml',
    'document/sequence/08-dat-mon-online-giao-hang.puml',
    'document/sequence/09-huy-don-hoan-tien.puml'
)
$forbiddenCurrentFlow = '(Authorize/Capture|PENDING_PAYMENT|actor\s+"Cổng thanh toán"|participant\s+"Cổng thanh toán")'
foreach ($relativePath in $currentScopeFiles) {
    $fullPath = Join-Path $projectRoot $relativePath
    if (Test-Path -LiteralPath $fullPath) {
        $matches = Select-String -LiteralPath $fullPath -Pattern $forbiddenCurrentFlow -Encoding UTF8
        foreach ($match in $matches) {
            Add-Failure "Future electronic payment leaked into current flow: ${relativePath}:$($match.LineNumber)"
        }
    }
}

$sourceRoot = Join-Path $projectRoot 'source'
if (Test-Path -LiteralPath $sourceRoot) {
    $sourceFiles = Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '[\\/](node_modules|target|build|dist|\.git)[\\/]' }

    $secretPattern = '(?i)(password|secret|api[_-]?key|private[_-]?key|access[_-]?token)\s*[:=]\s*["''][^"'']{8,}["'']'
    foreach ($file in $sourceFiles) {
        if ($file.Length -gt 2MB) { continue }
        $matches = Select-String -LiteralPath $file.FullName -Pattern $secretPattern -Encoding UTF8 -ErrorAction SilentlyContinue
        foreach ($match in $matches) {
            Add-Failure "Possible committed secret: $($file.FullName.Substring($projectRoot.Length + 1)):$($match.LineNumber)"
        }
    }

    $dockerFiles = $sourceFiles | Where-Object { $_.Name -eq 'Dockerfile' }
    foreach ($file in $dockerFiles) {
        $content = Get-Content -Raw -LiteralPath $file.FullName -Encoding UTF8
        if ($content -notmatch '(?im)^\s*USER\s+\S+') {
            Add-Warning "Dockerfile has no explicit non-root USER: $($file.FullName.Substring($projectRoot.Length + 1))"
        }
        if ($content -notmatch '(?im)^\s*HEALTHCHECK\b') {
            Add-Warning "Dockerfile has no HEALTHCHECK (an orchestrator probe may be used instead): $($file.FullName.Substring($projectRoot.Length + 1))"
        }
    }
}

foreach ($warning in $warnings) { Write-Warning $warning }
foreach ($failure in $failures) { Write-Error $failure -ErrorAction Continue }

Write-Host ("Audit summary: {0} PlantUML files, {1} warning(s), {2} failure(s)." -f $plantUmlFiles.Count, $warnings.Count, $failures.Count)

if ($failures.Count -gt 0 -or ($Strict -and $warnings.Count -gt 0)) { exit 1 }
exit 0

