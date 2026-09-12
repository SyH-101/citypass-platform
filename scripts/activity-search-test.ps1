param(
    [string]$Project = 'citypasssearch',
    [string]$BaseUrl = 'http://localhost:8081',
    [string]$EsUrl = 'http://localhost:9200',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$adminToken = 'citypass-search-test-admin'
$cursorSecret = 'citypass-search-test-cursor-secret'
$env:SEARCH_ENABLED = 'true'
$env:SEARCH_CURSOR_SECRET = $cursorSecret
$env:RELIABLE_TASK_ADMIN_TOKEN = $adminToken
$env:SEARCH_PIT_KEEP_ALIVE_SECONDS = '3'

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Wait-Until([scriptblock]$Condition, [int]$Seconds, [string]$Failure) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try { if (& $Condition) { return } } catch { }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw $Failure
}

function Invoke-Db([string]$Sql) {
    $output = @(& docker compose -p $Project exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -B -e $Sql)
    if ($LASTEXITCODE -ne 0) { throw "MySQL command failed: $Sql" }
    return ($output -join "`n").Trim()
}

function Login-TestUser {
    $phone = '137' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
    $sent = Invoke-RestMethod -Method Post -Uri "$BaseUrl/user/code?phone=$phone"
    Assert-True $sent.success 'Cannot request verification code'
    $code = (& docker compose -p $Project exec -T redis redis-cli GET "login:code:$phone").Trim()
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/user/login" -ContentType 'application/json' `
        -Body ([Text.Encoding]::UTF8.GetBytes((@{phone=$phone;code=$code} | ConvertTo-Json -Compress)))
    Assert-True $login.success "Cannot login: $($login.errorMsg)"
    return [string]$login.data
}

function New-Activity([string]$Token, [long]$VenueId, [string]$Title, [string]$Description,
                      [string]$Category, [long]$Price, [string]$Start, [string]$End, [string]$Tags) {
    $body = @{
        venueId=$VenueId; title=$Title; subTitle='CityPass search fixture'; rules='预约资格以交易链实时校验为准'
        description=$Description; activityCategory=$Category; tags=$Tags
        eventStartTime=$Start; eventEndTime=$End; payValue=$Price; actualValue=$Price
    } | ConvertTo-Json
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/passes" -Headers @{authorization=$Token} `
        -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    Assert-True $response.success "Cannot create activity: $($response.errorMsg)"
    return [long]$response.data
}

function Search([string]$Query) {
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl/search/activities?$Query"
}

function Wait-SearchTitle([long]$Id, [string]$Title, [int]$Seconds = 60) {
    $escaped = [uri]::EscapeDataString($Title)
    Wait-Until {
        $result = Search "keyword=$escaped&size=10"
        $result.success -and @($result.data.items | Where-Object activityId -eq $Id).Count -eq 1
    } $Seconds "Search index did not converge for activity $Id"
}

if ($SkipBuild) {
    & docker compose -p $Project --profile search up -d --no-build mysql redis namesrv broker elasticsearch app openresty
} else {
    & docker compose -p $Project --profile search up -d --build mysql redis namesrv broker elasticsearch app openresty
}
if ($LASTEXITCODE -ne 0) { throw 'Cannot start the search integration stack' }
Wait-Until { (Invoke-RestMethod -Uri "$BaseUrl/actuator/health").status -eq 'UP' } 180 'Application did not become healthy'
Wait-Until { (Invoke-RestMethod -Uri "$EsUrl").version.number -eq '7.17.29' } 180 'Elasticsearch 7.17.29 did not become ready'

