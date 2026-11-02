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

/**
 * 切换陪诊员接单状态（V3.1 · T0）
 *
 * 只影响新订单匹配，不影响已接订单 —— 已接单/服务中的订单继续按状态机推进。
 * @param {'AVAILABLE'|'REST'|'OFFLINE'} workStatus
 */
export function updateCompanionWorkStatus(workStatus) {
  return request({
    url: '/user/companion/work-status',
    method: 'put',
    data: { workStatus }
  })
}

/* ---------------- 账号注销与数据导出（V4 · P0-1，仅家属 / 陪诊员） ---------------- */

/**
 * 注销当前账号（对应 docs/api/01-auth-user.md §8）。
 *
 * @param {{password: string, confirmText: string}} data
 *   confirmText 必须原样输入固定串「我已知晓注销后果」。
 * 错误码：1006 密码错误；11001 存在进行中订单（data.pendingOrderCount）。
 */
export function deactivateAccount(data) {
  return request({ url: '/user/deactivate', method: 'post', data })
}

/**
 * 导出我的个人数据为 Excel（对应 docs/api/01-auth-user.md §9）。
 *
 * 返回 Blob；后端错误时可能 200 + json body（与统计导出同一口径），
 * 调用方须按 blob.type 甄别后再触发下载。
 */
export function exportMyData() {
  return request({
    url: '/user/data-export',
    method: 'get',
    responseType: 'blob',
    timeout: 60000
  })
}
