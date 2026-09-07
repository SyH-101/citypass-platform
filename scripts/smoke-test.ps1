param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$TerminalTimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function New-TestPhone {
    return '13' + (Get-Random -Minimum 100000000 -Maximum 999999999).ToString()
}

function Login-TestUser([string]$Phone) {
    $sent = Invoke-RestMethod -Method Post -Uri "$BaseUrl/user/code?phone=$Phone"
    Assert-True $sent.success "Verification code request failed: $($sent.errorMsg)"
    $code = (docker compose exec -T redis redis-cli GET "login:code:$Phone").Trim()
    Assert-True ($code -match '^\d{6}$') "Verification code not found in Redis: $Phone"
    $body = @{ phone = $Phone; code = $code } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/user/login" -ContentType 'application/json' -Body $body
    Assert-True $login.success "Login failed: $($login.errorMsg)"
    return [string]$login.data
}

function Wait-Reservation([hashtable]$Headers, [long]$RequestId, [string[]]$Expected) {
    $deadline = (Get-Date).AddSeconds($TerminalTimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 300
        $result = Invoke-RestMethod -Method Get -Uri "$BaseUrl/reservations/requests/$RequestId" -Headers $Headers
        if ($result.success -and $Expected -contains [string]$result.data.status) { return $result }
    } while ((Get-Date) -lt $deadline)
    throw "Reservation $RequestId did not reach [$($Expected -join ',')]. Current: $($result.data.status)"
}

function Wait-RedisStock([long]$PassId, [string]$Expected) {
    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 250
        $actual = (docker compose exec -T redis redis-cli GET "reservation:stock:$PassId").Trim()
        if ($actual -eq $Expected) { return }
    } while ((Get-Date) -lt $deadline)
    throw "Redis stock mismatch: passId=$PassId, expected=$Expected, actual=$actual"
}

