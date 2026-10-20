/**
 * 老人健康协同的展示口径（V3.2 · T-0 / FR-Overview / FR-Timeline）
 * 对应文档：docs/api/12-elder-health.md
 *
 * 为什么前端不自己算指标：所有统计口径由后端统一计算
 * （docs/spec/metric-dictionary.md 指标字典），前端再算一遍，
 * 迟早会在某个边界上和管理端、陪诊员端分叉，而这种分叉没人能自查出来。
 * 所以这里只做「分类 → 视觉」的映射，以及把后端算好的数字原样显示。
 */

/** 时间线分类 → 视觉。key 决定色条，icon 决定图形 */
export const TIMELINE_CATEGORIES = {
  ORDER: { key: 'order', icon: 'Tickets', label: '订单' },
  CHECKIN: { key: 'checkin', icon: 'Location', label: '陪诊打卡' },
  MEDICATION: { key: 'medication', icon: 'FirstAidKit', label: '用药' },
  MESSAGE: { key: 'message', icon: 'Bell', label: '消息提醒' }
}

const DEFAULT_CATEGORY = { key: 'default', icon: 'Document', label: '其他' }

/**
 * 取某个时间线分类的视觉配置。
 * @param {string} [category] 后端下发的 category
 * @returns {{key: string, icon: string, label: string}}
 */
export function timelineCategoryTone(category) {
  return TIMELINE_CATEGORIES[category] || DEFAULT_CATEGORY
}

/**
 * 合规词表：这些词不允许出现在健康中心的文案里。
 *
 * 本项目不做诊断、不开药方（AGENTS.md §0.1），健康中心只陈述事实
 * ——「今日有 1 次服药任务未确认」可以说，「老人今天健康状况较差」不能说。
 * 词表同时被前端单测与全仓文案扫描共用。
 */
export const FORBIDDEN_JUDGMENT_WORDS = [
  '诊断',
  '建议服用',
  '推荐剂量',
  '病情',
  '健康状况',
  '疾病',
  '可能是',
  '痊愈',
  '恶化',
  '危险'
]

/**
 * 文案里是否出现了结论式判断词。
 * @param {string} [text] 待检查的文案
 * @returns {boolean} 命中返回 true
 */
export function containsJudgment(text) {
  if (!text) return false
  return FORBIDDEN_JUDGMENT_WORDS.some((word) => text.includes(word))
}

/**
 * 用药完成率文案。
 *
 * 后端算好的字符串，前端**原样展示**，不做任何再计算（G8）。
 * 只有后端没给值时才兜底成 0.00%。
 *
 * @param {string} [rate] 后端下发的 completionRate
 * @returns {string}
 */
export function completionRateText(rate) {
  return typeof rate === 'string' && rate ? rate : '0.00%'
}

/**
 * 今日用药的「已服 / 总数」文案。
 *
 * 这不是指标计算 —— taken 与 total 都是后端各自下发的计数，
 * 这里只把两个数并排放，中间不引入任何新的除法。
 *
 * @param {{total?: number, taken?: number}} [medication] 今日用药计数
 * @returns {string} 形如 "2/3"
 */
export function todayMedicationText(medication) {
  const total = Number(medication?.total) || 0
  const taken = Number(medication?.taken) || 0
  return `${taken}/${total}`
}

/**
 * 概览里的「待处理」文案。
 * @param {number} [count] 待处理事项条数
 * @returns {string}
 */
export function pendingItemText(count) {
  const n = Number(count) || 0
  return n > 0 ? `${n} 项` : '无'
}

/**
 * 事件时间文案：只显示「月-日 时:分」，完整时间戳在 title 属性里备查。
 * @param {string} [occurredAt] 后端下发的时间戳
 * @returns {string}
 */
export function eventTimeText(occurredAt) {
  if (!occurredAt) return ''
  const value = String(occurredAt)
  return value.length >= 16 ? value.slice(5, 16) : value
}
