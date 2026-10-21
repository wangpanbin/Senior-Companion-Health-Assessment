/**
 * A-2 · 新增功能域的路由可达性核对（V3.1–V3.7）
 *
 * 跑法（后端 8080 + dev server 5141 就绪后）：
 *     cd frontend && node e2e/a2-reachability.mjs
 *
 * ⚠️ **本脚本不是 playwright spec**，所以不受 `playwright.config.js` 的 `webServer` 托管 ——
 *    而 `webServer` 跑完会把 vite 收掉。因此跑它之前要**自己把前端常驻起来**：
 *    `cd frontend && pnpm run dev`（另开一个终端），确认 http://127.0.0.1:5141/ 能打开再跑本脚本。
 *    它也不进常规回归（playwright 只收 `specs/*.spec.js`），是「可复跑的工具」而不是 CI 用例。
 *
 * 判据（与 e2e/helpers/pageAudit.js 的 sweep 同源，四条）：
 *   ① 落地路径 === 目标路由（防「被路由守卫静默重定向回角色主页」的假绿）
 *   ② 页面有实质内容（正文 > 20 字）
 *   ③ 无 console error / pageerror
 *   ④ 无 ≥400 的接口响应
 *
 * ⚠️ 例外：meta.mobileOnly 的路由在桌面形态下由 NlDesktopShell 渲染
 *    NlMobileOnlyNotice（ADR-0008），此时落地路径本就不是目标路由 ——
 *    判据改成「出现 .nl-mobile-only__title 即视为可达」并单列标注。
 *
 * 产物：reports/playwright/a2-shots/*.png（截图，含真实页面状态）
 */
import { chromium } from '@playwright/test'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { mkdirSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PASSWORD } from './helpers/accounts.js'
import { collectPageIssues } from './helpers/pageAudit.js'

const exec = promisify(execFile)
const __dirname = dirname(fileURLToPath(import.meta.url))
// ⚠️ 前端端口**不要写死**：vite.config.js 的默认端口是 5141
//    （原 5173 落在 Windows Hyper-V 排除端口段 5152-5251 内才改的），
//    与 tools/e2e/probe_ws_handshake.py 的 VITE_PORT 口径保持一致。
const WEB_PORT = process.env.VITE_PORT || '5141'
const BASE = process.env.BASE_URL || `http://127.0.0.1:${WEB_PORT}`
const API_ORIGIN = process.env.NIANGLIN_API_ORIGIN || 'http://127.0.0.1:8080'
const REDIS_CLI = process.env.REDIS_CLI || 'D:/develop/Redis-8.8.0/redis-cli.exe'
const OUT = resolve(__dirname, '../../reports/playwright/a2-shots')
const TOKEN_KEY = 'nianglin_access_token'
const USER_INFO_KEY = 'nianglin_user_info'

const VIEWPORTS = [
  { name: 'desktop', width: 1280, height: 900 },
  { name: 'mobile', width: 390, height: 844 }
]

/** 八个新增功能域的代表页面（path 抄自 frontend/src/router/routes.js） */
const PAGES = [
  { domain: 'V3.1 履约保障', path: '/admin/fulfillment', account: 'admin' },
  { domain: 'V3.1 履约保障', path: '/family/order/1001', account: 'fam001' },
  { domain: 'V3.2 老人健康', path: '/family/health', account: 'fam001' },
  { domain: 'V3.2 老人健康', path: '/family/elder/401/health', account: 'fam001' },
  { domain: 'V3.2 老人健康', path: '/elder/health', account: 'elder001' },
  { domain: 'V3.3 陪诊员工作台', path: '/companion/workbench', account: 'comp007' },
  { domain: 'V3.3 陪诊员工作台', path: '/companion/income', account: 'comp007' },
  { domain: 'V3.4 智能调度', path: '/family/order/1001', account: 'fam001' },
  { domain: 'V3.5 运营驾驶舱', path: '/admin/operations', account: 'admin' },
  { domain: 'V3.6 数据闭环', path: '/admin/operations', account: 'admin' },
  { domain: 'V3.7 家属助手', path: '/family/assistant', account: 'fam001' }
]

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
  return body.data
}

