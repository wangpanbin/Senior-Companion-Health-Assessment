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

test.describe('≥1280 双栏（ADR-0007 2026-10 变更）', () => {
  test.use({ viewport: { width: 1280, height: 900 } })

  test('相邻两张 .nl-card 并排成两列（同行不同列）', async ({ page }) => {
    await page.goto(ROUTES.familyHome)
    const content = page.locator('.nl-desktop-shell__content')
    await expect(content).toBeVisible()

    // 容器放宽到 1120（≥1280 档），不再是 720 单列
    const width = (await content.boundingBox())?.width || 0
    expect(width, '≥1280 内容区应放宽到 1120 档').toBeGreaterThan(1000)

    // 取前两张卡片，断言顶边同行（y 相同容差 2px）且左边界不同（一左一右）
    const cards = content.locator('> .nl-card')
    const count = await cards.count()
    expect(count, '桌面首页应有至少 2 张卡片可验证双栏').toBeGreaterThanOrEqual(2)

    const a = await cards.nth(0).boundingBox()
    const b = await cards.nth(1).boundingBox()
    expect(a && b, '两张卡片都应有位置').toBeTruthy()
    expect(Math.abs(a.y - b.y), '相邻卡片应顶边同行').toBeLessThan(2)
    expect(b.x, '第二张卡片应在右侧另一列').toBeGreaterThan(a.x + a.width - 2)

    // 单卡不半宽：最后一张孤立卡片应撑满整行（> 720 单列时代的宽度）
    const last = await cards.nth(count - 1).boundingBox()
    expect(last, '最后一张卡片应有位置').toBeTruthy()
    const siblingsInRow = await cards.evaluateAll((els, lastY) =>
      els.filter((el) => Math.abs(el.getBoundingClientRect().top - lastY) < 2).length,
      last.y
    )
    if (siblingsInRow === 1) {
      expect(last.width, '孤卡应自动撑满整行').toBeGreaterThan(700)
    }
  })

  test('768–1279 档仍为 720 单列（不受双栏影响）', async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 800 })
    await page.goto(ROUTES.familyHome)
    const width = (await page.locator('.nl-desktop-shell__content').boundingBox())?.width || 0
    expect(width, '1024 档内容区应保持 720 限宽').toBeLessThanOrEqual(720.5)
  })
})