$token = Login-TestUser
$ids = [ordered]@{}
$ids.titleMatch = New-Activity $token 2 '城市音乐节限定专场' '青年乐队与独立音乐现场' 'MUSIC' 1200 '2098-05-01T19:00:00' '2098-05-01T22:00:00' '音乐,现场,青年'
$ids.descriptionMatch = New-Activity $token 2 '周末青年开放日' '包含城市音乐节主题分享和幕后交流' 'MUSIC' 800 '2098-05-02T14:00:00' '2098-05-02T17:00:00' '分享,音乐'
$ids.sport = New-Activity $token 3 '城市夜跑挑战赛' '五公里夜跑与完赛互动' 'SPORT' 300 '2098-05-01T18:00:00' '2098-05-01T21:00:00' '运动,夜跑'
$ids.family = New-Activity $token 4 '亲子自然观察营' '面向家庭的昆虫与植物观察' 'FAMILY' 2600 '2098-05-03T09:00:00' '2098-05-03T16:00:00' '亲子,自然'
$ids.sameTimeA = New-Activity $token 1 '青年艺术导览 A' '当代艺术展览导览' 'EXHIBITION' 500 '2098-05-04T10:00:00' '2098-05-04T12:00:00' '艺术,展览'
$ids.sameTimeB = New-Activity $token 1 '青年艺术导览 B' '当代艺术展览导览' 'EXHIBITION' 500 '2098-05-04T10:00:00' '2098-05-04T12:00:00' '艺术,展览'

$rebuild = Invoke-RestMethod -Method Post -Uri "$BaseUrl/internal/activity-search/rebuild" -Headers @{'X-Admin-Token'=$adminToken}
Assert-True ($rebuild.success -and $rebuild.data.status -eq 'SUCCEEDED') "Rebuild failed: $($rebuild.errorMsg)"

# Force the outbox INSERT to fail and verify the activity update/version are rolled back with it.
$beforeRollback = Invoke-Db "SELECT CONCAT(title,'|',search_version) FROM citypass.tb_activity_pass WHERE id=$($ids.titleMatch);"
$beforeTaskCount = [long](Invoke-Db "SELECT COUNT(*) FROM citypass.tb_reliable_task WHERE biz_key LIKE 'index-activity-search:$($ids.titleMatch):%';")
$null = Invoke-Db "DROP TRIGGER IF EXISTS citypass.test_fail_search_task;"
$null = Invoke-Db "CREATE TRIGGER citypass.test_fail_search_task BEFORE INSERT ON citypass.tb_reliable_task FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced search outbox failure';"
try {
    $rollbackBody = @{title='事务不应提交';subTitle='rollback';description='rollback';activityCategory='MUSIC';tags='rollback';eventStartTime='2098-05-01T19:00:00';eventEndTime='2098-05-01T22:00:00'} | ConvertTo-Json
    $rollbackResponse = Invoke-RestMethod -Method Put -Uri "$BaseUrl/passes/$($ids.titleMatch)/search-metadata" -Headers @{authorization=$token} -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($rollbackBody))
    Assert-True (-not $rollbackResponse.success) 'Forced outbox failure unexpectedly returned success'
} finally {
    $null = Invoke-Db "DROP TRIGGER IF EXISTS citypass.test_fail_search_task;"
}
$afterRollback = Invoke-Db "SELECT CONCAT(title,'|',search_version) FROM citypass.tb_activity_pass WHERE id=$($ids.titleMatch);"
$afterTaskCount = [long](Invoke-Db "SELECT COUNT(*) FROM citypass.tb_reliable_task WHERE biz_key LIKE 'index-activity-search:$($ids.titleMatch):%';")
Assert-True ($afterRollback -eq $beforeRollback -and $afterTaskCount -eq $beforeTaskCount) 'Business rollback left changed activity facts or an executable search task'

