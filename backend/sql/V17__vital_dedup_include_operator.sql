-- =============================================================================
-- V17 · 体征去重键加入录入人（收口 B · P2-2）
-- 版本号说明：父指令原本分配 V16，但 A 批先占 V16（arbitrate_flags，见 flyway_schema_history），
-- 故顺延为 V17；未修改任何规格文件。
--
-- 背景：V9 的 dedup_key = elder_id + vital_type + minute，不含录入人。
--       陪诊员与家属在同一分钟各录一次同类体征时，第二条必然撞 uk_vital_dedup 返回 2008，
--       而当事人并不知道对方也测过。两台血压计本来就会给出两个值，都应被保留。
--
-- 口径：同一录入人 + 同一老人 + 同一类型 + 同一分钟才判重。
--       服务层不改 —— 它仍然只捕获 DuplicateKeyException 转 2008。
--
-- 字段差异报备：规格写的是 record_by，实际 DDL（V9）里的列名是 operator_user_id，
--               本迁移按仓库现实字段名实现，语义一致（都是「录入人用户 ID」）。
--
-- 历史数据：旧键更严格（不含录入人），旧键能存在的数据在新键下必然仍唯一，
--           因此改键不会因历史数据失败；operator_user_id 在 V9 即为 NOT NULL，无需补值。
--
-- 回滚（仓库无独立 rollback 目录，按本文件手工执行）：
--   ALTER TABLE vital_sign_record DROP INDEX uk_vital_dedup,
--     MODIFY COLUMN dedup_key VARCHAR(80) GENERATED ALWAYS AS (
--       CASE WHEN deleted = 0
--            THEN CONCAT(elder_id, ':', vital_type, ':', DATE_FORMAT(measured_at, '%Y%m%d%H%i'))
--            ELSE NULL END
--     ) STORED COMMENT '有效记录去重键；逻辑删除后自动置 NULL',
--     ADD UNIQUE KEY uk_vital_dedup (dedup_key);
--
-- @since 收口 B · P2-2
-- =============================================================================

ALTER TABLE `vital_sign_record`
    DROP INDEX `uk_vital_dedup`,
    MODIFY COLUMN `dedup_key` VARCHAR(120) GENERATED ALWAYS AS (
        CASE WHEN `deleted` = 0
             THEN CONCAT(`elder_id`, ':', `vital_type`, ':',
                         DATE_FORMAT(`measured_at`, '%Y%m%d%H%i'), ':', `operator_user_id`)
             ELSE NULL
        END
    ) STORED COMMENT '有效记录去重键：老人+类型+分钟+录入人；逻辑删除后自动置 NULL',
    ADD UNIQUE KEY `uk_vital_dedup` (`dedup_key`);
