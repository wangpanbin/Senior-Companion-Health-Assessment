-- =============================================================================
-- 银龄伴诊 —— 运营参数种子（V4 · P2-3）
-- -----------------------------------------------------------------------------
-- 版本      : V10
-- 覆盖模块  : P2-3 运营参数可调化
-- 变更内容  : 在 sys_dict 下新增 OPS_PARAM 类型，写入 4 个可在线调整的运营阈值。
--             不改表结构；与既有下拉字典（HOSPITAL / DEPARTMENT / ...）按 dict_type 隔离。
--
-- 生效方式  : OperationParamService 进程内缓存（默认 TTL 60s）；
--             管理端修改成功后事务提交即刷新本实例缓存，无需重启或发版。
--             读取失败时回落 application.yml 默认值（60 / 10 / 10000 / 3）。
--
-- 幂等性    : ON DUPLICATE KEY UPDATE 只刷新名称/说明/排序，
--             不覆盖 dict_value 与 status，避免手工重跑把运营已调好的值重置回默认。
-- =============================================================================

SET NAMES utf8mb4;

INSERT INTO `sys_dict` (`id`, `dict_type`, `dict_code`, `dict_label`, `dict_value`, `sort_no`, `status`, `remark`) VALUES
  (44801, 'OPS_PARAM', 'medication.missed-threshold-minutes', '漏服判定阈值', '60', 1, 1,
   '服药计划时间超过该分钟数仍未确认即判定漏服；可调范围 [1, 1440] 分钟'),
  (44802, 'OPS_PARAM', 'fulfillment.urge-cooldown-minutes', '催单最小间隔', '10', 2, 1,
   '家属两次催单之间的最小间隔，防止把陪诊员刷到关接单；可调范围 [1, 1440] 分钟'),
  (44803, 'OPS_PARAM', 'export.max-rows', '单次导出条数上限', '10000', 3, 1,
   '单次导出的最大行数，超出时提示缩小筛选范围；可调范围 [100, 50000] 行'),
  (44804, 'OPS_PARAM', 'sms.frequency.missed-dose-daily-cap', '漏服短信单用户日上限', '3', 4, 1,
   '同一用户单日可收到的漏服短信上限，站内信不受此限；可调范围 [1, 100] 条')
ON DUPLICATE KEY UPDATE
  `dict_label` = VALUES(`dict_label`),
  `sort_no`    = VALUES(`sort_no`),
  `remark`     = VALUES(`remark`);
