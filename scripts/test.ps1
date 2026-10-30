param([switch]$SkipDatabase)
$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$config=Get-Content tools/service.json -Raw | ConvertFrom-Json
$name='smartgn-verification-'+[Guid]::NewGuid().ToString('N')
$broker=$name+'-mqtt'
$password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$arguments=@('run','--rm','-v',"${PWD}:/workspace",'-v',"${PWD}/.local/m2:/root/.m2",'-w','/workspace')
try{
 if(-not $SkipDatabase){
  docker run --rm -d --name $name -e "POSTGRES_PASSWORD=$password" -e POSTGRES_DB=verification $config.postgres | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Cannot start verification database'}
  for($i=0;$i -lt 60;$i++){docker exec $name pg_isready -U postgres *> $null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Seconds 1}
  if($LASTEXITCODE -ne 0){throw 'Verification database did not become ready'}
  docker run --rm -d --name $broker --network "container:$name" -e "TEST_MQTT_PASSWORD=$password" --entrypoint sh eclipse-mosquitto:2 -c 'mosquitto_passwd -b -c /tmp/passwords support-service "$TEST_MQTT_PASSWORD"; printf "listener 1883\nallow_anonymous false\npassword_file /tmp/passwords\n" > /tmp/config; chmod 644 /tmp/passwords; mosquitto -c /tmp/config' | Out-Null
  for($i=0;$i -lt 20;$i++){docker exec -e "TEST_MQTT_PASSWORD=$password" $broker sh -c 'mosquitto_pub -h localhost -u support-service -P "$TEST_MQTT_PASSWORD" -t verification -m ready' *> $null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Seconds 1}
  if($LASTEXITCODE -ne 0){throw 'Verification broker did not become ready'}
  $arguments+=@('--network',"container:$name",'-e','TEST_DB_URL=jdbc:postgresql://localhost:5432/verification','-e',"TEST_DB_PASSWORD=$password",'-e','SMARTGN_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/verification','-e','SMARTGN_TEST_DATABASE_USERNAME=postgres','-e',"SMARTGN_TEST_DATABASE_PASSWORD=$password",'-e','TEST_MQTT_URL=tcp://localhost:1883','-e',"TEST_MQTT_PASSWORD=$password")
 }
 $arguments+=@('maven:3.9.11-eclipse-temurin-25','mvn','-B','--no-transfer-progress','clean','verify')
 if($SkipDatabase){$arguments+='-Dtest=*,!*Database*,!SecurityTest'}
 & docker @arguments
 if($LASTEXITCODE -ne 0){throw 'Maven verification failed'}
}finally{if(-not $SkipDatabase){docker stop $broker *> $null;docker stop $name *> $null}}
