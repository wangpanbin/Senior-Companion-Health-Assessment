import request from '@/utils/request'

/**
 * 管理后台接口
 * 对应文档：docs/api/08-admin.md
 * 负责模块：M9 管理后台（前端 A / 后端 B / 建表 D）
 *
 * 约束：所有接口仅 ADMIN 可访问；所有写操作必须写入 admin_oper_log。
 */

/* ---------------- 陪诊员资质审核 ---------------- */

export function listAuditApplications(params) {
  return request({ url: '/admin/companion/audit', method: 'get', params })
}

/**
 * 资质申请详情。
 *
 * 比列表多返回 `rejectReason` / `auditRemark` / `applyRemark` ——
 * 后端 `AuditApplicationVO.of(..., includeAuditNote)` 用这个开关区分两套口径：
 * 列表不返回，避免一屏之内把驳回理由铺满；详情才给全。
 */
export function getAuditDetail(applicationId) {
  return request({ url: `/admin/companion/audit/${applicationId}`, method: 'get' })
}

/** 审核：{ approved: true/false, reason } —— 驳回时 reason 必填 */
export function auditCompanion(applicationId, data) {
  return request({ url: `/admin/companion/audit/${applicationId}`, method: 'post', data })
}

/* ---------------- 用户管理 ---------------- */

export function listUsers(params) {
  return request({ url: '/admin/user', method: 'get', params })
}

export function disableUser(userId, data) {
  return request({ url: `/admin/user/${userId}/disable`, method: 'post', data })
}

/**
 * 解封用户。
 *
 * 后端契约（{@code docs/api/08-admin.md} §6）：请求体 {@code remark} 可选，
 * 但接口签名是 {@code @RequestBody UserEnableDTO} —— body 缺失会触发
 * {@code HttpMessageNotReadableException}。调用方不写备注时也要发空对象，
 * 不能省略 data，否则 Spring 把"没 body"也当成"格式不对"误报。
 */
export function enableUser(userId, data = {}) {
  return request({ url: `/admin/user/${userId}/enable`, method: 'post', data })
}

/**
 * 重置密码。
 *
 * 后端契约（{@code docs/api/08-admin.md} §7）：
 * - {@code remark} 必填，且是审计字段（谁、为什么重置）—— 调用方必须经表单收集原因再传入。
 * - {@code newPassword} 可选：管理员指定的新口令（6-32 位且需同时包含字母与数字）。
 *   传了则用户用该口令登录，由管理员线下/电话转交（平台没有短信通道）；
 *   不传则沿用部署配置的默认口令。
 *
 * ⚠️ **响应体不含任何口令字段**（安全验收判据，见
 * {@code docs/security/安全评估报告-2026-10-18.md} §8.2 P0-3）。
 * 管理员要转交的那份口令由**前端**用他刚输入的值展示，服务端不回传、不记录。
 *
 * ⚠️ 平台没有短信通道，调用方**不得**声称口令已送达用户。
 */
export function resetUserPassword(userId, data) {
  return request({ url: `/admin/user/${userId}/reset-password`, method: 'post', data })
}

/* ---------------- 订单与纠纷 ---------------- */

export function listAllOrders(params) {
  return request({ url: '/admin/order', method: 'get', params })
}

/** 纠纷处理：强制把订单置为终态 */
export function arbitrateOrder(orderId, data) {
  return request({ url: `/admin/order/${orderId}/arbitrate`, method: 'post', data })
}

/* ---------------- 投诉处理 ---------------- */

export function listComplaints(params) {
  return request({ url: '/admin/complaint', method: 'get', params })
}

export function handleComplaint(complaintId, data) {
  return request({ url: `/admin/complaint/${complaintId}/handle`, method: 'post', data })
}

/**
 * 管理员裁定评价有效性（E4 评价公信力闭环）。
 *
 * 本期仅接受 {@code isValid:false}（单向裁定）；后端对 isValid=true 返 400。
 * @param {number} reviewId 评价 ID
 * @param {{isValid: false, reason: string}} data 理由 10–200 字
 */
export function rulingReview(reviewId, data) {
  return request({ url: `/admin/review/${reviewId}/validity`, method: 'post', data })
}

/* ---------------- 操作日志 ---------------- */

export function listOperLogs(params) {
  return request({ url: '/admin/oper-log', method: 'get', params })
}

/* ---------------- 履约风险中心（V3.1 · T6） ---------------- */

/**
 * 四档风险计数（整口径，只统计待接单订单）。
 *
 * 四个字段恒定全部返回，某档为 0 时返回 0 而不是省略 —— 卡片据此显示数字，
 * 不必再做 undefined 兜底。
 * 返回：{ manualRequired, urgent, warning, normal }
 */
export function getFulfillmentSummary() {
  return request({ url: '/admin/fulfillment/summary', method: 'get' })
}

/**
 * 风险订单列表。
 *
 * @param {{ page: number, size: number, risk?: string }} params
 *   risk 缺省返回全部等级；只能是 NORMAL / WARNING / URGENT / MANUAL_REQUIRED，
 *   传别的后端返 400 且**不静默返回全量**。
 * 排序固定为「等级降序 → 等待时长降序 → id 降序」，接口不接受排序参数。
 */
export function listFulfillmentRisks(params) {
  return request({ url: '/admin/fulfillment/risk-list', method: 'get', params })
}


/**
 * 改派陪诊员（V3.1 · T7）。
 *
 * 仅对「待接单且尚无接单人」的订单开放；已接单的订单要换人走纠纷仲裁。
 * 服务端会再校验目标陪诊员的资质与接单状态，前端不做第二套判定。
 * @param {number} orderId
 * @param {{ companionId: number, reason: string }} data reason 10–200 字
 */
export function reassignOrder(orderId, data) {
  return request({ url: `/admin/order/${orderId}/reassign`, method: 'post', data })
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

/**
 * 指标字典只读导出（V3.6 · T7）
 *
 * GET /api/admin/metrics
 * 出参：version（字典版本）/ total（指标条数）/
 *      metrics[]{ key, name, scope, period, source, formula, caliberVersion, endpoints }
 *
 * ⚠️ 导出的是**口径定义**，不是统计值 —— 看板上的数在各自的统计端点。
 * ⚠️ 口径不可在运行时修改，本端点只读。
 */
export function getMetricDictionary() {
  return request({ url: '/admin/metrics', method: 'get' })
}
