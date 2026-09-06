param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$TerminalTimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) {
        throw $Message
    }
}

function New-TestPhone {
    return '13' + (Get-Random -Minimum 100000000 -Maximum 999999999).ToString()
}

function Login-TestUser([string]$Phone) {
    $sent = Invoke-RestMethod -Method Post -Uri "$BaseUrl/user/code?phone=$Phone"
    Assert-True $sent.success "发送验证码失败：$($sent.errorMsg)"

    $code = (docker compose exec -T redis redis-cli GET "login:code:$Phone").Trim()
    Assert-True ($code -match '^\d{6}$') "Redis 中没有找到验证码：$Phone"

    $body = @{ phone = $Phone; code = $code } | ConvertTo-Json
    $login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/user/login" `
        -ContentType 'application/json' -Body $body
    Assert-True $login.success "登录失败：$($login.errorMsg)"
    return [string]$login.data
}

$token1 = Login-TestUser (New-TestPhone)
$token2 = Login-TestUser (New-TestPhone)
$headers1 = @{ authorization = $token1 }
$headers2 = @{ authorization = $token2 }

$voucherBody = @{
    shopId      = 1
    title       = "smoke-test-$(Get-Date -Format yyyyMMddHHmmss)"
    subTitle    = '自动冒烟测试'
    rules       = '一人一单'
    payValue    = 8000
    actualValue = 10000
    type        = 1
    status      = 1
    stock       = 2
    beginTime   = '2026-01-01T00:00:00'
    endTime     = '2099-01-01T00:00:00'
} | ConvertTo-Json

$created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/voucher/seckill" `
    -Headers $headers1 -ContentType 'application/json' -Body $voucherBody
Assert-True $created.success "创建秒杀券失败：$($created.errorMsg)"
$voucherId = [long]$created.data

$stockReady = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 500
    $redisStock = (docker compose exec -T redis redis-cli GET "seckill:stock:$voucherId").Trim()
    if ($redisStock -eq '2') {
        $stockReady = $true
        break
    }
}
Assert-True $stockReady '可靠任务未在 15 秒内初始化 Redis 库存'

$accepted = Invoke-RestMethod -Method Post -Uri "$BaseUrl/voucher-order/seckill/$voucherId" `
    -Headers $headers1
Assert-True $accepted.success "秒杀请求未受理：$($accepted.errorMsg)"
$orderId = [long]$accepted.data

$terminal = $null
$deadline = (Get-Date).AddSeconds($TerminalTimeoutSeconds)
do {
    Start-Sleep -Milliseconds 500
    $terminal = Invoke-RestMethod -Method Get `
        -Uri "$BaseUrl/voucher-order/seckill/result/$orderId" -Headers $headers1
} while ($terminal.success -and $terminal.data.status -eq 'WAITING' -and (Get-Date) -lt $deadline)
Assert-True ($terminal.success -and $terminal.data.status -eq 'SUCCESS') `
    "订单未进入 SUCCESS，当前状态：$($terminal.data.status)"

$foreignQuery = Invoke-RestMethod -Method Get `
    -Uri "$BaseUrl/voucher-order/seckill/result/$orderId" -Headers $headers2
$foreignPay = Invoke-RestMethod -Method Put `
    -Uri "$BaseUrl/voucher-order/pay/$orderId" -Headers $headers2
$duplicate = Invoke-RestMethod -Method Post `
    -Uri "$BaseUrl/voucher-order/seckill/$voucherId" -Headers $headers1
$pay = Invoke-RestMethod -Method Put `
    -Uri "$BaseUrl/voucher-order/pay/$orderId" -Headers $headers1
$payAgain = Invoke-RestMethod -Method Put `
    -Uri "$BaseUrl/voucher-order/pay/$orderId" -Headers $headers1

Assert-True (-not $foreignQuery.success) '另一用户可以查询订单结果'
Assert-True (-not $foreignPay.success) '另一用户可以支付订单'
Assert-True (-not $duplicate.success) '同一用户可以重复下单'
Assert-True $pay.success "本人支付失败：$($pay.errorMsg)"
Assert-True (-not $payAgain.success) '同一订单可以重复支付'

[pscustomobject]@{
    Success             = $true
    VoucherId           = $voucherId
    OrderId             = $orderId
    TerminalStatus      = $terminal.data.status
    ForeignQueryBlocked = -not $foreignQuery.success
    ForeignPayBlocked   = -not $foreignPay.success
    DuplicateBlocked    = -not $duplicate.success
    PayCasVerified      = -not $payAgain.success
}
