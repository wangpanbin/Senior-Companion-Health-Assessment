/**
 * 响应拦截器单测 · V4V5 联调缺陷回归（DEF-2 / DEF-3）
 *
 * 背景（2026-11-14 ego-browser 联调发现）：
 *
 * DEF-2：注销遇进行中订单时后端回 `11001 + data.pendingOrderCount`，
 *        但拦截器 reject 只带 `code`，把 `data` 丢了 —— 前端
 *        `err?.data?.pendingOrderCount` 永远是 undefined，toast 显示
 *        「您还有 笔进行中的订单」（数字缺失）。
 *
 * DEF-3：会话失效后第一个 401 弹出「去登录」对话框；`clearAuth()` 使
 *        refreshToken 变空，**后续每个并发 401 都再开一个对话框**，
 *        现场堆叠了几十个。handleLogout 必须幂等。
 *
 * 测试方式：axios 实例被 mock，直接调用 request.js 注册的响应拦截器
 * 处理器（不发真实请求、不依赖网络）。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'

/* ---------------- mock：axios（只截获拦截器注册） ---------------- */
const captured = vi.hoisted(() => ({ response: [] }))

vi.mock('axios', () => {
  const instance = {
    interceptors: {
      request: { use: () => {} },
      response: {
        use: (onFulfilled, onRejected) => captured.response.push({ onFulfilled, onRejected })
      }
    },
    defaults: {},
    get: vi.fn(),
    post: vi.fn()
  }
  // 默认导出上的 post 是刷新令牌用的那条通道（request.js 里动态 import axios）
  return { default: { create: () => instance, post: vi.fn() } }
})

/* ---------------- mock：element-plus（只记调用） ---------------- */
vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
    closeAll: vi.fn()
  },
  ElMessageBox: {
    // reject 模拟「用户关掉对话框未跳转」——用于验证对话框关闭后的复位逻辑
    alert: vi.fn(() => Promise.reject(new Error('dismissed')))
  }
}))

/* ---------------- mock：auth（无 refreshToken → 直接走登出） ---------------- */
vi.mock('@/utils/auth', () => ({
  getToken: vi.fn(() => 'access-token'),
  getRefreshToken: vi.fn(() => null),
  setToken: vi.fn(),
  clearAuth: vi.fn()
}))

const { ElMessage, ElMessageBox } = await import('element-plus')
const { clearAuth, getRefreshToken } = await import('@/utils/auth')
const axiosModule = await import('axios')
// 动态 import：确保上面的 mock 全部注册后再加载被测模块
await import('@/utils/request')

/** 成功通道处理器（业务错误走这里：HTTP 200 + code≠200） */
function onSuccess() {
  return captured.response[0].onFulfilled
}

/** 错误通道处理器（HTTP 非 2xx 走这里，如 401） */
function onError() {
  return captured.response[0].onRejected
}

/** 构造 401 错误对象（axios error 形状） */
function err401() {
  return {
    response: { status: 401, data: { code: 401, message: '未登录或登录已失效' } },
    config: { url: '/some/api', headers: {} }
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  // vi.clearAllMocks() 只清调用记录，不清 mockReturnValue 设过的返回值：
  // 显式复位，保证「有 refreshToken」的状态不会漏到下一个用例
  getRefreshToken.mockReturnValue(null)
  axiosModule.default.post.mockReset()
})

/* ================================================================== *
 * DEF-2：业务错误必须把后端 data 透传给调用方
 * ================================================================== */

describe('DEF-2 · 业务错误透传 data（11001 pendingOrderCount）', () => {
  const body = {
    code: 11001,
    message: '存在进行中的订单，请先完成或取消后再注销',
    data: { pendingOrderCount: 3 }
  }

  it('reject 的错误对象携带 code 与 data', async () => {
    const rejected = await onSuccess()({ status: 200, data: body, config: {} }).catch((e) => e)
    expect(rejected.code).toBe(11001)
    expect(rejected.data).toEqual({ pendingOrderCount: 3 })
  })

  it('data 为 null 的普通业务错误不受影响（结构与既往一致）', async () => {
    const rejected = await onSuccess()({
      status: 200,
      data: { code: 4001, message: '参数错误', data: null },
      config: {}
    }).catch((e) => e)
    expect(rejected.code).toBe(4001)
    expect(rejected.data).toBeNull()
  })

  it('仍弹出后端 message（全局兜底 toast 行为不变）', async () => {
    await onSuccess()({ status: 200, data: body, config: {} }).catch(() => {})
    expect(ElMessage.error).toHaveBeenCalledWith(body.message)
  })
})

/* ================================================================== *
 * DEF-3：会话失效对话框必须幂等
 * ================================================================== */

describe('DEF-3 · handleLogout 幂等（并发 401 只弹一个对话框）', () => {
  it('3 个并发 401 只开 1 个「去登录」对话框，且 clearAuth 被调用', async () => {
    await Promise.allSettled([onError()(err401()), onError()(err401()), onError()(err401())])
    expect(ElMessageBox.alert).toHaveBeenCalledTimes(1)
    expect(clearAuth).toHaveBeenCalled()
    // 对话框内容仍是后端给的真实原因（联调观察项：不要一律显示「服务挂了」）
    expect(ElMessageBox.alert).toHaveBeenCalledWith(
      '未登录或登录已失效',
      '提示',
      expect.objectContaining({ confirmButtonText: '去登录' })
    )
  })

  it('用户关掉对话框后复位：下一次会话失效仍能提示', async () => {
    await onError()(err401()).catch(() => {})
    expect(ElMessageBox.alert).toHaveBeenCalledTimes(1)
    // 上一条用例的对话框因 reject 已复位，这里再触发一次应当再弹
    await onError()(err401()).catch(() => {})
    expect(ElMessageBox.alert).toHaveBeenCalledTimes(2)
  })
})

/* ================================================================== *
 * F1：刷新失败时，挂起的请求必须被 reject（不能永远悬挂）
 * ================================================================== */

describe('F1 · 刷新失败时挂起的请求被 reject，而不是悬挂', () => {
  it('刷新失败 → 挂起的请求以 401 失败，且不会永远 pending', async () => {
    getRefreshToken.mockReturnValue('refresh-token')
    axiosModule.default.post.mockRejectedValue(new Error('refresh failed'))

    // 第一个请求触发刷新（refreshing = true）
    const first = onError()(err401()).catch((e) => e)
    // 第二个请求撞上「刷新在途」→ 进 pendingQueue
    const second = onError()(err401())

    // 用 race 证明「它 settle 了」，而不是「它还挂着」
    const settled = await Promise.race([
      second.then(
        () => 'resolved',
        (e) => e
      ),
      new Promise((resolve) => setTimeout(() => resolve('timeout'), 200))
    ])

    expect(settled).not.toBe('timeout')
    expect(settled.code).toBe(401)
    expect(settled.message).toContain('登录状态已失效')
    await first
  })
})
