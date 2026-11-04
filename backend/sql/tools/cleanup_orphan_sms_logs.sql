-- -----------------------------------------------------------------------------
-- cleanup_orphan_sms_logs.sql —— 一次性清理孤儿短信回执（V4 缺陷修复）
--
-- 背景：用药任务被删除 / 停用后，其 `sms_send_log` 回执仍可能是 PENDING，
-- 而旧版频控按「行数」统计，孤儿行会继续占家属当日额度，导致后续提醒被静默跳过。
-- 新代码已在删除路径作废回执 + 频控只计有效任务；本脚本只用于**存量历史数据**。
--
-- 适用范围：`scene='missed-dose'` 且 `biz_id` 指向已不存在（含逻辑删除）的
--           `medication_task`，状态为 PENDING / FAILED / DEAD 的回执。
--          `SENT` 行不动：确实发送过，保留审计事实。
--
-- 幂等：脚本可重复执行。已作废的行不再满足 `status IN (...)`，第二次执行影响 0 行。
-- 执行方式：手工在目标库执行（本文件不参与 Flyway，不会被自动迁移）。
-- -----------------------------------------------------------------------------

-- 1) dry-run：先确认待清理行数与样本，再决定是否执行第 2 段
SELECT s.`id`,
       s.`biz_id`,
       s.`user_id`,
       s.`status`,
       s.`create_time`,
       s.`last_error`
FROM `sms_send_log` s
WHERE s.`scene` = 'missed-dose'
  AND s.`status` IN ('PENDING', 'FAILED', 'DEAD')
  AND NOT EXISTS (
      SELECT 1
      FROM `medication_task` t
      WHERE t.`id` = s.`biz_id`
        AND t.`deleted` = 0
  )
ORDER BY s.`id`;

-- 2) 执行作废（与上面 SELECT 同一条件；可重复执行）
UPDATE `sms_send_log` s
SET s.`status` = 'VOIDED',
    s.`last_error` = 'voided:task-not-found',
    s.`next_retry_at` = NULL,
    s.`update_time` = NOW()
WHERE s.`scene` = 'missed-dose'
  AND s.`status` IN ('PENDING', 'FAILED', 'DEAD')
  AND NOT EXISTS (
      SELECT 1
      FROM `medication_task` t
      WHERE t.`id` = s.`biz_id`
        AND t.`deleted` = 0
  );

-- 3) 复核：应返回 0
SELECT COUNT(*) AS `remaining_orphan`
FROM `sms_send_log` s
WHERE s.`scene` = 'missed-dose'
  AND s.`status` IN ('PENDING', 'FAILED', 'DEAD')
  AND NOT EXISTS (
      SELECT 1
      FROM `medication_task` t
      WHERE t.`id` = s.`biz_id`
        AND t.`deleted` = 0
  );