# A malformed geo source forces a real bulk item failure. The old alias must remain intact,
# and a later clean rebuild must still succeed before the failed physical index is removed explicitly.
$aliasBeforeFailure = (Invoke-RestMethod -Uri "$EsUrl/_alias/citypass-activity-search").PSObject.Properties.Name
$null = Invoke-Db 'UPDATE citypass.tb_venue SET y=100 WHERE id=4;'
try {
    $failedRebuild = Invoke-RestMethod -Method Post -Uri "$BaseUrl/internal/activity-search/rebuild" -Headers @{'X-Admin-Token'=$adminToken}
    Assert-True (-not $failedRebuild.success) 'Malformed geo source unexpectedly produced a successful rebuild'
    $failedState = Invoke-RestMethod -Method Get -Uri "$BaseUrl/internal/activity-search/rebuild" -Headers @{'X-Admin-Token'=$adminToken}
    $aliasAfterFailure = (Invoke-RestMethod -Uri "$EsUrl/_alias/citypass-activity-search").PSObject.Properties.Name
    Assert-True ($failedState.success -and $failedState.data.status -eq 'FAILED' -and $aliasAfterFailure -eq $aliasBeforeFailure) 'Failed rebuild replaced the working alias'
    $failedIndex = [string]$failedState.data.targetIndex
} finally {
    $null = Invoke-Db 'UPDATE citypass.tb_venue SET y=30.167500 WHERE id=4;'
}
$recoveredRebuild = Invoke-RestMethod -Method Post -Uri "$BaseUrl/internal/activity-search/rebuild" -Headers @{'X-Admin-Token'=$adminToken}
Assert-True ($recoveredRebuild.success -and $recoveredRebuild.data.status -eq 'SUCCEEDED') 'Clean rebuild did not recover after a failed attempt'
$removed = Invoke-RestMethod -Method Delete -Uri "$BaseUrl/internal/activity-search/indices/$failedIndex" -Headers @{'X-Admin-Token'=$adminToken}
Assert-True $removed.success 'Failed inactive index could not be removed explicitly'

