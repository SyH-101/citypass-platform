param(
    [string]$Project = 'citypassgateway',
    [string]$GatewayUrl = 'http://localhost:8080',
    [string]$JavaUrl = 'http://localhost:8081',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Net.Http
$env:GATEWAY_CACHE_PURGE_URLS = 'http://openresty/internal/cache/venue'
$env:GATEWAY_CACHE_PURGE_URL = ''
$env:VENUE_READ_RATE_PER_SECOND = '60'
$env:VENUE_READ_BURST = '120'

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Wait-Until([scriptblock]$Condition, [int]$Seconds, [string]$Failure) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try { if (& $Condition) { return } } catch { }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw $Failure
}

function Send-Http(
    [string]$Method,
    [string]$Url,
    [hashtable]$Headers = @{},
    [string]$Body = $null
) {
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(5)
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method), $Url)
    try {
        foreach ($name in $Headers.Keys) {
            $null = $request.Headers.TryAddWithoutValidation($name, [string]$Headers[$name])
        }
        if (-not [string]::IsNullOrEmpty($Body)) {
            $request.Content = [System.Net.Http.StringContent]::new(
                $Body, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $responseBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $responseHeaders = @{}
        foreach ($header in $response.Headers) {
            $responseHeaders[$header.Key] = ($header.Value -join ',')
        }
        foreach ($header in $response.Content.Headers) {
            $responseHeaders[$header.Key] = ($header.Value -join ',')
        }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Headers = $responseHeaders
            Body = $responseBody
        }
    } finally {
        $request.Dispose()
        $client.Dispose()
    }
}

function Read-Json([object]$Response) {
    return $Response.Body | ConvertFrom-Json
}

function Login-TestUser {
    $phone = '138' + (Get-Random -Minimum 10000000 -Maximum 99999999).ToString()
    $sent = Read-Json (Send-Http 'POST' "$JavaUrl/user/code?phone=$phone")
    Assert-True $sent.success 'Cannot request verification code'
    $code = (& docker compose -p $Project exec -T redis redis-cli GET "login:code:$phone").Trim()
    $loginBody = @{phone=$phone;code=$code} | ConvertTo-Json -Compress
    $login = Read-Json (Send-Http 'POST' "$JavaUrl/user/login" @{} $loginBody)
    Assert-True $login.success "Cannot login: $($login.errorMsg)"
    return [string]$login.data
}

if ($SkipBuild) {
    & docker compose -p $Project up -d --no-build mysql redis namesrv broker app openresty
} else {
    & docker compose -p $Project up -d --build mysql redis namesrv broker app openresty
}
if ($LASTEXITCODE -ne 0) { throw 'Cannot start gateway cache integration stack' }

Wait-Until {
    (Read-Json (Send-Http 'GET' "$JavaUrl/actuator/health")).status -eq 'UP'
} 180 'Application did not become healthy'

# Restarting OpenResty gives Case 1 a deterministic empty shared dictionary.
& docker compose -p $Project restart openresty | Out-Null
Wait-Until { (Send-Http 'GET' "$GatewayUrl/venues/1").Status -eq 200 } 30 'OpenResty did not become ready'
& docker compose -p $Project restart openresty | Out-Null
Start-Sleep -Seconds 2

# Case 1: the first exact GET is a MISS and the second is a versioned HIT.
$case1Miss = Send-Http 'GET' "$GatewayUrl/venues/1"
$case1Hit = Send-Http 'GET' "$GatewayUrl/venues/1"
$case1Json = Read-Json $case1Miss
Assert-True ($case1Json.success -and $case1Miss.Headers['X-Cache-Hit'] -eq 'MISS') `
    'Case 1 first request was not a successful MISS'
Assert-True ($case1Hit.Headers['X-Cache-Hit'] -eq 'HIT') 'Case 1 second request was not a HIT'
Assert-True ($case1Hit.Body -eq $case1Miss.Body) 'Case 1 cached body changed'
$oldVersion = [long]$case1Miss.Headers['X-Cache-Version']
Assert-True ($oldVersion -ge 0 -and [long]$case1Hit.Headers['X-Cache-Version'] -eq $oldVersion) `
    'Case 1 cache version header is missing or inconsistent'

# Query strings are deliberately outside the cache contract.
$queryResponse = Send-Http 'GET' "$GatewayUrl/venues/1?preview=true"
Assert-True (-not $queryResponse.Headers.ContainsKey('X-Cache-Hit')) `
    'A query-string request unexpectedly entered the gateway cache'

$token = Login-TestUser
$newName = "Gateway version test $(Get-Date -Format HHmmssfff)"
$updateBody = @{id=1;name=$newName} | ConvertTo-Json -Compress
$updated = Read-Json (Send-Http 'PUT' "$JavaUrl/venues" @{authorization=$token} $updateBody)
Assert-True $updated.success "Venue update failed: $($updated.errorMsg)"

# Case 2: the committed update advances the gateway floor and the next fill uses the new version.
$case2Miss = $null
Wait-Until {
    $candidate = Send-Http 'GET' "$GatewayUrl/venues/1"
    $json = Read-Json $candidate
    if ($json.success -and $json.data.name -eq $newName -and
        $candidate.Headers['X-Cache-Hit'] -eq 'MISS') {
        $script:case2Miss = $candidate
        return $true
    }
    return $false
} 45 'Case 2 did not converge to a new-version MISS'
$newVersion = [long]$case2Miss.Headers['X-Cache-Version']
Assert-True ($newVersion -gt $oldVersion) 'Case 2 cache version did not advance'
$case2Hit = Send-Http 'GET' "$GatewayUrl/venues/1"
Assert-True ($case2Hit.Headers['X-Cache-Hit'] -eq 'HIT' -and
    [long]$case2Hit.Headers['X-Cache-Version'] -eq $newVersion) `
    'Case 2 new version was not cached'

