-- =============================================================================
-- 银龄伴诊 —— 数据校验脚本（M1 验收用）
-- -----------------------------------------------------------------------------
-- 用法：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password \
--         --database=nianglin --table < verify_data.sql
--
-- 校验通过标准见同目录 README.md。
-- =============================================================================

SELECT '=== 1. 行数（应与 docs/db/04-种子数据说明.md 一致） ===' AS section;
SELECT 'sys_user' t, COUNT(*) c FROM sys_user
UNION ALL SELECT 'sys_login_log', COUNT(*) FROM sys_login_log
UNION ALL SELECT 'sys_dict', COUNT(*) FROM sys_dict
UNION ALL SELECT 'sys_file', COUNT(*) FROM sys_file
UNION ALL SELECT 'admin_oper_log', COUNT(*) FROM admin_oper_log
UNION ALL SELECT 'elder_profile', COUNT(*) FROM elder_profile
UNION ALL SELECT 'family_elder_relation', COUNT(*) FROM family_elder_relation
UNION ALL SELECT 'companion_audit_record', COUNT(*) FROM companion_audit_record
UNION ALL SELECT 'companion_profile', COUNT(*) FROM companion_profile
UNION ALL SELECT 'companion_order', COUNT(*) FROM companion_order
UNION ALL SELECT 'order_status_log', COUNT(*) FROM order_status_log
UNION ALL SELECT 'order_reject_log', COUNT(*) FROM order_reject_log
UNION ALL SELECT 'order_checkin', COUNT(*) FROM order_checkin
UNION ALL SELECT 'companion_track', COUNT(*) FROM companion_track
UNION ALL SELECT 'medicine_dict', COUNT(*) FROM medicine_dict
UNION ALL SELECT 'medication_plan', COUNT(*) FROM medication_plan
UNION ALL SELECT 'medication_task', COUNT(*) FROM medication_task
UNION ALL SELECT 'order_review', COUNT(*) FROM order_review
UNION ALL SELECT 'complaint', COUNT(*) FROM complaint
UNION ALL SELECT 'internal_message', COUNT(*) FROM internal_message;
SELECT 'TOTAL_TABLES' k, COUNT(*) v FROM information_schema.tables
 WHERE table_schema = DATABASE() AND table_name NOT LIKE 'flyway%';


SELECT '=== 2. 字符集与排序规则（应全为 utf8mb4 / utf8mb4_general_ci） ===' AS section;
SELECT 'db' k, default_character_set_name, default_collation_name
  FROM information_schema.schemata WHERE schema_name = 'nianglin';
SELECT 'tables' k, GROUP_CONCAT(DISTINCT table_collation) v
  FROM information_schema.tables
 WHERE table_schema = 'nianglin' AND table_name NOT LIKE 'flyway%';
SELECT 'columns' k, GROUP_CONCAT(DISTINCT collation_name) v
  FROM information_schema.columns
 WHERE table_schema = 'nianglin' AND collation_name IS NOT NULL;


SELECT '=== 3. 中文编码（「张」= E5BCA0，「郑」= E98391） ===' AS section;
SELECT id, name, HEX(LEFT(name, 1)) first_char_hex,
       CHAR_LENGTH(name) chars, LENGTH(name) bytes
  FROM elder_profile WHERE id IN (401, 421) ORDER BY id;


SELECT '=== 4. 密码与身份证密文 ===' AS section;
SELECT LEFT(password, 4) pw_prefix, COUNT(*) n FROM sys_user GROUP BY LEFT(password, 4);
SELECT '老人档案' src, SUM(id_card IS NOT NULL AND id_card NOT REGEXP '^[0-9]+$') encrypted,
       SUM(id_card IS NULL) is_null, MAX(CHAR_LENGTH(id_card)) max_len FROM elder_profile
UNION ALL
SELECT '陪诊员资料', SUM(id_card IS NOT NULL AND id_card NOT REGEXP '^[0-9]+$'),
       SUM(id_card IS NULL), MAX(CHAR_LENGTH(id_card)) FROM companion_profile;


SELECT '=== 5. Flyway 迁移历史（应 3 条且 success 全为 1） ===' AS section;
SELECT version, description, success, execution_time
  FROM flyway_schema_history ORDER BY installed_rank;


SELECT '=== 6. 订单状态机 —— 6 种状态全覆盖，无跳级（见 7 的链路校验） ===' AS section;
SELECT status, COUNT(*) c FROM companion_order GROUP BY status ORDER BY status;
SELECT '取消订单明细' AS note;
SELECT id, order_no, cancel_by, arbitrate_flag, LEFT(cancel_reason, 24) cancel_reason
  FROM companion_order WHERE status = 'CANCELLED' ORDER BY id;
SELECT '强制变更日志（is_force=1）' AS note;
SELECT id, order_id, from_status, to_status, operator_role FROM order_status_log WHERE is_force = 1;


