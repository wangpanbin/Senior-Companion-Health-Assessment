/**
 * 08 · M9 管理后台（ADMIN）
 *
 * 覆盖 8 个后台路由渲染 + 关键接口契约（资质审核列表 / 用户 / 订单 / 投诉 / 操作日志）。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

test.describe('后台 8 个路由渲染', () => {
  test('W-01 数据看板', async ({ page }) => {
    await sweep(page, ROUTES.adminDashboard)
  })
  test('W-02 陪诊员资质审核', async ({ page }) => {
    const text = await sweep(page, ROUTES.adminCompanionAudit)
    expect(text).toContain('审核')
  })
  test('W-03 订单纠纷仲裁', async ({ page }) => {
    await sweep(page, ROUTES.adminOrderDispute)
  })
  test('W-04 用户管理', async ({ page }) => {
    await sweep(page, ROUTES.adminUser)
  })
  test('W-05 订单管理', async ({ page }) => {
    await sweep(page, ROUTES.adminOrder)
  })
  test('W-06 投诉管理', async ({ page }) => {
    await sweep(page, ROUTES.adminComplaint)
  })
  test('W-07 数据导出', async ({ page }) => {
    await sweep(page, ROUTES.adminExport)
  })
  test('W-08 操作日志', async ({ page }) => {
    await sweep(page, ROUTES.adminOperLog)
  })
})

test.describe('后台接口契约', () => {
  test('资质审核列表按 auditStatus=PENDING 过滤且非空', async () => {
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      const body = await (
        await ctx.get(`${API}/admin/companion/audit?page=1&size=50&auditStatus=PENDING`)
      ).json()
      expect(body.code).toBe(200)
      // 分页契约固定为 { records, total }：原写法用 Array.isArray 兼容两种形状，
      // 会让「后端换了响应结构」被静默放过 —— 那是假绿断言，不是健壮性。
      expect(Array.isArray(body.data.records), '分页响应必须带 records 数组').toBe(true)
      // ⚠️ 不断言「恰好 4 条」：条数随历史写路径漂移（参 e2e-report §F-04），
      //    只断言「该筛选条件下确有数据」这一不变量。
      expect(body.data.records.length, '资质待审应有种子记录').toBeGreaterThan(0)
    } finally {
      await dispose()
    }
  })

  test('用户列表 / 订单列表 / 投诉列表 / 操作日志 均可读', async () => {
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      for (const p of ['/admin/user', '/admin/order', '/admin/complaint', '/admin/oper-log']) {
        const body = await (await ctx.get(`${API}${p}?page=1&size=10`)).json()
        expect(body.code, `${p} 应 200`).toBe(200)
      }
    } finally {
      await dispose()
    }
  })

  test('操作日志有记录（admin_oper_log 非空）', async () => {
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      const body = await (await ctx.get(`${API}/admin/oper-log?page=1&size=10`)).json()
      expect(body.code).toBe(200)
      expect(body.data.total, '操作日志应有种子记录').toBeGreaterThan(0)
    } finally {
      await dispose()
    }
  })
})
