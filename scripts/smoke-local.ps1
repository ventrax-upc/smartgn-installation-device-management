$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
python ../smartgn-support-maintenance/tools/smoke-local.py ../smartgn-payments-billing/.env
if($LASTEXITCODE -ne 0){throw 'Integrated verification failed'}
