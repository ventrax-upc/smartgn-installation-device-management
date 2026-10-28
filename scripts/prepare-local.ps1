$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$localPath = Join-Path $repoRoot '.local'
New-Item -ItemType Directory -Path $localPath -Force | Out-Null
$envPath = Join-Path $localPath 'integration.env'
function New-Secret { [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
if (-not (Test-Path -LiteralPath $envPath)) {
    $settings = @{
        IAM_DB_PASSWORD = (New-Secret)
        INSTALLATION_DB_PASSWORD = (New-Secret)
        JWT_SECRET = (New-Secret)
        INTERNAL_SERVICE_TOKEN = (New-Secret)
        DELIVERY_ENCRYPTION_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        DEVICE_CREDENTIAL_KEY = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        FIXTURE_ADMIN_TOKEN = (New-Secret)
        NOTIFICATION_SERVICE_TOKEN = (New-Secret)
        BILLING_FIXTURE_TOKEN = (New-Secret)
        EVENT_FIXTURE_TOKEN = (New-Secret)
        EMQX_PASSWORD = (New-Secret)
        EMQX_API_KEY = (New-Secret)
        EMQX_API_SECRET = (New-Secret)
        BOOTSTRAP_SUPERADMIN_EMAIL = 'local-superadmin@example.test'
        BOOTSTRAP_SUPERADMIN_PASSWORD = (New-Secret)
    }
    $settings.GetEnumerator() | Sort-Object Name | ForEach-Object { $_.Name + '=' + $_.Value } | Set-Content -LiteralPath $envPath -Encoding utf8
}
$settings = @{}
Get-Content -LiteralPath $envPath | ForEach-Object { if ($_ -match '^([A-Z_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2] } }
($settings.EMQX_API_KEY + ':' + $settings.EMQX_API_SECRET) | Set-Content -LiteralPath (Join-Path $localPath 'emqx-api-keys') -Encoding ascii
Write-Output 'Local configuration is ready in ignored .local/integration.env; no existing secrets or volumes were replaced.'