$analyze = Invoke-RestMethod -Method Post -Uri "$EsUrl/citypass-activity-search/_analyze" -ContentType 'application/json' `
    -Body ([Text.Encoding]::UTF8.GetBytes((@{analyzer='smartcn';text='城市音乐节'} | ConvertTo-Json -Compress)))
Assert-True (@($analyze.tokens).Count -ge 2) 'analysis-smartcn was not installed or did not tokenize Chinese text'

$keyword = Search ('keyword=' + [uri]::EscapeDataString('城市音乐节') + '&size=10')
Assert-True ($keyword.success -and $keyword.data.items[0].activityId -eq $ids.titleMatch) 'Title boost did not rank title match first'
Assert-True (@($keyword.data.items | Where-Object activityId -eq $ids.descriptionMatch).Count -eq 1) 'Description match was not found'
$noResult = Search ('keyword=' + [uri]::EscapeDataString('量子潜水艇') + '&size=10')
Assert-True ($noResult.success -and @($noResult.data.items).Count -eq 0) 'No-result query returned unrelated data'

$combined = Search 'category=MUSIC&eventFrom=2098-05-01T22%3A00%3A00&eventTo=2098-05-02T14%3A00%3A00&minPriceCents=500&maxPriceCents=1500&longitude=120.1915&latitude=30.2362&radiusMeters=2000&sort=DISTANCE&size=10'
Assert-True ($combined.success -and @($combined.data.items).Count -eq 2) 'Category/price/intersecting-time/distance filters are incorrect'

$invalidCoordinates = Search 'sort=DISTANCE&size=10'
Assert-True (-not $invalidCoordinates.success) 'Distance sort accepted missing coordinates'

# Stable PIT + search_after traversal, including equal event times and activityId tie-breaker.
$firstPage = Search 'sort=EVENT_TIME&size=2'
Assert-True ($firstPage.success -and $firstPage.data.hasMore) 'First cursor page is missing'
$seen = New-Object 'System.Collections.Generic.HashSet[long]'
foreach ($item in @($firstPage.data.items)) { $null = $seen.Add([long]$item.activityId) }
$page = $firstPage
while ($page.data.hasMore) {
    $cursor = [uri]::EscapeDataString([string]$page.data.nextCursor)
    $page = Search "sort=EVENT_TIME&size=2&cursor=$cursor"
    Assert-True $page.success "Cursor page failed: $($page.errorMsg)"
    foreach ($item in @($page.data.items)) { Assert-True ($seen.Add([long]$item.activityId)) 'Cursor pagination returned a duplicate' }
}
Assert-True ($seen.Count -eq $ids.Count) "Cursor pagination lost results: expected $($ids.Count), got $($seen.Count)"

$expiryPage = Search 'sort=EVENT_TIME&size=1'
Start-Sleep -Seconds 5
$expired = Search ("sort=EVENT_TIME&size=1&cursor=" + [uri]::EscapeDataString([string]$expiryPage.data.nextCursor))
Assert-True ((-not $expired.success) -and (-not [string]::IsNullOrWhiteSpace([string]$expired.errorMsg))) 'Expired PIT did not return a clear cursor error'

$freshPage = Search 'sort=EVENT_TIME&size=1'
$signed = [string]$freshPage.data.nextCursor
$tampered = $signed.Substring(0, 4) + ($(if ($signed[4] -eq 'A') {'B'} else {'A'})) + $signed.Substring(5)
$invalidCursor = Search ("sort=EVENT_TIME&size=1&cursor=" + [uri]::EscapeDataString($tampered))
Assert-True ((-not $invalidCursor.success) -and (-not [string]::IsNullOrWhiteSpace([string]$invalidCursor.errorMsg))) 'Tampered cursor was accepted'

# A PIT can still contain an old listed document; the API batch-checks current MySQL status and supplements forward.
$ordered = Search 'category=EXHIBITION&sort=EVENT_TIME&size=20'
$downlistId = [long]$ordered.data.items[1].activityId
$downlistPage = Search 'category=EXHIBITION&sort=EVENT_TIME&size=1'
$downlisted = Invoke-RestMethod -Method Put -Uri "$BaseUrl/passes/$downlistId/status?status=2" -Headers @{authorization=$token}
Assert-True $downlisted.success 'Cannot downlist activity'
$afterDownlist = Search ("category=EXHIBITION&sort=EVENT_TIME&size=1&cursor=" + [uri]::EscapeDataString([string]$downlistPage.data.nextCursor))
Assert-True (@($afterDownlist.data.items | Where-Object activityId -eq $downlistId).Count -eq 0) 'Current MySQL status filter returned a downlisted activity'
Wait-Until {
    $indexed = Invoke-RestMethod -Uri "$EsUrl/citypass-activity-search/_doc/$downlistId"
    $indexed._source.searchable -eq $false -and [long]$indexed._version -ge 2
} 30 'Soft downlist did not reach Elasticsearch'

# Deterministic stale write: an older external version must be rejected and cannot resurrect the document.
$doc = Invoke-RestMethod -Uri "$EsUrl/citypass-activity-search/_doc/$downlistId"
$currentVersion = [long]$doc._version
$staleStatus = 0
try {
    $staleResponse = Invoke-WebRequest -Method Put -Uri "$EsUrl/citypass-activity-search/_doc/$downlistId`?version=$($currentVersion-1)&version_type=external_gte" `
        -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes('{"title":"迟到旧任务复活"}'))
    $staleStatus = [int]$staleResponse.StatusCode
} catch {
    if ($_.Exception.Response) { $staleStatus = [int]$_.Exception.Response.StatusCode }
}
Assert-True ($staleStatus -eq 409) 'Older external version unexpectedly overwrote the search document'
$unchanged = Invoke-RestMethod -Uri "$EsUrl/citypass-activity-search/_doc/$downlistId"
Assert-True (([long]$unchanged._version -eq $currentVersion) -and ($unchanged._source.searchable -eq $false)) 'Stale write resurrected a downlisted activity'

# Maintenance window blocks relevant writes. This emulates the flag held by the synchronous rebuild service.
$null = Invoke-Db "UPDATE citypass.tb_search_rebuild_state SET write_blocked=1 WHERE id=1;"
$blockedBody = @{title='不应写入';activityCategory='MUSIC';eventStartTime='2098-06-01T10:00:00';eventEndTime='2098-06-01T12:00:00'} | ConvertTo-Json
$blocked = Invoke-RestMethod -Method Put -Uri "$BaseUrl/passes/$($ids.titleMatch)/search-metadata" -Headers @{authorization=$token} -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($blockedBody))
Assert-True ((-not $blocked.success) -and (-not [string]::IsNullOrWhiteSpace([string]$blocked.errorMsg))) 'Search-facing write was not blocked by maintenance state'
$null = Invoke-Db "UPDATE citypass.tb_search_rebuild_state SET write_blocked=0 WHERE id=1;"

