-- ============================================================
-- V16 · 仲裁执法开关落结构化字段（closure-2026-11 · P1-3）
-- ============================================================
-- 背景：管理员在订单仲裁时有两个执法开关：「标记退费给家属」和「对陪诊员计违规」。
--       旧实现只往备注文本里追加一句话，除此之外什么都不落库：
--         · companion_order 没有「已退费」标记列；
--         · companion_profile 没有违规计数；
--         · arbitrate_flag 这一列全仓只在**下单时写成 0**，没有任何地方写 1。
--       结果是管理员以为「系统记下了这个陪诊员违规」，实际上除了那行自由文本，
--       什么都查不到 —— 「累计违规 N 次」这类治理动作没有数据基础。
--
-- 本次变更：
--   1. arbitrate_flag 的语义定死为「本次仲裁判定对服务方不利（对陪诊员计违规）」，
--      并**补列注释**。旧注释「是否被管理员强制处理」会被后人误读成
--      「是否经过仲裁」—— 那是完全不同的语义，而且没有另一列能补救
--      （仲裁发生过这件事由 order_status_log 与订单 remark 记录）。
--   2. 新增 refund_flag：仲裁判定需退费给家属。
--      退费只是**线下执行的记账线索**（一期不做在线支付，见计划书合规与隐私说明）。
--
-- ⚠️ 不新增「违规次数」计数列：违规次数由 companion_order 聚合得出
--    （COUNT(*) WHERE companion_id = ? AND arbitrate_flag = 1 AND deleted = 0），
--    避免计数列与订单行双写不一致 —— 双写一旦漏一次，治理动作就会建立在错数上。

-- 1. arbitrate_flag 补注释（语义定死）
ALTER TABLE `companion_order`
    MODIFY COLUMN `arbitrate_flag` TINYINT NOT NULL DEFAULT 0
        COMMENT '仲裁判定对服务方不利（对陪诊员计违规）：0-否 1-是（注意：不是「是否经过仲裁」）';

-- 2. 新增 refund_flag
ALTER TABLE `companion_order`
    ADD COLUMN `refund_flag` TINYINT NOT NULL DEFAULT 0
        COMMENT '仲裁判定需退费给家属：0-否 1-是（线下执行，一期不做在线支付）'
        AFTER `arbitrate_flag`;
