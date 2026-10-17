-- =============================================================================
-- V6 · 履约保障（V3.1 · T0~T7）
-- 依据：docs/spec/V3.1-fulfillment-spec.md §3 · 仓库根目录 迭代计划.md §20/§51
-- ⚠️ V1~V5 已被 Flyway 执行（validate-on-migrate 校验 checksum），本脚本为纯增量，
--    不得以任何形式改动 V1/V2/V3/V4/V5。
--
-- 口径（本版最关键的一条）：
--   履约风险是**独立字段**，不是订单状态机的新状态。
--   companion_order.status 继续只由 OrderStatus 状态机驱动；
--   companion_order.fulfillment_risk 只在 status='PENDING' 时有意义，
--   离开 PENDING 即回落 NORMAL。两者永不互相推导（docs/adr/0010）。
--
--   事件表**不复用** order_status_log：后者语义是 from_status→to_status 的状态流转，
--   而风险升级不改状态，塞进去会让状态机唯一入口的日志失去可读性。
-- =============================================================================

-- ----------------------------------------------------------------------------- 
-- 1. companion_order 增三列
--    用 information_schema 守卫，保证脚本重复执行不报错。
--    （MySQL 的 ADD COLUMN IF NOT EXISTS 要 8.0.29+，这里不依赖该版本特性）
-- ----------------------------------------------------------------------------- 
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `companion_order` ADD COLUMN `fulfillment_risk` VARCHAR(20) NOT NULL DEFAULT ''NORMAL'' COMMENT ''履约风险：NORMAL-正常等待 / WARNING-等待较久 / URGENT-履约风险较高 / MANUAL_REQUIRED-需人工介入；仅 PENDING 时有意义''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'companion_order' AND COLUMN_NAME = 'fulfillment_risk'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `companion_order` ADD COLUMN `urge_count` INT NOT NULL DEFAULT 0 COMMENT ''累计催单次数（V3.1）''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'companion_order' AND COLUMN_NAME = 'urge_count'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `companion_order` ADD COLUMN `last_urge_at` DATETIME DEFAULT NULL COMMENT ''最近一次催单时间；NULL=从未催单，用于冷却期判定''',
    'DO 0')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'companion_order' AND COLUMN_NAME = 'last_urge_at'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 风险扫描候选集 + 风险中心列表都走这一个索引：
-- 先按风险等级等值过滤，再按状态过滤，最后按下单时间排等待时长。
SET @ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `companion_order` ADD INDEX `idx_risk_status_time` (`fulfillment_risk`, `status`, `create_time`)',
    'DO 0')
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'companion_order' AND INDEX_NAME = 'idx_risk_status_time'
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------- 
-- 2. order_fulfillment_event —— 履约事件流水（只增不改）
--    与 order_status_log 同风格、不同语义：那张表记「状态从哪到哪」，
--    这张表记「履约过程中发生了什么」。
-- ----------------------------------------------------------------------------- 
CREATE TABLE IF NOT EXISTS `order_fulfillment_event` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '事件 ID',
  `order_id`       BIGINT        NOT NULL                COMMENT '关联 companion_order.id',
  `event_type`     VARCHAR(30)   NOT NULL                COMMENT '事件类型：ORDER_CREATED / MATCHING_STARTED / FULFILLMENT_WARNING / USER_URGED / FULFILLMENT_URGENT / MANUAL_REQUIRED / ADMIN_COORDINATED / ORDER_REASSIGNED',
  `from_risk`      VARCHAR(20)            DEFAULT NULL   COMMENT '变更前风险等级；首次事件为 NULL',
  `to_risk`        VARCHAR(20)            DEFAULT NULL   COMMENT '变更后风险等级；非风险类事件可与 from_risk 相同',
  `trigger_source` VARCHAR(20)   NOT NULL                COMMENT '触发来源：USER-用户操作 / ADMIN-管理员 / SYSTEM-系统自动',
  `operator_id`    BIGINT                 DEFAULT NULL   COMMENT '操作人用户 ID；系统自动触发为 NULL',
  `operator_role`  VARCHAR(20)            DEFAULT NULL   COMMENT '操作人角色快照：FAMILY / COMPANION / ADMIN / SYSTEM',
  `remark`         VARCHAR(500)           DEFAULT NULL   COMMENT '备注（如管理员协调说明；写入前已过合规词表）',
  `operate_time`   DATETIME      NOT NULL                COMMENT '发生时间',
  `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`        TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  -- 订单履约详情页按订单拉事件流并按时间升序
  KEY `idx_order_time` (`order_id`, `operate_time`),
  -- 按事件类型做运营统计（如「本月催单次数」）
  KEY `idx_event_time` (`event_type`, `operate_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单履约事件流水表（只增不改，与 order_status_log 语义不同）';
