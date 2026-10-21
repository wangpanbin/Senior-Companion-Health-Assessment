/**
 * 18 · V3.4 智能调度
 *
 * 覆盖：大厅排序维度开放（DISTANCE / TIME_MATCH）/ INCOME 仍 400 /
 *      大厅页面渲染排序选择器 / 家属端候选推荐端点的响应契约、limit、越权矩阵、脱敏。
 *
 * 契约来源：docs/spec/V3.4-tickets.md §七（T6 大厅排序升级）、§八（T7 候选推荐端点）
 *          docs/spec/V3.4-matching-spec.md §四 接口契约
 *
 * ⚠️ 排序「开放」的判据是**接口层**：V3.1 时 DISTANCE / TIME_MATCH 一律 400，
 *    本版必须能拿到 200。页面层的断言另行用 UI 巡检覆盖。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES, SEED, HALL_SORT_BY, RECOMMEND_COMPANIONS_PATH } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

/* ================================================================== *
 * 1 · 大厅排序（T6）
 * ================================================================== */

test.describe('V3.4 · 大厅排序维度', () => {
  test('DISTANCE / TIME_MATCH 不再返回 400（V3.1 的「后续开放」本版兑现）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      for (const sortBy of ['DISTANCE', 'TIME_MATCH']) {
        const body = await (await ctx.get(`${API}/order/hall?sortBy=${sortBy}`)).json()
        expect(body.code, `sortBy=${sortBy} 本版应可用`).toBe(200)
        expect(Array.isArray(body.data?.records), '响应结构零变更，仍是 records 数组').toBeTruthy()
        expect(typeof body.data?.total, '响应结构零变更，仍是 total 数字').toBe('number')
      }
    } finally {
      await dispose()
    }
  })

  test('INCOME 仍然返回 400（本版没有真实结算金额口径，不做）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const body = await (await ctx.get(`${API}/order/hall?sortBy=INCOME`)).json()
      expect(body.code, 'INCOME 必须仍被拒').toBe(400)
      // 非法值不得静默降级为推荐序 —— 错误消息要回显传进来的取值
      expect(String(body.message || '')).toContain('INCOME')
    } finally {
      await dispose()
    }
  })

  test('白名单里的维度（RECOMMEND / DISTANCE / TIME_MATCH）全部返回 200', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      for (const sortBy of HALL_SORT_BY) {
        const body = await (await ctx.get(`${API}/order/hall?sortBy=${sortBy}`)).json()
        expect(body.code, `sortBy=${sortBy} 应可用`).toBe(200)
      }
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== *
 * 2 · 大厅页面的排序选择器
 * ================================================================== */

test.describe('V3.4 · 大厅排序选择器（comp001，移动形态）', () => {
  test.use({ storageState: authFile('comp001'), viewport: { width: 390, height: 844 } })

  test('页面渲染排序选择器，且不含「收益优先 / INCOME」选项', async ({ page }) => {
    // sweep 顺带断言：无 console 错误、无 ≥400 的接口响应
    const text = await sweep(page, ROUTES.companionHall)
    expect(text).toContain('距离最近')
    expect(text).toContain('时间匹配')
    // INCOME 本版后端返回 400，前端不得给出这个选项
    expect(text).not.toContain('收益优先')
    expect(text).not.toContain('INCOME')

    // 选择器本体在 DOM 里（三个可点按钮，不含第四个）
    const group = page.getByRole('group', { name: '排序方式' })
    await expect(group).toHaveCount(1)
    const labels = await group.locator('button').allInnerTexts()
    expect(labels.map((t) => t.trim())).toEqual(['推荐', '距离最近', '时间匹配'])
  })
})

/* ================================================================== *
 * 3 · 候选推荐端点（T7）
 * ================================================================== */

test.describe('V3.4 · 候选推荐端点', () => {
  test('家属本人查自己的订单 → 200，响应结构完整', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.get(`${API}${RECOMMEND_COMPANIONS_PATH(SEED.orderPending)}`)
      ).json()
      expect(body.code).toBe(200)
      expect(body.data.orderId).toBe(SEED.orderPending)
      expect(typeof body.data.total, 'total 必须是数字（候选总数）').toBe('number')
      expect(Array.isArray(body.data.candidates), 'candidates 必须是数组').toBeTruthy()
      for (const c of body.data.candidates) {
        expect(typeof c.companionId).toBe('number')
        expect(typeof c.companionName).toBe('string')
        expect(typeof c.totalScore, '加权总分必须是 0-100 的整数').toBe('number')
        expect(c.totalScore).toBeGreaterThanOrEqual(0)
        expect(c.totalScore).toBeLessThanOrEqual(100)
        expect(Array.isArray(c.reasons), 'reasons 必须是数组').toBeTruthy()
        expect(Array.isArray(c.mismatches), 'mismatches 必须是数组').toBeTruthy()
        expect(Array.isArray(c.notes), 'notes 必须是数组').toBeTruthy()
        // 三段互斥：「命中理由 / 没命中 / 没法算」不能混在一起
        for (const r of c.reasons) {
          expect(c.mismatches, `「${r}」不得同时出现在 reasons 与 mismatches`).not.toContain(r)
        }
        // 五个维度得分都是 0-100 的整数
        for (const key of ['regionScore', 'timeScore', 'distanceScore', 'skillScore', 'ratingScore']) {
          expect(typeof c[key], `${key} 必须是数字`).toBe('number')
          expect(c[key]).toBeGreaterThanOrEqual(0)
          expect(c[key]).toBeLessThanOrEqual(100)
        }
      }
    } finally {
      await dispose()
    }
  })

  test('limit 生效：传 3 最多返回 3 条', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.get(`${API}${RECOMMEND_COMPANIONS_PATH(SEED.orderPending)}?limit=3`)
      ).json()
      expect(body.code).toBe(200)
      expect(body.data.candidates.length, 'limit=3 时最多 3 条').toBeLessThanOrEqual(3)
    } finally {
      await dispose()
    }
  })

  test('候选姓名必须脱敏（含 *），不得出现完整姓名', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.get(`${API}${RECOMMEND_COMPANIONS_PATH(SEED.orderPending)}`)
      ).json()
      expect(body.code).toBe(200)
      for (const c of body.data.candidates) {
        expect(c.companionName, '脱敏姓名必须含 *').toContain('*')
      }
    } finally {
      await dispose()
    }
  })

  test('越权：家属查别人的订单 → 3004', async () => {
    // 1001 是 fam001 名下的单；fam019 查它必须被拒
    const { ctx, dispose } = await apiAsAccount('fam019')
    try {
      const body = await (
        await ctx.get(`${API}${RECOMMEND_COMPANIONS_PATH(SEED.orderPending)}`)
      ).json()
      expect(body.code, '非本人订单必须返回 3004').toBe(3004)
    } finally {
      await dispose()
    }
  })

  test('越权：管理员 / 陪诊员 / 老人访问该端点一律 403', async () => {
    for (const account of ['admin', 'comp001', 'elder001']) {
      const { ctx, dispose } = await apiAsAccount(account)
      try {
        const res = await ctx.get(`${API}${RECOMMEND_COMPANIONS_PATH(SEED.orderPending)}`)
        expect(res.status(), `${account} 访问候选推荐端点应为 403`).toBe(403)
      } finally {
        await dispose()
      }
    }
  })
})
