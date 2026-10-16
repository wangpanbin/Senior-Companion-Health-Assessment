-- =============================================================================
-- V5 · chat_message + chat_read_cursor —— 订单聊天（M13 · 二期扩围）
-- 依据：docs/adr/0011-order-chat.md（Accepted）
-- ⚠️ V1~V4 已被 Flyway 执行（validate-on-migrate 校验 checksum），本脚本为纯增量，
--    不得以任何形式改动 V1/V2/V3/V4。
-- 口径：会话身份 = 陪诊订单（family_id + companion_id），不建独立会话表；
--       msg_type 与 sys_file.ext 的匹配由服务层校验（图片 jpg/png/webp，视频 mp4）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 21. chat_message —— 订单聊天消息（家属↔陪诊员双向；ELDER/ADMIN 只读由服务层拦截）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息 ID（会话内游标锚点，单调递增）',
  `order_id`    BIGINT        NOT NULL                COMMENT '关联 companion_order.id（会话身份 = 订单）',
  `sender_id`   BIGINT        NOT NULL                COMMENT '发送人用户 ID（仅本单 FAMILY/COMPANION）',
  `sender_role` VARCHAR(20)   NOT NULL                COMMENT '发送人角色快照：FAMILY / COMPANION',
  `msg_type`    VARCHAR(10)   NOT NULL                COMMENT '消息类型：TEXT-文字 / IMAGE-图片 / VIDEO-视频',
  `content`     VARCHAR(1000)          DEFAULT NULL   COMMENT 'TEXT 正文（≤1000 字）；媒体消息可传说明文字',
  `file_id`     VARCHAR(64)            DEFAULT NULL   COMMENT '媒体消息必填，关联 sys_file.file_id（须 biz_type=CHAT 且 uploader_id=sender_id）',
  `file_url`    VARCHAR(255)           DEFAULT NULL   COMMENT '媒体 URL 快照（/uploads/...），展示用冗余',
  `file_size`   BIGINT                 DEFAULT NULL   COMMENT '媒体字节数',
  `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  -- 游标倒序分页（id < cursor ORDER BY id DESC）/ 未读计数 / 会话最后一条 全走此索引
  KEY `idx_order_id_id` (`order_id`, `id`),
  -- 「我发过的消息」审计与排查
  KEY `idx_sender` (`sender_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单聊天消息表（会话=订单，ADR-0011）';

-- -----------------------------------------------------------------------------
-- 22. chat_read_cursor —— 已读游标（未读数 = id>游标 且 sender_id≠me 的计数）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_read_cursor` (
  `id`                   BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_id`             BIGINT   NOT NULL                COMMENT '订单 ID',
  `user_id`              BIGINT   NOT NULL                COMMENT '已读人用户 ID（会话双方各一条）',
  `last_read_message_id` BIGINT   NOT NULL DEFAULT 0      COMMENT '已读游标：≤ 该 id 的对方消息视为已读，只前进不后退',
  `read_time`            DATETIME          DEFAULT NULL   COMMENT '最近上报时间',
  `create_time`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`              TINYINT  NOT NULL DEFAULT 0      COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_user` (`order_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单聊天已读游标表（ADR-0011）';
