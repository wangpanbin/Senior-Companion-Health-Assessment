-- =============================================================================
-- 银龄伴诊 —— 种子数据 · 边界场景补充
-- -----------------------------------------------------------------------------
-- 版本      : V3
-- 覆盖模块  : M1 数据库设计与数据初始化
-- 前置      : V2__seed_data.sql（必须先执行）
--
-- 为什么单独一个版本而不是改 V2：
--   V2 已由 Flyway 执行并记入 flyway_schema_history，改动已执行脚本会造成
--   checksum 校验失败（application-dev.yml 中 validate-on-migrate=true）。
--   追加数据一律新开版本号，这是 Flyway 的正确用法。
--
-- 本脚本补齐 V2 未覆盖的三个边界场景（对方为 M9 管理后台演示所必需）：
--   1. 订单终态 CANCELLED —— V2 的 60 条订单只覆盖 5 种状态，缺「已取消」；
--      补 3 条：家属主动取消 / 接单后取消 / 管理员强制终止（arbitrate_flag=1）
--   2. 一星差评 —— V2 的 30 条评价最低 2 星，缺 1 星；补 1 条用于
--      「差评 → 投诉 → 管理员介入」链路演示
--   3. 已解绑的绑定关系 —— V2 的 30 条关系全部 BOUND，缺解绑记录；
--      补 1 条带 unbind_time 的 UNBOUND 记录
--
-- 本脚本对「按序重复执行」是安全的：所有 id 均为追加段，且不影响 V2 已有行。
-- =============================================================================

SET NAMES utf8mb4;


-- =============================================================================
-- 1. 已取消订单（3 条）
--    id 1061 —— 家属在待接单阶段主动取消
--    id 1062 —— 陪诊员已接单，家属因老人身体不适取消
--    id 1063 —— 服务中，管理员强制终止（纠纷处理，非正常流转）
-- =============================================================================
INSERT INTO `companion_order`
  (`id`,`order_no`,`family_id`,`elder_id`,`companion_id`,`hospital`,`department`,`visit_time`,
   `address`,`longitude`,`latitude`,`remark`,`status`,`fee`,`actual_fee`,`payment_status`,
   `service_summary`,`service_photos`,`accept_time`,`start_time`,`finish_time`,`cancel_time`,
   `cancel_reason`,`cancel_by`,`arbitrate_flag`,`arbitrate_result`,`version`,`create_time`) VALUES
  (1061, 'NL20260918000061', 121, 421, NULL, '海南省人民医院', '心血管内科', '2026-09-25 09:00:00',
   '海口市秀英区秀华路19号', 110.293100, 20.008700, '老人听力不好，请大声沟通', 'CANCELLED', 128.00, NULL, 'UNPAID',
   NULL, NULL, NULL, NULL, NULL, '2026-09-20 10:15:00',
   '老人在外地住院，本次预约取消', 121, 0, NULL, 0, '2026-09-18 09:30:00'),

  (1062, 'NL20260918000062', 122, 422, 311, '海南医学院第一附属医院', '神经内科', '2026-09-21 08:30:00',
   '海口市龙华区龙华路31号', 110.331900, 20.031500, NULL, 'CANCELLED', 188.00, NULL, 'UNPAID',
   NULL, NULL, '2026-09-19 08:10:00', NULL, NULL, '2026-09-19 20:40:00',
   '老人身体不适，改期就诊', 122, 0, NULL, 0, '2026-09-18 20:05:00'),

  (1063, 'NL20260914000063', 123, 423, 312, '海南省中医院', '康复医学科', '2026-09-16 14:00:00',
   '海口市龙华区城西路45号', 110.325600, 20.017800, '需要轮椅推行', 'CANCELLED', 248.00, NULL, 'UNPAID',
   NULL, NULL, '2026-09-15 09:20:00', '2026-09-16 14:05:00', NULL, '2026-09-16 16:20:00',
   '管理员强制终止：陪诊员中途离岗', 1, 1,
   '经调取轨迹点与打卡记录核实，陪诊员在服务中途离岗属实，订单强制终止，记违规一次并冻结接单 3 天。',
   0, '2026-09-14 16:45:00');


