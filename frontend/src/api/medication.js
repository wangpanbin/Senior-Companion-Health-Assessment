import request from '@/utils/request'

/**
 * 用药管理接口
 * 对应文档：docs/api/05-medication.md
 * 负责模块：M6 用药管理与漏服提醒（D 建表 / B 定时任务 / A 日历页）
 *
 * ⚠️ 合规红线：本模块只做「记录与提醒」，不做诊断、不开药方。
 *    药品字典只提供通用信息，页面必须展示免责声明。
 */

/* ---------------- 药品字典 ---------------- */

export function listMedicineDict(params) {
  return request({ url: '/medication/dict', method: 'get', params })
}

export function getMedicine(medicineId) {
  return request({ url: `/medication/dict/${medicineId}`, method: 'get' })
}

/* ---------------- 用药计划 ---------------- */

export function listMedicationPlans(params) {
  return request({ url: '/medication/plan', method: 'get', params })
}

export function createMedicationPlan(data) {
  return request({ url: '/medication/plan', method: 'post', data })
}

export function updateMedicationPlan(planId, data) {
  return request({ url: `/medication/plan/${planId}`, method: 'put', data })
}

/** 停用（不物理删除，保留历史服药记录） */
export function disableMedicationPlan(planId) {
  return request({ url: `/medication/plan/${planId}`, method: 'delete' })
}

/* ---------------- 服药任务 ---------------- */

/**
 * 服药日历
 * @param {object} params { elderId, startDate, endDate }
 */
export function getMedicationCalendar(params) {
  return request({ url: '/medication/task/calendar', method: 'get', params })
}

/** 今日待服任务 */
export function getTodayTasks(elderId) {
  return request({ url: '/medication/task/today', method: 'get', params: { elderId } })
}

/** 确认服药（家属或陪诊员代确认） */
export function confirmMedication(taskId, data) {
  return request({ url: `/medication/task/${taskId}/confirm`, method: 'post', data })
}
