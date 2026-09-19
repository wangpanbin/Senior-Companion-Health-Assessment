/**
 * 10 · 形态与角色正交（ADR-0007 / ADR-0008）
 *
 * 同一 URL：<768 → NlPhoneShell（.nl-shell）；≥768 → NlDesktopShell（.nl-desktop-shell）。
 * 4 条 mobile-only 路由在 ≥768 渲染 NlMobileOnlyNotice。
 * 老人模式开关仅在 <768 可见可用。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'

test.use({ storageState: authFile('fam001') })

test.describe('断点 768：同一 URL 两种形态', () => {
  for (const [w, h] of [
    [390, 844],
    [768, 900],
    [1280, 800],
    [1366, 768],
    [1920, 1080]
  ]) {
    test(`${w}×${h} 下 /family/home 的壳`, async ({ page }) => {
      await page.setViewportSize({ width: w, height: h })
      await page.goto(ROUTES.familyHome)
      const phone = page.locator('.nl-shell').first()
      const desktop = page.locator('.nl-desktop-shell').first()
      if (w < 768) {
        await expect(phone, `${w}px 应为移动壳`).toBeVisible()
        await expect(desktop).toHaveCount(0)
      } else {
        await expect(desktop, `${w}px 应为桌面壳`).toBeVisible()
      }
    })
  }
})

test.describe('mobile-only 路由在桌面形态给出提示页', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('/profile 在桌面显示 NlMobileOnlyNotice', async ({ page }) => {
    await page.goto(ROUTES.profile)
    await expect(page.locator('.nl-mobile-only__title')).toBeVisible()
    await expect(page.locator('.nl-mobile-only__title')).toContainText('请在手机上使用')
  })
})

test.describe('老人模式开关仅移动形态可用', () => {
  // 各页开关 title 不同，统一用包含匹配（老人端「开启/退出老人模式」、家属端「切换老人模式」）
  const TOGGLE = 'button[title*="老人模式"]'

  test('桌面形态下老人模式开关不存在', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto(ROUTES.familyHome)
    await expect(page.locator(TOGGLE)).toHaveCount(0)
    await expect(page.locator('html')).not.toHaveClass(/elderly-mode/)
  })

  test('移动形态下开关存在', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(ROUTES.familyHome)
    await expect(page.locator(TOGGLE)).toBeVisible()
  })
})
