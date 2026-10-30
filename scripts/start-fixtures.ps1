$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
docker compose --env-file .local/integration.env -f compose.fixtures.yml up -d --build --wait
if($LASTEXITCODE -ne 0){throw 'Fixture stack failed'}
