import request from '@/utils/request'

/**
 * 数据统计与导出接口
 * 对应文档：docs/api/09-statistics-export.md
 * 负责模块：M10 数据统计、可视化与导出（前端 A / 后端 B）
 *
 * V3.3 陪诊员工作台（前端 A / 后端 B）：下方 4 个陪诊员自助端点，
 * 口径全部由后端算好，前端只做展示（roadmap 决策 G8「前端统计零自行计算」）。
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

/* ---------------- V3.3 陪诊员工作台（COMPANION 自助，无 companionId 入参） ---------------- */

/**
 * 陪诊员工作台 · 区间经营概览
 *
 * GET /api/statistics/companion/me
 * 入参：startDate? endDate?（yyyy-MM-dd，闭区间，缺省最近 30 天，上限 366 天）
 * 出参：完成单数 / 服务时长（分钟）/ 完成率 / 平均评分 / 区间收入
 *
 * ⚠️ 区间口径由后端统一计算，前端不得再自行汇总。
 */
export function getCompanionWorkbenchOverview(params) {
  return request({ url: '/statistics/companion/me', method: 'get', params })
}

/**
 * 陪诊员工作台 · 趋势
 *
 * GET /api/statistics/companion/me/trend
 * 入参：startDate? endDate? dimension?（ORDER / INCOME / RATING，默认 ORDER）
 *      granularity?（DAY / WEEK / MONTH，默认 DAY）
 * 出参：categories（分桶标签）+ series（序列，缺口由后端补 0）
 */
export function getCompanionWorkbenchTrend(params) {
  return request({ url: '/statistics/companion/me/trend', method: 'get', params })
}

/**
 * 陪诊员工作台 · 服务画像（累计口径）
 *
 * GET /api/statistics/companion/me/profile
 * 出参：平均评分 / 累计完成单数 / 累计服务老人数 / 准时率 / 累计完成率 /
 *      累计服务时长（分钟）/ 擅长服务标签
 */
export function getCompanionWorkbenchProfile() {
  return request({ url: '/statistics/companion/me/profile', method: 'get' })
}

/**
 * 陪诊员工作台 · 收入摘要（区间口径）
 *
 * GET /api/statistics/companion/me/income
 * 入参：startDate? endDate?（闭区间，缺省最近 30 天）
 * 出参：订单金额 / 实际收入 / 待结算 / 已结算 / 代垫合计 / 服务费合计
 */
export function getCompanionWorkbenchIncome(params) {
  return request({ url: '/statistics/companion/me/income', method: 'get', params })
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
