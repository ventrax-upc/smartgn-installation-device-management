param([switch]$SkipBuild, [switch]$DependenciesOnly)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
& "$PSScriptRoot/prepare-local.ps1"
docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop engine is unavailable' }
$composeArguments = @('compose','--env-file','.local/integration.env','-f','compose.integration.yml','up','-d')
if ($DependenciesOnly) { $composeArguments += @('iam-db','installation-db','broker','telemetry','future-services') }
elseif (-not $SkipBuild) { $composeArguments += '--build' }
& docker @composeArguments
if ($LASTEXITCODE -ne 0) { throw 'Local stack startup failed' }
if (-not $DependenciesOnly) {
    foreach ($port in @(8080,8081)) {
        $ready = $false
        for ($i=0; $i -lt 90; $i++) {
            try { if ((Invoke-RestMethod "http://127.0.0.1:$port/actuator/health" -TimeoutSec 3).status -eq 'UP') { $ready=$true; break } } catch { }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "Service on port $port did not become healthy" }
    }
    Write-Output 'IAM: http://localhost:8080/swagger-ui.html'
    Write-Output 'Installation: http://localhost:8081/swagger-ui.html'
}
