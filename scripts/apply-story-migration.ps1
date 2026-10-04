param([string]$Project='citypass',[string]$Database='citypass')
$ErrorActionPreference='Stop'
if ($Database -notmatch '^[A-Za-z0-9_]+$') { throw 'Invalid database name' }
$present=(& docker compose -p $Project exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -B -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$Database' AND table_name='tb_story' AND column_name='status'").Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect database' }
if ($present -eq '1') { throw 'v6 story columns already exist. Do not apply the ALTER migration twice; inspect tables and task retirement manually.' }
Write-Host 'Stop ALL old application instances first. This script only applies v6; earlier migrations must already be present.'
$sql=Get-Content -LiteralPath (Join-Path $PSScriptRoot '../deploy/mysql/migration-v6-story-files.sql') -Raw -Encoding UTF8
$previousEncoding=$OutputEncoding
try {
    $OutputEncoding=[Text.UTF8Encoding]::new($false)
    $sql | & docker compose -p $Project exec -T -e MYSQL_PWD=123456 mysql mysql -uroot --default-character-set=utf8mb4 $Database
    if ($LASTEXITCODE -ne 0) { throw 'Migration failed. MySQL DDL is not transactional: inspect partial completion before retrying.' }
} finally { $OutputEncoding=$previousEncoding }
Write-Host 'v6 applied; start the new application version and inspect reliable task status.'
