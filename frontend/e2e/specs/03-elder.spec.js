/**
 * 03 · M11 老人端 + 适老化（老人模式）
 *
 * 老人端是只读视图；适老化基线：字号 ≥18px、可点击 ≥48px、
 * <html class="elderly-mode"> 一键切换且**无需刷新**（PRD §2.1）。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'

test.use({ storageState: authFile('elder001'), viewport: { width: 390, height: 844 } })

test.describe('老人端页面（移动形态 390×844）', () => {
  test('老人首页渲染', async ({ page }) => {
    const text = await sweep(page, ROUTES.elderHome)
    expect(text).toContain('银龄伴诊')
  })

  test('老人用药页渲染', async ({ page }) => {
    await sweep(page, ROUTES.elderMedication)
  })

  test('消息页渲染（3 角色共用页）', async ({ page }) => {
    await sweep(page, ROUTES.elderMessage)
  })

  test('我的页渲染（mobile-only）', async ({ page }) => {
    await sweep(page, ROUTES.profile)
  })

  test('老人端路由均落在移动壳内', async ({ page }) => {
    for (const r of [ROUTES.elderHome, ROUTES.elderMedication]) {
      await page.goto(r)
      await expect(page.locator('.nl-shell').first()).toBeVisible()
    }
  })
})

test.describe('老人模式（适老化）', () => {
  // 开关的 title 各页不同（老人端是「开启/退出老人模式」，家属端是「切换老人模式」），
  // 统一用包含匹配。
  const TOGGLE = 'button[title*="老人模式"]'

  test('一键切换：html 加 elderly-mode、字号 ≥18px、再切回移除', async ({ page }) => {
    await page.goto(ROUTES.elderHome)

    const toggle = page.locator(TOGGLE)
    await expect(toggle, '顶栏应有老人模式开关').toBeVisible()

    await toggle.click()
    await expect(page.locator('html'), '应给 <html> 加 elderly-mode').toHaveClass(/elderly-mode/)

    // ⚠️ 适老化基线令牌是 `--nl-font-body`（见 PRD §2.1 / AGENTS.md §3.5 / elderly.scss L26）。
    //    这里同时断言令牌与 body 实际字号，避免只测「名字」不测「效果」。
    const fontBody = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--nl-font-body').trim()
    )
    expect(parseFloat(fontBody), `老人模式 --nl-font-body 应 ≥18px（实际 ${fontBody}）`).toBeGreaterThanOrEqual(18)

    const bodyPx = await page.evaluate(() => parseFloat(getComputedStyle(document.body).fontSize))
    expect(bodyPx, `老人模式 body 实际字号应 ≥18px（实际 ${bodyPx}px）`).toBeGreaterThanOrEqual(18)

    // 无需刷新即生效 —— 页面仍在移动壳内
    await expect(page.locator('.nl-shell').first()).toBeVisible()

    await toggle.click()
    await expect(page.locator('html')).not.toHaveClass(/elderly-mode/)
  })

  test('老人模式偏好写入 localStorage', async ({ page }) => {
    await page.goto(ROUTES.elderHome)
    await page.locator(TOGGLE).click()
    await expect(page.locator('html')).toHaveClass(/elderly-mode/)
    const stored = await page.evaluate(() => localStorage.getItem('nianglin_elderly_mode'))
    expect(stored).toBe('true')
    await page.locator(TOGGLE).click()
  })

  test('老人模式的可点击区域令牌 ≥48px', async ({ page }) => {
    await page.goto(ROUTES.elderHome)
    await page.locator(TOGGLE).click()
    await expect(page.locator('html')).toHaveClass(/elderly-mode/)
    const touchMin = await page.evaluate(() =>
      getComputedStyle(document.documentElement).getPropertyValue('--nl-touch-min').trim()
    )
    expect(parseFloat(touchMin), `--nl-touch-min 应 ≥48（实际 ${touchMin}）`).toBeGreaterThanOrEqual(48)
    await page.locator(TOGGLE).click()
  })
})
