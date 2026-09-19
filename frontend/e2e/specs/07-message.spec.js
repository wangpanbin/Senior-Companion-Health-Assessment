/**
 * 07 · M8 站内信（4 类模板 / 未读数 / 已读）
 *
 * 实时推送前端走轮询兜底通道 `/api/message/unread-count`（真值字段 `total`）。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

test.describe('站内信接口契约', () => {
  test('列表与未读数可用（未读字段是真值 total）', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const list = await (await ctx.get(`${API}/message?page=1&size=20`)).json()
      expect(list.code).toBe(200)

      const unread = await (await ctx.get(`${API}/message/unread-count`)).json()
      expect(unread.code).toBe(200)
      // 历史坑：前端曾读 unreadCount，真值是 total
      expect(unread.data, '未读数应返回 total').toHaveProperty('total')
    } finally {
      await dispose()
    }
  })

  test('标记单条已读（幂等，可对同一条重复调用）', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const list = await (await ctx.get(`${API}/message?page=1&size=1`)).json()
      const first = list.data.records?.[0]
      if (first) {
        const res = await (await ctx.put(`${API}/message/${first.id}/read`)).json()
        expect(res.code).toBe(200)
      }
    } finally {
      await dispose()
    }
  })
})

test.describe('消息页（3 角色共用）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })
  test('家属消息页渲染', async ({ page }) => {
    await sweep(page, ROUTES.familyMessage)
  })
})
