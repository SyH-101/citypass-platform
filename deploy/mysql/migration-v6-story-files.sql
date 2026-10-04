-- Apply ONCE after v5 (v5 remains a retired historical migration).
-- Stop old app instances first: they can publish stories or recreate search tasks.
-- No business rows, local images, or object-storage volumes are deleted here.
ALTER TABLE tb_story
  MODIFY venue_id bigint NOT NULL DEFAULT 0,
  MODIFY images varchar(2048) NOT NULL DEFAULT '',
  ADD COLUMN activity_pass_id bigint UNSIGNED NULL,
  ADD COLUMN status varchar(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'old rows are published',
  ADD COLUMN version bigint UNSIGNED NOT NULL DEFAULT 1,
  ADD COLUMN publish_time datetime(3) NULL,
  ADD COLUMN draft_expires_at datetime NULL,
  ADD COLUMN client_key varchar(64) NULL,
  ADD UNIQUE KEY uk_story_client (user_id,client_key),
  ADD KEY idx_story_visibility (status,user_id,id),
  ADD KEY idx_story_draft_expiry (status,draft_expires_at),
  ADD CONSTRAINT chk_story_status CHECK (status IN ('DRAFT','PUBLISHED','DELETED'));
UPDATE tb_story SET publish_time=create_time WHERE status='PUBLISHED' AND publish_time IS NULL;

CREATE TABLE IF NOT EXISTS tb_story_attachment (
  id bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id bigint UNSIGNED NOT NULL,
  story_id bigint UNSIGNED NOT NULL,
  staging_key varchar(255) NOT NULL,
  final_key varchar(255) NULL,
  state varchar(16) NOT NULL DEFAULT 'PENDING',
  actual_format varchar(16) NULL,
  actual_size bigint NULL,
  width int NULL,
  height int NULL,
  current_attempt varchar(36) NULL,
  confirm_lease_until datetime NULL,
  expires_at datetime NOT NULL,
  upload_url_expires_at datetime NOT NULL,
  delete_after datetime NULL,
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_attachment_staging (staging_key),
  UNIQUE KEY uk_attachment_final (final_key),
  KEY idx_attachment_owner (user_id,state),
  KEY idx_attachment_story (story_id,id),
  KEY idx_attachment_expiry (state,expires_at),
  CONSTRAINT fk_attachment_story FOREIGN KEY (story_id) REFERENCES tb_story(id),
  CONSTRAINT chk_attachment_state CHECK (state IN ('PENDING','READY','DELETING','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_story_attachment_ref (
  story_id bigint UNSIGNED NOT NULL,
  attachment_id bigint UNSIGNED NOT NULL,
  position int NOT NULL,
  active tinyint NOT NULL DEFAULT 1,
  PRIMARY KEY (story_id,attachment_id),
  UNIQUE KEY uk_attachment_single_story (attachment_id),
  KEY idx_ref_order (story_id,active,position),
  CONSTRAINT fk_ref_story FOREIGN KEY (story_id) REFERENCES tb_story(id),
  CONSTRAINT fk_ref_attachment FOREIGN KEY (attachment_id) REFERENCES tb_story_attachment(id),
  CONSTRAINT chk_ref_active CHECK (active IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per staging key or confirmation attempt. Inserted BEFORE external copy.
-- Rows survive deletion as tombstones so a late PUT/copy is still re-swept.
CREATE TABLE IF NOT EXISTS tb_story_file_object (
  id bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  attachment_id bigint UNSIGNED NOT NULL,
  object_key varchar(255) NOT NULL,
  attempt_id varchar(36) NULL,
  kind varchar(16) NOT NULL,
  state varchar(16) NOT NULL DEFAULT 'TRACKED',
  cleanup_after datetime NOT NULL,
  last_deleted_at datetime NULL,
  last_scheduled_at datetime NULL,
  cleanup_task_id bigint NULL COMMENT 'one unfinished cleanup task per object',
  create_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_file_object_key (object_key),
  UNIQUE KEY uk_file_attempt (attempt_id),
  KEY idx_object_sweep (cleanup_after,last_scheduled_at),
  CONSTRAINT fk_object_attachment FOREIGN KEY (attachment_id) REFERENCES tb_story_attachment(id),
  CONSTRAINT chk_object_kind CHECK (kind IN ('STAGING','FINAL')),
  CONSTRAINT chk_object_state CHECK (state IN ('TRACKED','DELETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tb_story_feed_progress (
  event_key varchar(80) NOT NULL,
  last_subscription_id bigint NOT NULL DEFAULT 0,
  PRIMARY KEY (event_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Only the confirmed search task type is retired. Other outbox tasks are untouched.
UPDATE tb_reliable_task SET status='DONE',last_error='retired by migration-v6-story-files',
  locked_by=NULL,lease_until=NULL,version=version+1
  WHERE task_type='INDEX_ACTIVITY_SEARCH' AND status IN ('PENDING','RUNNING','DEAD');
-- tb_search_rebuild_state/search_version may remain as historical data; no new code reads them.
