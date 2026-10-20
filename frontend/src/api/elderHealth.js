import request from '@/utils/request'

/**
 * 老人健康协同接口
 * 对应文档：docs/api/12-elder-health.md
 * 负责模块：M3 用户与档案管理 · V3.2 老人健康协同中心
 */

/* ---------------- 家庭健康协同（家属端） ---------------- */

/**
 * 家庭健康协同首页摘要
 * 返回当前家属名下每位老人的轻量摘要；没有绑定老人时返回空数组
 */
export function getFamilyHealthSummary() {
  return request({ url: '/family/health/summary', method: 'get' })
}

/**
 * 某位老人的健康概览
 * @param {number|string} elderId 老人档案 ID
 * @param {{startDate?: string, endDate?: string}} [params] 统计区间（缺省最近 30 天）
 */
export function getElderHealthOverview(elderId, params) {
  return request({ url: `/family/elder/${elderId}/health/overview`, method: 'get', params })
}

/**
 * 某位老人的健康时间线
 * @param {number|string} elderId 老人档案 ID
 * @param {{startDate?: string, endDate?: string, categories?: string, limit?: number, cursor?: string}} [params]
 */
export function getElderHealthTimeline(elderId, params) {
  return request({ url: `/family/elder/${elderId}/health/timeline`, method: 'get', params })
}

/* ---------------- 老人本人 ---------------- */

/**
 * 我自己的健康概览
 * 不传 elderId —— 老人端只能看自己，服务端从登录态取档案 ID
 * @param {{startDate?: string, endDate?: string}} [params] 统计区间
 */
export function getMyHealthOverview(params) {
  return request({ url: '/elder/health/overview', method: 'get', params })
}

/**
 * 我自己的健康时间线
 * @param {{startDate?: string, endDate?: string, categories?: string, limit?: number, cursor?: string}} [params]
 */
export function getMyHealthTimeline(params) {
  return request({ url: '/elder/health/timeline', method: 'get', params })
}
