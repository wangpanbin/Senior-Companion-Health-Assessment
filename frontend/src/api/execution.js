import request from '@/utils/request'
import { createProgressSocket } from '@/utils/realtime'

/**
 * 陪诊执行与打卡接口
 * 对应文档：docs/api/04-companion-execution.md
 * 负责模块：M5 陪诊执行、打卡与实时进度（前端 A / 后端 B）
 *
 * 说明：一期不做地图导航，定位只用于「是否到达陪诊地点」的距离校验。
 */

/** 打卡（陪诊员） */
export function checkin(orderId, data) {
  return request({ url: `/execution/${orderId}/checkin`, method: 'post', data })
}

/** 打卡记录列表 */
export function listCheckins(orderId) {
  return request({ url: `/execution/${orderId}/checkins`, method: 'get' })
}

/** 陪诊轨迹点列表 */
export function getTrack(orderId) {
  return request({ url: `/execution/${orderId}/track`, method: 'get' })
}

/** 进度快照（含当前节点与下一个节点） */
export function getProgress(orderId) {
  return request({ url: `/execution/${orderId}/progress`, method: 'get' })
}

/** 上传现场照片 / 取药凭证 */
export function uploadExecutionPhoto(orderId, formData) {
  return request({
    url: `/execution/${orderId}/photo`,
    method: 'post',
    data: formData,
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 建立实时进度推送连接（WebSocket）。
 *
 * 端点：`/ws/progress?token=<accessToken>&orderId=<orderId>`（M5 已交付）。
 * 令牌走 query 参数 —— 浏览器原生 WebSocket 不允许自定义请求头，
 * 后端 `JwtHandshakeInterceptor` 因此在握手阶段从 query 取令牌并校验订单归属。
 *
 * ⚠️ 订阅前必须**先拉一次 `/progress` 快照铺底**，之后才靠推送增量更新。
 *    只等推送的话，页面在连接建立前会一直空白；连接失败更是永远空白。
 *    这是后端 `ProgressVO` 类注释里明确的约定。
 *
 * @param {number|string} orderId 订单 ID
 * @param {object} [handlers] 见 `@/utils/realtime` 的 createProgressSocket
 * @returns {{ close: () => void, isOpen: () => boolean }} 页面卸载时务必调用 close()
 */
export function connectProgressSocket(orderId, handlers) {
  if (!orderId) {
    throw new Error('connectProgressSocket 需要 orderId')
  }
  return createProgressSocket(orderId, handlers)
}
