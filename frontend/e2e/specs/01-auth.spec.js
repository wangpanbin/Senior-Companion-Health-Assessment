/**
 * 01 · M2 认证与鉴权（登录页 / 4 角色登录态 / 越权跳转 / 注销 / 错误页）
 *
 * 用例依据：PRD §6 M2、docs/api/01-auth-user.md、FRONTEND_CONTRACT §10.7
 */
import { test, expect } from '@playwright/test'
import { ACCOUNTS, ROLE_HOME, PASSWORD } from '../helpers/accounts'
import { authFile } from '../helpers/paths'
import { apiAs, apiLogin, API } from '../helpers/api'
import { loginViaUI } from '../fixtures/login'
import { ROUTES } from '../helpers/constants'

// ============================================================
// 登录页本身（未登录上下文）
// ============================================================
test.describe('登录页', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('渲染品牌区 / 4 角色卡 / 验证码 / 合规免责文案', async ({ page }) => {
    await page.goto(ROUTES.login)
    await expect(page.locator('.login__title')).toHaveText('银龄伴诊')
    await expect(page.locator('.login__rolecard')).toHaveCount(4)
    await expect(page.locator('.login__captcha-img img')).toBeVisible()
    // 合规红线：免责声明必须在
    await expect(page.locator('.login__footer-tip')).toContainText('不构成任何医疗诊断或用药建议')
  })

  test('未登录访问受限路由 → /login?redirect=<原路径>', async ({ page }) => {
    await page.goto(ROUTES.familyOrder)
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.login)
    expect(new URL(page.url()).searchParams.get('redirect')).toBe(ROUTES.familyOrder)
  })

  test('错误验证码 → 登录失败且不落登录态', async ({ page }) => {
    await page.goto(ROUTES.login)
    await page.getByPlaceholder('请输入手机号或用户名').fill('fam001')
    await page.getByPlaceholder('请输入密码').fill(PASSWORD)
    await page.getByPlaceholder('请输入右侧 4 位验证码').fill('ZZZZ')
    await page.locator('button.login__submit').click()
    await expect(page.locator('.el-message')).toBeVisible()
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.login)
    const token = await page.evaluate(() => localStorage.getItem('nianglin_access_token'))
    expect(token).toBeFalsy()
  })

  test('注册页与找回页可渲染', async ({ page }) => {
    await page.goto(ROUTES.register)
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.register)
    await expect(page.locator('body')).not.toBeEmpty()
    await page.goto(ROUTES.forget)
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.forget)
  })

  test('错误页 403 / 404 可访问', async ({ page }) => {
    await page.goto(ROUTES.error403)
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.error403)
    await page.goto('/definitely-not-a-route')
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.error404)
  })
})

// ============================================================
// 4 角色登录态：访问 / 落到各自主页（防假绿：同时校验 role）
// ============================================================
for (const acc of ACCOUNTS) {
  test.describe(`${acc.account} 登录态`, () => {
    test.use({ storageState: authFile(acc.account) })

    test(`访问 / 落到角色主页 ${ROLE_HOME[acc.role]}`, async ({ page }) => {
      await page.goto('/')
      const home = ROLE_HOME[acc.role]
      await expect(page).toHaveURL((u) => u.pathname === home)
      const role = await page.evaluate(() => {
        try {
          return JSON.parse(localStorage.getItem('nianglin_user_info') || '{}').role
        } catch {
          return null
        }
      })
      expect(role).toBe(acc.role)
    })
  })
}

// ============================================================
// 接口层：/auth/me 与 elderId 可见性（FRONTEND_CONTRACT §11）
// ============================================================
test.describe('接口层：/auth/me', () => {
  test('ELDER 返回自己的 elderId（非空）', async () => {
    const { token } = await apiLogin('elder001')
    const ctx = await apiAs(token)
    try {
      const body = await (await ctx.get(`${API}/auth/me`)).json()
      expect(body.code).toBe(200)
      expect(body.data.role).toBe('ELDER')
      expect(body.data.elderId, 'ELDER 应下发 elderId').toBeTruthy()
    } finally {
      await ctx.dispose()
    }
  })

  test('非 ELDER 的 elderId 字段整个消失', async () => {
    const { token } = await apiLogin('fam001')
    const ctx = await apiAs(token)
    try {
      const body = await (await ctx.get(`${API}/auth/me`)).json()
      expect(body.code).toBe(200)
      expect(body.data.role).toBe('FAMILY')
      expect(body.data).not.toHaveProperty('elderId')
    } finally {
      await ctx.dispose()
    }
  })
})

// ============================================================
// 注销：清空本地登录态并跳回登录页
// ============================================================
test.describe('注销', () => {
  // ⚠️ 两条约定（实测踩过）：
  //   1) 不复用共享 storageState —— 登出会作废**当前会话**的令牌；
  //   2) 用**专用账号** comp025（其余 spec 不依赖它的登录态）。
  //   根因：F-01 之后登出是**按 jti** 拉黑的（只拉黑当前 accessToken + refreshToken，
  //   不再递增 `pwd:version`），因此**不会**波及其它设备的登录态。
  //   保留「专用账号 + 空登录态」是为了让本用例与其它 spec 完全解耦，
  //   而不是因为登出会踢掉整个账号。
  // 资料页是纯手机形态（≥768 会渲染 NlMobileOnlyNotice），故用手机视口
  test.use({ storageState: { cookies: [], origins: [] }, viewport: { width: 390, height: 844 } })

  test('退出登录后清空 localStorage 并跳 /login', async ({ page }) => {
    await loginViaUI(page, 'comp025', undefined, 'COMPANION')

    await page.goto(ROUTES.profile)
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.profile)
    await page.getByText('退出登录').first().click()
    // 可能弹确认框
    const confirm = page.locator('.el-message-box__btns button').last()
    if (await confirm.isVisible().catch(() => false)) await confirm.click()
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.login, { timeout: 15000 })
    const token = await page.evaluate(() => localStorage.getItem('nianglin_access_token'))
    expect(token).toBeFalsy()
  })
})
