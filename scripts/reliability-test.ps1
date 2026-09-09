param(
    [string]$Project = 'citypassv4',
    [string]$BaseUrl = 'http://localhost:8081',
    [int]$Users = 100
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Net.Http

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-Db([string]$Sql) {
    return @(& docker compose -p $Project exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -B -e $Sql)
}

function Invoke-Batch([object[]]$Specs) {
    $client = [System.Net.Http.HttpClient]::new()
    $tasks = New-Object System.Collections.ArrayList
    $requests = New-Object System.Collections.ArrayList
    try {
        foreach ($spec in $Specs) {
            $method = [System.Net.Http.HttpMethod]::new([string]$spec.Method)
            $request = [System.Net.Http.HttpRequestMessage]::new($method, [string]$spec.Uri)
            if ($spec.Token) { $null = $request.Headers.TryAddWithoutValidation('authorization', [string]$spec.Token) }
            if ($null -ne $spec.Body) {
                $request.Content = [System.Net.Http.StringContent]::new(
                    ([string]$spec.Body), [Text.Encoding]::UTF8, 'application/json')
            }
            $null = $requests.Add($request)
            $null = $tasks.Add($client.SendAsync($request))
        }
        $results = New-Object System.Collections.ArrayList
        foreach ($task in $tasks) {
            $response = $task.GetAwaiter().GetResult()
            $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $null = $results.Add(($body | ConvertFrom-Json))
        }
        return @($results)
    } finally {
        foreach ($request in $requests) { $request.Dispose() }
        $client.Dispose()
    }
}

function Invoke-InChunks([object[]]$Specs, [int]$Size = 20) {
    $all = New-Object System.Collections.ArrayList
    for ($offset=0; $offset -lt $Specs.Count; $offset += $Size) {
        $last = [Math]::Min($offset + $Size - 1, $Specs.Count - 1)
        foreach ($item in (Invoke-Batch @($Specs[$offset..$last]))) { $null = $all.Add($item) }
    }
    return @($all)
}

function Wait-Until([scriptblock]$Condition, [int]$Seconds, [string]$Failure) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw $Failure
}

