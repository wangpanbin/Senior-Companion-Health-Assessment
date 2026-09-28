-- =============================================================================
-- V4 · order_fee_item —— 订单费用明细表（代垫/服务费分账）
-- 依据：docs/adr/0009-order-fee-item-model.md（Accepted）
-- ⚠️ V1~V3 已被 Flyway 执行（validate-on-migrate 校验 checksum），本脚本为纯增量，
--    不得以任何形式改动 V1/V2/V3。
-- 口径：明细是真源；companion_order.actual_fee 由 SUM(amount) 派生，不一致以明细为准。
-- =============================================================================

CREATE TABLE IF NOT EXISTS `order_fee_item` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '明细 ID',
  `order_id`    BIGINT        NOT NULL                COMMENT '关联 companion_order.id',
  `item_type`   VARCHAR(16)   NOT NULL                COMMENT '费用类型：ADVANCE-代垫（家属还给陪诊员）/ SERVICE-服务费（平台结算或家属直付）',
  `item_name`   VARCHAR(64)   NOT NULL                COMMENT '项目名，如「心内科挂号费」「陪诊服务费」',
  `amount`      DECIMAL(10,2) NOT NULL                COMMENT '金额（元），两位小数',
  `occurred_at` DATETIME      NOT NULL                COMMENT '费用发生时间',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  -- 按订单取明细 + 按类型分账（家属看代垫、平台看服务费）
  KEY `idx_order_type` (`order_id`, `item_type`),
  KEY `idx_occurred_at` (`occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单费用明细表（代垫/服务费分账，ADR-0009）';
