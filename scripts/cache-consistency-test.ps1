param(
    [string]$Project = 'citypassv4',
    [string]$PrimaryUrl = 'http://localhost:8081',
    [string]$SecondaryUrl = 'http://localhost:8082'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Wait-Until([scriptblock]$Condition, [int]$Seconds, [string]$Failure) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try { if (& $Condition) { return } } catch {}
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw $Failure
}

$phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
$sent = Invoke-RestMethod -Method Post -Uri "$PrimaryUrl/user/code?phone=$phone"
Assert-True $sent.success 'Cannot issue verification code'
$code = (& docker compose -p $Project exec -T redis redis-cli GET "login:code:$phone").Trim()
$login = Invoke-RestMethod -Method Post -Uri "$PrimaryUrl/user/login" -ContentType 'application/json' -Body (@{phone=$phone;code=$code} | ConvertTo-Json)
Assert-True $login.success 'Cannot log in cache test user'
$token = [string]$login.data

$secondary = "$Project-app2"
$existingSecondary = (& docker ps -aq -f "name=^${secondary}$") -join ''
if ($existingSecondary) { & docker rm -f $secondary | Out-Null }
& docker run -d --name $secondary --network "${Project}_default" -p 8082:8081 `
    -e 'MYSQL_URL=jdbc:mysql://mysql:3306/citypass?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf8' `
    -e MYSQL_USERNAME=root -e MYSQL_PASSWORD=123456 -e REDIS_HOST=redis `
    -e ROCKETMQ_ENABLED=true -e ROCKETMQ_NAME_SERVER=namesrv:9876 `
    -e GATEWAY_CACHE_PURGE_URL=http://openresty/internal/cache/venue `
    -e JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai -e TZ=Asia/Shanghai "${Project}-app" | Out-Null

try {
    Wait-Until {
        (Invoke-RestMethod -Method Get -Uri "$SecondaryUrl/actuator/health").status -eq 'UP'
    } 45 'Second application instance did not become healthy'

    $before1 = Invoke-RestMethod -Method Get -Uri "$PrimaryUrl/venues/1"
    $before2 = Invoke-RestMethod -Method Get -Uri "$SecondaryUrl/venues/1"
    Assert-True ($before1.success -and $before2.success) 'Cannot warm both Caffeine instances'
    $oldName = [string]$before1.data.name
    $newName = "Versioned venue $(Get-Date -Format HHmmssfff)"

    $updated = Invoke-RestMethod -Method Put -Uri "$PrimaryUrl/venues" -Headers @{authorization=$token} `
        -ContentType 'application/json' -Body (@{id=1;name=$newName} | ConvertTo-Json)
    Assert-True $updated.success "Venue update failed: $($updated.errorMsg)"

    Wait-Until {
        $script:after1 = Invoke-RestMethod -Method Get -Uri "$PrimaryUrl/venues/1"
        $script:after2 = Invoke-RestMethod -Method Get -Uri "$SecondaryUrl/venues/1"
        $after1.data.name -eq $newName -and $after2.data.name -eq $newName
    } 30 'Redis pub/sub did not invalidate both JVM local caches'

    $dbVersion = (& docker compose -p $Project exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -B -e 'SELECT cache_version FROM citypass.tb_venue WHERE id=1;').Trim()
    $redisVersion = (& docker compose -p $Project exec -T redis redis-cli GET 'cache:venue:version:1').Trim()
    Assert-True ($dbVersion -eq $redisVersion) "Cache version watermark mismatch: DB=$dbVersion Redis=$redisVersion"

    # Simulate an old async reader trying to populate data after a newer invalidation watermark.
    $redisContainer = "$Project-redis-1"
    & docker cp .\src\main\resources\write-cache-if-version.lua "${redisContainer}:/tmp/write-cache-if-version.lua" | Out-Null
    & docker exec $redisContainer redis-cli DEL cache:venue:stale-test | Out-Null
    & docker exec $redisContainer redis-cli SET cache:venue:version:stale-test 5 | Out-Null
    $staleWrite = (& docker exec $redisContainer redis-cli --eval /tmp/write-cache-if-version.lua cache:venue:stale-test cache:venue:version:stale-test ',' 4 '{"data":{"name":"old"}}' 60).Trim()
    $staleExists = (& docker exec $redisContainer redis-cli EXISTS cache:venue:stale-test).Trim()
    Assert-True ($staleWrite -eq '0' -and $staleExists -eq '0') 'An old read was allowed to overwrite a newer cache version'

    [pscustomobject]@{
        Success = $true
        OldName = $oldName
        NewName = $newName
        DatabaseAndRedisVersion = "$dbVersion / $redisVersion"
        BothJvmCachesRefreshed = $true
        StaleWriteRejected = $true
    }
} finally {
    $existingSecondary = (& docker ps -aq -f "name=^${secondary}$") -join ''
    if ($existingSecondary) { & docker rm -f $secondary | Out-Null }
}