SELECT '=== 7. 终态订单的状态链路（必须按序、不跳级、不回退） ===' AS section;
SELECT o.id, o.status,
       GROUP_CONCAT(CONCAT(IFNULL(l.from_status, 'NULL'), '>', l.to_status)
                    ORDER BY l.operate_time, l.id SEPARATOR ' | ') chain
  FROM companion_order o
  JOIN order_status_log l ON l.order_id = o.id
 WHERE o.id IN (1063, 1064)
 GROUP BY o.id, o.status;


SELECT '=== 8. 边界场景 ===' AS section;
SELECT 'elder_profile 空值' k,
       SUM(phone IS NULL) no_phone, SUM(allergy_history IS NULL) no_allergy,
       SUM(medical_history IS NULL) no_medhis, SUM(id_card IS NULL) no_idcard,
       SUM(bind_status = 'UNBOUND') unbound
  FROM elder_profile;
SELECT 'family_elder_relation 解绑' k, SUM(status = 'UNBOUND') unbound,
       SUM(unbind_time IS NOT NULL) has_unbind_time FROM family_elder_relation;
SELECT 'medication_plan 长期/跨零点' k, SUM(end_date IS NULL) long_term,
       SUM(time_points LIKE '%00:30%') midnight,
       SUM(JSON_LENGTH(time_points) = frequency) freq_match, COUNT(*) total
  FROM medication_plan;
SELECT 'order_checkin 异常打卡' k, SUM(is_abnormal = 1) abnormal,
       MIN(node_sort) min_sort, MAX(node_sort) max_sort FROM order_checkin;
SELECT 'order_review 匿名/无效/一星' k, SUM(is_anonymous = 1) anon,
       SUM(is_valid = 0) invalid, SUM(score = 1) one_star FROM order_review;
SELECT 'companion_profile 健康证过期' k, SUM(health_cert_expire < CURDATE()) expired,
       SUM(audit_status = 'REJECTED') rejected FROM companion_profile;
SELECT 'companion_order 管理员强制' k, SUM(arbitrate_flag = 1) arbitrated,
       SUM(status = 'CANCELLED') cancelled FROM companion_order;
SELECT 'sys_user 封禁' k, SUM(status = 'DISABLED') disabled FROM sys_user;
SELECT 'order_reject_log 唯一性' k, COUNT(*) total,
       COUNT(DISTINCT order_id, companion_id) distinct_pairs FROM order_reject_log;


SELECT '=== 9. 超长文本（写清字段上限） ===' AS section;
SELECT 'elder_profile.medical_history (500)' k, MAX(CHAR_LENGTH(medical_history)) max_len FROM elder_profile
UNION ALL SELECT 'companion_order.remark (500)', MAX(CHAR_LENGTH(remark)) FROM companion_order
UNION ALL SELECT 'medication_plan.remark (200)', MAX(CHAR_LENGTH(remark)) FROM medication_plan
UNION ALL SELECT 'complaint.content (1000)', MAX(CHAR_LENGTH(content)) FROM complaint
UNION ALL SELECT 'medicine_dict.precautions (500)', MAX(CHAR_LENGTH(precautions)) FROM medicine_dict
UNION ALL SELECT 'order_review.content (500)', MAX(CHAR_LENGTH(content)) FROM order_review;


SELECT '=== 10. 枚举分布 ===' AS section;
SELECT 'sys_dict' k, dict_type, COUNT(*) c FROM sys_dict GROUP BY dict_type ORDER BY dict_type;
SELECT 'sys_login_log' k, status, COUNT(*) c FROM sys_login_log GROUP BY status;
SELECT 'admin_oper_log' k, oper_type, COUNT(*) c FROM admin_oper_log GROUP BY oper_type ORDER BY oper_type;
SELECT 'complaint' k, type, COUNT(*) c FROM complaint GROUP BY type ORDER BY type;
SELECT 'internal_message' k, type, COUNT(*) c FROM internal_message GROUP BY type ORDER BY type;
SELECT 'order_checkin' k, node, COUNT(*) c FROM order_checkin GROUP BY node ORDER BY node;
SELECT 'medication_task' k, status, was_missed, COUNT(*) c FROM medication_task GROUP BY status, was_missed ORDER BY status;


SELECT '=== 11. 冗余聚合字段（companion_profile vs 明细表） ===' AS section;
-- 注意：real_reviews 只有 1~2，而 review_count 是 50+，这是**预期的**，不是数据错误。
-- review_count / order_count / accept_count 表示「平台累计值」（含种子数据窗口之前的更早历史），
-- order_review 只落了最近 31 条样本。详见 docs/db/04-种子数据说明.md 第四节。
SELECT p.user_id, p.real_name, p.score, p.review_count,
       (SELECT COUNT(*) FROM order_review r WHERE r.companion_id = p.user_id AND r.is_valid = 1) real_reviews
  FROM companion_profile p WHERE p.user_id IN (311, 312, 313) ORDER BY p.user_id;
