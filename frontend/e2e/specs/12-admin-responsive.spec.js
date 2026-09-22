/**
 * 12 · desktop-adapt-v2 admin 端响应式断点回归
 *
 * 覆盖 8 个 admin view 在 1024 / 1280 / 1920 三档断点下的渲染正确性：
 *   - ≥1280:el-table 容器存在,操作列可见
 *   - <1280:卡片列表(.nl-admin-table__cards)存在
 *   - sidebar 按断点:auto(<1280 收起,≥1280 展开)
 *
 * 依据:`docs/spec/desktop-adapt-v2.md` §7.2
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'

const VIEWPORTS = [
  { name: 'sm-1024', width: 1024, height: 768 },
  { name: 'md-1280', width: 1280, height: 800 },
  { name: 'lg-1920', width: 1920, height: 1080 }
]

/** 走断点切换的 5 个表格页面 */
const TABLE_ROUTES = [
  { route: ROUTES.adminOrder, label: '订单管理' },
  { route: ROUTES.adminUser, label: '用户管理' },
  { route: ROUTES.adminComplaint, label: '投诉管理' },
  { route: ROUTES.adminOrderDispute, label: '订单纠纷仲裁' },
  { route: ROUTES.adminCompanionAudit, label: '陪诊员资质审核' },
  { route: ROUTES.adminOperLog, label: '操作日志' }
]

for (const vp of VIEWPORTS) {
  test.describe(`viewport ${vp.name} (${vp.width}x${vp.height})`, () => {
    test.use({
      storageState: authFile('admin'),
      viewport: { width: vp.width, height: vp.height }
    })

    test('sidebar 自适应(< 1280 收起,≥ 1280 展开)', async ({ page }) => {
      await page.goto(ROUTES.adminOrder)
      // 等路由 + admin layout 渲染完成
      await page.waitForSelector('.admin__aside', { state: 'visible' })
      const aside = page.locator('.admin__aside')
      const width = await aside.evaluate((el) => el.getBoundingClientRect().width)
      if (vp.width < 1280) {
        expect(width, '1024 应收起(sidebar 64px)').toBeLessThan(80)
      } else {
        expect(width, '≥1280 应展开(sidebar 220px)').toBeGreaterThan(200)
      }
    })

    for (const { route, label } of TABLE_ROUTES) {
      test(`${label} 在 ${vp.name} 渲染正常(无 console error / 落地等于目标路由)`, async ({ page }) => {
        const errors = []
        page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
        page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message))

        await page.goto(route)
        await page.waitForLoadState('networkidle')

        // 落地路径硬断言(防 guard 静默重定向,FRONTEND_CONTRACT §12.1)
        expect(new URL(page.url()).pathname, `必须落在 ${route}`).toBe(route)

        // NlAdminTable 双形态之一必渲染
        const desktop = await page.locator('.nl-admin-table__desktop').count()
        const cards = await page.locator('.nl-admin-table__cards').count()
        expect(desktop + cards, `${label} 至少有一种表格形态渲染`).toBeGreaterThan(0)

        // 断点驱动:
        //   ≥1280:desktop 形态
        //   <1280:mobile 形态
        if (vp.width >= 1280) {
          expect(desktop, `${label} ≥1280 应走 desktop 形态`).toBeGreaterThan(0)
          expect(cards, `${label} ≥1280 不应走 mobile 形态`).toBe(0)
        } else {
          expect(cards, `${label} <1280 应走 mobile 形态`).toBeGreaterThan(0)
          expect(desktop, `${label} <1280 不应走 desktop 形态`).toBe(0)
        }

        // 关键字段文案必须出现(至少一个 NlStatusChip 或表格行,说明数据非真空)
        const hasContent = await page.locator('table, .nl-admin-table__card').count()
        expect(hasContent, `${label} 应有内容渲染`).toBeGreaterThan(0)

        // 无 console error
        expect(errors, `${label} 不应有 console error`).toEqual([])
      })
    }

    test(`dashboard 在 ${vp.name} 渲染 + KPI 卡可见`, async ({ page }) => {
      const errors = []
      page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

      await page.goto(ROUTES.adminDashboard)
      await page.waitForLoadState('networkidle')
      expect(new URL(page.url()).pathname).toBe(ROUTES.adminDashboard)

      // 4 个 KPI 卡(订单总数 / 完成订单 / 完成率 / 用户数)
      const kpiCards = await page.locator('.kpi').count()
      expect(kpiCards, `dashboard 应有 4 个 KPI 卡`).toBe(4)
      expect(errors).toEqual([])
    })

    test(`export 在 ${vp.name} 渲染(表单 max-width 自适应)`, async ({ page }) => {
      const errors = []
      page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })

      await page.goto(ROUTES.adminExport)
      await page.waitForLoadState('networkidle')
      expect(new URL(page.url()).pathname).toBe(ROUTES.adminExport)

      // 表单容器存在
      const form = page.locator('.form').first()
      await expect(form).toBeVisible()
      expect(errors).toEqual([])
    })
  })
}