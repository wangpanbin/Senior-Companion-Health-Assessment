/**
 * 24 · 404 兜底页（catch-all → /error/404，2026-11-28 重设计）
 *
 * 01-auth.spec.js 已覆盖「坏路径落 /error/404」的 URL 断言；
 * 本 spec 补重设计后的三件事：
 *   1. 页面内容真实渲染（标题 / 404 数字 / 装饰插画）；
 *   2. 未登录态主按钮文案是「去登录」，点击落 /login；
 *   3. 新上下文直接打开坏地址没有来路 → 「返回上一页」不渲染
 *      （有来路的分支由单测 not-found-page.test.js 覆盖）。
 */
import { test, expect } from '@playwright/test'
import { ROUTES } from '../helpers/constants'

test.describe('404 兜底页', () => {
  // 公开页：用空登录态，不依赖 auth.setup 的共享 storage
  test.use({ storageState: { cookies: [], origins: [] } })

  test('坏路径落 404 页，内容渲染完整', async ({ page }) => {
    await page.goto('/definitely/not/exist')
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.error404)
    await expect(page.locator('.nl-404__title')).toHaveText('页面走丢了')
    await expect(page.locator('.nl-404__code')).toHaveText('404')
    await expect(page.locator('.nl-404__svg')).toBeVisible()
  })

  test('无来路时不渲染「返回上一页」；未登录主按钮「去登录」点击落登录页', async ({ page }) => {
    await page.goto('/definitely/not/exist')
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.error404)

    // 新上下文直接打开坏地址 → history.state.back 为空 → 不给「点了没反应」的返回按钮
    await expect(page.getByRole('button', { name: '返回上一页' })).toHaveCount(0)

    await page.getByRole('button', { name: '去登录' }).click()
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.login)
  })

  test('document.title 带错误页标题（守卫统一拼装）', async ({ page }) => {
    await page.goto('/no/such/page')
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.error404)
    await expect(page).toHaveTitle(/页面不存在/)
  })
})
