import request from '@/utils/request'
import { createRealtimeSocket } from '@/utils/realtime'

/**
 * 陪诊执行与打卡接口
 * 对应文档：docs/api/04-companion-execution.md
 * 负责模块：M5 陪诊执行、打卡与实时进度（前端 A / 后端 B）
 *
 * 说明：定位数据的用途见 ADR-0022——打卡与位置上报都只做「是否到达」与「实时位置展示」
 * （家属端 / 陪诊员端渲染百度地图，**只显示直线距离，不做路线规划与导航**）。
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

/* ==================== 实时位置（ADR-0022） ==================== */

/**
 * 上报当前位置（陪诊员）。
 *
 * ⚠️ 坐标一律 **WGS84**（浏览器 `navigator.geolocation` 原生值），
 * 服务端**不做任何坐标转换**；百度地图要的 BD09 在渲染前用
 * `@/utils/coordConvert` 转。
 *
 * 后端限流：同一订单两次调用间隔小于 `min-report-interval-seconds`（默认 10 秒）时回 `4004`；
 * 陪诊员已暂停共享时回 `4005`。两者都**不要**静默重试 —— 它们是明确的业务答复。
 *
 * @param {number|string} orderId 订单 ID
 * @param {{longitude: string, latitude: string, accuracy?: number, speed?: number}} data
 */
export function reportLocation(orderId, data) {
  return request({ url: `/execution/${orderId}/location`, method: 'post', data })
}

/**
 * 最新位置快照（铺底用）。
 *
 * **仅本单家属与陪诊员可见**；老人 / 管理员会得到 `3004`。
 * 从未上报过时返回 200 + 坐标字段为 null —— 不是错误，按「尚未开始共享位置」渲染。
 */
export function getLocation(orderId) {
  return request({ url: `/execution/${orderId}/location`, method: 'get' })
}

/**
 * 开关位置共享（陪诊员）。
 *
 * 关闭后停止对外暴露实时位置，并推 `LOCATION_PAUSED` 让家属端显示「已暂停共享」。
 * **不影响打卡**：共享只决定「是否给家属看实时位置」，不决定「是否记录履约证据」。
 *
 * @param {number|string} orderId 订单 ID
 * @param {boolean} enabled true 恢复 / false 暂停
 */
export function setLocationSharing(orderId, enabled) {
  return request({
    url: `/execution/${orderId}/location-sharing`,
    method: 'put',
    data: { enabled }
  })
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
 * @param {object} [handlers] 见 `@/utils/realtime` 的 createRealtimeSocket
 * @returns {{ close: () => void, isOpen: () => boolean }} 页面卸载时务必调用 close()
 */
export function connectProgressSocket(orderId, handlers) {
  if (!orderId) {
    throw new Error('connectProgressSocket 需要 orderId')
  }
  return createRealtimeSocket(orderId, handlers)
}
