/**
 * 16 · V3.2 老人健康协同中心（家庭健康首页 / 健康概览 / 健康时间线 / 老人端）
 *
 * 覆盖 docs/spec/V3.2-tickets.md 的 DoD 后半段：「既有端到端用例全部通过」由 01–15 保证，
 * 本文件只负责**本版新增**的行为。
 *
 * 三条自陈约束（与既有套件一致）：
 *   1. **断言不变量，不断言绝对条数** —— 库相对种子已被历史写路径污染，
 *      绝对条数会把环境债伪装成代码缺陷（见 constants.js §F-04）；
 *   2. **写操作必须自清理** —— 本版是纯只读聚合，无写操作，不涉及；
 *   3. **越权用例必须携带合法路径参数** —— 否则请求会死在参数校验层，
 *      拿到 400 而不是 403/2006，看起来像鉴权生效、实则根本没走到鉴权。
 *
 * 合规红线（AGENTS.md §0.1）：健康中心只陈述事实，**不做诊断、不开药方**。
 * 本文件用后端下发的文案做断言，不在用例里断言任何结论式表述。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES, SEED, FORBIDDEN_MEDICAL_PHRASES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

/** 时间线的四类事件分类 —— 与后端 HealthTimelineCategory 同源 */
const TIMELINE_CATEGORIES = ['ORDER', 'CHECKIN', 'MEDICATION', 'MESSAGE']

/* ================================================================== */
/* 1 · 家庭健康协同首页摘要                                             */
/* ================================================================== */

