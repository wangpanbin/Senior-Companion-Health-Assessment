import request from '@/utils/request'

/**
 * 评价与投诉接口
 * 对应文档：docs/api/06-review-complaint.md
 * 负责模块：M7 评价与投诉（前端 A / 后端 B）
 *
 * 约束：只有「已完成」状态的订单才能评价；同一订单只能评价一次。
 */

/* ---------------- 评价 ---------------- */

/** 提交评价（家属） */
export function createReview(data) {
  return request({ url: '/review', method: 'post', data })
}

/** 查询某订单的评价 */
export function getReviewByOrder(orderId) {
  return request({ url: `/review/order/${orderId}`, method: 'get' })
}

/** 陪诊员收到的评价列表 */
export function listCompanionReviews(companionId, params) {
  return request({ url: `/review/companion/${companionId}`, method: 'get', params })
}

/** 陪诊员评分聚合：平均分、评价数、星级分布 */
export function getCompanionScore(companionId) {
  return request({ url: `/review/companion/${companionId}/score`, method: 'get' })
}

/* ---------------- 投诉 ---------------- */

export function createComplaint(data) {
  return request({ url: '/complaint', method: 'post', data })
}

export function listMyComplaints(params) {
  return request({ url: '/complaint', method: 'get', params })
}

export function getComplaint(complaintId) {
  return request({ url: `/complaint/${complaintId}`, method: 'get' })
}
