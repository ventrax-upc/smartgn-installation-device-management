$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
python ../smartgn-api-gateway/tools/smoke-local.py
if($LASTEXITCODE -ne 0){throw 'Five-service Gateway acceptance failed'}
