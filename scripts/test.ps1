param([switch]$SkipDatabase)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
New-Item -ItemType Directory -Path '.local/m2' -Force | Out-Null
$container = 'smartgn-verification-' + [Guid]::NewGuid().ToString('N')
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$dockerArguments = @('run', '--rm', '-v', "${repoRoot}:/workspace", '-v', "${repoRoot}/.local/m2:/root/.m2", '-w', '/workspace')
try {
    if (-not $SkipDatabase) {
        docker run --rm -d --name $container -e "POSTGRES_PASSWORD=$password" -e POSTGRES_DB=verification postgres:17.6 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Cannot start isolated verification database' }
        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            docker exec $container pg_isready -U postgres -d verification *> $null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw 'Isolated PostgreSQL did not become ready' }
        $dockerArguments += @('--network', "container:$container", '-e', 'SMARTGN_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/verification', '-e', 'SMARTGN_TEST_DATABASE_USERNAME=postgres', '-e', "SMARTGN_TEST_DATABASE_PASSWORD=$password")
    }
    $dockerArguments += @('maven:3.9.11-eclipse-temurin-25', 'mvn', '-B', '--no-transfer-progress', 'clean', 'verify')
    & docker @dockerArguments
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed' }
} finally {
    if (-not $SkipDatabase -and $container -match '^smartgn-verification-[a-f0-9]{32}$') { docker stop $container *> $null }
}
