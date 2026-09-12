/*
 Navicat Premium Data Transfer

 Source Server         : local
 Source Server Type    : MySQL
 Source Server Version : 50622
 Source Host           : localhost:3306
 Source Schema         : citypass

 Target Server Type    : MySQL
 Target Server Version : 50622
 File Encoding         : 65001

 Date: 02/03/2022 23:12:54
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for tb_story
-- ----------------------------
DROP TABLE IF EXISTS `tb_story`;
CREATE TABLE `tb_story`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `venue_id` bigint(20) NOT NULL COMMENT '商户id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '标题',
  `images` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '活动的照片，最多9张，多张以\",\"隔开',
  `content` varchar(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '活动的文字描述',
  `liked` int(8) UNSIGNED NULL DEFAULT 00000000 COMMENT '点赞数量',
  `comments` int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '评论数量',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_story
-- ----------------------------

-- ----------------------------
-- Table structure for tb_story_comment
-- ----------------------------
DROP TABLE IF EXISTS `tb_story_comment`;
CREATE TABLE `tb_story_comment`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `story_id` bigint(20) UNSIGNED NOT NULL COMMENT '动态id',
  `parent_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '关联的一级评论id，一级评论为0',
  `answer_id` bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '回复的评论id，无则为0',
  `content` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '回复的内容',
  `liked` int(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞数',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态，0：正常，1：被举报，2：禁止查看',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_story_comment
-- ----------------------------

-- ----------------------------
-- Table structure for tb_subscription
-- ----------------------------
DROP TABLE IF EXISTS `tb_subscription`;
CREATE TABLE `tb_subscription`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '用户id',
  `target_user_id` bigint(20) UNSIGNED NOT NULL COMMENT '被订阅的创作者用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_subscription_user_target` (`user_id`, `target_user_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_subscription
-- ----------------------------

-- ----------------------------
-- Table structure for tb_limited_pass_stock
-- ----------------------------
DROP TABLE IF EXISTS `tb_limited_pass_stock`;
CREATE TABLE `tb_limited_pass_stock`  (
  `activity_pass_id` bigint(20) UNSIGNED NOT NULL COMMENT '关联的通行证的id',
  `stock` int(8) NOT NULL COMMENT '库存',
  `initial_stock` int(8) NOT NULL DEFAULT 0 COMMENT '初始库存（对账账本：限量预约结束库存重算的基准，发布时与 stock 一致）',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `begin_time` datetime NOT NULL COMMENT '生效时间',
  `end_time` datetime NOT NULL COMMENT '失效时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`activity_pass_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '限量预约通行证表，与通行证是一对一关系' ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_limited_pass_stock
-- ----------------------------

-- ----------------------------
-- Table structure for tb_venue
-- ----------------------------
DROP TABLE IF EXISTS `tb_venue`;
CREATE TABLE `tb_venue`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '场馆名称',
  `category_id` bigint(20) UNSIGNED NOT NULL COMMENT '场馆类型的id',
  `images` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '场馆图片，多个图片以\',\'隔开',
  `area` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '商圈，例如陆家嘴',
  `address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '地址',
  `x` double UNSIGNED NOT NULL COMMENT '经度',
  `y` double UNSIGNED NOT NULL COMMENT '维度',
  `avg_price` bigint(10) UNSIGNED NULL DEFAULT NULL COMMENT '均价，取整数',
  `sold` int(10) UNSIGNED NOT NULL COMMENT '销量',
  `comments` int(10) UNSIGNED NOT NULL COMMENT '评论数量',
  `score` int(2) UNSIGNED NOT NULL COMMENT '评分，1~5分，乘10保存，避免小数',
  `open_hours` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '营业时间，例如 10:00-22:00',
  `cache_version` bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '缓存版本，防止旧读回写',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `foreign_key_type`(`category_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_venue
-- ----------------------------
INSERT INTO `tb_venue`
(`id`,`name`,`category_id`,`images`,`area`,`address`,`x`,`y`,`avg_price`,`sold`,`comments`,`score`,`open_hours`)
VALUES
(1,'云栖青年艺术中心',1,'','滨江','江南大道 88 号',120.174100,30.188800,68,1260,318,48,'09:00-21:30'),
(2,'星港独立音乐空间',2,'','上城','望江东路 17 号',120.191500,30.236200,128,2340,506,47,'14:00-23:30'),
(3,'拾光城市运动馆',3,'','拱墅','湖墅南路 260 号',120.158300,30.291800,45,980,175,46,'08:00-22:00'),
(4,'青岚自然教育营地',4,'','西湖','龙坞茶镇 6 号',120.065400,30.167500,188,760,143,49,'08:30-18:30'),
(5,'像素工坊创客空间',5,'','余杭','文一西路 969 号',120.023900,30.281500,88,1540,229,48,'10:00-22:00');

-- ----------------------------
-- Table structure for tb_venue_category
-- ----------------------------
DROP TABLE IF EXISTS `tb_venue_category`;
CREATE TABLE `tb_venue_category`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '类型名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '图标',
  `sort` int(3) UNSIGNED NULL DEFAULT NULL COMMENT '顺序',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 11 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_venue_category
-- ----------------------------
INSERT INTO `tb_venue_category` (`id`,`name`,`icon`,`sort`) VALUES
(1,'展览与艺术','/types/art.png',1),
(2,'演出与音乐','/types/music.png',2),
(3,'运动与赛事','/types/sport.png',3),
(4,'户外与研学','/types/outdoor.png',4),
(5,'科技与工作坊','/types/tech.png',5);

-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
CREATE TABLE `tb_user`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '手机号码',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '密码，加密存储',
  `nick_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '昵称，默认是用户id',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '人物头像',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uniqe_key_phone`(`phone`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1010 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user
-- ----------------------------

-- ----------------------------
-- Table structure for tb_user_info
-- ----------------------------
DROP TABLE IF EXISTS `tb_user_info`;
CREATE TABLE `tb_user_info`  (
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '主键，用户id',
  `city` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT '' COMMENT '城市名称',
  `introduce` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '个人介绍，不要超过128个字符',
  `fans` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '粉丝数量',
  `subscription_count` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '订阅的创作者数量',
  `gender` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '性别，0：男，1：女',
  `birthday` date NULL DEFAULT NULL COMMENT '生日',
  `credits` int(8) UNSIGNED NULL DEFAULT 0 COMMENT '积分',
  `level` tinyint(1) UNSIGNED NULL DEFAULT 0 COMMENT '会员级别，0~9级,0代表未开通会员',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_user_info
-- ----------------------------

-- ----------------------------
-- Table structure for tb_activity_pass
-- ----------------------------
DROP TABLE IF EXISTS `tb_activity_pass`;
CREATE TABLE `tb_activity_pass`  (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `venue_id` bigint(20) UNSIGNED NULL DEFAULT NULL COMMENT '场馆id',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '通行证标题',
  `sub_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '副标题',
  `rules` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '使用规则',
  `description` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '活动介绍',
  `activity_category` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '活动分类，不同于票券 type',
  `tags` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '逗号分隔标签',
  `event_start_time` datetime NULL DEFAULT NULL COMMENT '活动实际举办开始时间',
  `event_end_time` datetime NULL DEFAULT NULL COMMENT '活动实际举办结束时间',
  `pay_value` bigint(10) UNSIGNED NOT NULL COMMENT '支付金额，单位是分。例如200代表2元',
  `actual_value` bigint(10) NOT NULL COMMENT '抵扣金额，单位是分。例如200代表2元',
  `type` tinyint(1) UNSIGNED NOT NULL DEFAULT 0 COMMENT '0,普通券；1,限量预约券',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '1,上架; 2,下架; 3,过期',
  `search_version` bigint(20) UNSIGNED NOT NULL DEFAULT 1 COMMENT '搜索文档单调版本',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_activity_venue_search` (`venue_id`,`id`),
  CONSTRAINT `chk_activity_event_time` CHECK (
    (`event_start_time` IS NULL AND `event_end_time` IS NULL)
    OR (`event_start_time` IS NOT NULL AND `event_end_time` IS NOT NULL AND `event_end_time` > `event_start_time`)
  )
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_activity_pass
-- ----------------------------

-- ----------------------------
-- Table structure for tb_reservation_order
-- ----------------------------
DROP TABLE IF EXISTS `tb_reservation_order`;
CREATE TABLE `tb_reservation_order`  (
  `id` bigint(20) NOT NULL COMMENT '主键',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '下单的用户id',
  `activity_pass_id` bigint(20) UNSIGNED NOT NULL COMMENT '预约的活动通行证id',
  `pay_type` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '支付方式 1：余额支付；2：支付宝；3：微信',
  `status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款',
  `source` varchar(16) NOT NULL DEFAULT 'DIRECT' COMMENT '名额来源：DIRECT / WAITLIST',
  `promotion_round` int(11) NOT NULL DEFAULT 0 COMMENT '名额补位轮次',
  `offer_expire_time` datetime NULL DEFAULT NULL COMMENT '支付资格截止时间',
  `resource_version` bigint(20) UNSIGNED NOT NULL DEFAULT 0 COMMENT '名额归属版本',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `pay_time` timestamp NULL DEFAULT NULL COMMENT '支付时间',
  `use_time` timestamp NULL DEFAULT NULL COMMENT '核销时间',
  `refund_time` timestamp NULL DEFAULT NULL COMMENT '退款时间',
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `active_user_id` bigint(20) GENERATED ALWAYS AS (
      CASE WHEN `status` IN (1,2,3,5) THEN `user_id` ELSE NULL END
  ) STORED COMMENT '有效订单用户，用于允许取消后重新预约',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_active_user_pass`(`activity_pass_id`, `active_user_id`) USING BTREE,
  INDEX `idx_order_expire`(`status`, `offer_expire_time`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Compact;

-- ----------------------------
-- Records of tb_reservation_order
-- ----------------------------

-- ----------------------------
-- Stable FIFO waitlist for limited city activities
-- ----------------------------
DROP TABLE IF EXISTS `tb_reservation_waitlist`;
CREATE TABLE `tb_reservation_waitlist` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '候补记录主键',
  `request_id` bigint(20) NOT NULL COMMENT '入口生成的单调请求号，同时定义 FIFO 顺序；补位后复用为订单号',
  `activity_pass_id` bigint(20) UNSIGNED NOT NULL COMMENT '限量活动通行证id',
  `user_id` bigint(20) UNSIGNED NOT NULL COMMENT '候补用户id',
  `status` varchar(16) NOT NULL DEFAULT 'WAITING' COMMENT 'WAITING/OFFERED/ACCEPTED/EXPIRED/CANCELLED',
  `offered_order_id` bigint(20) NULL DEFAULT NULL COMMENT '补位生成的订单号',
  `offer_expire_time` datetime NULL DEFAULT NULL COMMENT '候补资格确认截止时间',
  `wait_expire_time` datetime NULL DEFAULT NULL COMMENT '候补最晚有效时间',
  `invalid_reason` varchar(128) NULL DEFAULT NULL COMMENT '跳过异常候补的原因',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `active_user_id` bigint(20) GENERATED ALWAYS AS (
      CASE WHEN `status` IN ('WAITING','OFFERED') THEN `user_id` ELSE NULL END
  ) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_waitlist_request` (`request_id`),
  UNIQUE KEY `uk_active_waiter` (`activity_pass_id`, `active_user_id`),
  KEY `idx_waitlist_pick` (`activity_pass_id`, `status`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限量活动候补队列';

-- ----------------------------
-- Durable reservation request fact + CREATE outbox source
-- ----------------------------
DROP TABLE IF EXISTS `tb_reservation_request`;
CREATE TABLE `tb_reservation_request` (
  `request_id` bigint(20) NOT NULL COMMENT '客户端轮询的请求号',
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `activity_pass_id` bigint(20) UNSIGNED NOT NULL,
  `accept_waitlist` tinyint(1) NOT NULL DEFAULT 1,
  `status` varchar(32) NOT NULL DEFAULT 'PROCESSING',
  `order_id` bigint(20) NULL DEFAULT NULL,
  `last_error` varchar(500) NULL DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`request_id`),
  KEY `idx_request_owner` (`user_id`,`request_id`),
  KEY `idx_request_status` (`status`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约请求事实表';

-- ----------------------------
-- Local reliable tasks (transactional outbox / Redis compensation)
-- ----------------------------
DROP TABLE IF EXISTS `tb_reliable_task`;
CREATE TABLE `tb_reliable_task` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_type` varchar(64) NOT NULL COMMENT 'CREATE_RESERVATION / ORDER_TIMEOUT / Redis补偿 / 缓存失效',
  `biz_key` varchar(128) NOT NULL COMMENT '业务幂等键',
  `payload` varchar(2048) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'PENDING',
  `retry_count` int(11) NOT NULL DEFAULT 0,
  `max_retry` int(11) NOT NULL DEFAULT 12,
  `next_retry_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `locked_by` varchar(64) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `version` bigint(20) UNSIGNED NOT NULL DEFAULT 0,
  `last_error` varchar(500) DEFAULT NULL,
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reliable_task_biz_key` (`biz_key`),
  KEY `idx_reliable_task_scan` (`status`,`next_retry_time`,`lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地可靠任务/事务发件箱';

-- Full-index rebuild uses a controlled maintenance window for activity/venue search writes.
DROP TABLE IF EXISTS `tb_search_rebuild_state`;
CREATE TABLE `tb_search_rebuild_state` (
  `id` tinyint(1) UNSIGNED NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE/RUNNING/SUCCEEDED/FAILED',
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
INSERT INTO `tb_search_rebuild_state` (`id`) VALUES (1);

SET FOREIGN_KEY_CHECKS = 1;
