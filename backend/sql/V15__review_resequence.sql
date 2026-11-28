-- ============================================================
-- V15 · 评价可重评（closure-2026-11 · P1-2）
-- ============================================================
-- 背景：管理员把一条恶意差评裁定为「无效」之后，系统里发生的事只有
--       order_review.is_valid = 0。于是这一单的真实状态是：
--         · 订单停在终态 REVIEWED，不能回退；
--         · 该单不能重新评价（唯一键 uk_order_id(order_id) 拦住）；
--         · 评分聚合不再计入这条；
--         · 但评价率统计仍把它算作「已评价」。
--       结果是一单恶意差评被「纠正」后的真实效果是「这单等于没人评过」，
--       而系统两边都记录成「已评价」。
--
-- 本次变更：
--   1. 加列 review_seq（重评序号），历史数据全为 1；
--   2. 唯一键从 (order_id) 改为 (order_id, review_seq)：
--      允许同一订单存在「一条无效 + 一条有效」，但仍限制同一 (order_id, seq) 唯一。
--      应用层只允许重评一次（review_seq 最大 2），第三次提交回 6002。
--
-- ⚠️ 破坏性变更：drop 旧唯一索引。执行前必须先跑脏数据检查：
--      SELECT order_id, COUNT(*) FROM order_review WHERE deleted = 0
--        GROUP BY order_id HAVING COUNT(*) > 1;
--    结果非空说明库里已有同订单多条评价，新唯一键会建不起来 —— 需人工处理。
--
-- ⚠️ 索引名以本仓库迁移脚本为准：旧唯一键实际叫 uk_order_id（V1 建表时命名），
--    而不是「uk_order_review」。

-- 1. 加列（历史数据默认 1）
ALTER TABLE `order_review`
    ADD COLUMN `review_seq` TINYINT NOT NULL DEFAULT 1
        COMMENT '重评序号：1-首次评价 2-裁定无效后重新评价（closure-2026-11 · P1-2）'
        AFTER `is_valid`;

-- 2. 唯一键换成 (order_id, review_seq)
ALTER TABLE `order_review` DROP INDEX `uk_order_id`;
ALTER TABLE `order_review`
    ADD UNIQUE KEY `uk_order_id_seq` (`order_id`, `review_seq`);
