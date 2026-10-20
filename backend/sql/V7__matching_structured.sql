-- =============================================================================
-- V7 · 智能调度（V3.4）· 结构化改造（expand）
--
-- 为什么需要这一步：
--   迭代计划 §37 的匹配模型要求「区域 / 时间 / 距离」三个维度，
--   但今天的数据基础撑不起其中任何一个：
--     · 陪诊员 service_area / available_time 是自由文本 → 只能做包含式匹配
--     · 订单侧根本没有区域字段
--     · 陪诊员档案没有坐标 → 「距离 20%」完全算不了
--   ADR-0013 原文即写着「一期无坐标、无真实结算」。
--
-- 所以本迁移先把「可匹配」变成「可计算的列」：
--   1. companion_profile   增坐标 + 服务区域码
--   2. companion_available_slot 新表：可服务时段（用表不用 JSON 列 —— 时段要参与筛选，必须可索引可 JOIN）
--   3. companion_order     增服务区域码
--   4. backfill：自由文本 → 区域码（映射不上的留空 = 该维度按「无数据」处理，不猜）
--
-- 契约（expand–contract）：
--   · expand 阶段：**全部可空**，老数据不受影响
--   · 读路径：**优先用结构化列；结构化列缺失时回退自由文本包含式匹配**（与 V3.1 大厅同口径）
--   · contract 阶段：**一期不删旧列** —— 删列不可逆，且自由文本里还有历史语义
--
-- 区域码格式：<CITY>_<DISTRICT>（全大写拼音）
--   城市前缀（_ 之前）就是「区」这一层：
--     区域码相等        → 1.0
--     同市不同区        → 0.5
--     跨市              → 0
--   映射表由 backend/sql/tools/gen_region_codes.py 从
--   backend/sql/tools/region_code_mapping.json 生成，本文件里的 CASE 与之逐字一致
--   （由该脚本的 --check 模式机械核对）。
--
-- @since V3.4
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. companion_profile —— 陪诊员坐标 + 服务区域码
-- -----------------------------------------------------------------------------
ALTER TABLE `companion_profile`
  ADD COLUMN `longitude`          DECIMAL(10,7) NULL COMMENT '陪诊员档案经度（一期自填或管理员代录；NULL = 无坐标，距离维度记 0 且不推断）',
  ADD COLUMN `latitude`           DECIMAL(10,7) NULL COMMENT '陪诊员档案纬度',
  ADD COLUMN `service_region_code` VARCHAR(32)  NULL COMMENT '服务区域码（<CITY>_<DISTRICT>）；自由文本 service_area 保留并作为回退匹配依据';

-- -----------------------------------------------------------------------------
-- 2. companion_available_slot —— 可服务时段
--
-- 为什么用表不用 JSON 列：时段要参与筛选（订单就诊时间落在哪个时段里），
-- 必须可索引、可 JOIN。JSON 列做不到「按星期 + 时刻范围」的高效筛选。
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `companion_available_slot` (
  `id`           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '时段 ID',
  `companion_id` BIGINT   NOT NULL                COMMENT '陪诊员用户 ID',
  `weekday`      TINYINT  NOT NULL                COMMENT '周内第几天：1=周一 … 7=周日（与 Java WeekFields.ISO 对齐）',
  `start_time`   TIME     NOT NULL                COMMENT '可服务起始时刻',
  `end_time`     TIME     NOT NULL                COMMENT '可服务结束时刻',
  `create_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`      TINYINT  NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_companion_weekday` (`companion_id`, `weekday`, `deleted`),
  KEY `idx_companion_id` (`companion_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='陪诊员可服务时段（V3.4 结构化改造）';

-- -----------------------------------------------------------------------------
-- 3. companion_order —— 订单服务区域码
-- -----------------------------------------------------------------------------
ALTER TABLE `companion_order`
  ADD COLUMN `service_region_code` VARCHAR(32) NULL COMMENT '订单服务区域码（<CITY>_<DISTRICT>）；NULL = 未映射，该维度按「无数据」处理';

-- -----------------------------------------------------------------------------
-- 4. backfill —— 自由文本 → 区域码
--
-- 只填**能确定**的：文本里出现某个区县名就填对应码。
-- 映射不上的**留空**（NULL）—— 留空意味着「这个维度没有数据」，
-- 匹配时该维度记 0 并标注，**绝不猜**。
-- -----------------------------------------------------------------------------

-- 4.1 陪诊员服务区域（service_area 形如「海口市美兰区」）
UPDATE `companion_profile` SET `service_region_code` = 'HAIKOU_MEILAN'  WHERE `service_region_code` IS NULL AND `service_area` LIKE '%美兰区%';
UPDATE `companion_profile` SET `service_region_code` = 'HAIKOU_LONGHUA'  WHERE `service_region_code` IS NULL AND `service_area` LIKE '%龙华区%';
UPDATE `companion_profile` SET `service_region_code` = 'HAIKOU_XIUYING'  WHERE `service_region_code` IS NULL AND `service_area` LIKE '%秀英区%';
UPDATE `companion_profile` SET `service_region_code` = 'HAIKOU_QONGSHAN'  WHERE `service_region_code` IS NULL AND `service_area` LIKE '%琼山区%';
UPDATE `companion_profile` SET `service_region_code` = 'SANYA_JIYANG'  WHERE `service_region_code` IS NULL AND `service_area` LIKE '%吉阳区%';
UPDATE `companion_profile` SET `service_region_code` = 'SANYA_TIANYA'  WHERE `service_region_code` IS NULL AND `service_area` LIKE '%天涯区%';
UPDATE `companion_profile` SET `service_region_code` = 'DANZHOU_NADA'  WHERE `service_region_code` IS NULL AND `service_area` LIKE '%那大镇%';
UPDATE `companion_profile` SET `service_region_code` = 'QIONGHAI_JIJI'  WHERE `service_region_code` IS NULL AND `service_area` LIKE '%嘉积镇%';

-- 4.2 订单服务区域（address 形如「海南省海口市美兰区人民大道43号」）
UPDATE `companion_order` SET `service_region_code` = 'HAIKOU_MEILAN'  WHERE `service_region_code` IS NULL AND `address` LIKE '%美兰区%';
UPDATE `companion_order` SET `service_region_code` = 'HAIKOU_LONGHUA'  WHERE `service_region_code` IS NULL AND `address` LIKE '%龙华区%';
UPDATE `companion_order` SET `service_region_code` = 'HAIKOU_XIUYING'  WHERE `service_region_code` IS NULL AND `address` LIKE '%秀英区%';
UPDATE `companion_order` SET `service_region_code` = 'HAIKOU_QONGSHAN'  WHERE `service_region_code` IS NULL AND `address` LIKE '%琼山区%';
UPDATE `companion_order` SET `service_region_code` = 'SANYA_JIYANG'  WHERE `service_region_code` IS NULL AND `address` LIKE '%吉阳区%';
UPDATE `companion_order` SET `service_region_code` = 'SANYA_TIANYA'  WHERE `service_region_code` IS NULL AND `address` LIKE '%天涯区%';
UPDATE `companion_order` SET `service_region_code` = 'DANZHOU_NADA'  WHERE `service_region_code` IS NULL AND `address` LIKE '%那大镇%';
UPDATE `companion_order` SET `service_region_code` = 'QIONGHAI_JIJI'  WHERE `service_region_code` IS NULL AND `address` LIKE '%嘉积镇%';