test.describe('V3.2 · 家庭健康协同首页', () => {
  test('摘要只含本人绑定的老人，已解绑的不出现', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (await ctx.get(`${API}/family/health/summary`)).json()
      expect(body.code, '家庭健康摘要应可读').toBe(200)
      const records = body.data || []
      expect(Array.isArray(records), '摘要必须是数组').toBe(true)

      const ids = records.map((r) => r.elderId)
      expect(ids, 'fam001 绑定的老人应在列表里').toContain(SEED.elderProfileBound)
      expect(ids, '已解绑的绑定关系不得出现在列表里').not.toContain(SEED.elderProfileUnbound)

      for (const r of records) {
        expect(r.elderId, '每行必须有 elderId').toBeTruthy()
        expect(typeof r.elderName, '每行必须有姓名').toBe('string')
        expect(r.todayMedication, '每行必须有今日用药').toBeTruthy()
        expect(typeof r.todayMedication.total, '今日用药必须有 total').toBe('number')
        expect(typeof r.todayMedication.taken, '今日用药必须有 taken').toBe('number')
        expect(typeof r.todayMedication.pending, '今日用药必须有 pending').toBe('number')
        expect(typeof r.recentVisitCount, '每行必须有近期就医次数').toBe('number')
        expect(typeof r.pendingItemCount, '每行必须有待处理条数').toBe('number')
        // 姓名必须脱敏（AGENTS.md §0.1 隐私最小化）
        expect(r.elderName, '姓名应脱敏，不含完整姓名').not.toMatch(/^[\u4e00-\u9fa5]{2,3}$/)
      }
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== */
/* 2 · 健康概览口径                                                   */
/* ================================================================== */

test.describe('V3.2 · 老人健康概览', () => {
  test('概览返回客观指标，且三桶之和等于总数', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.get(`${API}/family/elder/${SEED.elderProfileBound}/health/overview`)
      ).json()
      expect(body.code, '概览应可读').toBe(200)

      const data = body.data
      expect(data?.elder?.id).toBe(SEED.elderProfileBound)
      expect(typeof data.visit.orderCount, '必须有就医次数').toBe('number')
      expect(typeof data.visit.companionCount, '必须有陪诊人数').toBe('number')

      const med = data.medication
      expect(typeof med.total).toBe('number')
      expect(typeof med.taken).toBe('number')
      expect(typeof med.missed).toBe('number')
      expect(typeof med.pending).toBe('number')
      // 三桶必须构成划分：这是 V3.2 修掉的老问题（taken + missed ≠ total）
      expect(med.taken + med.missed + med.pending, '三桶之和必须等于总数').toBe(med.total)
      // 完成率是后端算好的字符串，前端不再计算
      expect(med.completionRate, '完成率必须是百分比字符串').toMatch(/^\d+(\.\d{2})?%$/)
      if (med.total === 0) {
        expect(med.completionRate, '总数为 0 时完成率应为 0.00%').toBe('0.00%')
      }
    } finally {
      await dispose()
    }
  })

  test('区间越界返回 9002，非法分类返回 400（都不静默降级）', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const wide = await (
        await ctx.get(
          `${API}/family/elder/${SEED.elderProfileBound}/health/overview?startDate=2020-01-01&endDate=2026-10-01`
        )
      ).json()
      expect(wide.code, '区间超过 366 天应 9002').toBe(9002)

      const bad = await (
        await ctx.get(
          `${API}/family/elder/${SEED.elderProfileBound}/health/timeline?categories=NOT_A_CATEGORY`
        )
      ).json()
      expect(bad.code, '非法分类应 400').toBe(400)
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== */
/* 3 · 健康时间线                                                     */
/* ================================================================== */

test.describe('V3.2 · 老人健康时间线', () => {
  test('事件按发生时间倒序，字段齐备，分类合法', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.get(`${API}/family/elder/${SEED.elderProfileBound}/health/timeline?limit=50`)
      ).json()
      expect(body.code, '时间线应可读').toBe(200)

      const items = body.data?.items
      expect(Array.isArray(items), 'items 必须是数组（无数据时为空数组，不是 null）').toBe(true)

      let previous = null
      for (const e of items) {
        expect(e.eventId, '每条事件必须有唯一标识').toBeTruthy()
        expect(TIMELINE_CATEGORIES, `未知分类 ${e.category}`).toContain(e.category)
        expect(e.occurredAt, '每条事件必须有发生时间').toBeTruthy()
        expect(typeof e.title, '每条事件必须有一句话描述').toBe('string')

        if (previous) {
          expect(
            new Date(e.occurredAt).getTime(),
            '事件必须按发生时间倒序'
          ).toBeLessThanOrEqual(new Date(previous).getTime())
        }
        previous = e.occurredAt
      }
    } finally {
      await dispose()
    }
  })

  test('categories 过滤只返回选中的分类', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.get(
          `${API}/family/elder/${SEED.elderProfileBound}/health/timeline?categories=MEDICATION&limit=50`
        )
      ).json()
      expect(body.code).toBe(200)
      for (const e of body.data?.items || []) {
        expect(e.category, '过滤后只应出现 MEDICATION').toBe('MEDICATION')
      }
    } finally {
      await dispose()
    }
  })

  test('limit 生效，hasMore 与 nextCursor 配套', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.get(`${API}/family/elder/${SEED.elderProfileBound}/health/timeline?limit=1`)
      ).json()
      expect(body.code).toBe(200)
      expect((body.data?.items || []).length, 'limit=1 时最多一条').toBeLessThanOrEqual(1)
      expect(typeof body.data?.hasMore, '必须有 hasMore').toBe('boolean')
      if (body.data?.hasMore) {
        expect(body.data?.nextCursor, 'hasMore 为真时必须给 nextCursor').toBeTruthy()
      }
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== */
/* 4 · 越权矩阵                                                       */
/* ================================================================== */

