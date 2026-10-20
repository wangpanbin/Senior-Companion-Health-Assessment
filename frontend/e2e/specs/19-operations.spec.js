/**
 * 19 · V3.5 平台运营驾驶舱
 *
 * 覆盖 docs/spec/V3.5-tickets.md §八（T7 驾驶舱首页 / T8 前端测试）：
 *   1. 驾驶舱首页渲染（管理员登录态）
 *   2. 概览八项指标出现
 *   3. **四类异常卡片逐个点开，落地页必须可达**（不能 404、不能跳回登录页）—— 本版最重要的一条
 *   4. 趋势切换维度后重新向后端要数
 *   5. 自定义区间生效（带 startDate / endDate）
 *   6. 空数据走空态
 *   7. 越权：非管理员访问该路由被挡
 *   8. 越权矩阵：非管理员调四个运营端点一律 403
 *
 * 三条自陈约束（与既有套件一致）：
 *   1. **异常卡片的 linkUrl 由后端下发**，前端不拼路由；本文件断言的是
 *      「后端给的 linkUrl 点得开」—— 若后端改了 linkUrl，这里会红，这是契约而不是前端自由。
 *   2. **不断言绝对条数**：异常中心的 count 取值取决于库的当前状态，
 *      这里只断言「四类卡片都在」与「每类点得开」。
 *   3. **不写库**：本文件全程只读，无需夹具回滚。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { ROLE_HOME } from '../helpers/accounts'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

/** 工单 §4.2 锁死的四类异常 type（后端 items[].type） */
const EXCEPTION_TYPES = ['MANUAL_REQUIRED', 'MATCH_WARNING', 'COMPANION_COMPLAINT_RATE', 'MEDICATION_MISSED']

/** 概览八项指标标签（与页面一一对应） */
const OVERVIEW_LABELS = [
  '今日订单',
  '完成订单',
  '服务中',
  '待接单',
  '履约率',
  '平均接单时间',
  '投诉率',
  '平均评分'
]

/** 收集趋势端点的请求 URL */
function collectTrendRequests(page) {
  const reqs = []
  page.on('request', (r) => {
    if (r.url().includes('/admin/operations/trend')) reqs.push(r.url())
  })
  return reqs
}

/* ================================================================== *
 * 1 – 5 · 驾驶舱页面（管理员，桌面形态）
 * ================================================================== */
