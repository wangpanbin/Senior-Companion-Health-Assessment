import request from '@/utils/request'

/**
 * 站内信接口
 * 对应文档：docs/api/07-message.md
 * 负责模块：M8 站内信与通知（后端 B）
 *
 * 背景：一期砍掉 IM 聊天，用站内信替代（计划书「范围控制」）。
 * 四类消息：订单事件 / 资质审核结果 / 漏服提醒 / 系统公告。
 */

/** 消息列表（分页 + 按类型筛选） */
export function listMessages(params) {
  return request({ url: '/message', method: 'get', params })
}

/** 未读数（顶栏红点） */
export function getUnreadCount() {
  return request({ url: '/message/unread-count', method: 'get' })
}

/** 标记单条已读 */
export function markRead(messageId) {
  return request({ url: `/message/${messageId}/read`, method: 'put' })
}

/** 全部已读 */
export function markAllRead() {
  return request({ url: '/message/read-all', method: 'put' })
}

/** 删除消息（仅自己可见的软删除） */
export function removeMessage(messageId) {
  return request({ url: `/message/${messageId}`, method: 'delete' })
}

/**
 * 建立站内信推送连接（WebSocket）。
 *
 * TODO(M8)：后端端点就绪后补充实现；当前返回 null 占位。
 * 约定：ws://localhost:8080/ws/message?token=<accessToken>
 */
export function connectMessageSocket() {
  return null
}
