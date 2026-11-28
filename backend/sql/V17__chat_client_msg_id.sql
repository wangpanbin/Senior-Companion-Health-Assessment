-- ============================================================
-- V17 · 聊天消息幂等键（closure-2026-11 · P2-4）
-- ============================================================
-- 背景：弱网下前端重试「发送消息」，同一条消息会在会话里出现两次。
--       陪诊员要自己分辨哪条是真的，聊天记录也被冲淡。
--       客户端为每条消息生成一个唯一 ID（clientMsgId），
--       服务端按 (order_id, sender_id, client_msg_id) 唯一键去重：
--       重复提交时捕获唯一键冲突，回查已存在的那条返回（幂等成功，不报错）。
--
-- 为什么聊天侧用数据库唯一键而不是 Redis：
--   消息本身就要落库，Redis 挂了也不该丢消息；
--   而下单只是「建一笔单」，Redis 不可用时降级放行的代价可接受（见 V15 之后的下单幂等实现）。
--
-- ⚠️ MySQL 唯一键对 NULL 不约束 —— 客户端不传 clientMsgId 时，
--    同一会话可以有多条 NULL 行，也就是「不传即不幂等」，老客户端行为完全不变。

ALTER TABLE `chat_message`
    ADD COLUMN `client_msg_id` VARCHAR(64) DEFAULT NULL
        COMMENT '客户端消息 ID（幂等键）：与 order_id / sender_id 组成唯一键；NULL 表示不做幂等'
        AFTER `file_size`;

ALTER TABLE `chat_message`
    ADD UNIQUE KEY `uk_order_sender_client_msg` (`order_id`, `sender_id`, `client_msg_id`);
