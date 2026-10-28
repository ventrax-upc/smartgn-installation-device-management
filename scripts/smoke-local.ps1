$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
docker run --rm --network smartgn-local_default -v "${repoRoot}/tools:/tools:ro" -v "${repoRoot}/.local:/config:ro" python:3.13-slim python /tools/smoke_upgrade.py
if ($LASTEXITCODE -ne 0) { throw 'Local integration acceptance failed' }
