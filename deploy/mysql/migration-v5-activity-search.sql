-- Activity search metadata and rebuild guard. Apply after migration-v4-reliability.sql on MySQL 8.
ALTER TABLE `tb_activity_pass`
  ADD COLUMN `description` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '活动介绍' AFTER `rules`,
  ADD COLUMN `activity_category` varchar(32) NULL COMMENT '活动分类，不同于票券 type' AFTER `description`,
  ADD COLUMN `tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '逗号分隔标签' AFTER `activity_category`,
  ADD COLUMN `event_start_time` datetime NULL COMMENT '活动实际举办开始时间' AFTER `tags`,
  ADD COLUMN `event_end_time` datetime NULL COMMENT '活动实际举办结束时间' AFTER `event_start_time`,
  ADD COLUMN `search_version` bigint(20) UNSIGNED NOT NULL DEFAULT 1 COMMENT '搜索文档单调版本' AFTER `status`,
  ADD INDEX `idx_activity_venue_search` (`venue_id`,`id`),
  ADD CONSTRAINT `chk_activity_event_time` CHECK (
    (`event_start_time` IS NULL AND `event_end_time` IS NULL)
    OR (`event_start_time` IS NOT NULL AND `event_end_time` IS NOT NULL AND `event_end_time` > `event_start_time`)
  );

CREATE TABLE IF NOT EXISTS `tb_search_rebuild_state` (
  `id` tinyint(1) UNSIGNED NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'IDLE',
  `write_blocked` tinyint(1) NOT NULL DEFAULT 0,
  `target_index` varchar(128) DEFAULT NULL,
  `previous_index` varchar(128) DEFAULT NULL,
  `source_count` bigint(20) NOT NULL DEFAULT 0,
  `indexed_count` bigint(20) NOT NULL DEFAULT 0,
  `last_error` varchar(500) DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动搜索全量重建状态';

INSERT IGNORE INTO `tb_search_rebuild_state` (`id`) VALUES (1);
