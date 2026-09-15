import request from '@/utils/request'

/**
 * 数据统计与导出接口
 * 对应文档：docs/api/09-statistics-export.md
 * 负责模块：M10 数据统计、可视化与导出（前端 A / 后端 B）
 */

/** 总览：订单量、完成率、用户增长、漏服率 */
export function getOverview(params) {
  return request({ url: '/statistics/overview', method: 'get', params })
}

/** 订单趋势（折线图）：按日 / 周 / 月 */
export function getOrderTrend(params) {
  return request({ url: '/statistics/order-trend', method: 'get', params })
}

/** 订单状态分布（饼图） */
export function getOrderStatusDistribution(params) {
  return request({ url: '/statistics/order-status', method: 'get', params })
}

/** 陪诊员接单排行（柱状图） */
export function getCompanionRank(params) {
  return request({ url: '/statistics/companion-rank', method: 'get', params })
}

/** 漏服率统计 */
export function getMedicationMissedStat(params) {
  return request({ url: '/statistics/medication-missed', method: 'get', params })
}

/* ---------------- Excel 导出 ---------------- */

/**
 * 导出订单数据为 Excel。
 * 注意：返回 Blob，不走统一响应结构，拦截器会自动透传。
 */
export function exportOrders(params) {
  return request({
    url: '/statistics/export/order',
    method: 'get',
    params,
    responseType: 'blob',
    timeout: 60000
  })
}

/** 导出用户数据为 Excel */
export function exportUsers(params) {
  return request({
    url: '/statistics/export/user',
    method: 'get',
    params,
    responseType: 'blob',
    timeout: 60000
  })
}
