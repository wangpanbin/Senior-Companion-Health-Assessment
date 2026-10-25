/**
 * 20 · V3.6 数据闭环（供给缺口 + 效果对比）
 *
 * 覆盖 docs/spec/V3.6-tickets.md §九（T8 前端）与 docs/api/08-admin.md §21 / §22：
 *   1. 驾驶舱页出现「供给缺口」与「效果对比」两块
 *   2. 缺口表渲染出 168 个桶（7 天 × 24 小时）
 *   3. 效果对比默认区间生效并显示两期值
 *   4. 切换指标会重新向后端要数
 *   5. 空数据走空态
 *   6. 越权：非管理员访问该路由被挡
 *
 * ─────────────────────────────────────────────────────────────
 * 本文件**直连真后端**（8080）：/admin/operations/gap 与 /admin/operations/effect
 * 都是真实端点，断言的是接口契约本身。
 *
 * 只有第 5 条「空数据走空态」用 page.route 把响应换成 data:null ——
 * 那条测的是**前端的空态分支**，真后端没有「无数据」这个入参能触发它。
 *
 * 三条自陈约束（与既有套件一致）：
 *   1. **不断言绝对业务条数**：只断言「168 个桶」「两期都在」「区间相邻等长」
 *      这类**契约不变量**；订单数、缺口数这类随库状态浮动的值不断言
 *      （库里当前无在期订单，断言具体数字会把环境债伪装成代码缺陷）。
 *   2. **不写库**：本文件全程只读。
 *   3. **不拼路由**：页面路径取自 helpers/constants.js 的 ROUTES。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'

/* ---------------- 小工具 ---------------- */

