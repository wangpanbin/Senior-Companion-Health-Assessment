import request from '@/utils/request'

/**
 * 陪诊订单接口
 * 对应文档：docs/api/03-order.md
 * 负责模块：M4 陪诊订单与状态机（后端 B / 前端 A）
 *
 * 状态机：待接单 → 已接单 → 服务中 → 已完成 → 已评价（禁止跳级、禁止回退）
 * 状态枚举与流转规则见后端 org.company.nianglin.constant.OrderStatus
 */

/** 下单（家属） */
export function createOrder(data) {
  return request({ url: '/order', method: 'post', data })
}

/** 我的订单列表：按当前登录角色自动区分（家属看自己的、陪诊员看接的） */
export function listMyOrders(params) {
  return request({ url: '/order', method: 'get', params })
}

/** 待接单订单大厅（陪诊员） */
export function listOrderHall(params) {
  return request({ url: '/order/hall', method: 'get', params })
}

/** 订单详情 */
export function getOrder(orderId) {
  return request({ url: `/order/${orderId}`, method: 'get' })
}

/** 取消订单（仅「待接单」状态，家属） */
export function cancelOrder(orderId, data) {
  return request({ url: `/order/${orderId}/cancel`, method: 'put', data })
}

/** 接单（陪诊员，乐观锁防超卖） */
export function acceptOrder(orderId) {
  return request({ url: `/order/${orderId}/accept`, method: 'post' })
}

/** 拒单（陪诊员，需填原因） */
export function rejectOrder(orderId, data) {
  return request({ url: `/order/${orderId}/reject`, method: 'post', data })
}

/** 开始服务：已接单 → 服务中（陪诊员） */
export function startService(orderId) {
  return request({ url: `/order/${orderId}/start`, method: 'post' })
}

/** 完成服务：服务中 → 已完成（陪诊员） */
export function completeService(orderId, data) {
  return request({ url: `/order/${orderId}/complete`, method: 'post', data })
}

/** 状态流转时间线 */
export function getOrderTimeline(orderId) {
  return request({ url: `/order/${orderId}/timeline`, method: 'get' })
}
