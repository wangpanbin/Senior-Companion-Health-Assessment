import request from '@/utils/request'

/**
 * 产品反馈接口（V6）
 * 对应文档：docs/api/15-product-feedback.md
 * 负责模块：M2（客户端入口）/ M9（管理端处理）
 *
 * 与「评价」「投诉」的关键区别：**这条通道与订单完全解耦**。
 * 用户提的是产品本身（"按钮太小"、"希望支持多老人一键下单"），
 * 因此入参里没有 orderId，也不会有被投诉人。
 *
 * 三条与前端有关的硬口径：
 *  - 提交人 / 角色由服务端从登录态取，**前端不传，也传不了**；
 *  - 截图先走 {@link uploadFile}(bizType='FEEDBACK') 拿到 url，再随反馈一起提交（最多 3 张）；
 *  - 处理说明**只在终态**（已采纳 / 未采纳）返回；`PROCESSING` 阶段拿到的是 `undefined`，
 *    页面不要自己补一句"暂无结论"以外的任何推测文案。
 */

/**
 * 提交产品反馈
 * @param {{type: 'FEATURE'|'EXPERIENCE'|'DEFECT'|'OTHER', content: string, images?: string[]}} data
 *   content 10–1000 字（trim 后）；命中敏感词后端返回 6003
 * @returns {Promise<{feedbackId:number, status:string, statusLabel:string}>}
 */
export function createFeedback(data) {
  return request({ url: '/feedback', method: 'post', data })
}

/**
 * 我的反馈列表（分页）
 * @param {{page?: number, size?: number, status?: string}} params
 * @returns {Promise<{total:number, page:number, size:number, pages:number, records:Array}>}
 */
export function listMyFeedback(params) {
  return request({ url: '/feedback/mine', method: 'get', params })
}

/**
 * 反馈详情：仅提交人本人或管理员可见，其余（含不存在）统一返回 12001。
 * @param {number} id 反馈 ID
 */
export function getFeedback(id) {
  return request({ url: `/feedback/${id}`, method: 'get' })
}