test.describe('V3.2 · 越权矩阵', () => {
  test('家属读别人家绑定的老人 → 2006', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.get(`${API}/family/elder/${SEED.elderProfileOtherFamily}/health/overview`)
      ).json()
      expect(body.code, '访问他人老人必须 2006').toBe(2006)
    } finally {
      await dispose()
    }
  })

  test('家属端三个端点：非家属角色一律 403', async () => {
    for (const account of ['elder001', 'comp001', 'admin']) {
      const { ctx, dispose } = await apiAsAccount(account)
      try {
        const summary = await ctx.get(`${API}/family/health/summary`)
        expect(summary.status(), `${account} 读家庭摘要应 403`).toBe(403)

        const overview = await ctx.get(
          `${API}/family/elder/${SEED.elderProfileBound}/health/overview`
        )
        expect(overview.status(), `${account} 读家属概览应 403`).toBe(403)

        const timeline = await ctx.get(
          `${API}/family/elder/${SEED.elderProfileBound}/health/timeline`
        )
        expect(timeline.status(), `${account} 读家属时间线应 403`).toBe(403)
      } finally {
        await dispose()
      }
    }
  })

  test('老人端两个端点：非老人角色一律 403', async () => {
    for (const account of ['fam001', 'comp001', 'admin']) {
      const { ctx, dispose } = await apiAsAccount(account)
      try {
        const overview = await ctx.get(`${API}/elder/health/overview`)
        expect(overview.status(), `${account} 读老人端概览应 403`).toBe(403)

        const timeline = await ctx.get(`${API}/elder/health/timeline`)
        expect(timeline.status(), `${account} 读老人端时间线应 403`).toBe(403)
      } finally {
        await dispose()
      }
    }
  })

  test('老人端只看得到自己：不含费用、催单、履约风险与家属姓名', async () => {
    const { ctx, dispose } = await apiAsAccount('elder001')
    try {
      const body = await (await ctx.get(`${API}/elder/health/overview`)).json()
      expect(body.code, '老人应能读自己的概览').toBe(200)

      const data = body.data
      expect(data?.elder?.relationLabel, '老人端不应返回与家属的关系标签').toBeFalsy()
      expect(data?.medication, '老人端应有用药统计').toBeTruthy()
      // 费用 / 催单 / 履约风险都不属于老人端
      expect(data?.fee, '老人端不得返回费用').toBeFalsy()
      expect(data?.urgeCount, '老人端不得返回催单次数').toBeFalsy()
      expect(data?.fulfillmentRisk, '老人端不得返回履约风险等级').toBeFalsy()

      // 待处理事项只保留与老人自身相关的
      for (const item of data?.pendingItems || []) {
        expect(item.type, '老人端不应出现订单类待办').not.toBe('ORDER_WAITING')
        expect(item.type, '老人端不应出现待评价').not.toBe('ORDER_TO_REVIEW')
        expect(item.type, '老人端不应出现履约风险').not.toBe('FULFILLMENT_RISK')
      }
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== */
/* 5 · 页面可达性                                                     */
/* ================================================================== */

test.describe('V3.2 · 页面可达性', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('家庭健康首页渲染每位老人的摘要', async ({ page }) => {
    const text = await sweep(page, ROUTES.familyHealth)
    expect(text, '应展示今日用药口径').toContain('今日用药')
    expect(text, '应展示近期就医口径').toContain('近期就医')
    expect(text, '应展示待处理口径').toContain('待处理')
  })

  test('某老人健康中心渲染概览与时间线', async ({ page }) => {
    const text = await sweep(page, ROUTES.familyElderHealth(SEED.elderProfileBound))
    expect(text, '应展示就医次数').toContain('就医次数')
    expect(text, '应展示用药完成率').toContain('用药完成率')
    expect(text, '应展示健康时间线').toContain('健康时间线')
  })
})

test.describe('V3.2 · 老人端健康中心页面', () => {
  test.use({ storageState: authFile('elder001'), viewport: { width: 390, height: 844 } })

  test('老人端健康中心渲染且不含违禁文案', async ({ page }) => {
    const text = await sweep(page, ROUTES.elderHealth)
    expect(text, '应展示今日已服药').toContain('今日已服药')
    for (const phrase of FORBIDDEN_MEDICAL_PHRASES) {
      expect(text, `页面不得出现「${phrase}」`).not.toContain(phrase)
    }
  })
})