# Venue denormalization advances every related activity document version.
$venueName = 'Starport Music Space Search Sync'
$venueUpdated = Invoke-RestMethod -Method Put -Uri "$BaseUrl/venues" -Headers @{authorization=$token} -ContentType 'application/json' `
    -Body ([Text.Encoding]::UTF8.GetBytes((@{id=2;name=$venueName} | ConvertTo-Json -Compress)))
Assert-True $venueUpdated.success 'Venue update failed'
Wait-Until {
    $result = Search ('keyword=' + [uri]::EscapeDataString('城市音乐节') + '&size=10')
    @($result.data.items | Where-Object { $_.activityId -eq $ids.titleMatch -and $_.venueName -eq $venueName }).Count -eq 1
} 60 'Venue denormalized fields did not converge'

# Elasticsearch outage does not roll back MySQL; the durable task remains and converges after recovery.
& docker compose -p $Project stop elasticsearch | Out-Null
$recoveredTitle = '城市音乐节恢复后新标题'
$metadata = @{title=$recoveredTitle;subTitle='恢复验证';description='搜索服务中断期间提交';activityCategory='MUSIC';tags='音乐,恢复';eventStartTime='2098-05-01T19:00:00';eventEndTime='2098-05-01T22:00:00'} | ConvertTo-Json
$updatedWhileDown = Invoke-RestMethod -Method Put -Uri "$BaseUrl/passes/$($ids.titleMatch)/search-metadata" -Headers @{authorization=$token} -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($metadata))
Assert-True $updatedWhileDown.success 'MySQL business write failed while Elasticsearch was unavailable'
$taskState = Invoke-Db "SELECT status FROM citypass.tb_reliable_task WHERE biz_key LIKE 'index-activity-search:$($ids.titleMatch):%' ORDER BY id DESC LIMIT 1;"
Assert-True ($taskState -in @('PENDING','RUNNING')) "Index task was not retained during outage: $taskState"
& docker compose -p $Project start elasticsearch | Out-Null
Wait-Until { (Invoke-RestMethod -Uri "$EsUrl").version.number -eq '7.17.29' } 120 'Elasticsearch did not recover'
Wait-SearchTitle $ids.titleMatch $recoveredTitle 120

$dbLike = Invoke-Db "SELECT COUNT(*) FROM citypass.tb_activity_pass WHERE title LIKE CONCAT('%',CONVERT(0xE59F8EE5B882E99FB3E4B990E88A82 USING utf8mb4) COLLATE utf8mb4_general_ci,'%') OR description LIKE CONCAT('%',CONVERT(0xE59F8EE5B882E99FB3E4B990E88A82 USING utf8mb4) COLLATE utf8mb4_general_ci,'%');"
$physical = (Invoke-RestMethod -Uri "$EsUrl/_alias/citypass-activity-search").PSObject.Properties.Name
$esCount = (Invoke-RestMethod -Uri "$EsUrl/citypass-activity-search/_count").count
$dbCount = [long](Invoke-Db 'SELECT COUNT(*) FROM citypass.tb_activity_pass;')
Assert-True ([long]$esCount -eq $dbCount) 'Rebuild or duplicate tasks produced the wrong document count'

$report = [ordered]@{
    Success=$true
    ElasticsearchVersion='7.17.29'
    SmartCnTokens=@($analyze.tokens | ForEach-Object { $_.token })
    SearchableFixtures=$ids.Count
    TitleBoostFirstId=[long]$keyword.data.items[0].activityId
    CombinedFilterCount=@($combined.data.items).Count
    CursorUniqueCount=$seen.Count
    PitExpiry='PASS'
    TamperGuard='PASS'
    MySqlDownlistFilter='PASS'
    ExternalVersionStaleWrite='HTTP 409'
    VenueFanOut='PASS'
    OutageRecovery='PASS'
    RebuildFailureGuard='PASS'
    RebuildSourceCount=$rebuild.data.sourceCount
    AliasTarget=$physical
    DbLikeMatchCount=[long]$dbLike
    ElasticsearchKeywordMatchCount=@($keyword.data.items).Count
    Note='本结果是小样本功能与故障验收，不是生产性能压测。'
}
New-Item -ItemType Directory -Force -Path 'target' | Out-Null
$report | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 'target/search-quality-result.json'
$report
