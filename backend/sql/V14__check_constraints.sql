-- =============================================================================
-- V14 · 枚举 / 金额列的库级 CHECK 约束（最终复盘 DB-01）
--
-- 背景：V1–V13 全库 **0 个 CHECK**，`status` / `score` / `amount` / `bind_status`
--   全靠应用层枚举与 DTO 校验。任何一次手工修数、脚本导入、或将来新增的写入路径
--   （定时任务、数据迁移、运维 SQL）都可能写入枚举外的值，之后所有按枚举分支的代码
--   会**静默走错分支** —— 例如关系状态既不是 BOUND 也不是 UNBOUND，
--   `countBoundRelations` 就数不到它，解绑判定随之失真。
--
-- 口径（刻意只补一小撮，不一次性全表加）：
--   ① 只覆盖「被代码按枚举分支处理」的状态列 + 「参与金额计算」的金额列；
--   ② 其余自由文本 / 可扩展字典列不加 CHECK —— 加错了会挡住正常的业务演进；
--   ③ 约束名统一 `ck_<表>_<列>`，命名可预测，回滚时不用查 information_schema。
--
-- ⚠️ MySQL 8.0.16 起 CHECK 才真正生效（低版本只解析不执行），本项目基线 MySQL 8.0。
-- ⚠️ `ALTER TABLE ... ADD CONSTRAINT ... CHECK` 会**校验存量数据**：若库里有历史脏数据
--    （枚举外的值 / 负金额），本迁移会直接失败并指出是哪张表 —— 这是刻意的：
--    宁可让迁移红，也不要让约束「加了但没生效」。修数据用：
--      SELECT DISTINCT status FROM <表>;      -- 找出越界值
--      UPDATE <表> SET status = <合法值> WHERE status = <越界值>;
--    然后重跑迁移。
-- =============================================================================

-- ---------- 账号 ----------
ALTER TABLE `sys_user`
  ADD CONSTRAINT `ck_sys_user_status` CHECK (`status` IN ('NORMAL', 'DISABLED'));

-- ---------- 老人档案 / 绑定关系 ----------
ALTER TABLE `elder_profile`
  ADD CONSTRAINT `ck_elder_profile_bind_status` CHECK (`bind_status` IN ('BOUND', 'UNBOUND'));

ALTER TABLE `family_elder_relation`
  ADD CONSTRAINT `ck_family_elder_relation_status` CHECK (`status` IN ('BOUND', 'UNBOUND'));

-- ---------- 订单 ----------
ALTER TABLE `companion_order`
  ADD CONSTRAINT `ck_companion_order_status`
    CHECK (`status` IN ('PENDING', 'ACCEPTED', 'IN_SERVICE', 'COMPLETED', 'REVIEWED', 'CANCELLED'));

ALTER TABLE `companion_order`
  ADD CONSTRAINT `ck_companion_order_payment_status` CHECK (`payment_status` IN ('UNPAID', 'SETTLED'));

-- 金额：服务费不可为负；actual_fee 允许为 NULL（ADR-0009 的历史订单兜底口径），但不允许为负
ALTER TABLE `companion_order`
  ADD CONSTRAINT `ck_companion_order_fee_non_negative` CHECK (`fee` >= 0);

ALTER TABLE `companion_order`
  ADD CONSTRAINT `ck_companion_order_actual_fee_non_negative`
    CHECK (`actual_fee` IS NULL OR `actual_fee` >= 0);

-- ---------- 费用明细（ADR-0009：明细是真源）----------
ALTER TABLE `order_fee_item`
  ADD CONSTRAINT `ck_order_fee_item_type` CHECK (`item_type` IN ('ADVANCE', 'SERVICE'));

ALTER TABLE `order_fee_item`
  ADD CONSTRAINT `ck_order_fee_item_amount_non_negative` CHECK (`amount` >= 0);

-- ---------- 用药任务 ----------
ALTER TABLE `medication_task`
  ADD CONSTRAINT `ck_medication_task_status` CHECK (`status` IN ('PENDING', 'TAKEN', 'MISSED'));

-- ---------- 投诉 / 产品反馈（同一套四态，只可正向流转）----------
ALTER TABLE `complaint`
  ADD CONSTRAINT `ck_complaint_status`
    CHECK (`status` IN ('PENDING', 'PROCESSING', 'RESOLVED', 'REJECTED'));

ALTER TABLE `product_feedback`
  ADD CONSTRAINT `ck_product_feedback_status`
    CHECK (`status` IN ('PENDING', 'PROCESSING', 'RESOLVED', 'REJECTED'));
