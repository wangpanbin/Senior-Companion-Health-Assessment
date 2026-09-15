import request from '@/utils/request'

/**
 * 用户与档案接口
 * 对应文档：docs/api/02-elder-family.md
 * 负责模块：M3 用户与档案管理（后端 B / 数据库 D）
 */

/* ---------------- 当前用户资料 ---------------- */

export function getProfile() {
  return request({ url: '/user/profile', method: 'get' })
}

export function updateProfile(data) {
  return request({ url: '/user/profile', method: 'put', data })
}

/* ---------------- 陪诊员资质 ---------------- */

/** 提交陪诊员资质申请（任何已登录用户都可申请） */
export function applyCompanion(data) {
  return request({ url: '/user/companion/apply', method: 'post', data })
}

/** 查询自己的资质申请状态 */
export function getMyCompanionApplication() {
  return request({ url: '/user/companion/application', method: 'get' })
}

/** 陪诊员公开资料（评分、接单数等） */
export function getCompanionProfile(companionId) {
  return request({ url: `/user/companion/${companionId}`, method: 'get' })
}

/* ---------------- 老人档案（家属操作） ---------------- */

export function listElder(params) {
  return request({ url: '/user/elder', method: 'get', params })
}

export function createElder(data) {
  return request({ url: '/user/elder', method: 'post', data })
}

export function getElder(elderId) {
  return request({ url: `/user/elder/${elderId}`, method: 'get' })
}

export function updateElder(elderId, data) {
  return request({ url: `/user/elder/${elderId}`, method: 'put', data })
}

export function removeElder(elderId) {
  return request({ url: `/user/elder/${elderId}`, method: 'delete' })
}

/** 绑定老人（邀请码 / 手机号验证） */
export function bindElder(data) {
  return request({ url: '/user/elder/bind', method: 'post', data })
}

/** 解绑 */
export function unbindElder(elderId) {
  return request({ url: `/user/elder/${elderId}/bind`, method: 'delete' })
}
