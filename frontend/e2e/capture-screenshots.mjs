/**
 * 竞讲截图采集（convergence T3.3）。
 * 跑法：后端 + dev server 就绪后 `node e2e/capture-screenshots.mjs`
 * 产出：docs/reports/screenshots/<name>.png（≥10 张，文件名可对上页面）
 * 登录走 e2e 同款旁路：真实验证码 + 真口令（验证码明文取自 Redis）。
 *
 * 说明：登录逻辑内联而非复用 helpers/api.js —— api.js 内部是无扩展名
 * ESM import（Playwright 解析器支持，裸 node 不支持）。
 */
import { chromium } from '@playwright/test'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PASSWORD } from './helpers/accounts.js'

const exec = promisify(execFile)
const __dirname = dirname(fileURLToPath(import.meta.url))
const BASE = process.env.BASE_URL || 'http://127.0.0.1:5141'
const API_ORIGIN = process.env.NIANGLIN_API_ORIGIN || 'http://127.0.0.1:8080'
const REDIS_CLI = process.env.REDIS_CLI || 'D:/develop/Redis-8.8.0/redis-cli.exe'
const OUT = resolve(__dirname, '../../docs/reports/screenshots')

/** 用真实验证码 + 真口令做一次接口登录，返回令牌与用户信息（与 helpers/api.js 同款） */
async function apiLogin(account, password = PASSWORD) {
  const capRes = await fetch(`${API_ORIGIN}/api/auth/captcha`)
  const cap = await capRes.json()
  const key = cap?.data?.captchaKey
  if (!key) throw new Error(`取验证码失败：${JSON.stringify(cap)}`)
  const { stdout } = await exec(REDIS_CLI, ['GET', `captcha:${key}`], { windowsHide: true })
  const code = String(stdout || '').trim().replace(/^"|"$/g, '')
  const res = await fetch(`${API_ORIGIN}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: account, password, captchaKey: key, captchaCode: code })
  })
  const body = await res.json()
  if (body?.code !== 200) throw new Error(`登录失败 ${account}：${JSON.stringify(body)}`)
  return {
    token: body.data.accessToken,
    refreshToken: body.data.refreshToken,
    userInfo: body.data.userInfo
  }
}

// 与 src/utils/auth.js 的常量一致（该文件只导出读写函数，不导出键名）
const TOKEN_KEY = 'nianglin_access_token'
const USER_INFO_KEY = 'nianglin_user_info'
const REFRESH_TOKEN_KEY = 'nianglin_refresh_token'

// 页面清单：path 均抄自 frontend/src/router/routes.js；账号选「有真实数据」的种子用户
const SHOTS = [
  { name: 'login', path: '/login', account: null },
  { name: 'family-home', path: '/family/home', account: 'fam001' },
  { name: 'family-elder', path: '/family/elder', account: 'fam001' },
  { name: 'family-medication', path: '/family/medication', account: 'fam001' },
  { name: 'family-order-list', path: '/family/order', account: 'fam025' },
  { name: 'family-order-detail', path: '/family/order/1025', account: 'fam025' },
  { name: 'elder-home', path: '/elder/home', account: 'elder001' },
  { name: 'elder-medication', path: '/elder/medication', account: 'elder001' },
  { name: 'companion-hall', path: '/companion/hall', account: 'comp001' },
  { name: 'companion-order', path: '/companion/order', account: 'comp001' },
  { name: 'companion-income', path: '/companion/income', account: 'comp001' },
  { name: 'companion-execute', path: '/companion/execute/1007', account: 'comp007' },
  { name: 'admin-dashboard', path: '/admin/dashboard', account: 'admin' },
  { name: 'admin-companion-audit', path: '/admin/companion-audit', account: 'admin' },
  { name: 'admin-user', path: '/admin/user', account: 'admin' }
]

mkdirSync(OUT, { recursive: true })
// 与 playwright.config.js 同口径：用本机 Chrome（不下载 Chromium 包）
const browser = await chromium.launch({ channel: 'chrome' })

// 每张截图用独立 page：addInitScript 会累积，跨账号会互相污染登录态
for (const shot of SHOTS) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  if (shot.account) {
    const { token, userInfo, refreshToken } = await apiLogin(shot.account, PASSWORD)
    await page.addInitScript(
      ([t, u, r]) => {
        localStorage.setItem('nianglin_access_token', t)
        localStorage.setItem('nianglin_user_info', u)
        localStorage.setItem('nianglin_refresh_token', r)
      },
      [token, JSON.stringify(userInfo), refreshToken || '']
    )
  } else {
    await page.addInitScript(() => localStorage.clear())
  }
  await page.goto(BASE + shot.path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  await page.screenshot({ path: resolve(OUT, `${shot.name}.png`), fullPage: true })
  console.log('✓', shot.name)
  await page.close()
}
await browser.close()