function New-LimitedPass([string]$Token, [int]$Stock, [string]$Title) {
    $body = @{
        venueId = 1; title = "$Title-$(Get-Date -Format HHmmssfff)"; subTitle = 'Reliability test'
        rules = 'One active reservation per user'; payValue = 100; actualValue = 100
        type = 1; status = 1; stock = $Stock
        beginTime = '2026-01-01T00:00:00'; endTime = '2099-01-01T00:00:00'
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/passes/limited" -Headers @{authorization=$Token} -ContentType 'application/json' -Body $body
    Assert-True $created.success "Cannot create pass: $($created.errorMsg)"
    $id = [long]$created.data
    Wait-Until { ((& docker compose -p $Project exec -T redis redis-cli GET "reservation:stock:$id").Trim()) -eq [string]$Stock } 20 "Redis stock was not initialized: $id"
    return $id
}

function Reserve-One([string]$Token, [long]$PassId) {
    $accepted = Invoke-RestMethod -Method Post -Uri "$BaseUrl/reservations/$PassId`?acceptWaitlist=true" -Headers @{authorization=$Token}
    Assert-True $accepted.success "Reservation was not accepted: $($accepted.errorMsg)"
    return [long]$accepted.data.requestId
}

function Wait-Request([long]$RequestId, [string[]]$States, [int]$Seconds = 60) {
    Wait-Until {
        $state = (Invoke-Db "SELECT status FROM citypass.tb_reservation_request WHERE request_id=$RequestId;") -join ''
        return $States -contains $state
    } $Seconds "Request $RequestId did not reach $($States -join '/')"
}

# Create 100 independent authenticated users without serial docker calls: issue codes and log in in two HTTP batches.
$phoneSet = New-Object 'System.Collections.Generic.HashSet[string]'
while ($phoneSet.Count -lt $Users) {
    $null = $phoneSet.Add('139' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString())
}
$phones = @($phoneSet)
$codeSpecs = @($phones | ForEach-Object { [pscustomobject]@{Method='POST'; Uri="$BaseUrl/user/code?phone=$_"; Token=$null; Body=$null} })
$codeResults = Invoke-InChunks $codeSpecs 20
Assert-True (($codeResults | Where-Object {-not $_.success}).Count -eq 0) 'A verification-code request failed'
$codeKeys = @($phones | ForEach-Object { "login:code:$_" })
$codes = @(& docker compose -p $Project exec -T redis redis-cli --raw MGET @codeKeys)
Assert-True ($codes.Count -eq $Users) "Expected $Users verification codes, got $($codes.Count)"

$loginSpecs = for ($i=0; $i -lt $Users; $i++) {
    [pscustomobject]@{Method='POST'; Uri="$BaseUrl/user/login"; Token=$null; Body=(@{phone=$phones[$i]; code=$codes[$i]} | ConvertTo-Json -Compress)}
}
$loginResults = Invoke-InChunks $loginSpecs 20
Assert-True (($loginResults | Where-Object {-not $_.success}).Count -eq 0) 'A concurrent login failed'
$tokens = @($loginResults | ForEach-Object { [string]$_.data })

$phoneList = ($phones | ForEach-Object { "'$_'" }) -join ','
$userRows = Invoke-Db "SELECT id,phone FROM citypass.tb_user WHERE phone IN ($phoneList);"
$tokenByUser = @{}
$tokenByPhone = @{}
for ($i=0; $i -lt $Users; $i++) { $tokenByPhone[$phones[$i]] = $tokens[$i] }
foreach ($row in $userRows) {
    $parts = $row -split "`t"
    $tokenByUser[[long]$parts[0]] = $tokenByPhone[$parts[1]]
}

# 100-way reservation: stock 10 must produce exactly 10 active orders and 90 FIFO waiters.
$passId = New-LimitedPass $tokens[0] 10 '100-way stock race'
$reserveSpecs = @($tokens | ForEach-Object { [pscustomobject]@{Method='POST'; Uri="$BaseUrl/reservations/$passId`?acceptWaitlist=true"; Token=$_; Body=$null} })
$accepted = Invoke-Batch $reserveSpecs
Assert-True (($accepted | Where-Object {-not $_.success}).Count -eq 0) 'A concurrent reservation was rejected at ingress'
$requestIds = @($accepted | ForEach-Object { [long]$_.data.requestId })
Wait-Until {
    [int]((Invoke-Db "SELECT COUNT(*) FROM citypass.tb_reservation_request WHERE activity_pass_id=$passId AND status<>'PROCESSING';") -join '') -eq $Users
} 120 'Concurrent reservations did not all reach a terminal ingress state'

$activeOrders = [int]((Invoke-Db "SELECT COUNT(*) FROM citypass.tb_reservation_order WHERE activity_pass_id=$passId AND status IN (1,2,3,5);") -join '')
$waiting = [int]((Invoke-Db "SELECT COUNT(*) FROM citypass.tb_reservation_waitlist WHERE activity_pass_id=$passId AND status='WAITING';") -join '')
$dbStock = [int]((Invoke-Db "SELECT stock FROM citypass.tb_limited_pass_stock WHERE activity_pass_id=$passId;") -join '')
$redisStock = [int]((& docker compose -p $Project exec -T redis redis-cli GET "reservation:stock:$passId").Trim())
Assert-True ($activeOrders -eq 10) "Oversell/undersell: expected 10 active orders, got $activeOrders"
Assert-True ($waiting -eq ($Users - 10)) "Waitlist mismatch: expected $($Users-10), got $waiting"
Assert-True ($dbStock -eq 0 -and $redisStock -eq 0) "Stock mismatch DB/Redis: $dbStock/$redisStock"

# Two releases race on one activity. They must promote two distinct FIFO waiters and never return stock publicly.
$orderRows = Invoke-Db "SELECT id,user_id FROM citypass.tb_reservation_order WHERE activity_pass_id=$passId AND status=1 ORDER BY id LIMIT 2;"
$cancelSpecs = foreach ($row in $orderRows) {
    $parts = $row -split "`t"; [pscustomobject]@{Method='PUT'; Uri="$BaseUrl/reservations/$($parts[0])/cancel"; Token=$tokenByUser[[long]$parts[1]]; Body=$null}
}
$cancelResults = Invoke-Batch @($cancelSpecs)
Assert-True (($cancelResults | Where-Object {-not $_.success}).Count -eq 0) 'A concurrent release failed'
$offered = [int]((Invoke-Db "SELECT COUNT(*) FROM citypass.tb_reservation_waitlist WHERE activity_pass_id=$passId AND status='OFFERED';") -join '')
$dbStockAfterRelease = [int]((Invoke-Db "SELECT stock FROM citypass.tb_limited_pass_stock WHERE activity_pass_id=$passId;") -join '')
Assert-True ($offered -eq 2 -and $dbStockAfterRelease -eq 0) 'Concurrent releases duplicated a waiter or leaked stock'

# Lock the true head. Cancellation must wait instead of skipping it or restoring public stock.
$lockedPass = New-LimitedPass $tokens[20] 1 'Locked FIFO head'
$ownerRequest = Reserve-One $tokens[20] $lockedPass; Wait-Request $ownerRequest @('RESERVED')
$headRequest = Reserve-One $tokens[21] $lockedPass; Wait-Request $headRequest @('WAITLISTED')
$tailRequest = Reserve-One $tokens[22] $lockedPass; Wait-Request $tailRequest @('WAITLISTED')
$lockJob = Start-Job -ScriptBlock {
    param($P,$R)
    & docker compose -p $P exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -e "START TRANSACTION; SELECT id FROM citypass.tb_reservation_waitlist WHERE request_id=$R FOR UPDATE; DO SLEEP(8); COMMIT;"
} -ArgumentList $Project,$headRequest
Wait-Until {
    $activeLock = (Invoke-Db "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_query LIKE 'DO SLEEP(8)%';") -join ''
    return [int]$activeLock -ge 1
} 15 'Test fixture failed to acquire the FIFO-head row lock'
$blockedClient = [System.Net.Http.HttpClient]::new()
$blockedMessage = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Put, "$BaseUrl/reservations/$ownerRequest/cancel")
$null = $blockedMessage.Headers.TryAddWithoutValidation('authorization', $tokens[20])
$blockedCancel = $blockedClient.SendAsync($blockedMessage)
Start-Sleep -Milliseconds 1200
$duringLockStock = [int]((Invoke-Db "SELECT stock FROM citypass.tb_limited_pass_stock WHERE activity_pass_id=$lockedPass;") -join '')
$duringHead = (Invoke-Db "SELECT status FROM citypass.tb_reservation_waitlist WHERE request_id=$headRequest;") -join ''
Assert-True (-not $blockedCancel.IsCompleted) 'Release skipped a locked FIFO head instead of waiting'
Assert-True ($duringLockStock -eq 0 -and $duringHead -eq 'WAITING') 'Stock leaked while FIFO head was locked'
$null = Wait-Job $lockJob -Timeout 15; $null = Receive-Job $lockJob
$blockedBody = $blockedCancel.GetAwaiter().GetResult().Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
$blockedMessage.Dispose(); $blockedClient.Dispose(); Remove-Job $lockJob
Assert-True ($blockedBody.success -and $blockedBody.data -eq 'PROMOTED') 'Locked head was not promoted after lock release'
$headStatus = (Invoke-Db "SELECT status FROM citypass.tb_reservation_waitlist WHERE request_id=$headRequest;") -join ''
$tailStatus = (Invoke-Db "SELECT status FROM citypass.tb_reservation_waitlist WHERE request_id=$tailRequest;") -join ''
Assert-True ($headStatus -eq 'OFFERED' -and $tailStatus -eq 'WAITING') 'Strict FIFO order was violated'

