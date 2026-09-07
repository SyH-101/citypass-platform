-- 从早期本地生活原型迁移到 CityPass 领域模型。
-- 适用：已执行旧 schema 且尚未执行本文件的 MySQL 8 数据库。
-- 全新环境直接使用 src/main/resources/db/citypass.sql，无需执行迁移。

SET FOREIGN_KEY_CHECKS = 0;

RENAME TABLE
  tb_shop TO tb_venue,
  tb_shop_type TO tb_venue_category,
  tb_voucher TO tb_activity_pass,
  tb_seckill_voucher TO tb_limited_pass_stock,
  tb_voucher_order TO tb_reservation_order,
  tb_blog TO tb_story,
  tb_blog_comments TO tb_story_comment,
  tb_follow TO tb_subscription;

ALTER TABLE tb_venue RENAME COLUMN type_id TO category_id;
ALTER TABLE tb_activity_pass RENAME COLUMN shop_id TO venue_id;
ALTER TABLE tb_limited_pass_stock RENAME COLUMN voucher_id TO activity_pass_id;
ALTER TABLE tb_reservation_order RENAME COLUMN voucher_id TO activity_pass_id;
ALTER TABLE tb_story RENAME COLUMN shop_id TO venue_id;
ALTER TABLE tb_story_comment RENAME COLUMN blog_id TO story_id;
ALTER TABLE tb_subscription RENAME COLUMN follow_user_id TO target_user_id;
ALTER TABLE tb_user_info RENAME COLUMN followee TO subscription_count;

UPDATE tb_story SET comments = 0 WHERE comments IS NULL;
UPDATE tb_story_comment
SET parent_id = COALESCE(parent_id, 0),
    answer_id = COALESCE(answer_id, 0),
    liked = COALESCE(liked, 0),
    status = COALESCE(status, 0);

ALTER TABLE tb_story
  MODIFY comments int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数量';
ALTER TABLE tb_story_comment
  MODIFY parent_id bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  MODIFY answer_id bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  MODIFY liked int(8) UNSIGNED NOT NULL DEFAULT 0,
  MODIFY status tinyint(1) UNSIGNED NOT NULL DEFAULT 0;

DELETE duplicate_row
FROM tb_subscription duplicate_row
JOIN tb_subscription kept
  ON duplicate_row.user_id = kept.user_id
 AND duplicate_row.target_user_id = kept.target_user_id
 AND duplicate_row.id > kept.id;

ALTER TABLE tb_subscription
  ADD UNIQUE INDEX uk_subscription_user_target(user_id, target_user_id);

SET FOREIGN_KEY_CHECKS = 1;
