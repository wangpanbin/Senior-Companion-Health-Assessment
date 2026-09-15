-- =============================================================================
-- 银龄伴诊 —— 索引命中校验（M1 验收用）
-- -----------------------------------------------------------------------------
-- 用法：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password \
--         --database=nianglin --table < explain_index.sql
--
-- 通过标准：每条 EXPLAIN 的 type 不为 ALL，且 Extra 中不含 Using filesort。
-- 说明见 docs/db/03-索引设计与EXPLAIN.md。
-- =============================================================================

SELECT '=== 1. 待接单订单按时间倒序（应 idx_status_create，Backward index scan） ===' AS section;
EXPLAIN SELECT id, order_no, status, create_time FROM companion_order
 WHERE status = 'PENDING' ORDER BY create_time DESC LIMIT 10;

SELECT '=== 2. 老人某日待服药任务（应 idx_elder_date_status，三列全用） ===' AS section;
EXPLAIN SELECT id, medicine_name, plan_time FROM medication_task
 WHERE elder_id = 401 AND plan_date = '2026-09-10' AND status = 'PENDING';

SELECT '=== 3. 可接单陪诊员列表（应 idx_audit_work） ===' AS section;
EXPLAIN SELECT id, real_name, score FROM companion_profile
 WHERE audit_status = 'APPROVED' AND work_status = 'AVAILABLE';

SELECT '=== 4. 订单大厅：待接单且就诊时间未过（应 idx_status_visit，无 filesort） ===' AS section;
EXPLAIN SELECT id, order_no, visit_time FROM companion_order
 WHERE status = 'PENDING' AND visit_time > NOW() ORDER BY visit_time ASC LIMIT 20;

SELECT '=== 5. 未读站内信数（应 idx_receiver_read，Using index 覆盖索引） ===' AS section;
EXPLAIN SELECT COUNT(*) FROM internal_message
 WHERE receiver_id = 101 AND is_read = 0;

SELECT '=== 6. 漏服扫描（应 idx_status_plan_time，range） ===' AS section;
EXPLAIN SELECT id, plan_id FROM medication_task
 WHERE status = 'PENDING' AND plan_time < NOW();

SELECT '=== 7. 陪诊员评分聚合（应 idx_companion_valid） ===' AS section;
EXPLAIN SELECT companion_id, ROUND(AVG(score), 2) FROM order_review
 WHERE companion_id = 301 AND is_valid = 1 GROUP BY companion_id;

-- -----------------------------------------------------------------------------
-- 已知未走索引的查询（有意不优化，详见 docs/db/03-索引设计与EXPLAIN.md"已知未走索引的查询"）
-- 「按 cancel_time 排序筛选 status='CANCELLED'」会出现 Using filesort。
-- 现状约定：订单终态列表统一按 create_time 排序，复用 idx_status_create。
-- 若 M9 管理端确实需要按取消时间排序，再加 KEY idx_status_cancel(status, cancel_time)。
-- -----------------------------------------------------------------------------
SELECT '=== 附：按 cancel_time 排序（预期出现 Using filesort，属已知项） ===' AS section;
EXPLAIN SELECT id, order_no, cancel_reason FROM companion_order
 WHERE status = 'CANCELLED' ORDER BY cancel_time DESC LIMIT 10;