# Outbox recovery: HTTP acceptance must survive a broker outage and complete after the broker returns.
$recoveryPass = New-LimitedPass $tokens[30] 1 'Outbox broker recovery'
& docker compose -p $Project stop broker | Out-Null
$recoveryRequest = Reserve-One $tokens[30] $recoveryPass
$processing = (Invoke-Db "SELECT status FROM citypass.tb_reservation_request WHERE request_id=$recoveryRequest;") -join ''
$createTask = (Invoke-Db "SELECT status FROM citypass.tb_reliable_task WHERE biz_key='create-reservation:$recoveryRequest';") -join ''
Assert-True ($processing -eq 'PROCESSING' -and @('PENDING','RUNNING') -contains $createTask) 'Ingress was not durably accepted while MQ was down'
& docker compose -p $Project start broker | Out-Null
Wait-Request $recoveryRequest @('RESERVED') 90

# Payment at the expiry boundary races with timeout handling; one terminal state and one resource action may win.
$boundaryPass = New-LimitedPass $tokens[31] 1 'Pay timeout boundary'
$boundaryOrder = Reserve-One $tokens[31] $boundaryPass; Wait-Request $boundaryOrder @('RESERVED')
$null = Invoke-Db "UPDATE citypass.tb_reservation_order SET offer_expire_time=DATE_ADD(NOW(),INTERVAL 2 SECOND) WHERE id=$boundaryOrder; UPDATE citypass.tb_reliable_task SET status='PENDING',next_retry_time=DATE_ADD(NOW(),INTERVAL 2 SECOND) WHERE biz_key='order-timeout:$boundaryOrder';"
Start-Sleep -Milliseconds 1800
$boundarySpec = [pscustomobject]@{Method='PUT'; Uri="$BaseUrl/reservations/$boundaryOrder/pay"; Token=$tokens[31]; Body=$null}
$boundaryResult = (Invoke-Batch @($boundarySpec))[0]
Wait-Until {
    $script:boundaryStatus = [int]((Invoke-Db "SELECT status FROM citypass.tb_reservation_order WHERE id=$boundaryOrder;") -join '')
    return $script:boundaryStatus -in @(2,4)
} 30 'Payment/timeout race did not reach one terminal order state'
$terminalTransitions = [int]((Invoke-Db "SELECT COUNT(*) FROM citypass.tb_reservation_request WHERE request_id=$boundaryOrder AND status IN ('PAID','EXPIRED');") -join '')
Assert-True ($terminalTransitions -eq 1) 'Payment/timeout race produced an invalid request terminal state'

