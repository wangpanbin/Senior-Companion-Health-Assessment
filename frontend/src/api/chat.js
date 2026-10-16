/**
 * 订单聊天接口
 * 对应文档：docs/api/11-order-chat.md
 * 负责模块：M13 订单聊天（家属 / 陪诊员发言，老人 / 管理员只读）
 */
import request from '@/utils/request'
import { createProgressSocket } from '@/utils/realtime'

export function sendMessage(orderId, data) {
  return request({ url: `/chat/${orderId}/messages`, method: 'post', data })
}

export function getHistory(orderId, params) {
  return request({ url: `/chat/${orderId}/messages`, method: 'get', params })
}

export function listConversations(params) {
  return request({ url: '/chat/conversations', method: 'get', params })
}

export function markRead(orderId, lastMessageId) {
  return request({ url: `/chat/${orderId}/read`, method: 'post', data: { lastMessageId } })
}

export function getUnreadTotal() {
  return request({ url: '/chat/unread-total', method: 'get' })
}

export function listAdminChatOrders(params) {
  return request({ url: '/admin/chat/orders', method: 'get', params })
}

export function connectChatSocket(orderId, handlers) {
  return createProgressSocket(orderId, handlers, '/ws/chat')
}