# Case 3: a late lower-version purge cannot move the watermark backwards.
$purgeNew = Send-Http 'DELETE' "$GatewayUrl/internal/cache/venue?id=1&version=$newVersion"
$purgeOld = Send-Http 'DELETE' "$GatewayUrl/internal/cache/venue?id=1&version=$oldVersion"
Assert-True ($purgeNew.Status -eq 204 -and $purgeOld.Status -eq 204) 'Case 3 purge failed'
Assert-True ([long]$purgeOld.Headers['X-Gateway-Cache-Watermark'] -eq $newVersion) `
    'Case 3 lower purge moved the gateway watermark backwards'

# Case 4: simulate a response that is one generation behind the accepted floor.
# Both requests remain MISS because the body filter refuses to install the old response.
$futureVersion = $newVersion + 1
$advance = Send-Http 'DELETE' "$GatewayUrl/internal/cache/venue?id=1&version=$futureVersion"
Assert-True ([long]$advance.Headers['X-Gateway-Cache-Watermark'] -eq $futureVersion) `
    'Case 4 could not advance the gateway watermark'
$staleAttempt1 = Send-Http 'GET' "$GatewayUrl/venues/1"
$staleAttempt2 = Send-Http 'GET' "$GatewayUrl/venues/1"
Assert-True ($staleAttempt1.Headers['X-Cache-Hit'] -eq 'MISS' -and
    $staleAttempt2.Headers['X-Cache-Hit'] -eq 'MISS' -and
    [long]$staleAttempt2.Headers['X-Cache-Version'] -eq $newVersion) `
    'Case 4 accepted an old response below the gateway watermark'

# Restore the fixture by committing the version expected by the manual watermark advance.
$restoredName = "Gateway restored $(Get-Date -Format HHmmssfff)"
$restoreBody = @{id=1;name=$restoredName} | ConvertTo-Json -Compress
$restored = Read-Json (Send-Http 'PUT' "$JavaUrl/venues" @{authorization=$token} $restoreBody)
Assert-True $restored.success 'Cannot restore venue after stale-response test'
Wait-Until {
    $response = Send-Http 'GET' "$GatewayUrl/venues/1"
    $json = Read-Json $response
    $json.success -and $json.data.name -eq $restoredName -and
        [long]$response.Headers['X-Cache-Version'] -eq $futureVersion
} 45 'Gateway did not converge after fixture restoration'

# Case 5: detail reads are public through both ports; writes require authentication through both.
$publicJava = Send-Http 'GET' "$JavaUrl/venues/1"
$publicGateway = Send-Http 'GET' "$GatewayUrl/venues/1"
$anonymousWriteBody = @{id=1;name='must-not-write'} | ConvertTo-Json -Compress
$privateJava = Send-Http 'PUT' "$JavaUrl/venues" @{} $anonymousWriteBody
$privateGateway = Send-Http 'PUT' "$GatewayUrl/venues" @{} $anonymousWriteBody
Assert-True ($publicJava.Status -eq 200 -and $publicGateway.Status -eq 200) `
    'Case 5 public detail read differs between Java and gateway'
Assert-True ($privateJava.Status -eq 401 -and $privateGateway.Status -eq 401) `
    'Case 5 write authentication differs between Java and gateway'

# Case 6: use a deliberately small, isolated read bucket and scan distinct missing IDs.
# Only the OpenResty container is recreated; Java and its warmed caches remain running.
$env:VENUE_READ_RATE_PER_SECOND = '1'
$env:VENUE_READ_BURST = '3'
& docker compose -p $Project up -d --no-deps --force-recreate openresty | Out-Null
Wait-Until { (Send-Http 'GET' "$GatewayUrl/actuator/health").Status -eq 200 } 30 `
    'OpenResty did not restart for read rate-limit test'
$rateLimited = 0
$rateLayer = $null
for ($i = 0; $i -lt 20; $i++) {
    $scan = Send-Http 'GET' "$GatewayUrl/venues/$([long]990000000 + $i)"
    if ($scan.Status -eq 429) {
        $rateLimited++
        $rateLayer = $scan.Headers['X-RateLimit-Layer']
    }
}
Assert-True ($rateLimited -gt 0) 'Case 6 random missing-ID scan was not rate limited'
Assert-True ($rateLayer -eq 'gateway-venue-read') 'Case 6 used the wrong rate-limit layer'

# Leave the local stack with production-like defaults after the fault-oriented assertion.
$env:VENUE_READ_RATE_PER_SECOND = '60'
$env:VENUE_READ_BURST = '120'
& docker compose -p $Project up -d --no-deps --force-recreate openresty | Out-Null
Wait-Until { (Send-Http 'GET' "$GatewayUrl/actuator/health").Status -eq 200 } 30 `
    'OpenResty did not recover after read rate-limit test'

[pscustomobject]@{
    Success = $true
    MissThenHit = "$oldVersion / $oldVersion"
    UpdatedVersion = $newVersion
    LowerPurgeKeptWatermark = $newVersion
    StaleResponseRejectedTwice = $true
    JavaAndGatewayAuthSemanticsMatch = $true
    RandomMissingIdRateLimited = $rateLimited
    MultiGatewayFailureSemantics = 'Covered by VenueCacheInvalidatorTest'
}
