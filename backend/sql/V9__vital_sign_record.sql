-- =============================================================================
-- V9 · 体征记录（V4 · P2-2）
--
-- 一张表装三类体征（血压 / 血糖 / 体重），按 vital_type 区分。
--
-- 三条硬约束：
--   1. 只存测量数值，不存任何分级、区间或解释性字段；
--   2. dedup_key 为「有效记录」唯一键：老人 + 类型 + 分钟；
--      逻辑删除后生成列自动变 NULL，释放唯一索引槽位，支持撤回后重录；
--   3. 不提供自由文本备注，杜绝在数据层写入判断性描述。
--
-- 回滚（仓库无独立 rollback 目录，按本文件头部注释手工执行）：
--   DROP TABLE IF EXISTS `vital_sign_record`;
--
-- 回滚影响面：仅本表；不涉及 elder_profile / companion_order 等既有表与索引。
-- 回滚即丢失全部体征记录且不可恢复，仅限开发 / 演示环境；无其它迁移依赖本表。
--
-- @since V4 · P2-2
-- =============================================================================

CREATE TABLE IF NOT EXISTS `vital_sign_record` (
  `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录 ID',
  `elder_id`         BIGINT      NOT NULL                COMMENT '老人档案 ID',
  `vital_type`       VARCHAR(24) NOT NULL                COMMENT '体征类型：BLOOD_PRESSURE-血压 / BLOOD_GLUCOSE-血糖 / WEIGHT-体重',
  `measured_at`      DATETIME    NOT NULL                COMMENT '测量时间（分钟精度，秒固定 00）',
  `systolic`         SMALLINT             DEFAULT NULL   COMMENT '收缩压（mmHg），仅 BLOOD_PRESSURE',
  `diastolic`        SMALLINT             DEFAULT NULL   COMMENT '舒张压（mmHg），仅 BLOOD_PRESSURE',
  `glucose_value`    DECIMAL(4,1)         DEFAULT NULL   COMMENT '血糖值（mmol/L），仅 BLOOD_GLUCOSE',
  `glucose_context`  VARCHAR(24)          DEFAULT NULL   COMMENT '血糖场景：FASTING-空腹 / POSTPRANDIAL-餐后2小时 / RANDOM-随机',
  `weight_kg`        DECIMAL(4,1)         DEFAULT NULL   COMMENT '体重（kg），仅 WEIGHT',
  `source_type`      VARCHAR(24) NOT NULL                COMMENT '录入来源：FAMILY_MANUAL-家属代录 / COMPANION_SERVICE-陪诊员服务时代录',
  `operator_user_id` BIGINT      NOT NULL                COMMENT '录入人用户 ID（家属或陪诊员）',
  `order_id`         BIGINT               DEFAULT NULL   COMMENT '陪诊订单 ID；仅陪诊员代录时有值',
  `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT     NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已撤回',
  `dedup_key`        VARCHAR(80) GENERATED ALWAYS AS (
      CASE WHEN `deleted` = 0
           THEN CONCAT(`elder_id`, ':', `vital_type`, ':', DATE_FORMAT(`measured_at`, '%Y%m%d%H%i'))
           ELSE NULL
      END
  ) STORED COMMENT '有效记录去重键；逻辑删除后自动置 NULL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vital_dedup` (`dedup_key`),
  KEY `idx_elder_type_time` (`elder_id`, `vital_type`, `measured_at`),
  KEY `idx_elder_time` (`elder_id`, `measured_at`),
  KEY `idx_order_id` (`order_id`),
  KEY `idx_operator` (`operator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='体征记录表（只记录测量数值）';
