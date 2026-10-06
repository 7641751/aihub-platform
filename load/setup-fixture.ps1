# M6 T2 fixture: make the gateway route a load-test model to the host-side chat stub.
#   channel(base_url -> http://host.docker.internal:8089) -> model_route(stub-chat) -> api_key -> wide rate-limit
# then smoke-test the whole path once, and save the plaintext key for k6.
# Pure ASCII (PowerShell 5.1 reads BOM-less .ps1 as ANSI).
# Run with AIHUB_CONSOLE_SECRET set in the shell env (the admin container must use the same value).
$ErrorActionPreference = 'Continue'

$SECRET  = $env:AIHUB_CONSOLE_SECRET
$ADMIN   = 'http://127.0.0.1:8081'
$GATEWAY = 'http://127.0.0.1:8080'
$TENANT  = 1
$MODEL   = 'stub-chat'
$STUB    = 'http://host.docker.internal:8089'

function B64U([byte[]]$bytes) {
    $s = [Convert]::ToBase64String($bytes).TrimEnd([char]61)
    return $s.Replace([char]43, [char]45).Replace([char]47, [char]95)
}
function New-Token() {
    $enc = [System.Text.Encoding]::UTF8
    $header = B64U ($enc.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $iat = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $json = '{"sub":1,"tenantId":' + $TENANT + ',"role":"ADMIN","iat":' + $iat + ',"exp":' + ($iat + 3600) + '}'
    $payload = B64U ($enc.GetBytes($json))
    $signingInput = $header + '.' + $payload
    $mac = New-Object System.Security.Cryptography.HMACSHA256
    $mac.Key = $enc.GetBytes($SECRET)
    return $signingInput + '.' + (B64U ($mac.ComputeHash($enc.GetBytes($signingInput))))
}
function Post([string]$path, $body) {
    $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Compress }
    try {
        return Invoke-RestMethod -Uri ($ADMIN + $path) -Method Post -Headers @{ Authorization = 'Bearer ' + (New-Token) } `
            -ContentType 'application/json' -Body $json -TimeoutSec 20
    } catch {
        Write-Host ('  !! ' + $path + ' failed: ' + $_.Exception.Message)
        if ($_.ErrorDetails) { Write-Host ('     ' + $_.ErrorDetails.Message) }
        return $null
    }
}

Write-Host '=== 1) channel -> host stub ==='
$channel = Post '/api/channels' @{
    # 渠道名唯一：重名会 500（已实测）⇒ 用时间戳后缀，脚本可反复跑
    name = 'load-stub-' + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    provider = 'openai'; baseUrl = $STUB
    apiKey = 'sk-stub-dummy-key'; modelsJson = '["' + $MODEL + '"]'
}
if ($channel) {
    $channelId = $channel.data.id
    Write-Host ('  channelId=' + $channelId)
} else {
    Write-Host '  (channel 创建失败 -> 复用既有渠道)'
    if ($env:CHANNEL_ID) {
        # id=4 是首次运行建成的渠道，base_url 已指向宿主桩 ⇒ 直接复用；
        # 这也绕开"渠道重名 ⇒ 400/500"这条已实测的坑（渠道名必须唯一）。
        $channelId = [int]$env:CHANNEL_ID
    } else {
        $list = Invoke-RestMethod -Uri ($ADMIN + '/api/channels?tenantId=' + $TENANT + '&size=50') -Headers @{ Authorization = 'Bearer ' + (New-Token) } -TimeoutSec 20
        $channelId = ($list.data.items | Select-Object -First 1).id
    }
    Write-Host ('  reuse channelId=' + $channelId)
}

Write-Host '=== 2) model route ' + $MODEL + ' -> channel ==='
$route = Post '/api/routes' @{ modelName = $MODEL; channelId = $channelId; weight = 100; priority = 1; status = 'ACTIVE' }
if ($route) { Write-Host ('  routeId=' + $route.data.id) }

Write-Host '=== 3) api key (plaintext returned once) ==='
$created = Post '/api/api-keys' @{ tenantId = $TENANT; name = 'm6-load-test'; validDays = 30 }
if (-not $created) { Write-Host 'FIXTURE_FAILED_NO_KEY'; exit 1 }
$plain = $created.data.plaintextKey
$keyId = $created.data.id
Write-Host ('  apiKeyId=' + $keyId + ' keyId=' + $created.data.keyId + ' plaintextLength=' + $plain.Length)
New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot '..\.m6t2-logs') | Out-Null
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot '..\.m6t2-logs\api-key.txt'), $plain, (New-Object System.Text.UTF8Encoding($false)))

Write-Host '=== 4) wide rate-limit policy (so we measure the gateway, not the limiter) ==='
$policy = Post '/api/rate-limits' @{ tenantId = $TENANT; apiKeyId = $keyId; qps = 10000; burst = 10000 }
if ($policy) { Write-Host ('  policyId=' + $policy.data.id) }

Write-Host '=== 5) smoke: one real call through the gateway to the stub ==='
$body = '{"model":"' + $MODEL + '","messages":[{"role":"user","content":"smoke"}]}'
try {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $res = Invoke-WebRequest -Uri ($GATEWAY + '/v1/chat/completions') -Method Post `
        -Headers @{ Authorization = 'Bearer ' + $plain } -ContentType 'application/json' -Body $body -TimeoutSec 30 -UseBasicParsing
    $sw.Stop()
    $text = $res.Content
    Write-Host ('  HTTP=' + [int]$res.StatusCode + ' elapsedMs=' + $sw.ElapsedMilliseconds)
    Write-Host ('  bodyHead=' + $text.Substring(0, [Math]::Min(160, $text.Length)))
} catch {
    Write-Host ('  SMOKE_FAILED: ' + $_.Exception.Message)
    if ($_.ErrorDetails) { Write-Host ('     ' + $_.ErrorDetails.Message) }
    exit 2
}
Write-Host 'FIXTURE_READY'