function stamp() {
  return new Date().toISOString().replace(/[:.]/g, '-')
}

async function main() {
  mkdirSync(OUT, { recursive: true })
  const logins = {}
  for (const account of [...new Set(PAGES.map((p) => p.account))]) {
    logins[account] = await apiLogin(account)
  }

  const browser = await chromium.launch({ channel: 'chrome' })
  const results = []

  for (const vp of VIEWPORTS) {
    for (const item of PAGES) {
      const ctx = await browser.newContext({
        viewport: { width: vp.width, height: vp.height },
        locale: 'zh-CN',
        timezoneId: 'Asia/Shanghai'
      })
      await ctx.addInitScript(
        ([token, userInfo, tk, uk]) => {
          window.localStorage.setItem(tk, token)
          window.localStorage.setItem(uk, userInfo)
        },
        [
          logins[item.account].accessToken,
          JSON.stringify(logins[item.account].userInfo),
          TOKEN_KEY,
          USER_INFO_KEY
        ]
      )
      const page = await ctx.newPage()
      const issues = collectPageIssues(page)
      let note = ''
      let ok = false
      let landed = ''
      let textLen = 0
      try {
        await page.goto(BASE + item.path, { waitUntil: 'domcontentloaded', timeout: 20000 })
        await page.waitForTimeout(1200)
        landed = await page.evaluate(() => location.pathname)
        const text = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ').trim()
        textLen = text.length
        const mobileOnly = await page.locator('.nl-mobile-only__title').count()
        const shot = resolve(OUT, `${item.domain.replace(/[ ·/]/g, '_')}-${item.path.replace(/\//g, '_')}-${vp.name}-${stamp()}.png`)
        await page.screenshot({ path: shot, fullPage: true })

        if (mobileOnly > 0) {
          ok = true
          note = `mobile-only 提示页（ADR-0008，落地 ${landed}）`
        } else {
          const bad = []
          if (landed !== item.path) bad.push(`落地路径 ${landed} ≠ ${item.path}`)
          if (textLen <= 20) bad.push(`正文过短(${textLen})`)
          if (issues.errors.length) bad.push(`console/page 错误 ${JSON.stringify(issues.errors.slice(0, 2))}`)
          if (issues.apiFails.length) bad.push(`≥400 接口 ${JSON.stringify(issues.apiFails.slice(0, 3))}`)
          ok = bad.length === 0
          note = bad.join('；')
        }
      } catch (e) {
        note = '导航异常：' + String(e.message).slice(0, 160)
        ok = false
      } finally {
        issues.stop()
        await ctx.close()
      }
      results.push({ ...item, vp: vp.name, landed, textLen, ok, note })
      console.log(
        `  [${ok ? 'PASS' : 'FAIL'}] ${vp.name.padEnd(7)} ${item.domain.padEnd(16)} ${item.path.padEnd(28)} ` +
          `落地=${landed.padEnd(28)} ${note}`
      )
    }
  }
  await browser.close()

  const failed = results.filter((r) => !r.ok)
  console.log(`\n合计 ${results.length} 项（${PAGES.length} 页面 × ${VIEWPORTS.length} 形态），失败 ${failed.length} 项`)
  console.log(`截图目录：${OUT}`)
  if (failed.length) {
    console.log('\n=== 失败明细 ===')
    for (const f of failed) console.log(`  ${f.vp} ${f.path} -> ${f.note}`)
    process.exitCode = 1
  } else {
    console.log('✅ 路由可达性全绿')
  }
}

main().catch((e) => {
  console.error('A-2 脚本异常：', e)
  process.exitCode = 2
})
