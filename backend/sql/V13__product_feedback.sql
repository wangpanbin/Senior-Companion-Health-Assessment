-- =============================================================================
-- 银龄伴诊 —— 产品反馈通道（V6 · 客户端建议 → 管理端处理）
-- -----------------------------------------------------------------------------
-- 版本      : V13
-- 覆盖模块  : 产品反馈（docs/api/15-product-feedback.md）
-- 变更内容  : 1) 新建 product_feedback 表（与 complaint / order_review 完全解耦）；
--             2) 更新 sys_file.biz_type 的**列注释**，纳入新增取值 FEEDBACK
--                （只改元数据，不动类型与数据，幂等）。
--
-- 为什么新表而不是给 complaint 加类型：
--             complaint.order_id / target_user_id 均为 NOT NULL，语义是「A 就某笔
--             订单投诉 B」。产品反馈没有订单、没有被投诉人。改成可空会连带影响
--             ComplaintService 里所有 requireInvolved(orderId) 的归属校验分支、
--             管理端投诉页的「关联订单」列与既有 e2e 断言，且「投诉」一词会同时
--             代表仲裁与产品建议两件事，OperType.HANDLE_COMPLAINT 的审计语义也混。
--
-- 三条硬边界（见接口文档 §〇）：
--   1. 用户输入只过敏感词；管理员写的处理说明过敏感词 + 医疗红线两套词表；
--   2. ELDER 不提反馈（不开 @AllowElderWrite 豁免），POST 由只读拦截器 403；
--   3. 不采集任何设备 / 版本指纹（隐私最小化）。
--
-- 回滚（仓库无独立 rollback 目录，按本文件头部注释手工执行）：
--   DROP TABLE IF EXISTS `product_feedback`;
--   并把 sys_file.biz_type 的列注释改回：
--   '业务类型：COMPANION_CERT / CHECKIN / COMPLAINT / AVATAR / REVIEW / CHAT'
--
-- 回滚影响面：仅本表；不涉及 complaint / order_review / sys_file 的数据与索引。
-- 回滚即丢失全部反馈记录且不可恢复，仅限开发 / 演示环境；无其它迁移依赖本表。
--
-- 种子数据  : **无**。反馈是用户产出，预置假数据只会在演示时混进真实列表。
--
-- @since V6 · 产品反馈
-- =============================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `product_feedback` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '反馈 ID',
  `submitter_id`    BIGINT        NOT NULL                COMMENT '提交人用户 ID',
  `submitter_role`  VARCHAR(20)   NOT NULL                COMMENT '提交人角色：FAMILY / COMPANION',
  `type`            VARCHAR(20)   NOT NULL                COMMENT '分类：FEATURE-功能建议 / EXPERIENCE-使用体验 / DEFECT-缺陷问题 / OTHER-其他',
  `content`         VARCHAR(1000) NOT NULL                COMMENT '反馈内容，10-1000 字',
  `images`          JSON                   DEFAULT NULL   COMMENT '截图 URL 数组，最多 3 张',
  `status`          VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '处理状态：PENDING-待处理 / PROCESSING-处理中 / RESOLVED-已采纳 / REJECTED-未采纳（**只可正向流转**）',
  `handle_admin_id` BIGINT                 DEFAULT NULL   COMMENT '处理管理员用户 ID',
  `handle_result`   VARCHAR(500)           DEFAULT NULL   COMMENT '处理说明，10-500 字；**仅终态对提交人可见**',
  `handle_time`     DATETIME               DEFAULT NULL   COMMENT '处理时间',
  `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`         TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_status_create` (`status`, `create_time`),
  KEY `idx_submitter` (`submitter_id`),
  KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='产品反馈表（与订单解耦的用户建议通道）';

-- -----------------------------------------------------------------------------
-- sys_file.biz_type 列注释同步：新增 FEEDBACK-产品反馈截图。
-- 只改注释元数据，类型与既有数据一字不动；重复执行结果相同（幂等）。
-- 为什么值得单独写一条：列注释是本仓库「取值集合」的第二处真源
-- （第一处是 constant/FileBizType），注释过期会让后来的人以为 FEEDBACK 是脏数据。
-- -----------------------------------------------------------------------------
ALTER TABLE `sys_file` MODIFY COLUMN `biz_type` VARCHAR(20) DEFAULT NULL
  COMMENT '业务类型：COMPANION_CERT-资质证件 / CHECKIN-打卡照片 / COMPLAINT-投诉证据 / AVATAR-头像 / REVIEW-评价图片 / CHAT-订单聊天媒体 / FEEDBACK-产品反馈截图';
