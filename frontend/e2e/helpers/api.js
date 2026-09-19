/**
 * 接口层工具（绕过浏览器，直连后端）
 *
 * 用途：越权矩阵 / 归属校验 / 数据真值断言 —— 这些必须证明
 * **绕过前端也拦得住**，所以直接对后端发请求。
 *
 * 默认直连 8080（不走 vite 代理），这样测的是服务端本身。
 *
 * ⚠️ 坑：APIRequestContext 的 baseURL 若写成 `http://host:8080/api`，
 * 再传以 `/` 开头的相对路径，`new URL()` 会把 `/api` 整段替换掉
 * → 请求打到 `/auth/captcha`，返回 401。
 * 所以 baseURL 只给 origin，`/api` 前缀由本文件的 API 常量拼。
 */
import { request } from '@playwright/test'
import { PASSWORD } from './accounts'
import { readCaptcha } from '../fixtures/captcha'

/** 后端 origin（不含 /api） */
export const API_ORIGIN = process.env.NIANGLIN_API_ORIGIN || 'http://127.0.0.1:8080'
/** 统一接口前缀 */
export const API = '/api'

/** 用真实验证码 + 真口令做一次接口登录，返回令牌与用户信息 */
export async function apiLogin(account, password = PASSWORD) {
  const ctx = await request.newContext({ baseURL: API_ORIGIN })
  try {
    const capRes = await ctx.get(`${API}/auth/captcha`)
    const cap = await capRes.json()
    const key = cap?.data?.captchaKey
    if (!key) throw new Error(`取验证码失败（${capRes.status()}）：${JSON.stringify(cap)}`)
    const code = await readCaptcha(key)
    const res = await ctx.post(`${API}/auth/login`, {
      data: { username: account, password, captchaKey: key, captchaCode: code }
    })
    const body = await res.json()
    if (body?.code !== 200) throw new Error(`登录失败 ${account}：${JSON.stringify(body)}`)
    return { token: body.data.accessToken, refreshToken: body.data.refreshToken, userInfo: body.data.userInfo }
  } finally {
    await ctx.dispose()
  }
}

/**
 * 以某个令牌构造一个 APIRequestContext（调用方负责 dispose）。
 * 传 adminByDefault=true 时拿 admin 令牌；否则需先自取令牌。
 */
export async function apiAs(token) {
  return request.newContext({
    baseURL: API_ORIGIN,
    extraHTTPHeaders: { Authorization: `Bearer ${token}` }
  })
}

/** 便捷：直接以某账号身份拿一个已带令牌的 context */
export async function apiAsAccount(account, password = PASSWORD) {
  const { token, userInfo } = await apiLogin(account, password)
  const ctx = await apiAs(token)
  return { ctx, token, userInfo, dispose: () => ctx.dispose() }
}
