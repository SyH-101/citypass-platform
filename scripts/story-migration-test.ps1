param([string]$Project='citypassfiles')
$ErrorActionPreference='Stop'
$repoRoot=Split-Path $PSScriptRoot
$database='citypass_story_migration_'+[guid]::NewGuid().ToString('N').Substring(0,12)
function Db([string]$sql) {
    $previousEncoding=$OutputEncoding
    try {
        $OutputEncoding=[Text.UTF8Encoding]::new($false)
        $output=@($sql | & docker compose -p $Project exec -T -e MYSQL_PWD=123456 mysql mysql -uroot -N -B --default-character-set=utf8mb4)
        if ($LASTEXITCODE -ne 0) { throw 'Migration acceptance database command failed' }
        return ($output -join "`n").Trim()
    } finally { $OutputEncoding=$previousEncoding }
}
$null=Db "CREATE DATABASE $database CHARACTER SET utf8mb4;"
$initial=Get-Content -LiteralPath (Join-Path $repoRoot 'src/main/resources/db/citypass.sql') -Raw -Encoding UTF8
$null=Db "USE $database;`n$initial"
# Simulate the existing v5 search-only column and pending/dead search tasks.
$null=Db "USE $database; ALTER TABLE tb_activity_pass ADD COLUMN search_version bigint UNSIGNED NOT NULL DEFAULT 1; INSERT INTO tb_story(user_id,venue_id,title,content,images) VALUES(1,1,'legacy fixture','old published story','/stories/a/b/legacy-fixture.png'); INSERT INTO tb_reliable_task(task_type,biz_key,payload,status) VALUES('INDEX_ACTIVITY_SEARCH','migration-fixture-search-pending','1','PENDING'),('INDEX_ACTIVITY_SEARCH','migration-fixture-search-dead','2','DEAD'),('INVALIDATE_VENUE_CACHE','migration-fixture-cache','{}','PENDING'),('CREATE_RESERVATION','migration-fixture-reservation','{}','PENDING');"
$migration=Get-Content -LiteralPath (Join-Path $repoRoot 'deploy/mysql/migration-v6-story-files.sql') -Raw -Encoding UTF8
$null=Db "USE $database;`n$migration"
$backfill=Db "SELECT CONCAT(status,':',version,':',publish_time IS NOT NULL,':',images) FROM $database.tb_story WHERE title='legacy fixture';"
if ($backfill -ne 'PUBLISHED:1:1:/stories/a/b/legacy-fixture.png') { throw 'Historical story backfill or image path changed' }
$retired=Db "SELECT COUNT(*) FROM $database.tb_reliable_task WHERE task_type='INDEX_ACTIVITY_SEARCH' AND status='DONE';"
$shared=Db "SELECT COUNT(*) FROM $database.tb_reliable_task WHERE task_type IN ('INVALIDATE_VENUE_CACHE','CREATE_RESERVATION') AND status='PENDING';"
$tables=Db "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$database' AND table_name IN ('tb_story_attachment','tb_story_attachment_ref','tb_story_file_object','tb_story_feed_progress');"
if ($retired -ne '2' -or $shared -ne '2' -or $tables -ne '4') { throw 'Task retirement or new table creation failed' }
[pscustomobject]@{Success=$true;Database=$database;LegacyStory='PUBLISHED:1';LegacyPathPreserved=$true;SearchTasksRetired=2;SharedTasksUntouched=2;NewTables=4}
Write-Host 'The isolated fixture database is retained for inspection. No shared tables or volumes were removed.'
