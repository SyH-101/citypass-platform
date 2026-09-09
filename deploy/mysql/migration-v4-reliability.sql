-- CityPass v4: durable request outbox, task leases, strict handoff versions and cache versions.
-- Apply after migration-v3-waitlist.sql. Statements target MySQL 8.

ALTER TABLE tb_venue
  ADD COLUMN cache_version bigint UNSIGNED NOT NULL DEFAULT 0 COMMENT '缓存版本，防止旧读回写';

ALTER TABLE tb_reservation_order
  ADD COLUMN resource_version bigint UNSIGNED NOT NULL DEFAULT 0 AFTER offer_expire_time;

ALTER TABLE tb_reservation_waitlist
  ADD COLUMN wait_expire_time datetime NULL AFTER offer_expire_time,
  ADD COLUMN invalid_reason varchar(128) NULL AFTER wait_expire_time;

CREATE TABLE IF NOT EXISTS tb_reservation_request (
  request_id bigint NOT NULL,
  user_id bigint UNSIGNED NOT NULL,
  activity_pass_id bigint UNSIGNED NOT NULL,
  accept_waitlist tinyint(1) NOT NULL DEFAULT 1,
  status varchar(32) NOT NULL DEFAULT 'PROCESSING',
  order_id bigint NULL,
  last_error varchar(500) NULL,
  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (request_id),
  KEY idx_request_owner (user_id, request_id),
  KEY idx_request_status (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约请求事实表';

ALTER TABLE tb_reliable_task
  ADD COLUMN max_retry int NOT NULL DEFAULT 12 AFTER retry_count,
  ADD COLUMN locked_by varchar(64) NULL AFTER next_retry_time,
  ADD COLUMN lease_until datetime NULL AFTER locked_by,
  ADD COLUMN version bigint UNSIGNED NOT NULL DEFAULT 0 AFTER lease_until;

ALTER TABLE tb_reliable_task DROP INDEX idx_reliable_task_scan;
ALTER TABLE tb_reliable_task
  ADD INDEX idx_reliable_task_scan(status, next_retry_time, lease_until);

UPDATE tb_reservation_order
SET resource_version = promotion_round
WHERE resource_version = 0 AND promotion_round > 0;

UPDATE tb_reservation_waitlist w
JOIN tb_limited_pass_stock s ON s.activity_pass_id = w.activity_pass_id
SET w.wait_expire_time = s.end_time
WHERE w.wait_expire_time IS NULL;

INSERT IGNORE INTO tb_reservation_request
  (request_id,user_id,activity_pass_id,accept_waitlist,status,order_id)
SELECT id,user_id,activity_pass_id,IF(source='WAITLIST',1,0),
       CASE status WHEN 1 THEN 'RESERVED' WHEN 2 THEN 'PAID' WHEN 4 THEN 'CANCELLED'
                   ELSE CONCAT('ORDER_STATUS_',status) END,
       id
FROM tb_reservation_order;

INSERT INTO tb_reservation_request
  (request_id,user_id,activity_pass_id,accept_waitlist,status,order_id)
SELECT request_id,user_id,activity_pass_id,1,
       CASE status WHEN 'WAITING' THEN 'WAITLISTED' WHEN 'OFFERED' THEN 'RESERVED'
                   WHEN 'ACCEPTED' THEN 'PAID' WHEN 'EXPIRED' THEN 'EXPIRED' ELSE 'CANCELLED' END,
       offered_order_id
FROM tb_reservation_waitlist
ON DUPLICATE KEY UPDATE
  status=VALUES(status), order_id=VALUES(order_id), update_time=NOW();
