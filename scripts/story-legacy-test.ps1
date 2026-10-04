param(
    [string]$Project = 'citypassfiles',
    [string]$BaseUrl = 'http://localhost:18080'
)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
if ($Project -ne 'citypassfiles') { throw 'Legacy fixture checks require the isolated citypassfiles project' }
Add-Type -AssemblyName System.Net.Http
$client=[Net.Http.HttpClient]::new()
$client.Timeout=[TimeSpan]::FromSeconds(25)
$run=[guid]::NewGuid().ToString('N')
$directory=Join-Path $PSScriptRoot '../data/story-file-it'
$fixture=Join-Path $directory 'blue.png'
if (-not (Test-Path -LiteralPath $fixture)) { throw 'Run story-files-test.ps1 first to prepare the real image fixture' }
function Db([string]$sql) {
    $out=@($sql | & docker compose -p $Project exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -B citypass)
    if ($LASTEXITCODE -ne 0) { throw 'Legacy fixture database operation failed' }
    return ($out -join "`n").Trim()
}
function Http([string]$method,[string]$path,[string]$token='', $body=$null) {
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($method),"$BaseUrl$path")
    if ($token) { $null=$request.Headers.TryAddWithoutValidation('authorization',$token) }
    if ($null -ne $body) { $request.Content=[Net.Http.StringContent]::new(($body | ConvertTo-Json -Compress),[Text.Encoding]::UTF8,'application/json') }
    try {
        $response=$client.SendAsync($request).GetAwaiter().GetResult()
        $bytes=$response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $json=$null
        try { $json=[Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json } catch {}
        return @{code=[int]$response.StatusCode;bytes=$bytes;json=$json;cache=[string]$response.Headers.CacheControl}
    } catch { throw 'Legacy fixture HTTP request failed; credentials omitted' }
    finally { $request.Dispose() }
}
function Login {
    $phone='139'+(Get-Random -Minimum 10000000 -Maximum 99999999)
    $sent=Http POST "/user/code?phone=$phone"
    if (-not $sent.json.success) { throw 'Cannot send fixture login code' }
    $code=((& docker compose -p $Project exec -T redis redis-cli GET "login:code:$phone") -join '').Trim()
    $logged=Http POST '/user/login' '' @{phone=$phone;code=$code}
    if (-not $logged.json.success) { throw 'Cannot log in fixture user' }
    $token=[string]$logged.json.data
    $me=Http GET '/user/me' $token
    return @{token=$token;id=[long]$me.json.data.id}
}
function Assert([bool]$condition,[string]$message) { if (-not $condition) { throw $message } }
function Hash([byte[]]$bytes) {
    $sha=[Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($bytes)) } finally { $sha.Dispose() }
}
try {
    $alice=Login; $bob=Login
    $app=((& docker compose -p $Project ps -q app) -join '').Trim()
    Assert ([bool]$app) 'Isolated app is unavailable'
    & docker exec $app mkdir -p /app/data/images/stories/a/b
    Assert ($LASTEXITCODE -eq 0) 'Cannot prepare legacy fixture directory'
    & docker cp $fixture "${app}:/app/data/images/stories/a/b/$run.png"
    Assert ($LASTEXITCODE -eq 0) 'Cannot copy legacy fixture image'
    $path="/stories/a/b/$run.png"
    $id=Db "INSERT INTO tb_story(user_id,venue_id,title,content,images,status,version,publish_time) VALUES($($alice.id),1,'legacy-$run','retained local image','$path','PUBLISHED',1,NOW(3)); SELECT LAST_INSERT_ID();"
    $image=Http GET "/stories/$id/legacy-images/0"
    Assert ($image.code -eq 200 -and (Hash $image.bytes) -eq (Hash ([IO.File]::ReadAllBytes($fixture)))) 'Legacy image bytes changed or unavailable'
    Assert ($image.cache -match 'no-store') 'Legacy response is shared-cacheable'
    $detail=Http GET "/stories/$id"
    Assert ($detail.json.success -and $detail.json.data.status -eq 'PUBLISHED') 'Migrated-shaped story is not publicly readable'
    Assert ((Http GET "/stories/$id/legacy-images/1").code -eq 404) 'Out-of-range image position allowed'
    $null=Db "UPDATE tb_story SET status='DRAFT',draft_expires_at=DATE_ADD(NOW(),INTERVAL 1 HOUR) WHERE id=$id;"
    Assert ((Http GET "/stories/$id/legacy-images/0").code -eq 404) 'Anonymous legacy draft leak'
    Assert ((Http GET "/stories/$id/legacy-images/0" $bob.token).code -eq 404) 'Foreign legacy draft leak'
    Assert ((Http GET "/stories/$id/legacy-images/0" $alice.token).code -eq 200) 'Owner cannot read retained legacy draft'
    $null=Db "UPDATE tb_story SET images='../pom.xml' WHERE id=$id;"
    Assert ((Http GET "/stories/$id/legacy-images/0" $alice.token).code -eq 404) 'Legacy traversal path allowed'
    & docker exec $app ln -s /etc/passwd "/app/data/images/stories/a/b/$run-link.png"
    Assert ($LASTEXITCODE -eq 0) 'Cannot prepare fixture symlink'
    $null=Db "UPDATE tb_story SET images='/stories/a/b/$run-link.png' WHERE id=$id;"
    Assert ((Http GET "/stories/$id/legacy-images/0" $alice.token).code -eq 404) 'Legacy symlink escaped the upload directory'
    $null=Db "UPDATE tb_story SET images='$path' WHERE id=$id;"
    $deleted=Http DELETE "/stories/${id}?version=1" $alice.token
    Assert ($deleted.json.success) 'Cannot delete fixture story'
    Assert ((Http GET "/stories/$id/legacy-images/0" $alice.token).code -eq 404) 'Deleted legacy image remains visible'
    $result=@{passed=8;run=$run;project=$Project;storyId=[long]$id;executedAt=(Get-Date -Format o);checks=@('retained legacy bytes and public story','no-store response','position boundary','anonymous and foreign draft denied','owner draft read','traversal denied','real-path symlink boundary','deleted legacy story denied')}
    $result | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $directory 'legacy-results.json')
    Write-Host 'PASS 8 legacy read and permission checks. Own fixtures retained; no shared data removed.'
} finally { $client.Dispose() }
