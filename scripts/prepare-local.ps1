$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if(-not (Test-Path .env)){Copy-Item .env.example .env}
$settings=@{}
foreach($line in Get-Content .env){if($line -match '^([A-Z_]+)=(.*)$'){$settings[$Matches[1]]=$Matches[2]}}
foreach($name in @('DB_PASSWORD','JWT_SECRET','INTERNAL_SERVICE_TOKEN')){if(-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name]) -or $settings[$name].StartsWith('replace-')){$settings[$name]=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))}}
foreach($name in @('DELIVERY_ENCRYPTION_KEY','DEVICE_CREDENTIAL_KEY')){if(-not $settings.ContainsKey($name) -or $settings[$name].StartsWith('replace-')){$settings[$name]=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))}}
$settings.GetEnumerator() | Sort-Object Name | ForEach-Object {"$($_.Name)=$($_.Value)"} | Set-Content .env
Write-Output 'Local configuration prepared without replacing existing secrets. Match service credentials before connecting standalone services.'
