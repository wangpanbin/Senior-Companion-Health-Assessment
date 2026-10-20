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

/* ---------------- V3.5 平台运营驾驶舱（ADMIN） ---------------- */

/**
 * 平台运营驾驶舱 · 概览
 *
 * GET /api/admin/operations/overview
 * 出参：periodStart / periodEnd / todayOrderCount / completedOrderCount /
 *      inServiceCount / pendingCount / fulfillmentRate / avgAcceptMinutes /
 *      complaintRate / averageScore
 *
 * ⚠️ request 已带 `/api` 前缀，这里写 `/admin/operations/...` 即可。
 * ⚠️ 口径全部由后端算好，前端只做展示，不在本地汇总。
 */
export function getOperationsOverview() {
  return request({ url: '/admin/operations/overview', method: 'get' })
}

/**
 * 平台运营驾驶舱 · 异常中心
 *
 * GET /api/admin/operations/exceptions
 * 出参：total + items[]{ type, typeLabel, count, level, linkUrl, referenceId, description }
 *
 * ⚠️ `linkUrl` 是后端下发的**真实处理页路由**，前端直接拿去跳转，
 *    不自己拼路由 —— 拼了就可能指向 404（「看到了但处理不了」）。
 */
export function getOperationsExceptions() {
  return request({ url: '/admin/operations/exceptions', method: 'get' })
}

/**
 * 平台运营驾驶舱 · 趋势
 *
 * GET /api/admin/operations/trend?dimension=&range=&startDate=&endDate=
 * 入参：dimension（ORDER / COMPANION_ACTIVE / FULFILLMENT_RATE / COMPLAINT / REVIEW / MEDICATION）
 *      range（TODAY / LAST_7D / LAST_30D / CUSTOM；CUSTOM 时必带 startDate / endDate）
 * 出参：dimension / range / granularity / periodStart / periodEnd / categories[] / series[]
 *
 * ⚠️ 区间与维度切换都要**重新向后端要数**，前端不对已有数据自行切分。
 */
export function getOperationsTrend(params) {
  return request({ url: '/admin/operations/trend', method: 'get', params })
}

/**
 * 平台运营驾驶舱 · 平台健康度
 *
 * GET /api/admin/operations/health
 * 出参：fulfillmentRate / avgAcceptMinutes / completionRate / complaintRate /
 *      averageScore / activeCompanionCount
 *
 * ⚠️ 六个并列指标，**后端不给合成分**（没有「健康指数 / 综合评分」这类数），
 *    前端也不许自己合成一个。
 */
export function getOperationsHealth() {
  return request({ url: '/admin/operations/health', method: 'get' })
}

/* ---------------- V3.6 数据闭环（ADMIN） ---------------- */

/**
 * 平台运营驾驶舱 · 供给时段缺口分析
 *
 * GET /api/admin/operations/gap?range=&startDate=&endDate=
 * 入参：range（LAST_7D / LAST_30D / CUSTOM；CUSTOM 时必带 startDate / endDate）
 * 出参：periodStart / periodEnd / total（固定 168 个桶 = 7 天 × 24 小时，空桶也返回）/
 *      companionsWithoutSlots（没有可解析可服务时段的陪诊员数）/
 *      items[]{ weekday, hour, pendingUnaccepted, availableCompanions, gap }
 *
 * ⚠️ 桶的粒度固定为「周内第几天 × 小时」，与查询区间的长度无关。
 * ⚠️ 口径全部由后端算好，前端只做展示，不在本地重新分桶。
 */
export function getOperationsGap(params) {
  return request({ url: '/admin/operations/gap', method: 'get', params })
}

/**
 * 平台运营驾驶舱 · 干预效果对比
 *
 * GET /api/admin/operations/effect?metric=&from=&to=&compareFrom=&compareTo=
 * 入参：metric（**必须是后端已注册的指标 key**，未注册后端返回 400）
 *      from / to（后一期，闭区间）
 *      compareFrom / compareTo（前一期；**不传时后端自动取「等长且紧邻其前」的区间**）
 * 出参：metric / metricName / current{from,to,value} / previous{from,to,value} / delta
 *
 * ⚠️ 两期数值由后端同一个计算器算两遍得到，前端不做任何换算或二次计算。
 * ⚠️ `delta` 原样展示（后端给什么显示什么，含正负号；两值格式不同时后端给 "—"）。
 */
export function getOperationsEffect(params) {
  return request({ url: '/admin/operations/effect', method: 'get', params })
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