function New-LimitedPass(
    [hashtable]$Headers,
    [int]$Stock,
    [string]$Name,
    [string]$BeginTime = '2026-01-01T00:00:00',
    [string]$EndTime = '2099-01-01T00:00:00'
) {
    $body = @{
        venueId = 1
        title = "$Name-$(Get-Date -Format yyyyMMddHHmmssfff)"
        subTitle = 'City reservation integration test'
        rules = 'One active reservation per user'
        payValue = 6800
        actualValue = 6800
        type = 1
        status = 1
        stock = $Stock
        beginTime = $BeginTime
        endTime = $EndTime
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/passes/limited" -Headers $Headers -ContentType 'application/json' -Body $body
    Assert-True $created.success "Activity pass creation failed: $($created.errorMsg)"
    Wait-RedisStock ([long]$created.data) ([string]$Stock)
    return [long]$created.data
}

function New-Reservation([hashtable]$Headers, [long]$PassId, [bool]$AcceptWaitlist = $true) {
    $accepted = Invoke-RestMethod -Method Post -Uri "$BaseUrl/reservations/${PassId}?acceptWaitlist=$($AcceptWaitlist.ToString().ToLower())" -Headers $Headers
    Assert-True $accepted.success "Reservation request rejected: $($accepted.errorMsg)"
    return [long]$accepted.data.requestId
}

$venue = Invoke-RestMethod -Method Get -Uri "$BaseUrl/venues/1"
Assert-True ($venue.success -and $venue.data.id -eq 1) 'Public venue discovery is unavailable'

$tokens = 1..4 | ForEach-Object { Login-TestUser (New-TestPhone) }
$headers = @($tokens | ForEach-Object { @{ authorization = $_ } })
$users = @($headers | ForEach-Object {
    $me = Invoke-RestMethod -Method Get -Uri "$BaseUrl/user/me" -Headers $_
    Assert-True $me.success 'Cannot read logged-in user'
    $me.data
})

$subscription = Invoke-RestMethod -Method Put -Uri "$BaseUrl/subscriptions/$($users[0].id)" -Headers $headers[1]
Assert-True $subscription.success "Subscription failed: $($subscription.errorMsg)"

$storyBody = @{
    venueId = 1
    title = 'Independent music on a rainy night'
    images = ''
    content = 'Notes about the venue, live atmosphere, and reservation experience.'
} | ConvertTo-Json
$storyCreated = Invoke-RestMethod -Method Post -Uri "$BaseUrl/stories" -Headers $headers[0] -ContentType 'application/json' -Body $storyBody
Assert-True $storyCreated.success "Story creation failed: $($storyCreated.errorMsg)"
$storyId = [long]$storyCreated.data

$commentBody = @{ storyId = $storyId; content = 'The waitlist notification arrived on time.' } | ConvertTo-Json
$commentCreated = Invoke-RestMethod -Method Post -Uri "$BaseUrl/story-comments" -Headers $headers[1] -ContentType 'application/json' -Body $commentBody
Assert-True $commentCreated.success "Comment creation failed: $($commentCreated.errorMsg)"
$commentId = [long]$commentCreated.data
$commentList = Invoke-RestMethod -Method Get -Uri "$BaseUrl/story-comments/story/$storyId"
Assert-True ($commentList.success -and $commentList.total -eq 1 -and $commentList.data[0].authorName) 'Comment list or author enrichment failed'
$commentDeleted = Invoke-RestMethod -Method Delete -Uri "$BaseUrl/story-comments/$commentId" -Headers $headers[1]
Assert-True $commentDeleted.success "Comment deletion failed: $($commentDeleted.errorMsg)"

$passId = New-LimitedPass $headers[0] 1 'Harbor indoor music festival'
$request1 = New-Reservation $headers[0] $passId
$direct = Wait-Reservation $headers[0] $request1 @('RESERVED')
$request2 = New-Reservation $headers[1] $passId
$waiter1 = Wait-Reservation $headers[1] $request2 @('WAITLISTED')
$request3 = New-Reservation $headers[2] $passId
$waiter2 = Wait-Reservation $headers[2] $request3 @('WAITLISTED')
Assert-True ($waiter1.data.position -eq 1 -and $waiter2.data.position -eq 2) 'FIFO waitlist positions are incorrect'

$foreignQuery = Invoke-RestMethod -Method Get -Uri "$BaseUrl/reservations/requests/$request2" -Headers $headers[3]
Assert-True (-not $foreignQuery.success) 'Another user can read reservation status'

$cancelled = Invoke-RestMethod -Method Put -Uri "$BaseUrl/reservations/$request1/cancel" -Headers $headers[0]
Assert-True ($cancelled.success -and $cancelled.data -eq 'PROMOTED') "Cancellation did not promote a waiter: $($cancelled.errorMsg)"
$promoted = Wait-Reservation $headers[1] $request2 @('RESERVED')
Assert-True ($promoted.data.source -eq 'WAITLIST' -and $promoted.data.promotionRound -eq 1) 'Promoted order lacks source or round'
$remaining = Wait-Reservation $headers[2] $request3 @('WAITLISTED')
Assert-True ($remaining.data.position -eq 1) 'Remaining waiter did not advance'
Wait-RedisStock $passId '0'

$paid = Invoke-RestMethod -Method Put -Uri "$BaseUrl/reservations/$request2/pay" -Headers $headers[1]
$paidAgain = Invoke-RestMethod -Method Put -Uri "$BaseUrl/reservations/$request2/pay" -Headers $headers[1]
Assert-True $paid.success "Promoted reservation payment failed: $($paid.errorMsg)"
Assert-True (-not $paidAgain.success) 'Payment CAS did not block a repeated payment'
$left = Invoke-RestMethod -Method Delete -Uri "$BaseUrl/reservations/waitlist/$request3" -Headers $headers[2]
Assert-True $left.success "Leaving waitlist failed: $($left.errorMsg)"

$rebookPassId = New-LimitedPass $headers[3] 1 'Maker space open day'
$firstBooking = New-Reservation $headers[3] $rebookPassId
$null = Wait-Reservation $headers[3] $firstBooking @('RESERVED')
$released = Invoke-RestMethod -Method Put -Uri "$BaseUrl/reservations/$firstBooking/cancel" -Headers $headers[3]
Assert-True ($released.success -and $released.data -eq 'RELEASED') 'Stock was not restored for an empty waitlist'
Wait-RedisStock $rebookPassId '1'
$secondBooking = New-Reservation $headers[3] $rebookPassId
$rebooked = Wait-Reservation $headers[3] $secondBooking @('RESERVED')
Assert-True ($rebooked.data.orderId -eq $secondBooking) 'User cannot rebook after cancellation'

$futurePassId = New-LimitedPass $headers[2] 1 'Future exhibition' '2098-01-01T00:00:00' '2099-01-01T00:00:00'
$futureRequest = New-Reservation $headers[2] $futurePassId $false
$futureResult = Wait-Reservation $headers[2] $futureRequest @('FAIL_NOT_STARTED')
Assert-True ($futureResult.data.status -eq 'FAIL_NOT_STARTED') 'Future activity accepted an early reservation'

$dbStock = (docker compose exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -e "SELECT stock FROM citypass.tb_limited_pass_stock WHERE activity_pass_id=$passId;").Trim()
$orderStates = (docker compose exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -e "SELECT GROUP_CONCAT(CONCAT(source,':',status) ORDER BY id) FROM citypass.tb_reservation_order WHERE activity_pass_id=$passId;").Trim()
Assert-True ($dbStock -eq '0') "Database stock mismatch: $dbStock"

[pscustomobject]@{
    Success = $true
    PublicVenueRead = $venue.data.name
    StoryAndComment = 'PASS'
    Subscription = 'PASS'
    ActivityPassId = $passId
    DirectRequestId = $request1
    PromotedRequestId = $request2
    RemainingWaiterExited = $left.success
    ReservationOrderStates = $orderStates
    RedisAndDbStock = "0 / $dbStock"
    RebookAfterCancel = $rebooked.data.status
    ForeignQueryBlocked = -not $foreignQuery.success
    PaymentCas = -not $paidAgain.success
    ActivityWindowGuard = $futureResult.data.status
}
