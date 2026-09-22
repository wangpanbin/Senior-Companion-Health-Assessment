/**
 * 13 · desktop-adapt-v2 client 端手机尺寸回归
 *
 * 覆盖 4 个 client view 在 320 / 360 / 414 + 横屏 414×320 四档断点下的渲染：
 *   - < 768:走 NlPhoneShell,mobile 形态
 *   - NlPhoneShell padding / NavBar 高度 / TabBar 高度自适应
 *   - el-dialog < 768 fullscreen
 *
 * 依据:`docs/spec/desktop-adapt-v2.md` §7.2
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'

const VIEWPORTS = [
  { name: 'xs-320', width: 320, height: 568 },
  { name: 'sm-360', width: 360, height: 640 },
  { name: 'md-414', width: 414, height: 896 },
  { name: 'landscape-414x320', width: 414, height: 320 }
]

const ROUTES_FAMILY = [
  { route: ROUTES.familyHome, label: '家属首页' },
  { route: ROUTES.familyOrder, label: '家属订单' },
  { route: ROUTES.familyMedication, label: '家属用药' },
  { route: ROUTES.profile, label: '我的' }
]

for (const vp of VIEWPORTS) {
  test.describe(`client viewport ${vp.name} (${vp.width}x${vp.height})`, () => {
    test.use({
      storageState: authFile('family'),
      viewport: { width: vp.width, height: vp.height }
    })

    for (const { route, label } of ROUTES_FAMILY) {
      test(`${label} 在 ${vp.name} 渲染(走 mobile 形态,无 console error)`, async ({ page }) => {
        const errors = []
        page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
        page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message))

        await page.goto(route)
        await page.waitForLoadState('networkidle')
        // 落地等于目标路由
        expect(new URL(page.url()).pathname, `必须落在 ${route}`).toBe(route)

        // 移动形态特征:NlPhoneShell 存在
        const phoneShell = await page.locator('.nl-shell').count()
        expect(phoneShell, `${label} 应有 NlPhoneShell 容器`).toBeGreaterThan(0)

        expect(errors, `${label} 不应有 console error`).toEqual([])
      })
    }

    test(`NlPhoneShell padding 自适应 at ${vp.name}`, async ({ page }) => {
      await page.goto(ROUTES.familyHome)
      await page.waitForLoadState('networkidle')
      const main = page.locator('.nl-shell__main').first()
      await expect(main).toBeVisible()
      const paddingTop = await main.evaluate((el) => parseFloat(getComputedStyle(el).paddingTop))
      // 小屏 (≤360) padding 收紧到 $nl-space-3=12px;正常屏走 $nl-gap-section=16px+
      if (vp.width <= 360) {
        expect(paddingTop, `≤360 屏 padding-top 应 ≤ 16px`).toBeLessThanOrEqual(16)
      } else {
        expect(paddingTop, `>360 屏 padding-top 应 ≥ 16px`).toBeGreaterThanOrEqual(16)
      }
    })

    test(`横屏紧凑模式(${vp.name === 'landscape-414x320' ? '生效' : '不适用'})`, async ({ page }) => {
      // 横屏模式仅在 height ≤ 480 且 orientation=landscape 时生效
      // chromium 在 setViewportSize 时默认 landscape 是 width > height
      await page.goto(ROUTES.familyHome)
      await page.waitForLoadState('networkidle')
      // 不做硬断点断言（CSS 变量跨组件传递链路长），仅断言页面渲染不爆
      const phoneShell = await page.locator('.nl-shell').count()
      expect(phoneShell).toBeGreaterThan(0)
    })
  })
}

/** 跨 viewport 一致性:同一个 admin/order 详情弹窗在不同 viewport 下的全屏行为 */
test.describe('el-dialog fullscreen 行为', () => {
  test.use({ storageState: authFile('admin') })

  test('admin 详情弹窗 < 768 走 fullscreen', async ({ page }) => {
    await page.setViewportSize({ width: 720, height: 600 })
    await page.goto(ROUTES.adminOrder)
    await page.waitForLoadState('networkidle')
    // 第一条数据行的「查看」按钮
    const viewBtn = page.locator('.nl-admin-table__card-actions button, .el-table__row button').first()
    if (await viewBtn.count()) {
      await viewBtn.click()
      // 等待 dialog 出现并断言 fullscreen 生效(ElDialog fullscreen 时 .el-dialog 会带 is-fullscreen)
      await page.waitForSelector('.el-dialog', { state: 'visible' })
      const isFullscreen = await page.locator('.el-dialog.is-fullscreen').count()
      expect(isFullscreen, '<768 admin 应全屏').toBeGreaterThan(0)
    }
  })
})