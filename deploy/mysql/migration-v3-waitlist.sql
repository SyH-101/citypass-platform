-- 从 CityPass 基础领域模型升级到预约候补与自动补位。
-- 请先执行 migration-v2.sql；全新环境无需执行迁移。

SET @drop_legacy_unique = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_reservation_order'
       AND INDEX_NAME = 'uk_user_voucher') > 0,
    'ALTER TABLE tb_reservation_order DROP INDEX uk_user_voucher',
    'SELECT 1');
PREPARE stmt FROM @drop_legacy_unique;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_intermediate_unique = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_reservation_order'
       AND INDEX_NAME = 'uk_user_pass') > 0,
    'ALTER TABLE tb_reservation_order DROP INDEX uk_user_pass',
    'SELECT 1');
PREPARE stmt FROM @drop_intermediate_unique;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_source = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_reservation_order'
       AND COLUMN_NAME = 'source') = 0,
    'ALTER TABLE tb_reservation_order ADD COLUMN source varchar(16) NOT NULL DEFAULT ''DIRECT'' COMMENT ''DIRECT / WAITLIST'' AFTER status',
    'SELECT 1');
PREPARE stmt FROM @add_source;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_round = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_reservation_order'
       AND COLUMN_NAME = 'promotion_round') = 0,
    'ALTER TABLE tb_reservation_order ADD COLUMN promotion_round int NOT NULL DEFAULT 0 AFTER source',
    'SELECT 1');
PREPARE stmt FROM @add_round;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_expire = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_reservation_order'
       AND COLUMN_NAME = 'offer_expire_time') = 0,
    'ALTER TABLE tb_reservation_order ADD COLUMN offer_expire_time datetime NULL AFTER promotion_round',
    'SELECT 1');
PREPARE stmt FROM @add_expire;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE tb_reservation_order
SET source = 'DIRECT',
    promotion_round = 0,
    offer_expire_time = COALESCE(offer_expire_time, DATE_ADD(create_time, INTERVAL 15 MINUTE));

SET @add_active_user = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_reservation_order'
       AND COLUMN_NAME = 'active_user_id') = 0,
    'ALTER TABLE tb_reservation_order ADD COLUMN active_user_id bigint GENERATED ALWAYS AS (CASE WHEN status IN (1,2,3,5) THEN user_id ELSE NULL END) STORED',
    'SELECT 1');
PREPARE stmt FROM @add_active_user;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_active_unique = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_reservation_order'
       AND INDEX_NAME = 'uk_active_user_pass') = 0,
    'ALTER TABLE tb_reservation_order ADD UNIQUE INDEX uk_active_user_pass(activity_pass_id, active_user_id)',
    'SELECT 1');
PREPARE stmt FROM @add_active_unique;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @add_expire_index = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_reservation_order'
       AND INDEX_NAME = 'idx_order_expire') = 0,
    'ALTER TABLE tb_reservation_order ADD INDEX idx_order_expire(status, offer_expire_time)',
    'SELECT 1');
PREPARE stmt FROM @add_expire_index;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS tb_reservation_waitlist (
  id bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  request_id bigint NOT NULL,
  activity_pass_id bigint UNSIGNED NOT NULL,
  user_id bigint UNSIGNED NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'WAITING',
  offered_order_id bigint NULL,
  offer_expire_time datetime NULL,
  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  active_user_id bigint GENERATED ALWAYS AS (
      CASE WHEN status IN ('WAITING','OFFERED') THEN user_id ELSE NULL END
  ) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_waitlist_request (request_id),
  UNIQUE KEY uk_active_waiter (activity_pass_id, active_user_id),
  KEY idx_waitlist_pick (activity_pass_id, status, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限量活动候补队列';

CREATE TABLE IF NOT EXISTS tb_reliable_task (
  id bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  task_type varchar(64) NOT NULL,
  biz_key varchar(128) NOT NULL,
  payload varchar(2048) NOT NULL,
  status varchar(16) NOT NULL DEFAULT 'PENDING',
  retry_count int NOT NULL DEFAULT 0,
  next_retry_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_error varchar(500) DEFAULT NULL,
  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_reliable_task_biz_key (biz_key),
  KEY idx_reliable_task_scan (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地可靠任务与事务发件箱';