-- =============================================================================
-- 2. 上述 3 条订单的状态流转日志（9 条，含 1 条管理员强制变更 is_force=1）
--    禁止跳级、禁止回退：1063 走的是 PENDING→ACCEPTED→IN_SERVICE→CANCELLED 完整链路
-- =============================================================================
INSERT INTO `order_status_log`
  (`id`,`order_id`,`from_status`,`to_status`,`operator_id`,`operator_name`,`operator_role`,
   `remark`,`is_force`,`operate_time`,`create_time`) VALUES
  (2233, 1061, NULL,        'PENDING',   121, '郑凯',  'FAMILY',    '家属下单',                           0, '2026-09-18 09:30:00', '2026-09-18 09:30:00'),
  (2234, 1061, 'PENDING',   'CANCELLED', 121, '郑凯',  'FAMILY',    '家属取消预约',                       0, '2026-09-20 10:15:00', '2026-09-20 10:15:00'),
  (2235, 1062, NULL,        'PENDING',   122, '梁燕',  'FAMILY',    '家属下单',                           0, '2026-09-18 20:05:00', '2026-09-18 20:05:00'),
  (2236, 1062, 'PENDING',   'ACCEPTED',  311, '徐文斌', 'COMPANION', '陪诊员接单',                         0, '2026-09-19 08:10:00', '2026-09-19 08:10:00'),
  (2237, 1062, 'ACCEPTED',  'CANCELLED', 122, '梁燕',  'FAMILY',    '老人身体不适，改期就诊',             0, '2026-09-19 20:40:00', '2026-09-19 20:40:00'),
  (2238, 1063, NULL,        'PENDING',   123, '谢东',  'FAMILY',    '家属下单',                           0, '2026-09-14 16:45:00', '2026-09-14 16:45:00'),
  (2239, 1063, 'PENDING',   'ACCEPTED',  312, '孙玉兰', 'COMPANION', '陪诊员接单',                         0, '2026-09-15 09:20:00', '2026-09-15 09:20:00'),
  (2240, 1063, 'ACCEPTED',  'IN_SERVICE',312, '孙玉兰', 'COMPANION', '陪诊员到院打卡，开始服务',           0, '2026-09-16 14:05:00', '2026-09-16 14:05:00'),
  (2241, 1063, 'IN_SERVICE','CANCELLED', 1,   '王建国', 'ADMIN',     '管理员强制终止（陪诊员中途离岗）',   1, '2026-09-16 16:20:00', '2026-09-16 16:20:00');


-- =============================================================================
-- 3. 管理员纠纷处理操作日志（1 条）
--    M9 硬性约束：管理员每次写操作必须留痕
-- =============================================================================
INSERT INTO `admin_oper_log`
  (`id`,`operator_id`,`operator_name`,`oper_type`,`target_type`,`target_id`,`target_desc`,
   `before_status`,`after_status`,`remark`,`request_url`,`request_method`,`ip`,`oper_time`,`create_time`) VALUES
  (60037, 1, '王建国', 'ARBITRATE_ORDER', 'ORDER', 1063, 'NL20260914000063',
   'IN_SERVICE', 'CANCELLED', '陪诊员中途离岗属实，强制终止订单并记违规一次', '/api/admin/order/1063/arbitrate', 'POST', '192.168.3.10', '2026-09-16 16:20:00', '2026-09-16 16:20:00');


-- =============================================================================
-- 4. 一星差评订单（1 条）+ 其状态流转日志（5 条）
--    用于演示「差评 → 家属投诉 → 管理员判定 → 陪诊员违规累计」完整链路
-- =============================================================================
INSERT INTO `companion_order`
  (`id`,`order_no`,`family_id`,`elder_id`,`companion_id`,`hospital`,`department`,`visit_time`,
   `address`,`longitude`,`latitude`,`remark`,`status`,`fee`,`actual_fee`,`payment_status`,
   `service_summary`,`service_photos`,`accept_time`,`start_time`,`finish_time`,`cancel_time`,
   `cancel_reason`,`cancel_by`,`arbitrate_flag`,`arbitrate_result`,`version`,`create_time`) VALUES
  (1064, 'NL20260909000064', 124, 424, 313, '海口市人民医院', '内分泌科', '2026-09-10 10:00:00',
   '海口市美兰区人民大道43号', 110.345200, 20.062100, '老人需空腹抽血，请提前提醒不要进食', 'REVIEWED', 168.00, 168.00, 'SETTLED',
   '完成挂号、抽血、取报告全过程陪同。', NULL, '2026-09-09 19:30:00', '2026-09-10 10:42:00', '2026-09-10 13:20:00', NULL,
   NULL, NULL, 0, NULL, 0, '2026-09-09 11:20:00');

INSERT INTO `order_status_log`
  (`id`,`order_id`,`from_status`,`to_status`,`operator_id`,`operator_name`,`operator_role`,
   `remark`,`is_force`,`operate_time`,`create_time`) VALUES
  (2242, 1064, NULL,           'PENDING',    124, '宋佳',  'FAMILY',    '家属下单',         0, '2026-09-09 11:20:00', '2026-09-09 11:20:00'),
  (2243, 1064, 'PENDING',      'ACCEPTED',   313, '马晓东', 'COMPANION', '陪诊员接单',       0, '2026-09-09 19:30:00', '2026-09-09 19:30:00'),
  (2244, 1064, 'ACCEPTED',     'IN_SERVICE', 313, '马晓东', 'COMPANION', '陪诊员到院打卡',   0, '2026-09-10 10:42:00', '2026-09-10 10:42:00'),
  (2245, 1064, 'IN_SERVICE',   'COMPLETED',  313, '马晓东', 'COMPANION', '提交服务小结，订单完成', 0, '2026-09-10 13:20:00', '2026-09-10 13:20:00'),
  (2246, 1064, 'COMPLETED',    'REVIEWED',   124, '宋佳',  'FAMILY',    '家属提交评价（1 星）', 0, '2026-09-10 20:15:00', '2026-09-10 20:15:00');


