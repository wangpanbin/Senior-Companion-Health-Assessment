/**
 * C23「迭代 2 · 精修」报告 · 产品截图采集（定稿版，复现报告中全部 16 张图）
 *
 * 前置（见 docs/deployment.md）：
 *   1) MySQL 3306 / Redis 6379 在线
 *   2) 后端 8080（dev）：.\tools\e2e\start-services.ps1
 *   3) 前端 5141：cd frontend; node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5141
 *   4) 助手 8081（可选，仅助手页需要）：cd ai-service; .\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8081
 *   5) python tools\e2e\l0_identity_gate.py 必须 10/10 再开拍
 *
 * 运行：cd frontend && node scripts/report-final-shots.mjs
 * 产物：<repo>/docs/reports/assets/c23-iter2/*.png
 *
 * 说明：全部为**真界面**截图（真登录、真接口、真种子数据）。验证码走 Redis 明文旁路
 *      （与 e2e fixtures/captcha.js 同口径），不做 OCR、不伪造、不拼图。
 *      部分图带 clip 是有意的：① 驾驶舱「今日订单」为 0 是种子数据在 8 月所致，
 *      故只取「异常中心 + 趋势（自定义 8 月区间）」；② 订单详情的服务照片在种子数据里缺失，
 *      裁掉该占位区避免误导；③ 桌面列表页顶部分段筛选在 1440 下有换行溢出，故家属端列表不入图。
 */
import { chromium } from '@playwright/test'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import fs from 'node:fs'
import path from 'node:path'

const execFileAsync = promisify(execFile)
const REDIS_CLI = process.env.REDIS_CLI || 'D:\\develop\\Redis-8.8.0\\redis-cli.exe'
const BASE = process.env.NIANGLIN_APP || 'http://127.0.0.1:5141'
const PASSWORD = process.env.NIANGLIN_SEED_PASSWORD || 'Nl@123456'
const ROOT = path.resolve(import.meta.dirname, '../..')
const OUT = path.join(ROOT, 'docs', 'reports', 'assets', 'c23-iter2')
fs.mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({ channel: 'chrome' })
const done = []

async function session(account, viewport, dsf = 1) {
  const ctx = await browser.newContext({
    baseURL: BASE,
    viewport,
    deviceScaleFactor: dsf,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  })
  const page = await ctx.newPage()
  let key = null
  await page.route('**/api/auth/captcha', async (r) => {
    const resp = await r.fetch()
    key = (await resp.json())?.data?.captchaKey ?? key
    try {
      await r.fulfill({ response: resp })
    } catch (e) {
      if (!String(e).includes('Route is already handled')) throw e
    }
  })
  await page.goto('/login')
  for (let i = 0; i < 60 && !key; i++) await page.waitForTimeout(200)
  const { stdout } = await execFileAsync(REDIS_CLI, ['GET', `captcha:${key}`], { windowsHide: true })
  const code = String(stdout).trim().replace(/^"|"$/g, '')
  await page.getByPlaceholder('请输入手机号或用户名').fill(account)
  await page.getByPlaceholder('请输入密码').fill(PASSWORD)
  await page.getByPlaceholder('请输入右侧 4 位验证码').fill(code)
  await page.locator('button.login__submit').click()
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 25000 })
  await page.unroute('**/api/auth/captcha')
  await page.waitForTimeout(900)
  return { ctx, page }
}

async function shoot(page, name, { clip, wait = 2400 } = {}) {
  await page.waitForLoadState('networkidle', { timeout: 12000 }).catch(() => {})
  await page.waitForTimeout(wait)
  await page.mouse.move(4, 4)
  const file = path.join(OUT, `${name}.png`)
  await page.screenshot({ path: file, animations: 'disabled', clip })
  done.push(`${name}.png`)
  console.log('  ✔', name, `${Math.round(fs.statSync(file).size / 1024)}KB`)
}

