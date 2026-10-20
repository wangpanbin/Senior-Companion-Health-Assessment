/**
 * 17 · V3.3 陪诊员工作台
 *
 * 覆盖：工作台首页渲染四块 · 四个端点的响应契约 · 越权矩阵 ·
 *      companionId 入参被忽略 · 收入页数字与后端口径一致。
 *
 * 契约来源：docs/spec/V3.3-companion-workbench-spec.md §二 接口契约
 *          docs/api/09-statistics-export.md §8
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

/** 工作台的四个端点 */
const WORKBENCH_APIS = [
  '/statistics/companion/me',
  '/statistics/companion/me/trend',
  '/statistics/companion/me/profile',
  '/statistics/companion/me/income'
]

test.describe('V3.3 工作台首页（comp001，移动形态）', () => {
  test.use({ storageState: authFile('comp001'), viewport: { width: 390, height: 844 } })

  test('工作台渲染四块内容（经营指标 / 趋势 / 服务画像 / 收入摘要）', async ({ page }) => {
    // sweep 顺带断言：无 console 错误、无 ≥400 的接口响应
    const text = await sweep(page, ROUTES.companionWorkbench)
    expect(text).toContain('工作台')
    // ① 经营指标（区间口径）
    expect(text).toContain('区间完成单数')
    expect(text).toContain('区间完成率')
    expect(text).toContain('平均评分')
    expect(text).toContain('区间收入')
    // ② 服务画像（累计口径）
    expect(text).toContain('累计服务老人数')
    expect(text).toContain('准时率')
    // ③ 收入摘要
    expect(text).toContain('待结算')
    expect(text).toContain('已结算')
  })

  test('工作台明示当前统计区间（口径可解释）', async ({ page }) => {
    await page.goto(ROUTES.companionWorkbench, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(900)
    const text = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ')
    // 区间文案形如「2026-09-21 ~ 2026-10-20」
    expect(text).toMatch(/\d{4}-\d{2}-\d{2}\s*~\s*\d{4}-\d{2}-\d{2}/)
  })

  test('收入页的「待结算」数字与后端口径一致（前端不得再自算一遍）', async ({ page }) => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    let expected = null
    try {
      const body = await (await ctx.get(`${API}/statistics/companion/me/income`)).json()
      expect(body.code).toBe(200)
      expected = body.data.unsettledAmount
    } finally {
      await dispose()
    }

    await page.goto(ROUTES.companionIncome, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(900)
    // 「待结算」那张卡的数值
    const card = page
      .locator('.kpi__card')
      .filter({ has: page.locator('.kpi__label', { hasText: '待结算' }) })
    await expect(card).toHaveCount(1)
    const shown = ((await card.locator('.kpi__val').innerText()) || '').trim()
    // 页面展示形如 ¥1200.00，后端口径是 "1200.00" → 去掉货币符号后逐字相等
    expect(shown.replace(/[¥,]/g, ''), '收入页的待结算必须等于后端口径，不能前端再算一遍')
      .toBe(String(expected))
  })
})

test.describe('V3.3 工作台接口契约', () => {
  test('四个端点返回 200，且响应结构完整', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const overview = await (await ctx.get(`${API}/statistics/companion/me`)).json()
      expect(overview.code).toBe(200)
      expect(overview.data.periodStart).toBeTruthy()
      expect(overview.data.periodEnd).toBeTruthy()
      expect(typeof overview.data.completedOrderCount).toBe('number')
      expect(typeof overview.data.serviceDurationMinutes).toBe('number')
      expect(overview.data.completionRate).toMatch(/^\d+(\.\d+)?%$/)
      expect(overview.data.averageScore).toMatch(/^\d+(\.\d+)?$/)
      expect(overview.data.periodIncome).toMatch(/^\d+(\.\d+)?$/)

      const trend = await (await ctx.get(`${API}/statistics/companion/me/trend`)).json()
      expect(trend.code).toBe(200)
      expect(trend.data.dimension).toBe('ORDER')
      expect(Array.isArray(trend.data.categories)).toBeTruthy()
      expect(Array.isArray(trend.data.series)).toBeTruthy()

      const profile = await (await ctx.get(`${API}/statistics/companion/me/profile`)).json()
      expect(profile.code).toBe(200)
      expect(profile.data.punctualityRate).toMatch(/^\d+(\.\d+)?%$/)
      expect(profile.data.totalCompletionRate).toMatch(/^\d+(\.\d+)?%$/)
      expect(typeof profile.data.totalServedElderCount).toBe('number')
      expect(Array.isArray(profile.data.expertise)).toBeTruthy()

      const income = await (await ctx.get(`${API}/statistics/companion/me/income`)).json()
      expect(income.code).toBe(200)
      expect(income.data.actualIncome).toMatch(/^\d+(\.\d+)?$/)
      expect(income.data.unsettledAmount).toMatch(/^\d+(\.\d+)?$/)
      expect(income.data.settledAmount).toMatch(/^\d+(\.\d+)?$/)
    } finally {
      await dispose()
    }
  })

  test('趋势的 categories 与每条 series.data 长度严格一致（缺桶补 0）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      for (const dimension of ['ORDER', 'INCOME', 'RATING']) {
        const body = await (
          await ctx.get(`${API}/statistics/companion/me/trend?dimension=${dimension}&granularity=DAY`)
        ).json()
        expect(body.code, `${dimension} 趋势应可用`).toBe(200)
        const { categories, series } = body.data
        expect(categories.length).toBeGreaterThan(0)
        for (const s of series) {
          expect(s.data.length, `${dimension} 序列长度必须与 categories 一致`).toBe(categories.length)
        }
      }
    } finally {
      await dispose()
    }
  })

  test('待结算 + 已结算 = 实际收入（结算拆分与总额同源）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const body = await (await ctx.get(`${API}/statistics/companion/me/income`)).json()
      expect(body.code).toBe(200)
      const { actualIncome, unsettledAmount, settledAmount } = body.data
      const sum = Number(unsettledAmount) + Number(settledAmount)
      expect(Math.abs(sum - Number(actualIncome))).toBeLessThan(0.01)
    } finally {
      await dispose()
    }
  })

  test('越权：家属 / 管理员 / 老人访问工作台端点一律 403', async () => {
    for (const account of ['fam001', 'admin', 'elder001']) {
      const { ctx, dispose } = await apiAsAccount(account)
      try {
        for (const path of WORKBENCH_APIS) {
          const res = await ctx.get(`${API}${path}`)
          expect(res.status(), `${account} 访问 ${path} 应为 403`).toBe(403)
        }
      } finally {
        await dispose()
      }
    }
  })

  test('companionId 入参被忽略：返回的始终是本人数据', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const plain = await (await ctx.get(`${API}/statistics/companion/me`)).json()
      const injected = await (
        await ctx.get(`${API}/statistics/companion/me?companionId=302`)
      ).json()
      expect(injected.code).toBe(200)
      expect(injected.data.completedOrderCount, '注入别人的 ID 不该改变数据')
        .toBe(plain.data.completedOrderCount)
      expect(injected.data.periodIncome, '注入别人的 ID 不该改变收入')
        .toBe(plain.data.periodIncome)
    } finally {
      await dispose()
    }
  })

  test('非法趋势维度 / 粒度返回 400（不静默降级）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const bad = await (
        await ctx.get(`${API}/statistics/companion/me/trend?dimension=WHATEVER`)
      ).json()
      expect(bad.code, '非法维度应被拒').toBe(400)

      const badGran = await (
        await ctx.get(`${API}/statistics/companion/me/trend?granularity=HOUR`)
      ).json()
      expect(badGran.code, '非法粒度应被拒').toBe(400)
    } finally {
      await dispose()
    }
  })

  test('统计区间超上限返回 9002（共享区间约定生效）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const body = await (
        await ctx.get(`${API}/statistics/companion/me?startDate=2020-01-01&endDate=2026-01-01`)
      ).json()
      expect(body.code, '超过 366 天的区间应被拒').toBe(9002)
    } finally {
      await dispose()
    }
  })
})
