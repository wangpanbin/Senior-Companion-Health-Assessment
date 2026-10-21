/**
 * 履约风险的展示口径（V3.1 · T5）
 * 对应文档：docs/api/03-order.md §14
 * 负责模块：M9 管理后台 · V3.1 履约保障（docs/plan/plan.md 模块编号只到 M13，
 * 与 routes.js 的 module 字段保持一致，不另起 M14）
 *
 * 为什么前端不自己判断等级：等级由后端 `FulfillmentRiskEvaluator` 判定，
 * 家属端、管理端、订单列表必须显示同一个结论。前端再判一次，
 * 迟早会在某个阈值上和管理端分叉，而这种分叉没人能自查出来。
 * 所以这里只做「等级 → 图标 / 色条 / 语气」的视觉映射，文案一律用后端下发的
 * `fulfillmentRiskLabel`，映射表是全站唯一一份。
 */

/** 等级 → 视觉。key 决定色条与底色，icon 决定图形，rank 只用于「要不要出警示符」 */
export const FULFILLMENT_TONES = {
  NORMAL: { key: 'normal', icon: 'dot', rank: 0 },
  WARNING: { key: 'warning', icon: 'alert', rank: 1 },
  URGENT: { key: 'urgent', icon: 'alert', rank: 2 },
  MANUAL_REQUIRED: { key: 'manual', icon: 'alert', rank: 3 }
}

const DEFAULT_TONE = FULFILLMENT_TONES.NORMAL

/**
 * 取某个等级的视觉配置。
 * @param {string} [risk] 后端下发的 fulfillmentRisk
 * @returns {{key: string, icon: string, rank: number}}
 */
export function fulfillmentTone(risk) {
  return FULFILLMENT_TONES[risk] || DEFAULT_TONE
}

/** 履约风险只对「待接单」成立；订单被接走或取消后这条提示必须消失 */
export function isFulfillmentPending(order) {
  return !!order && order.status === 'PENDING'
}

/** 详情页整条状态条是否渲染 */
export function showFulfillmentBar(order) {
  return isFulfillmentPending(order) && !!order.fulfillmentRisk
}

/**
 * 列表页的轻量标记。
 *
 * 正常等待不返回任何东西 —— 列表是扫读界面，每张卡都挂一个「正常」标记
 * 只会把真正需要注意的那几条淹掉。
 *
 * @returns {null | {key: string, icon: string, rank: number, risk: string, label: string}}
 */
export function fulfillmentBadge(order) {
  if (!isFulfillmentPending(order)) return null
  const risk = order.fulfillmentRisk
  if (!risk || risk === 'NORMAL') return null
  return { ...fulfillmentTone(risk), risk, label: order.fulfillmentRiskLabel || '' }
}

/** 等待时长文案；分钟数由后端算好并做过负值钳制，这里只做展示 */
export function waitMinutesText(waitMinutes) {
  const minutes = Number(waitMinutes)
  if (!Number.isFinite(minutes) || minutes <= 0) return ''
  return `已等待 ${Math.floor(minutes)} 分钟`
}

/** 能否催单完全由后端 canUrge 决定，前端不做任何补充判断 */
export function canUrgeOrder(order) {
  return isFulfillmentPending(order) && order?.canUrge === true
}