-- =============================================================================
-- 5. 一星评价（1 条）
--    tags 取自 sys_dict 的 COMPLAINT_TYPE 语义（迟到 / 服务态度差）
-- =============================================================================
INSERT INTO `order_review`
  (`id`,`order_id`,`order_no`,`family_id`,`elder_id`,`companion_id`,`score`,`tags`,`content`,
   `is_anonymous`,`companion_reply`,`reply_time`,`is_valid`,`create_time`) VALUES
  (30031, 1064, 'NL20260909000064', 124, 424, 313, 1, '["迟到","服务态度差"]',
   '约定 10 点在门诊楼门口碰面，陪诊员 10 点 40 才到，抽血号已经过号需要重新排队，老人空腹等了近一小时。过程中询问情况也答复得很敷衍，希望平台加强管理。',
   0, NULL, NULL, 1, '2026-09-10 20:15:00');


-- =============================================================================
-- 6. 已解绑的家属-老人关系（1 条）
--    老人 431（张德海）为 V2 中 bind_status=UNBOUND 的档案，此处补其绑定后解绑的痕迹
-- =============================================================================
INSERT INTO `family_elder_relation`
  (`id`,`family_id`,`elder_id`,`relation`,`bind_type`,`is_default`,`status`,`bind_time`,`unbind_time`,`create_time`) VALUES
  (531, 101, 431, 'SON', 'PHONE', 0, 'UNBOUND', '2026-08-25 10:12:00', '2026-09-02 15:40:00', '2026-08-25 10:12:00');


-- =============================================================================
-- 7. 订单取消站内信（3 条，一期以站内信替代 IM）
--    由系统发出（sender_id 为 NULL），文案由后端模板生成
-- =============================================================================
INSERT INTO `internal_message`
  (`id`,`receiver_id`,`sender_id`,`type`,`title`,`content`,`biz_type`,`biz_id`,`link_url`,`is_read`,`receiver_deleted`,`create_time`) VALUES
  (40073, 121, NULL, 'ORDER_CANCELLED', '订单已取消',
   '您为郑文彬预约的 2026-09-25 海南省人民医院心血管内科陪诊订单已取消，如为误操作请重新下单。', 'ORDER', 1061, '/family/order/1061', 1, 0, '2026-09-20 10:15:05'),
  (40074, 122, NULL, 'ORDER_CANCELLED', '订单已取消',
   '您为梁淑华预约的 2026-09-21 海南医学院第一附属医院神经内科陪诊订单已取消，已接单陪诊员已同步收到通知。', 'ORDER', 1062, '/family/order/1062', 0, 0, '2026-09-19 20:40:05'),
  (40075, 123, NULL, 'ORDER_CANCELLED', '订单已被平台终止',
   '您为谢振华预约的 2026-09-16 海南省中医院康复医学科陪诊订单已被平台终止，处理结果：陪诊员中途离岗属实，已记违规一次。费用未产生，无需结算。', 'ORDER', 1063, '/family/order/1063', 0, 0, '2026-09-16 16:20:05');


-- =============================================================================
-- 8. 陪诊员聚合字段同步
--    companion_profile 的 score / review_count / order_count / accept_count 是
--    冗余聚合值，新增订单与评价后必须同步，否则 M10 统计口径不一致。
--    计数口径：accept_count = 累计接单（含进行中与被取消）；order_count = COMPLETED + REVIEWED
--    score 用加权平均重算：
--      313：4.09 × 56 = 229.04，追加 1 星后 (229.04 + 1) / 57 = 4.04
--    ⚠️ MySQL 的 UPDATE 中，后面的赋值会读到前面已更新的值，
--       因此 score 必须写在 review_count 之前，否则分母会变成 58。
-- =============================================================================
UPDATE `companion_profile` SET `accept_count` = `accept_count` + 1 WHERE `user_id` = 311;
UPDATE `companion_profile` SET `accept_count` = `accept_count` + 1 WHERE `user_id` = 312;
UPDATE `companion_profile`
   SET `score`        = ROUND((`score` * `review_count` + 1) / (`review_count` + 1), 2),
       `review_count` = `review_count` + 1,
       `order_count`  = `order_count` + 1,
       `accept_count` = `accept_count` + 1
 WHERE `user_id` = 313;
