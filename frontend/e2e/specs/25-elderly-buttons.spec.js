/**
 * 25 · 老人模式按钮 56px 档回归（issue #65）
 *
 * #65 根因：elderly.scss 的裸 `.el-button--large { min-height: 56px }` 编译后
 * 特异性 (0,2,1)，被 index.scss <768px 兜底
 * `.el-button:not(--small):not(--text):not(--link)` 的 (0,4,0) 压制
 * → 移动端大按钮全站钉在 48px，56px 档从未生效。
 * 修复：升级为 `.el-button.el-button--large:not(--text):not(--link)`（(0,5,1)）。
 *
 * 本 spec 把 #65 的判据固化成回归防线（390×844 移动形态）：
 *   1. 老人模式大按钮 ≥56px —— 登录页提交按钮 + 404 页主按钮（均为公共页，免登录）；
 *   2. 非老人模式对照：同一按钮 48 档（≥48 且 <56）—— 证明 56 档真的来自老人模式
 *      规则，防止「按钮本来就高」的假绿；
 *   3. `--small` 档（44px 兜底）不回归：新选择器自带 `.el-button--large`，
 *      结构上不可能命中 small 按钮，以注释存档，不单测。
 *
 * 老人模式开启方式：addInitScript 预置 localStorage（app store 启动时经
 * utils/auth.js 的 ELDERLY_MODE_KEY 恢复偏好）；390px 视口下桌面形态防御
 * （app.js applyElderlyClass 的 ≥768 不挂 class）不拦截，免登录即生效。
 */
import { test, expect } from '@playwright/test'
import { ROUTES } from '../helpers/constants'

test.describe('老人模式 · 大按钮 ≥56px（#65）', () => {
  test.use({ storageState: { cookies: [], origins: [] }, viewport: { width: 390, height: 844 } })

  test.beforeEach(async ({ context }) => {
    await context.addInitScript(() => localStorage.setItem('nianglin_elderly_mode', 'true'))
  })

  test('登录页提交按钮 ≥56px', async ({ page }) => {
    await page.goto(ROUTES.login)
    await expect(page.locator('html'), '预置偏好应在启动时挂上 elderly-mode').toHaveClass(
      /elderly-mode/
    )
    const h = await page
      .locator('button.login__submit')
      .evaluate((el) => Math.round(el.getBoundingClientRect().height))
    expect(h, `老人模式大按钮应 ≥56px（实际 ${h}px）`).toBeGreaterThanOrEqual(56)
  })

  test('404 页主按钮 ≥56px', async ({ page }) => {
    await page.goto('/x/bad/path')
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.error404)
    const h = await page
      .locator('.el-button')
      .first()
      .evaluate((el) => Math.round(el.getBoundingClientRect().height))
    expect(h, `老人模式大按钮应 ≥56px（实际 ${h}px）`).toBeGreaterThanOrEqual(56)
  })
})

test.describe('非老人模式对照 · 大按钮 48 档（#65）', () => {
  test.use({ storageState: { cookies: [], origins: [] }, viewport: { width: 390, height: 844 } })

  test('同一按钮在非老人模式下 ≥48 且 <56', async ({ page }) => {
    await page.goto(ROUTES.login)
    await expect(page.locator('html')).not.toHaveClass(/elderly-mode/)
    const h = await page
      .locator('button.login__submit')
      .evaluate((el) => Math.round(el.getBoundingClientRect().height))
    expect(h, `非老人模式大按钮应落在 48 档（实际 ${h}px）`).toBeGreaterThanOrEqual(48)
    expect(h, `非老人模式不应吃到 56px 档（实际 ${h}px）`).toBeLessThan(56)
  })
})
