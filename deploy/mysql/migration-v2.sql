-- 已经导入过旧版 hmdp.sql 时执行；全新 Compose 环境无需单独执行。
SET @add_initial_stock = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_seckill_voucher'
       AND COLUMN_NAME = 'initial_stock') = 0,
    'ALTER TABLE tb_seckill_voucher ADD COLUMN initial_stock int(8) NOT NULL DEFAULT 0 COMMENT ''初始库存（库存对账基准）'' AFTER stock',
    'SELECT 1');
PREPARE stmt FROM @add_initial_stock;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE tb_seckill_voucher sv
LEFT JOIN (
    SELECT voucher_id, COUNT(*) AS valid_orders
    FROM tb_voucher_order
    WHERE status IN (1, 2)
    GROUP BY voucher_id
) o ON o.voucher_id = sv.voucher_id
SET sv.initial_stock = sv.stock + COALESCE(o.valid_orders, 0)
WHERE sv.initial_stock = 0;

SET @add_order_unique = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_voucher_order'
       AND INDEX_NAME = 'uk_user_voucher') = 0,
    'ALTER TABLE tb_voucher_order ADD UNIQUE INDEX uk_user_voucher(user_id, voucher_id)',
    'SELECT 1');
PREPARE stmt FROM @add_order_unique;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `tb_reliable_task` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_type` varchar(64) NOT NULL,
  `biz_key` varchar(128) NOT NULL,
  `payload` varchar(2048) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `retry_count` int(11) NOT NULL DEFAULT 0,
  `next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_error` varchar(500) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reliable_task_biz_key` (`biz_key`),
  KEY `idx_reliable_task_scan` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地可靠任务/事务发件箱';