// ① 管理端（1920×1200）
{
  const { ctx, page } = await session('admin', { width: 1920, height: 1200 })

  await page.goto('/admin/order')
  await shoot(page, '01-管理端订单管理')

  await page.goto('/admin/operations')
  await page.waitForTimeout(1800)
  await page.getByRole('button', { name: '自定义' }).first().click()
  await page.waitForTimeout(1000)
  await page.locator('input[placeholder*="开始"]').first().fill('2026-08-01')
  await page.locator('input[placeholder*="结束"]').first().fill('2026-08-31')
  await page.locator('input[placeholder*="结束"]').first().press('Enter')
  await page.waitForTimeout(2800)
  await shoot(page, '02-运营驾驶舱', { clip: { x: 200, y: 306, width: 1712, height: 884 }, wait: 600 })

  await page.goto('/admin/review-audit')
  await shoot(page, '03-评价巡查')

  await page.goto('/admin/settlement')
  await shoot(page, '04-结算核对')

  await page.goto('/admin/fulfillment')
  await shoot(page, '05-履约风险')
  await ctx.close()
}

// ② 家属（桌面 1440×900）：消息中心 / 订单详情 / 智能助手 / 陪诊员工作台（同一桌面档）
{
  const { ctx, page } = await session('fam001', { width: 1440, height: 900 })

  await page.goto('/family/message')
  await shoot(page, '06-家属消息中心')

  await page.goto('/family/order/1031')
  await shoot(page, '09-订单详情时间线', { wait: 2600 })

  await page.goto('/family/assistant')
  await page.waitForTimeout(1200)
  await page.getByPlaceholder('说说你的问题，例如：陪诊费怎么算？').first().fill('检查前需要空腹多久？')
  await page.getByRole('button', { name: '发送' }).first().click()
  await page.waitForTimeout(6500)
  await shoot(page, '10-智能助手', { wait: 1200 })
  await ctx.close()
}

// ③ 陪诊员（桌面 1440×1000）
{
  const { ctx, page } = await session('comp007', { width: 1440, height: 1000 })
  await page.goto('/companion/workbench')
  await shoot(page, '07-陪诊员工作台')
  await ctx.close()
}

// ④ 陪诊员执行页（移动 390×844）
{
  const { ctx, page } = await session('comp007', { width: 390, height: 844 }, 2)
  await page.goto('/companion/execute/1007')
  await shoot(page, '08-订单执行打卡', { wait: 2600 })
  await ctx.close()
}

// ⑤ 家属移动端（390×844）
{
  const { ctx, page } = await session('fam001', { width: 390, height: 844 }, 2)

  await page.goto('/family/home')
  await shoot(page, '11-家属移动首页')

  await page.goto('/family/medication')
  await page.waitForTimeout(1500)
  const planTab = page.getByText('计划', { exact: true }).first()
  if (await planTab.count()) {
    await planTab.click()
    await page.waitForTimeout(1800)
  }
  await shoot(page, '12-家属用药计划')

  await page.goto('/family/order/step1')
  await page.waitForTimeout(1600)
  const elderCard = page.getByText('张*海').first()
  if (await elderCard.count()) {
    await elderCard.click()
    await page.waitForTimeout(600)
    await page.getByRole('button', { name: '下一步' }).first().click()
    await page.waitForTimeout(1800)
  }
  await shoot(page, '13-下单选择医院时间', { wait: 1800 })
  await ctx.close()
}

// ⑥ 老人端（390×844）
{
  const { ctx, page } = await session('elder001', { width: 390, height: 844 }, 2)

  await page.goto('/elder/home')
  await shoot(page, '14-老人首页')

  await page.goto('/elder/medication')
  await page.waitForTimeout(1500)
  const planTab = page.getByText('计划', { exact: true }).first()
  if (await planTab.count()) {
    await planTab.click()
    await page.waitForTimeout(1800)
  }
  await shoot(page, '15-老人用药计划')

  await page.goto('/elder/health')
  await shoot(page, '16-老人健康中心', { wait: 2200 })
  await ctx.close()
}

await browser.close()
console.log(`\n共 ${done.length} 张：`, done.join(' '))
