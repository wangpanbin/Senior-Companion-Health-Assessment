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
 * 后端契约（{@code docs/api/08-admin.md} §7）：{@code remark} 必填，
 * 且是审计字段（谁、为什么重置）—— 调用方必须经表单/prompt 收集原因再传入。
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

/* ---------------- 操作日志 ---------------- */

export function listOperLogs(params) {
  return request({ url: '/admin/oper-log', method: 'get', params })
}
