import request from '@/utils/request'

/**
 * 家属智能助手接口
 * 对应文档：docs/api/13-assistant.md
 * 负责模块：M14 家属智能助手（V3.7 · 后端 B / 前端 A）
 *
 * ⚠️ 能力边界（ADR-0020 决策 1）：助手只解答**平台服务规则**类问题，
 *    诊断 / 用药 / 剂量 / 病情判断一律由后端固定拒答并引导线下就医。
 *    前端不做任何本地「补答」，那会绕过服务端的合规护栏。
 *
 * ⚠️ 推荐 ≠ 派单：助手只解释系统已算好的候选陪诊员，
 *    接单权始终在陪诊员本人（ADR-0016 决策 4.4）。
 *    页面不得出现「已指派 / 已分配 / 自动派单」这类表述。
 */

/** 单次问答超时（毫秒） */
const CHAT_TIMEOUT = 60000

/**
 * 智能助手问答
 *
 * @param {{question: string, history?: Array<{role: string, content: string}>,
 *          orderId?: number, elderId?: number}} data
 * @returns {Promise<{answer: string, intent: string,
 *          actions: Array<{type: string, payload: object}>,
 *          sources: string[], degraded: boolean}>}
 *
 * 超时按请求覆写（axios 原生语义），**不改** request.js 的全局 15s 默认值 ——
 * 助手可能调一次外部模型，15s 不够；其它接口的失败等待时长也不该被拖长。
 */
export function chatAssistant(data) {
  return request({ url: '/assistant/chat', method: 'post', data, timeout: CHAT_TIMEOUT })
}