# Redis restore is retry-safe even if the process dies after Lua and before marking the task DONE.
$redisContainer = "$Project-redis-1"
& docker cp .\src\main\resources\restore-stock.lua "${redisContainer}:/tmp/restore-stock.lua" | Out-Null
& docker exec $redisContainer redis-cli DEL reservation:restore:777777 | Out-Null
& docker exec $redisContainer redis-cli SET reservation:stock:999999 0 | Out-Null
& docker exec $redisContainer redis-cli SADD reservation:holders:999999 888888 | Out-Null
& docker exec $redisContainer redis-cli SET reservation:claim:999999:888888 777777:0 | Out-Null
$restore1 = (& docker exec $redisContainer redis-cli --eval /tmp/restore-stock.lua reservation:stock:999999 reservation:restore:777777 ',' 999999 888888 777777 0).Trim()
$restore2 = (& docker exec $redisContainer redis-cli --eval /tmp/restore-stock.lua reservation:stock:999999 reservation:restore:777777 ',' 999999 888888 777777 0).Trim()
$restoredStock = (& docker exec $redisContainer redis-cli GET reservation:stock:999999).Trim()
Assert-True ($restore1 -eq '1' -and $restore2 -eq '0' -and $restoredStock -eq '1') "Restore Lua mismatch: first=$restore1 second=$restore2 stock=$restoredStock"

[pscustomobject]@{
    Success = $true
    ConcurrentUsers = $Users
    ActiveOrders = $activeOrders
    WaitingAfterRush = $waiting
    ConcurrentPromotions = $offered
    LockedHead = "$headStatus/$tailStatus"
    OutboxRecovery = 'RESERVED'
    PaymentTimeoutWinner = $(if ($boundaryStatus -eq 2) {'PAY'} else {'TIMEOUT'})
    RetrySafeRestore = "$restore1/$restore2 stock=$restoredStock"
}
