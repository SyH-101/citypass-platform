param([switch]$Force)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$repoRoot=Split-Path $PSScriptRoot
$binDir=Join-Path $repoRoot 'deploy/minio/bin'
New-Item -ItemType Directory -Force -Path $binDir | Out-Null
$artifacts=@(
    @{Name='minio';Release='RELEASE.2025-04-22T22-12-26Z';Sha='53e2a2cb16c5366ea6fbbc479c19ddb4c6a0948273e752f740fb1fbf27bb817c'},
    @{Name='mc';Release='RELEASE.2025-04-16T18-13-26Z';Sha='ac90da87a35641be5a0ac75d49de5161ddb47d629b5ba01261b0ae9e00aea15f'}
)
foreach($artifact in $artifacts){
    $file=Join-Path $binDir $artifact.Name
    if((Test-Path -LiteralPath $file) -and -not $Force -and (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLower() -eq $artifact.Sha){continue}
    $url="https://github.com/minio/$($artifact.Name)/releases/download/$($artifact.Release)/$($artifact.Name).linux-amd64.$($artifact.Release)"
    Write-Output "Downloading official $($artifact.Name) $($artifact.Release)"
    Invoke-WebRequest -Uri $url -OutFile $file -TimeoutSec 300 -UseBasicParsing
    if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLower() -ne $artifact.Sha){throw "SHA-256 mismatch for $($artifact.Name); do not build"}
}
Write-Output 'Official MinIO and mc binaries verified (linux-amd64).'
