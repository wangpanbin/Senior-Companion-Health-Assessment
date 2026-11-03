import request from '@/utils/request'

/**
 * 体征记录接口（V4 · P2-2）
 * 对应文档：docs/api/12-elder-health.md §6 ~ §8
 *
 * 统一口径：只记录测量数值，接口不返回任何解释、区间或判断字段。
 * 三端共用同一份 VO，展示文本 `valueText` 由后端拼好，前端不再拼字符串。
 */

/* ---------------- 家属端 ---------------- */

/**
 * 某位老人的体征时间线（按测量时间倒序分页）
 * @param {number|string} elderId 老人档案 ID
 * @param {{vitalType?: string, startDate?: string, endDate?: string, page?: number, size?: number}} [params]
 */
export function getElderVitals(elderId, params) {
  return request({ url: `/family/elder/${elderId}/vitals`, method: 'get', params })
}

/**
 * 某位老人的体征趋势（按测量时间升序，最多 500 点）
 * @param {number|string} elderId 老人档案 ID
 * @param {{vitalType: string, startDate?: string, endDate?: string}} params vitalType 必传
 */
export function getElderVitalTrend(elderId, params) {
  return request({ url: `/family/elder/${elderId}/vitals/trend`, method: 'get', params })
}

/**
 * 家属代录一条体征
 * 请求体不含 elderId / sourceType / operatorUserId / orderId —— 由服务端推导与写入
 * @param {number|string} elderId 老人档案 ID
 * @param {object} data VitalSignCreateDTO
 */
export function createElderVital(elderId, data) {
  return request({ url: `/family/elder/${elderId}/vitals`, method: 'post', data })
}

/**
 * 撤回本人代录的体征记录
 * @param {number|string} recordId 体征记录 ID
 */
export function withdrawElderVital(recordId) {
  return request({ url: `/family/vitals/${recordId}`, method: 'delete' })
}

/* ---------------- 老人端（只读） ---------------- */

/**
 * 我自己的体征时间线
 * 不传 elderId —— 老人端只看本人，服务端从登录态取档案 ID
 * @param {{vitalType?: string, startDate?: string, endDate?: string, page?: number, size?: number}} [params]
 */
export function getMyVitals(params) {
  return request({ url: '/elder/vitals', method: 'get', params })
}

/**
 * 我自己的体征趋势
 * @param {{vitalType: string, startDate?: string, endDate?: string}} params vitalType 必传
 */
export function getMyVitalTrend(params) {
  return request({ url: '/elder/vitals/trend', method: 'get', params })
}

/* ---------------- 陪诊员端（服务中代录） ---------------- */

/**
 * 陪诊员服务中为订单关联老人代录体征
 * @param {number|string} orderId 订单 ID
 * @param {object} data VitalSignCreateDTO
 */
export function createCompanionVital(orderId, data) {
  return request({ url: `/execution/${orderId}/vitals`, method: 'post', data })
}

/**
 * 撤回本单在服务中代录的体征
 * @param {number|string} orderId 订单 ID
 * @param {number|string} recordId 体征记录 ID
 */
export function withdrawCompanionVital(orderId, recordId) {
  return request({ url: `/execution/${orderId}/vitals/${recordId}`, method: 'delete' })
}
