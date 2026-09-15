import request from '@/utils/request'

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
 * TODO(M5)：后端 WebSocket 端点就绪后补充实现；当前返回 null 占位。
 * 约定：ws://localhost:8080/ws/progress?token=<accessToken>&orderId=<orderId>
 */
export function connectProgressSocket() {
  return null
}
