-- =============================================================================
-- V8 · 短信站外提醒编排（V4 · P1-3）
--
-- 两张表分别解决两个问题：
--   · notification_preference —— 家属「愿不愿意收站外短信、夜间要不要静默」；
--   · sms_send_log —— 发送回执 + 单日幂等 + 死信（失败可重试、耗尽进死信）。
--
-- 三条硬约束：
--   1. phone_masked 只存脱敏号（138****8888），**禁止**存完整手机号；
--   2. dedup_key 唯一索引是「提醒任务 + 渠道 + 日期一天只发一次」的最终防线，
--      业务层的先查后插只是减少无谓冲突，不能替代这条索引；
--   3. status + next_retry_at 建查询索引，重试扫描任务只按该组合取待发送行。
--
-- 回滚（仓库无独立 rollback 目录，按本文件头部注释手工执行）：
--   DROP TABLE IF EXISTS `sms_send_log`;
--   DROP TABLE IF EXISTS `notification_preference`;
--
-- @since V4 · P1-3
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. notification_preference —— 通知偏好（当前只服务 FAMILY）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `notification_preference` (
  `id`                BIGINT   NOT NULL AUTO_INCREMENT COMMENT '偏好 ID',
  `user_id`           BIGINT   NOT NULL                COMMENT '用户 ID（当前仅家属使用）',
  `sms_missed_enabled` TINYINT NOT NULL DEFAULT 1      COMMENT '漏服站外短信开关：1-开 0-关（关闭后仍保留站内信）',
  `quiet_hours_enabled` TINYINT NOT NULL DEFAULT 1     COMMENT '静默时段开关：1-开 0-关（开启后 22:00–08:00 不发送，顺延到 08:00）',
  `create_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`           TINYINT  NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通知偏好表（V4 P1-3 站外短信开关与静默时段）';

-- -----------------------------------------------------------------------------
-- 2. sms_send_log —— 短信发送回执 / 幂等 / 死信
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sms_send_log` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '回执 ID',
  `biz_type`      VARCHAR(32)  NOT NULL                COMMENT '业务类型，如 MEDICATION_MISSED',
  `biz_id`        BIGINT       NOT NULL                COMMENT '业务主键（漏服提醒为 medication_task.id）',
  `user_id`       BIGINT       NOT NULL                COMMENT '接收人用户 ID',
  `phone_masked`  VARCHAR(32)  NOT NULL                COMMENT '脱敏手机号（只存 138****8888，禁止存完整号码）',
  `scene`         VARCHAR(32)  NOT NULL                COMMENT '发送场景：verification / missed-dose',
  `template_code` VARCHAR(64)  NOT NULL                COMMENT '模板 Code 快照（便于排查「用了哪个模板」）',
  `status`        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待发送 / SENT-已发送 / FAILED-永久失败不重试 / DEAD-死信（重试耗尽或合规拦截）',
  `attempt_count` INT          NOT NULL DEFAULT 0      COMMENT '已尝试发送次数',
  `last_error`    VARCHAR(500)          DEFAULT NULL   COMMENT '最后一次失败原因（截断 500 字，禁止写入完整手机号）',
  `dedup_key`     VARCHAR(160) NOT NULL                COMMENT '幂等键：提醒任务 + 渠道 + 日期，唯一索引兜底',
  `scheduled_at`  DATETIME              DEFAULT NULL   COMMENT '计划发送时间（入队时间）',
  `sent_at`       DATETIME              DEFAULT NULL   COMMENT '实际发送成功时间',
  `next_retry_at` DATETIME              DEFAULT NULL   COMMENT '下次重试时间；静默时段内顺延到次日 08:00',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dedup_key` (`dedup_key`),
  KEY `idx_status_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='短信发送回执表（幂等 + 指数退避 + 死信，V4 P1-3）';
