$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
python tools/export-openapi.py
if($LASTEXITCODE -ne 0){throw 'OpenAPI export failed'}