/** 'yyyy-MM-dd' → UTC 毫秒（只做整日差，不涉及时区） */
function toUTC(dateStr) {
  const [y, m, d] = dateStr.split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

/** b − a，整日差 */
function daysBetween(a, b) {
  return Math.round((toUTC(b) - toUTC(a)) / 86400000)
}

/** 收集效果对比端点的请求 URL */
function collectEffectRequests(page) {
  const reqs = []
  page.on('request', (r) => {
    if (r.url().includes('/admin/operations/effect')) reqs.push(r.url())
  })
  return reqs
}

/** 收集缺口端点的响应体（用来断言接口契约本身） */
function collectGapResponses(page) {
  const bodies = []
  page.on('response', async (res) => {
    if (!res.url().includes('/admin/operations/gap')) return
    try {
      bodies.push(await res.json())
    } catch {
      bodies.push(null)
    }
  })
  return bodies
}

/* ================================================================== *
 * 1 – 5 · 驾驶舱页面（管理员，桌面形态）
 * ================================================================== */
test.describe('V3.6 · 数据闭环（管理员）', () => {
  test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

  test('驾驶舱页出现「供给缺口」与「效果对比」两块', async ({ page }) => {
    // sweep 顺带断言：无 console 错误、无 ≥400 的接口响应、落地路径就是目标路由
    const text = await sweep(page, ROUTES.adminOperations)
    expect(text).toContain('供给缺口')
    expect(text).toContain('效果对比')
  })

  test('缺口表渲染出 168 个桶（7 天 × 24 小时）', async ({ page }) => {
    const bodies = collectGapResponses(page)
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.ops__gap .gap__cell', { timeout: 20000 })

    // ① 接口契约：后端固定返回 168 个桶，空桶也返回
    expect(bodies.length, '应请求过一次缺口端点').toBeGreaterThan(0)
    const data = bodies[bodies.length - 1].data
    expect(data.total, '后端 total 固定 168').toBe(168)
    expect(data.items.length, '后端 items 固定 168 行（空桶也返回）').toBe(168)
    expect(
      new Set(data.items.map((i) => i.weekday)).size,
      'weekday 覆盖周一到周日'
    ).toBe(7)
    expect(
      Math.min(...data.items.map((i) => i.hour)),
      'hour 从 0 起'
    ).toBe(0)
    expect(
      Math.max(...data.items.map((i) => i.hour)),
      'hour 到 23 止'
    ).toBe(23)

    // ② 前端渲染：7 × 24 铺满，每个格子都有数值（空桶显示 0 而不是空白）
    const cells = page.locator('.ops__gap .gap__cell')
    expect(await cells.count(), '前端也应铺满 168 个格子').toBe(168)
    const texts = await cells.allInnerTexts()
    for (const t of texts) {
      expect(t.trim(), `格子「${t}」必须是数值，不能是空白`).toMatch(/^-?\d+$/)
    }

    // ③ 明细行：给出「待接 / 可服务 / 缺口」
    const detail = await page.locator('.ops__gap .gap__detail').innerText()
    expect(detail).toMatch(/周[一二三四五六日] \d{2}:00 · 待接 \d+ 单 \/ 可服务 \d+ 人 \/ 缺口 -?\d+/)
  })

  test('效果对比默认区间生效并显示两期值', async ({ page }) => {
    const reqs = collectEffectRequests(page)
    const bodies = []
    page.on('response', async (res) => {
      if (!res.url().includes('/admin/operations/effect')) return
      try {
        bodies.push(await res.json())
      } catch {
        bodies.push(null)
      }
    })

    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.ops__effect .eff__val', { timeout: 20000 })

    // ① 默认区间：请求只带后一期，前一期由后端按「等长且紧邻其前」推导
    expect(reqs.length, '首屏应已请求过一次效果对比').toBeGreaterThan(0)
    const last = reqs[reqs.length - 1]
    expect(last).toContain('metric=operations.fulfillment_rate')
    expect(last).toContain('from=')
    expect(last).toContain('to=')
    expect(last, '默认区间不该由前端推导前一期').not.toContain('compareFrom=')

    // ② 后端推导出的前一期：与后一期**等长**且**紧邻其前**
    const d = bodies[bodies.length - 1].data
    expect(daysBetween(d.previous.to, d.current.from), '前一期应紧邻后一期之前').toBe(1)
    expect(
      daysBetween(d.previous.from, d.previous.to),
      '两期必须等长'
    ).toBe(daysBetween(d.current.from, d.current.to))

    // ③ 页面展示：指标名 + 两期区间 + 两期取值 + delta（带正负号）
    const text = await page.locator('.ops__effect').innerText()
    expect(text).toContain('履约率')
    expect(text).toContain(`${d.previous.from} ~ ${d.previous.to}`)
    expect(text).toContain(`${d.current.from} ~ ${d.current.to}`)
    expect(text).toContain(d.current.value)
    expect(text).toContain(d.previous.value)
    expect(text).toContain(d.delta)
  })

  test('切换指标会重新向后端要数', async ({ page }) => {
    const reqs = collectEffectRequests(page)
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('.ops__effect .el-select', { timeout: 20000 })
    await page.waitForTimeout(800)

    const before = reqs.length
    await page.locator('.ops__effect .el-select').click()
    await page.locator('.el-select-dropdown__item').filter({ hasText: '完成率（按时）' }).click()
    await page.waitForTimeout(1000)

    expect(reqs.length, '切换指标应重新请求后端').toBeGreaterThan(before)
    expect(reqs[reqs.length - 1], '新请求应带上新的 metric').toContain(
      'metric=operations.completion_rate'
    )
  })

  test('空数据走空态（不是白屏，也不是报错）', async ({ page }) => {
    // 真后端没有「无数据」入参，空态只能由前端分支触发 → 这里把响应换成 data:null
    await page.route('**/api/admin/operations/gap*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: '操作成功', data: null })
      })
    )
    await page.route('**/api/admin/operations/effect*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: '操作成功', data: null })
      })
    )

    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1500)

    const text = await page.locator('body').innerText()
    expect(text).toContain('暂无缺口数据')
    expect(text).toContain('暂无对比数据')
    expect(text).not.toContain('供给缺口加载失败')
    expect(text).not.toContain('效果对比加载失败')
    // 其余四块不受影响
    expect(text).toContain('平台概览')
    expect(text).toContain('平台健康度')
  })
})

/* ================================================================== *
 * 6 · 越权
 * ================================================================== */
test.describe('V3.6 · 数据闭环越权', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1440, height: 900 } })

  test('非管理员访问驾驶舱被挡（进不去驾驶舱）', async ({ page }) => {
    await page.goto(ROUTES.adminOperations, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1500)
    const landed = await page.evaluate(() => location.pathname)
    expect(landed, '非管理员不得进入驾驶舱').not.toBe(ROUTES.adminOperations)
    // 路由守卫把越权访问改跳 403 页（P1-6）。**精确断言**——
    // 二选一的 toContain 等于没有判别力：改前改后都绿。
    expect(landed, '越权应落到 403 页').toBe(ROUTES.error403)
    const text = await page.locator('body').innerText()
    expect(text, '越权时不得看到供给缺口').not.toContain('供给缺口')
    expect(text, '越权时不得看到效果对比').not.toContain('效果对比')
  })
})
