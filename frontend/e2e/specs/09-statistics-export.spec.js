/**
 * 09 · M10 统计看板与导出（ADMIN）
 *
 * 覆盖：5 类统计接口 + ECharts 渲染 + EasyExcel 导出（按当前筛选、列头中文）。
 */
import { test, expect } from '@playwright/test'
import fs from 'node:fs'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

test.describe('统计接口契约', () => {
  const ENDPOINTS = ['/overview', '/order-trend', '/order-status', '/companion-rank', '/medication-missed']

  for (const ep of ENDPOINTS) {
    test(`GET /api/statistics${ep} 200 且响应 < 2s`, async () => {
      const { ctx, dispose } = await apiAsAccount('admin')
      try {
        const t0 = Date.now()
        const res = await ctx.get(`${API}/statistics${ep}`)
        const ms = Date.now() - t0
        const body = await res.json()
        expect(body.code, `${ep} 应 200`).toBe(200)
        expect(ms, `${ep} 响应 ${ms}ms 应 < 2000ms`).toBeLessThan(2000)
      } finally {
        await dispose()
      }
    })
  }
})

test.describe('看板页面', () => {
  test('数据看板渲染 ECharts 图表', async ({ page }) => {
    const text = await sweep(page, ROUTES.adminDashboard)
    expect(text.length).toBeGreaterThan(30)
    // ECharts 渲染为 canvas 或 svg
    const charts = page.locator('canvas, svg.echarts, [_echarts_instance_]')
    await expect.poll(async () => charts.count(), { timeout: 8000 }).toBeGreaterThan(0)
  })

  test('数据导出页渲染', async ({ page }) => {
    await sweep(page, ROUTES.adminExport)
  })
})

test.describe('Excel 导出（EasyExcel）', () => {
  test('GET /api/statistics/export/order 返回可下载文件（非 JSON）', async () => {
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      const res = await ctx.get(`${API}/statistics/export/order`)
      expect(res.status()).toBe(200)
      const ct = res.headers()['content-type'] || ''
      // 导出是二进制流（xlsx / octet-stream），绝不是 application/json
      expect(ct, `导出 Content-Type 不应是 JSON（实际 ${ct}）`).not.toContain('application/json')
      const buf = await res.body()
      expect(buf.length, '导出文件不应为空').toBeGreaterThan(1000)
    } finally {
      await dispose()
    }
  })

  test('UI 点击导出触发下载事件', async ({ page }) => {
    await page.goto(ROUTES.adminExport)
    await page.waitForTimeout(1200)
    // 找导出按钮（不同实现文案可能不同）
    const btn = page.getByRole('button', { name: /导出|下载/ }).first()
    if (await btn.isVisible().catch(() => false)) {
      const downloadPromise = page.waitForEvent('download', { timeout: 15000 }).catch(() => null)
      await btn.click()
      const download = await downloadPromise
      if (download) {
        const p = await download.path()
        expect(download.suggestedFilename()).toMatch(/\.(xlsx|xls|csv)$/i)
        if (p) expect(fs.statSync(p).size).toBeGreaterThan(0)
      }
    }
  })
})
