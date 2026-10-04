param(
    [string]$Project = 'citypassfiles',
    [string]$BaseUrl = 'http://localhost:18080',
    [switch]$Faults
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Drawing
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(25)
$run = [guid]::NewGuid().ToString('N')
$proof = [System.Collections.Generic.List[string]]::new()
$fixtureDir = Join-Path $PSScriptRoot '../data/story-file-it'
$null = New-Item -ItemType Directory -Force -Path $fixtureDir

function Assert([bool]$condition, [string]$message) { if (-not $condition) { throw $message } }
function Pass([string]$name) { $proof.Add($name); Write-Host "PASS $name" }
function Db([string]$sql) {
    if ($sql -match '^CREATE TRIGGER') { $sql="DELIMITER //`n$sql //`nDELIMITER ;" }
    $out = @($sql | & docker compose -p $Project exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -B citypass)
    if ($LASTEXITCODE -ne 0) { throw 'Fixture database operation failed' }
    return ($out -join "`n").Trim()
}
function Redis([string[]]$arguments) {
    return ((& docker compose -p $Project exec -T redis redis-cli @arguments) -join "`n").Trim()
}
function Http([string]$method, [string]$uri, [string]$token='', $body=$null, [byte[]]$bytes=$null, [string]$key='') {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method),$uri)
    if ($token) { $null = $request.Headers.TryAddWithoutValidation('authorization',$token) }
    if ($key) { $null = $request.Headers.TryAddWithoutValidation('Idempotency-Key',$key) }
    if ($null -ne $bytes) {
        $request.Content = [System.Net.Http.ByteArrayContent]::new($bytes)
        $request.Content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
    } elseif ($null -ne $body) {
        $request.Content = [System.Net.Http.StringContent]::new(($body | ConvertTo-Json -Depth 12 -Compress),[Text.Encoding]::UTF8,'application/json')
    }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $json = $null
        try { $json = [Text.Encoding]::UTF8.GetString($raw) | ConvertFrom-Json } catch {}
        return @{ code=[int]$response.StatusCode; json=$json; bytes=$raw }
    } catch { throw "HTTP $method failed (URL and credentials omitted)" }
    finally { $request.Dispose() }
}
function Api([string]$method,[string]$path,[string]$token='', $body=$null,[string]$key='') {
    return Http $method "$BaseUrl$path" $token $body $null $key
}
function Ok($result,[string]$what) {
    Assert ($result.code -eq 200 -and $result.json.success) "$what failed: HTTP $($result.code) $($result.json.errorMsg)"
    return $result.json.data
}
function Login {
    $phone = '139' + (Get-Random -Minimum 10000000 -Maximum 99999999)
    $null = Ok (Api POST "/user/code?phone=$phone") 'send code'
    $code = Redis @('GET',"login:code:$phone")
    $token = [string](Ok (Api POST '/user/login' '' @{phone=$phone;code=$code}) 'login')
    $me = Ok (Api GET '/user/me' $token) 'me'
    return @{ token=$token; id=[long]$me.id }
}
function Draft($user,[string]$name='fixture') {
    return Ok (Api POST '/stories/drafts' $user.token @{title="$name-$run";content='A real city activity experience.'} ([guid]::NewGuid().ToString('N'))) 'create draft'
}
function Reserve($story,$user) { return Ok (Api POST "/stories/$($story.id)/attachments" $user.token) 'reserve attachment' }
function Confirm($a,$user) { return Api POST "/stories/attachments/$($a.id)/confirm" $user.token }
function Edit($story,$user,[long[]]$ids,[string]$title='City walk') {
    return Api PUT "/stories/$($story.id)" $user.token @{version=$story.version;title=$title;content='A real city activity experience.';attachmentIds=@($ids)}
}
function Wait([scriptblock]$condition,[string]$what,[int]$seconds=80) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do { try { if (& $condition) { return } } catch {}; Start-Sleep -Milliseconds 300 } while ((Get-Date) -lt $deadline)
    throw "Timeout: $what"
}
function Hash([byte[]]$bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($bytes)) } finally { $sha.Dispose() }
}
function Png([Drawing.Color]$color,[string]$name) {
    $bitmap = [Drawing.Bitmap]::new(12,8)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $path = Join-Path $fixtureDir $name
    try { $graphics.Clear($color); $bitmap.Save($path,[Drawing.Imaging.ImageFormat]::Png) }
    finally { $graphics.Dispose(); $bitmap.Dispose() }
    return [IO.File]::ReadAllBytes($path)
}
function ObjectExists([string]$key) {
    $ErrorActionPreference='SilentlyContinue'
    $raw=(& docker compose -p $Project exec -T minio mc stat --json "local/citypass-story-files/$key" 2>$null) -join "`n"
    $exit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    $stat=$raw | ConvertFrom-Json
    if ($exit -eq 0 -and $stat.status -eq 'success') { return $true }
    if ($stat.error.cause.message -eq 'Object does not exist') { return $false }
    throw 'Storage inspection unavailable; absence is not proven'
}
function Concurrent([string]$method,[string]$path,[string]$token,$body=$null) {
    $requests=@(); $tasks=@()
    try {
        1..2 | ForEach-Object {
            $req = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($method),"$BaseUrl$path")
            $null=$req.Headers.TryAddWithoutValidation('authorization',$token)
            if ($null -ne $body) { $req.Content=[Net.Http.StringContent]::new(($body | ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json') }
            $requests+=$req; $tasks+=$client.SendAsync($req)
        }
        return @($tasks | ForEach-Object { $r=$_.GetAwaiter().GetResult(); @{code=[int]$r.StatusCode; json=($r.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)} })
    } finally { foreach($req in $requests){$req.Dispose()} }
}

try {
    Wait { (Api GET '/actuator/health').code -eq 200 } 'application readiness' 90
    if ($Faults) {
        Assert ($Project -eq 'citypassfiles') 'Fault injection only runs in the separate citypassfiles Compose project'
        Assert ((Db "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='citypass' AND table_name='tb_story' AND column_name='status'") -eq '1') 'Apply v6 first'
    }
    # Use local mc inside MinIO only for checking objects in this isolated test bucket.
    & docker compose -p $Project exec -T minio sh -c 'mc alias set local http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null' | Out-Null
    Assert ($LASTEXITCODE -eq 0) 'Cannot prepare storage inspection'
    $png = Png ([Drawing.Color]::RoyalBlue) 'blue.png'
    $other = Png ([Drawing.Color]::OrangeRed) 'orange.png'
    $alice=Login; $bob=Login
    $null=Ok (Api PUT "/subscriptions/$($alice.id)" $bob.token) 'follow'
    $story=Draft $alice
    $idempotency=[guid]::NewGuid().ToString('N')
    $first=Ok (Api POST '/stories/drafts' $alice.token @{title='idempotency';content='draft'} $idempotency) 'create once'
    $again=Ok (Api POST '/stories/drafts' $alice.token @{title='ignored retry';content='draft'} $idempotency) 'create retry'
    Assert ($first.id -eq $again.id) 'Duplicate draft'
    Pass 'draft creation idempotency'
    Assert ((Api GET "/stories/$($story.id)").code -eq 404) 'Anonymous draft leak'
    Assert ((Api GET "/stories/$($story.id)" $bob.token).code -eq 404) 'Foreign draft leak'
    Assert ((Api PUT "/stories/$($story.id)" $bob.token @{version=1;title='attack';content='attack'}).code -eq 404) 'Foreign edit'
    Assert ((Api DELETE "/stories/$($story.id)?version=1" $bob.token).code -eq 404) 'Foreign delete'
    Assert ((Api POST "/stories/$($story.id)/attachments").code -eq 401) 'Anonymous credential'
    Pass 'draft read and write ownership'

    $a=Reserve $story $alice
    Assert ((Confirm $a $bob).code -eq 404) 'Foreign confirm'
    Assert ((Api GET "/stories/$($story.id)/attachments/$($a.id)/url" $bob.token).code -eq 404) 'Foreign draft URL'
    Assert ((Api DELETE "/stories/attachments/$($a.id)" $bob.token).code -eq 404) 'Foreign attachment delete'
    Assert ((Http PUT $a.uploadUrl '' $null $png).code -eq 200) 'Real staging PUT'
    $confirmed=Concurrent POST "/stories/attachments/$($a.id)/confirm" $alice.token
    Assert (@($confirmed | Where-Object {$_.code -eq 200}).Count -ge 1) 'No confirmation succeeded'
    Assert (@($confirmed | Where-Object {$_.code -notin @(200,409)}).Count -eq 0) 'Unexpected confirmation error'
    $ready=Ok (Confirm $a $alice) 'confirm retry'
    Assert ($ready.state -eq 'READY' -and $ready.format -eq 'png' -and $ready.size -eq $png.Length -and $ready.width -eq 12) 'Actual image metadata mismatch'
    Assert ((Db "SELECT COUNT(*) FROM tb_story_attachment WHERE id=$($a.id) AND state='READY' AND final_key IS NOT NULL") -eq '1') 'Multiple adopted results'
    $story=Ok (Edit $story $alice @($a.id)) 'bind'
    $foreign=Draft $bob
    Assert ((Edit $foreign $bob @($a.id)).code -eq 404) 'Foreign binding'
    $story=Ok (Api POST "/stories/$($story.id)/publish?version=$($story.version)" $alice.token) 'publish'
    $publishedVersion=$story.version
    $retry=Ok (Api POST "/stories/$($story.id)/publish?version=1" $alice.token) 'publish retry'
    Assert ($retry.version -eq $publishedVersion) 'Publish retry changed version'
    Assert ((Db "SELECT COUNT(*) FROM tb_reliable_task WHERE biz_key='story-feed:$($story.id):$publishedVersion'") -eq '1') 'Duplicate publication event'
    Wait { (Redis @('ZSCORE',"feed:$($bob.id)",[string]$story.id)) -ne '' } 'published feed delivery'
    $feed=Ok (Api GET '/stories/of/subscriptions?lastId=9223372036854775807' $bob.token) 'feed'
    Assert ($feed.list.id -contains $story.id) 'Published story missing from feed'
    Pass 'real PUT, concurrent and repeated confirmation, binding, publication and feed'

    $url=(Ok (Api GET "/stories/$($story.id)/attachments/$($a.id)/url") 'public read URL').url
    $original=Http GET $url
    Assert ($original.code -eq 200 -and (Hash $original.bytes) -eq (Hash $png)) 'Final object bytes mismatch'
    Assert ((Http PUT $a.uploadUrl '' $null $other).code -eq 200) 'Reuse staging PUT within validity'
    $url2=(Ok (Api GET "/stories/$($story.id)/attachments/$($a.id)/url") 'new read URL').url
    Assert ((Hash (Http GET $url2).bytes) -eq (Hash $png)) 'Staging overwrite changed final'
    $wrong=$a.uploadUrl.Replace('/stories/staging/','/stories/tampered/')
    Assert ((Http PUT $wrong '' $null $png).code -eq 403) 'Wrong key signature accepted'
    $tampered=$a.uploadUrl -replace '(X-Amz-Signature=)[0-9a-f]','$1f'
    if ($tampered -eq $a.uploadUrl) { $tampered=$a.uploadUrl -replace '(X-Amz-Signature=)[0-9a-f]','$10' }
    Assert ((Http PUT $tampered '' $null $png).code -eq 403) 'Tampered signature accepted'
    Pass 'private final object, staging reuse cannot overwrite, key and signature binding'

    $raced=Concurrent PUT "/stories/$($story.id)" $alice.token @{version=$story.version;title='Edited';content='Edited with a CAS version.';attachmentIds=@($a.id)}
    Assert (@($raced | Where-Object {$_.code -eq 200}).Count -eq 1) 'Concurrent edits both succeeded or failed'
    Assert (@($raced | Where-Object {$_.code -eq 409}).Count -eq 1) 'Concurrent edit conflict missing'
    $story=Ok (Api GET "/stories/$($story.id)" $alice.token) 'refresh version'
    Pass 'concurrent versioned edit conflict'

    $bad=Draft $alice 'invalid-files'
    $missing=Reserve $bad $alice
    Assert ((Confirm $missing $alice).code -eq 503) 'Missing object accepted'
    $fake=Reserve $bad $alice
    $null=Http PUT $fake.uploadUrl '' $null ([Text.Encoding]::UTF8.GetBytes('this is not a PNG'))
    Assert ((Confirm $fake $alice).code -eq 422) 'Disguised image accepted'
    Assert ((Edit $bad $alice @($fake.id)).code -eq 409) 'Invalid attachment bound'
    $big=Reserve $bad $alice
    $null=Http PUT $big.uploadUrl '' $null ([byte[]]::new(10485761))
    Assert ((Confirm $big $alice).code -eq 422) 'Oversized object accepted'
    # Change PNG IHDR dimensions with a correct CRC; no large allocation or decompression is needed.
    $pixel=[byte[]]$png.Clone()
    [byte[]]$dimension=0,0,19,137 # 5001 x 5001 > 20 million pixels
    [Array]::Copy($dimension,0,$pixel,16,4);[Array]::Copy($dimension,0,$pixel,20,4)
    [uint32]$crc=4294967295
    foreach($byte in $pixel[12..28]){ $crc=$crc -bxor $byte; 1..8 | ForEach-Object {if($crc -band 1){$crc=($crc -shr 1) -bxor [uint32]3988292384}else{$crc=$crc -shr 1}} }
    $crc=$crc -bxor [uint32]4294967295
    [byte[]]$crcBytes=[BitConverter]::GetBytes($crc); [Array]::Reverse($crcBytes); [Array]::Copy($crcBytes,0,$pixel,29,4)
    $huge=Reserve $bad $alice; $null=Http PUT $huge.uploadUrl '' $null $pixel
    $hugeResult=Confirm $huge $alice
    Assert ($hugeResult.code -eq 422) "Pixel dimensions not enforced: HTTP $($hugeResult.code) $($hugeResult.json.errorMsg)"
    Assert ((Db "SELECT COUNT(*) FROM tb_story_attachment WHERE story_id=$($bad.id) AND state='READY'") -eq '0') 'Invalid files became READY'
    Pass 'missing object, fake format, actual byte limit and pixel limit'

    foreach($path in @("/stories/likes/$($bad.id)","/story-comments/story/$($bad.id)")) { Assert ((Api GET $path $bob.token).code -eq 404) 'Draft interaction read leak' }
    Assert ((Api PUT "/stories/like/$($bad.id)" $bob.token).code -eq 404) 'Draft like allowed'
    $blockedComment=Api POST '/story-comments' $bob.token @{storyId=$bad.id;content='attack'}
    Assert (-not $blockedComment.json.success -and (Db "SELECT COUNT(*) FROM tb_story_comment WHERE story_id=$($bad.id)") -eq '0') 'Draft comment allowed'
    $author=Ok (Api GET "/stories/of/user?id=$($alice.id)") 'author profile'
    Assert ($author.id -notcontains $bad.id) 'Draft on public author page'
    $hot=Ok (Api GET '/stories/hot') 'hot'
    Assert ($hot.id -notcontains $bad.id) 'Draft in hot list'
    Assert ((Api POST '/upload/story' $alice.token).code -eq 410) 'Old upload still writable'
    Assert ((Api DELETE '/upload/story?name=../../secret' $alice.token).code -eq 410) 'Old arbitrary file deletion enabled'
    Pass 'all public lists and interactions filter draft, old file writes retired'

    # Equal-score pagination must skip invisible IDs and still return later valid entries once each.
    $visible1=Draft $alice 'feed-one'; $visible2=Draft $alice 'feed-two'
    $visible1=Ok (Api POST "/stories/$($visible1.id)/publish?version=1" $alice.token) 'publish feed 1'
    $visible2=Ok (Api POST "/stories/$($visible2.id)/publish?version=1" $alice.token) 'publish feed 2'
    Wait { (Redis @('ZSCORE',"feed:$($bob.id)",[string]$visible2.id)) -ne '' } 'feed fixture relay'
    $null=Redis @('DEL',"feed:$($bob.id)")
    $score='1900000000000'
    $null=Redis @('ZADD',"feed:$($bob.id)",$score,[string]$bad.id,$score,[string]$first.id,$score,[string]$visible1.id,$score,[string]$visible2.id,$score,[string]$story.id)
    $page1=Ok (Api GET '/stories/of/subscriptions?lastId=9223372036854775807' $bob.token) 'feed page 1'
    $page2=Ok (Api GET "/stories/of/subscriptions?lastId=$($page1.minTime)&offset=$($page1.offset)" $bob.token) 'feed page 2'
    $seen=@($page1.list.id)+@($page2.list.id)
    Assert ($seen.Count -eq 3 -and @($seen | Select-Object -Unique).Count -eq 3 -and $seen -notcontains $bad.id -and $seen -notcontains $first.id) 'Filtered equal-score pagination lost or repeated stories'
    Pass 'feed cursor advances over hidden rows and equal timestamps'

    if ($Faults) {
        # Storage copy succeeds, then READY UPDATE is made to fail for this one fixture row.
        $failure=Draft $alice 'copy-db-failure'; $failed=Reserve $failure $alice
        $null=Http PUT $failed.uploadUrl '' $null $png
        $null=Db "CREATE TRIGGER it_story_ready_$run BEFORE UPDATE ON tb_story_attachment FOR EACH ROW BEGIN IF NEW.id=$($failed.id) AND NEW.state='READY' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture READY commit failure'; END IF; END"
        try {
            Assert ((Confirm $failed $alice).code -eq 503) 'DB failure not surfaced'
            $orphanKey=Db "SELECT object_key FROM tb_story_file_object WHERE attachment_id=$($failed.id) AND kind='FINAL' ORDER BY id DESC LIMIT 1"
            Assert (ObjectExists $orphanKey) 'Copy did not actually succeed before DB fault'
            Assert ((Db "SELECT state FROM tb_story_attachment WHERE id=$($failed.id)") -eq 'PENDING') 'Failed DB write adopted file'
        } finally { $null=Db "DROP TRIGGER IF EXISTS it_story_ready_$run" }
        $null=Ok (Confirm $failed $alice) 'confirm after DB recovery'
        $failure=Ok (Edit $failure $alice @($failed.id)) 'bind after recovery'
        $adopted=Db "SELECT final_key FROM tb_story_attachment WHERE id=$($failed.id)"
        Assert ($adopted -ne $orphanKey -and (ObjectExists $adopted)) 'Retry reused failed candidate'
        # Speed only this failed attempt's recorded due time; no production test endpoint.
        $null=Db "UPDATE tb_story_file_object SET cleanup_after=NOW(),last_scheduled_at=NULL WHERE attachment_id=$($failed.id) AND object_key='$orphanKey'"
        Wait { -not (ObjectExists $orphanKey) } 'unadopted copy cleanup'
        Assert (ObjectExists $adopted) 'Cleaner deleted referenced final image'
        Pass 'copy-before-DB-failure retry and unadopted attempt cleanup'

        $rollback=Draft $alice 'outbox-rollback'
        $null=Db "CREATE TRIGGER it_story_outbox_$run BEFORE INSERT ON tb_reliable_task FOR EACH ROW BEGIN IF NEW.biz_key='story-feed:$($rollback.id):2' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture outbox failure'; END IF; END"
        try {
            $rolled=Api POST "/stories/$($rollback.id)/publish?version=1" $alice.token
            Assert (-not $rolled.json.success) 'Publish should fail with outbox'
            Assert ((Db "SELECT CONCAT(status,':',version) FROM tb_story WHERE id=$($rollback.id)") -eq 'DRAFT:1') 'State not rolled back together with event'
        } finally { $null=Db "DROP TRIGGER IF EXISTS it_story_outbox_$run" }
        $null=Ok (Api POST "/stories/$($rollback.id)/publish?version=1" $alice.token) 'publish after recovery'
        Pass 'publication and outbox roll back together'

        # Cleanup and binding contend on the same story/attachment rows.
        $race=Draft $alice 'cleanup-race'; $ra=Reserve $race $alice
        $null=Http PUT $ra.uploadUrl '' $null $png; $null=Ok (Confirm $ra $alice) 'race confirm'
        $null=Db "UPDATE tb_story_attachment SET expires_at=NOW() WHERE id=$($ra.id)"
        $binding=Edit $race $alice @($ra.id)
        if ($binding.code -eq 200) {
            $race=$binding.json.data
            $null=Ok (Api POST "/stories/$($race.id)/publish?version=$($race.version)" $alice.token) 'race publish'
            $safe=Db "SELECT final_key FROM tb_story_attachment WHERE id=$($ra.id)"
            Start-Sleep -Seconds 6
            Assert (ObjectExists $safe) 'Cleaner deleted winning published image'
        } else { Assert ($binding.code -eq 409) 'Unexpected cleanup/bind race result' }
        Pass 'cleanup versus binding permits only protected READY or explicit rejection'
    }

    $finalKey=Db "SELECT final_key FROM tb_story_attachment WHERE id=$($a.id)"
    $stagingKey=Db "SELECT staging_key FROM tb_story_attachment WHERE id=$($a.id)"
    $deleted=Ok (Api DELETE "/stories/$($story.id)?version=$($story.version)" $alice.token) 'delete story'
    $null=Ok (Api DELETE "/stories/$($story.id)?version=1" $alice.token) 'delete retry'
    Assert ((Api GET "/stories/$($story.id)").code -eq 404) 'Deleted detail visible'
    Assert ((Api GET "/stories/$($story.id)/attachments/$($a.id)/url").code -eq 404) 'Deleted story can mint URL'
    $null=Db "UPDATE tb_reliable_task SET status='PENDING',next_retry_time=NOW(),version=version+1,locked_by=NULL,lease_until=NULL WHERE biz_key='story-feed:$($story.id):$publishedVersion'"
    Wait { (Redis @('ZSCORE',"feed:$($bob.id)",[string]$story.id)) -eq '' } 'late publish event respects deletion'
    Wait { (Db "SELECT state FROM tb_story_attachment WHERE id=$($a.id)") -eq 'DELETED' } 'persistent deletion'
    Assert (-not (ObjectExists $finalKey) -and -not (ObjectExists $stagingKey)) 'Deleted files remain'
    Assert ((Http GET $url).code -eq 403) 'Short GET URL did not expire'
    Pass 'delete idempotency, delayed publish cannot resurrect, expired GET, physical cleanup'
    Assert ((Http PUT $a.uploadUrl '' $null $png).code -eq 403) 'Expired PUT accepted'
    Pass 'expired PUT rejected'

    # Simulate an already-started late upload reappearing after first delete, via trusted test mc.
    & docker cp (Join-Path $fixtureDir 'blue.png') "${Project}-minio-1:/tmp/citypass-it-$run.png" | Out-Null
    & docker compose -p $Project exec -T minio mc cp "/tmp/citypass-it-$run.png" "local/citypass-story-files/$stagingKey" *> $null
    Assert (ObjectExists $stagingKey) 'Late-write fixture did not exist'
    Wait { -not (ObjectExists $stagingKey) } 'persistent tombstone resweep'
    Pass 'late write reappearance is removed by tombstone sweep'

    $unused=Draft $alice 'abandoned'; $ua=Reserve $unused $alice
    $null=Http PUT $ua.uploadUrl '' $null $png
    $never=Reserve $unused $alice
    $null=Db "UPDATE tb_story SET draft_expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=$($unused.id)"
    Wait { (Db "SELECT status FROM tb_story WHERE id=$($unused.id)") -eq 'DELETED' } 'abandoned draft expiry'
    Wait { (Db "SELECT COUNT(*) FROM tb_story_attachment WHERE story_id=$($unused.id) AND state<>'DELETED'") -eq '0' } 'unconfirmed and never-uploaded cleanup'
    Pass 'expired draft, unconfirmed upload and never-uploaded reservation cleanup'

    if ($Faults) {
        $retryDraft=Draft $alice 'delete-restart'; $da=Reserve $retryDraft $alice
        $null=Http PUT $da.uploadUrl '' $null $png; $null=Ok (Confirm $da $alice) 'deletion fixture confirm'
        $retryDraft=Ok (Edit $retryDraft $alice @($da.id)) 'deletion fixture bind'
        $retryKey=Db "SELECT final_key FROM tb_story_attachment WHERE id=$($da.id)"
        & docker compose -p $Project stop minio | Out-Null
        try {
            $null=Ok (Api DELETE "/stories/$($retryDraft.id)?version=$($retryDraft.version)" $alice.token) 'delete while storage down'
            $null=Db "UPDATE tb_story_file_object SET cleanup_after=NOW() WHERE attachment_id=$($da.id)"
            Wait { [int](Db "SELECT COUNT(*) FROM tb_reliable_task t JOIN tb_story_file_object o ON t.payload=CAST(o.id AS CHAR) WHERE t.task_type='DELETE_STORY_FILE' AND o.attachment_id=$($da.id) AND t.retry_count>0") -gt 0 } 'external deletion retry'
            Start-Sleep -Seconds 6
            Assert ((Db "SELECT COUNT(*) FROM (SELECT t.payload FROM tb_reliable_task t JOIN tb_story_file_object o ON t.payload=CAST(o.id AS CHAR) WHERE t.task_type='DELETE_STORY_FILE' AND o.attachment_id=$($da.id) AND t.status IN ('PENDING','RUNNING','DEAD') GROUP BY t.payload HAVING COUNT(*)>1) duplicates") -eq '0') 'Sweep created duplicate unfinished deletion tasks'
            & docker compose -p $Project restart app | Out-Null
        } finally { & docker compose -p $Project start minio | Out-Null }
        Wait { (Api GET '/actuator/health').code -eq 200 } 'app restart health'
        Wait { (Db "SELECT state FROM tb_story_attachment WHERE id=$($da.id)") -eq 'DELETED' } 'deletion after restart'
        Assert (-not (ObjectExists $retryKey)) 'Delete after restart left final object'
        Pass 'storage outage retries and process restart resume persistent cleanup'

        $ack=Draft $alice 'delete-db-ack'; $aa=Reserve $ack $alice
        $null=Http PUT $aa.uploadUrl '' $null $png; $null=Ok (Confirm $aa $alice) 'ack fixture confirm'
        $ack=Ok (Edit $ack $alice @($aa.id)) 'ack fixture bind'
        $ackKey=Db "SELECT final_key FROM tb_story_attachment WHERE id=$($aa.id)"
        $null=Db "CREATE TRIGGER it_story_delete_ack_$run BEFORE UPDATE ON tb_story_file_object FOR EACH ROW BEGIN IF NEW.attachment_id=$($aa.id) AND NEW.state='DELETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture deletion DB ack failure'; END IF; END"
        try {
            $null=Ok (Api DELETE "/stories/$($ack.id)?version=$($ack.version)" $alice.token) 'delete ack fixture'
            $null=Db "UPDATE tb_story_file_object SET cleanup_after=NOW() WHERE attachment_id=$($aa.id)"
            Wait { -not (ObjectExists $ackKey) } 'external delete succeeded'
            Assert ((Db "SELECT COUNT(*) FROM tb_story_file_object WHERE attachment_id=$($aa.id) AND state='TRACKED'") -ne '0') 'DB failure not injected'
        } finally { $null=Db "DROP TRIGGER IF EXISTS it_story_delete_ack_$run" }
        Wait { (Db "SELECT state FROM tb_story_attachment WHERE id=$($aa.id)") -eq 'DELETED' } 'idempotent deletion DB retry'
        Pass 'external deletion before DB ack failure, missing-object retry succeeds'
    }
    $result=@{run=$run; project=$Project; passed=$proof.Count; checks=@($proof); executedAt=(Get-Date -Format o)}
    $result | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $fixtureDir 'results.json')
    Write-Host "Story/file acceptance: $($proof.Count) checks passed. Evidence: data/story-file-it/results.json"
} finally { $client.Dispose() }