test.describe('V3.5 · 运营驾驶舱（管理员）', () => {
  test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

  test('驾驶舱首页渲染：四块都在', async ({ page }) => {
    // sweep 顺带断言：无 console 错误、无 ≥400 的接口响应、落地路径就是目标路由
    const text = await sweep(page, ROUTES.adminOperations)
    expect(text).toContain('平台概览')
    expect(text).toContain('异常中心')
    expect(text).toContain('趋势')
    expect(text).toContain('平台健康度')
  })

  test('概览八项指标齐全', async ({ page }) => {
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.ops__overview .kpi', { timeout: 20000 })
    const labels = (await page.locator('.ops__overview .kpi__label').allInnerTexts()).map((t) => t.trim())
    expect(labels).toEqual(OVERVIEW_LABELS)
  })

  test('四类异常卡片逐个点开，落地页必须可达（不能 404、不能跳回登录页）', async ({ page }) => {
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.ops__exceptions .exc', { timeout: 20000 })

    const cards = page.locator('.ops__exceptions .exc')
    expect(await cards.count(), '异常中心应有四类异常卡').toBe(4)

    const types = await page.locator('.ops__exceptions .exc').evaluateAll((els) =>
      els.map((e) => e.getAttribute('data-type'))
    )
    expect([...types].sort(), '四类异常 type 应与工单 §4.2 一致').toEqual([...EXCEPTION_TYPES].sort())

    for (let i = 0; i < types.length; i++) {
      const card = cards.nth(i)
      const link = await card.locator('.exc__go').getAttribute('data-link')
      expect(link, `异常卡 ${types[i]} 必须带后端下发的 linkUrl`).toBeTruthy()

      await card.locator('.exc__go').click()
      await page.waitForTimeout(900)

      const landed = await page.evaluate(() => location.pathname)
      expect(landed, `异常卡「${types[i]}」的 linkUrl 应能落地`).toBe(link)
      expect(landed, '不得跳回登录页').not.toBe('/login')
      expect(landed, '不得落到 404').not.toBe('/error/404')

      const body = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ').trim()
      expect(body.length, `落地页 ${landed} 应有实质内容`).toBeGreaterThan(50)

      // 回到驾驶舱继续点下一张
      await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
      await page.waitForSelector('.ops__exceptions .exc', { timeout: 20000 })
    }
  })

  test('趋势切换维度会重新向后端要数', async ({ page }) => {
    const reqs = collectTrendRequests(page)
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.ops__trend .seg__btn', { timeout: 20000 })
    await page.waitForTimeout(900)
    expect(reqs.length, '首屏应已请求过一次趋势').toBeGreaterThan(0)

    const before = reqs.length
    await page.locator('.ops__trend').getByRole('button', { name: '投诉', exact: true }).click()
    await page.waitForTimeout(1000)

    expect(reqs.length, '切换维度应重新请求后端').toBeGreaterThan(before)
    expect(reqs[reqs.length - 1], '新请求应带上新的 dimension').toContain('dimension=COMPLAINT')
  })

  test('自定义区间生效：请求带上 startDate / endDate', async ({ page }) => {
    const reqs = collectTrendRequests(page)
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.ops__trend .seg__btn', { timeout: 20000 })

    await page.locator('.ops__trend').getByRole('button', { name: '自定义', exact: true }).click()
    await page.waitForTimeout(800)

    await page.locator('.ops__trend input[placeholder="开始日期"]').fill('2026-10-01')
    await page.locator('.ops__trend input[placeholder="开始日期"]').press('Enter')
    await page.waitForTimeout(500)
    await page.locator('.ops__trend input[placeholder="结束日期"]').fill('2026-10-05')
    await page.locator('.ops__trend input[placeholder="结束日期"]').press('Enter')
    await page.waitForTimeout(500)
    // 「查询」兜底：日期改了但 change 未触发时也能生效
    await page.locator('.ops__trend').getByRole('button', { name: '查询', exact: true }).click()
    await page.waitForTimeout(1000)

    expect(reqs.length, '自定义区间应触发请求').toBeGreaterThan(0)
    const last = reqs[reqs.length - 1]
    expect(last, '应带 range=CUSTOM').toContain('range=CUSTOM')
    expect(last, '应带 startDate').toContain('startDate=2026-10-01')
    expect(last, '应带 endDate').toContain('endDate=2026-10-05')
  })

  test('空数据走空态（不是白屏，也不是报错）', async ({ page }) => {
    // 后端返回 data:null —— 概览块应走空态，其余块不受影响
    await page.route('**/api/admin/operations/overview*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: '操作成功', data: null })
      })
    })
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1500)
    const text = await page.locator('body').innerText()
    expect(text).toContain('暂无概览数据')
    expect(text).not.toContain('平台概览加载失败')
  })
})

/* ================================================================== *
 * 6 – 7 · 越权
 * ================================================================== */
test.describe('V3.5 · 运营驾驶舱越权', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1440, height: 900 } })

  test('非管理员访问驾驶舱被挡（进不去驾驶舱）', async ({ page }) => {
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1500)
    const landed = await page.evaluate(() => location.pathname)
    expect(landed, '非管理员不得进入驾驶舱').not.toBe(ROUTES.adminOperations)
    // 路由守卫把越权访问重定向回本人角色主页（或 403 页），两者都不是驾驶舱
    expect([ROLE_HOME.FAMILY, '/error/403']).toContain(landed)
    const text = await page.locator('body').innerText()
    expect(text, '越权时不得看到驾驶舱内容').not.toContain('平台健康度')
  })
})

test.describe('V3.5 · 运营端点越权矩阵', () => {
  test('非管理员调四个运营端点一律 403', async () => {
    const paths = [
      '/admin/operations/overview',
      '/admin/operations/exceptions',
      '/admin/operations/trend?dimension=ORDER&range=LAST_7D',
      '/admin/operations/health'
    ]
    for (const account of ['fam001', 'comp001', 'elder001']) {
      const { ctx, dispose } = await apiAsAccount(account)
      try {
        for (const p of paths) {
          const res = await ctx.get(`${API}${p}`)
          expect(res.status(), `${account} 访问 ${p} 应为 403`).toBe(403)
        }
      } finally {
        await dispose()
      }
    }
  })
})
