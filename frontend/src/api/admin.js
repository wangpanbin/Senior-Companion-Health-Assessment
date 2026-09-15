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

export function enableUser(userId) {
  return request({ url: `/admin/user/${userId}/enable`, method: 'post' })
}

export function resetUserPassword(userId) {
  return request({ url: `/admin/user/${userId}/reset-password`, method: 'post' })
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
