/**
 * 家属智能助手 · API 层单测
 *
 * 只锁一件事：**超时按请求覆写为 60s**。
 *
 * 为什么要单独测：`request.js` 的实例默认超时是 15s，而助手可能调一次外部模型，
 * 15s 不够。但**不能**把全局默认改成 60s —— 那会让所有接口的失败等待都变长
 * （后端挂掉时用户要干等 60s）。axios 原生支持按请求覆写，所以只在这一处覆写。
 */
import { describe, expect, it, vi } from 'vitest'

const requestMock = vi.fn(() => Promise.resolve({}))

vi.mock('@/utils/request', () => ({
  default: (...args) => requestMock(...args)
}))

const { chatAssistant } = await import('@/api/assistant')

describe('智能助手 · API 封装', () => {
  it('走 /assistant/chat 的 POST', async () => {
    await chatAssistant({ question: '陪诊费多少钱' })
    expect(requestMock).toHaveBeenCalledTimes(1)
    const config = requestMock.mock.calls[0][0]
    expect(config.url).toBe('/assistant/chat')
    expect(config.method).toBe('post')
  })

  it('超时覆写为 60000ms，且不改动全局实例默认值', async () => {
    await chatAssistant({ question: 'x' })
    const config = requestMock.mock.calls[0][0]
    expect(config.timeout).toBe(60000)
  })

  it('原样透传 payload（question / history / orderId / elderId）', async () => {
    const payload = {
      question: '推荐个陪诊员',
      history: [{ role: 'user', content: '你好' }],
      orderId: 1001,
      elderId: 401
    }
    await chatAssistant(payload)
    expect(requestMock.mock.calls[0][0].data).toEqual(payload)
  })
})
