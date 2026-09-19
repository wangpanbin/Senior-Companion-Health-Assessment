/**
 * 06 · M6 用药管理 + 合规红线（药品字典只给通用信息）
 *
 * 硬约束（PRD §2.2 / §9.1）：药品字典与全站不得出现「建议服用 / 推荐剂量 / 诊断为」等表述。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES, SEED, FORBIDDEN_MEDICAL_PHRASES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

test.describe('用药接口契约（fam001 / elder 401）', () => {
  test('用药计划列表', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (await ctx.get(`${API}/medication/plan?elderId=${SEED.elderProfileBound}`)).json()
      expect(body.code).toBe(200)
    } finally {
      await dispose()
    }
  })

  test('服药日历与今日任务', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const today = await (
        await ctx.get(`${API}/medication/task/today?elderId=${SEED.elderProfileBound}`)
      ).json()
      expect(today.code).toBe(200)

      const cal = await (
        await ctx.get(
          `${API}/medication/task/calendar?elderId=${SEED.elderProfileBound}&startDate=2026-09-01&endDate=2026-09-30`
        )
      ).json()
      expect(cal.code).toBe(200)
    } finally {
      await dispose()
    }
  })

  test('药品字典只返回通用信息 —— 全文不得含剂量/诊断类表述', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const res = await ctx.get(`${API}/medication/dict?page=1&size=50`)
      const raw = await res.text()
      expect(res.status()).toBe(200)
      for (const phrase of FORBIDDEN_MEDICAL_PHRASES) {
        expect(raw, `药品字典响应不应含「${phrase}」`).not.toContain(phrase)
      }
    } finally {
      await dispose()
    }
  })
})

test.describe('用药页面（家属端，移动形态）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('用药页渲染且不含违禁表述', async ({ page }) => {
    const text = await sweep(page, ROUTES.familyMedication)
    for (const phrase of FORBIDDEN_MEDICAL_PHRASES) {
      expect(text, `用药页不应出现「${phrase}」`).not.toContain(phrase)
    }
  })
})
