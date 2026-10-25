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
import { readCaptcha } from '../fixtures/captcha'
import { READ_TOKEN, READ_USER_INFO } from '../helpers/storage'
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
    const token = await page.evaluate(READ_TOKEN)
    expect(token).toBeFalsy()
  })

  /**
   * 按回车登录只发一次请求。
   *
   * <p>⚠️ 这条用例是为一个**已经存在过的严重缺陷**补的护栏：
   * 登录页同时在 {@code el-form} 上挂了 {@code @submit.prevent}、
   * 又在密码框/验证码框上挂了 {@code @keyup.enter}。浏览器在文本框里按回车会
   * 触发表单隐式提交，{@code keyup} 又再触发一次 → {@code handleSubmit} 执行两次
   * → 发两次登录请求。而验证码是**一次性**的（后端 {@code CaptchaServiceImpl}
   * 用 Redis {@code getAndDelete} 取出即删）→ 第二次必然报「验证码错误」，
   * 而密码其实是对的。</p>
   *
   * <p>为什么之前没被发现：整个 e2e 套件登录一律用
   * {@code page.locator('button.login__submit').click()}，
   * **没有任何一个用例按回车** —— 覆盖缺口正好等于 bug 的形状。</p>
   */
  test('按回车登录只发一次请求（回车曾触发隐式提交 + keyup 双发）', async ({ page }) => {
    let captchaKey = null
    const captchaRoute = '**/api/auth/captcha'
    await page.route(captchaRoute, async (r) => {
      const response = await r.fetch()
      try {
        captchaKey = (await response.json())?.data?.captchaKey ?? captchaKey
      } catch {
        /* 非 JSON 忽略 */
      }
      try {
        await r.fulfill({ response })
      } catch (e) {
        // fulfill 偶发 `Route is already handled!`（路由收尾时），只容忍这一句
        if (!String(e).includes('Route is already handled')) throw e
      }
    })

    let loginCalls = 0
    await page.route('**/api/auth/login', async (r) => {
      loginCalls += 1
      await r.continue()
    })

    await page.goto(ROUTES.login)
    await expect.poll(() => captchaKey, { timeout: 15000 }).not.toBeNull()
    const code = await readCaptcha(captchaKey)

    await page.getByPlaceholder('请输入手机号或用户名').fill('fam001')
    await page.getByPlaceholder('请输入密码').fill(PASSWORD)
    await page.getByPlaceholder('请输入右侧 4 位验证码').fill(code)
    // ⚠️ 按回车，不是点按钮 —— 这正是原缺陷的触发方式
    await page.getByPlaceholder('请输入密码').press('Enter')

    await expect(page).toHaveURL((u) => u.pathname === ROLE_HOME.FAMILY, { timeout: 20000 })
    // 等一拍，确认没有迟到的第二次请求
    await page.waitForTimeout(1500)
    expect(
      loginCalls,
      '按回车只能触发一次登录请求：验证码是一次性的（后端 getAndDelete），第二次必然报「验证码错误」'
    ).toBe(1)
  })

  test('验证码错误后不残留登录态，且换一张验证码可重试成功', async ({ page }) => {
    const keys = []
    const captchaRoute = '**/api/auth/captcha'
    await page.route(captchaRoute, async (r) => {
      const response = await r.fetch()
      try {
        const key = (await response.json())?.data?.captchaKey
        if (key) keys.push(key)
      } catch {
        /* 非 JSON 忽略 */
      }
      try {
        await r.fulfill({ response })
      } catch (e) {
        if (!String(e).includes('Route is already handled')) throw e
      }
    })

    await page.goto(ROUTES.login)
    await expect.poll(() => keys.length, { timeout: 15000 }).toBeGreaterThan(0)

    // 第一次：错验证码
    await page.getByPlaceholder('请输入手机号或用户名').fill('fam001')
    await page.getByPlaceholder('请输入密码').fill(PASSWORD)
    await page.getByPlaceholder('请输入右侧 4 位验证码').fill('ZZZZ')
    await page.locator('button.login__submit').click()

    // 失败后必须仍停在登录页，且不落登录态
    await expect(page.locator('.el-message')).toBeVisible()
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.login)
    const token = await page.evaluate(READ_TOKEN)
    expect(token).toBeFalsy()

    // 失败后页面应自动换一张验证码（后端 getAndDelete 会作废旧的那张），
    // 换新码重试必须能成功 —— 不能让用户卡在「怎么都登不上」
    await expect.poll(() => keys.length, { timeout: 15000 }).toBeGreaterThan(1)
    const freshCode = await readCaptcha(keys[keys.length - 1])
    await page.getByPlaceholder('请输入右侧 4 位验证码').fill(freshCode)
    await page.locator('button.login__submit').click()
    await expect(page).toHaveURL((u) => u.pathname === ROLE_HOME.FAMILY, { timeout: 20000 })
  })

  /**
   * 填表并做一次真实 UI 登录（图形验证码从 Redis 旁路取明文）。
   * @param {boolean|null} remember 是否勾选「7 天内自动登录」；null = 保持默认
   */
  async function uiLogin(page, remember = null) {
    let captchaKey = null
    const captchaRoute = '**/api/auth/captcha'
    await page.route(captchaRoute, async (r) => {
      const response = await r.fetch()
      try {
        captchaKey = (await response.json())?.data?.captchaKey ?? captchaKey
      } catch {
        /* 非 JSON 忽略 */
      }
      try {
        await r.fulfill({ response })
      } catch (e) {
        if (!String(e).includes('Route is already handled')) throw e
      }
    })

    await page.goto(ROUTES.login)
    await expect.poll(() => captchaKey, { timeout: 15000 }).not.toBeNull()
    const code = await readCaptcha(captchaKey)

    await page.getByPlaceholder('请输入手机号或用户名').fill('fam001')
    await page.getByPlaceholder('请输入密码').fill(PASSWORD)
    await page.getByPlaceholder('请输入右侧 4 位验证码').fill(code)
    if (remember === false) {
      // 默认是勾上的，要取消。
      // ⚠️ 不能用 getByRole('checkbox') —— Element Plus 的原生 input 是隐藏的
      //    （.el-checkbox__original），Playwright 点不到；要点可见的那层。
      await page.locator('.login__row .el-checkbox').click()
    }
    await page.locator('button.login__submit').click()
    await expect(page).toHaveURL((u) => u.pathname === ROLE_HOME.FAMILY, { timeout: 20000 })
  }

  test('「7 天内自动登录」勾选 → 令牌落 localStorage（跨浏览器重启仍在）', async ({ page }) => {
    await uiLogin(page)
    const token = await page.evaluate(() => localStorage.getItem('nianglin_access_token'))
    expect(token, '勾选后令牌必须在 localStorage —— 这正是「7 天内自动登录」的落点').toBeTruthy()
  })

  test('「7 天内自动登录」不勾选 → 令牌落 sessionStorage（关掉标签页即失效）', async ({ page }) => {
    await uiLogin(page, false)
    const session = await page.evaluate(() => sessionStorage.getItem('nianglin_access_token'))
    const local = await page.evaluate(() => localStorage.getItem('nianglin_access_token'))
    expect(session, '不勾选时令牌必须在 sessionStorage').toBeTruthy()
    expect(local, '不勾选时 localStorage 不应留下令牌').toBeFalsy()
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
      const role = await page.evaluate(READ_USER_INFO).then((info) => info.role)
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

  test('退出登录后清空登录态并跳 /login', async ({ page }) => {
    await loginViaUI(page, 'comp025', undefined, 'COMPANION')

    await page.goto(ROUTES.profile)
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.profile)
    await page.getByText('退出登录').first().click()
    // 可能弹确认框
    const confirm = page.locator('.el-message-box__btns button').last()
    if (await confirm.isVisible().catch(() => false)) await confirm.click()
    await expect(page).toHaveURL((u) => u.pathname === ROUTES.login, { timeout: 15000 })
    const token = await page.evaluate(READ_TOKEN)
    expect(token).toBeFalsy()
  })
})

// ============================================================
// F-01：登出**按设备（jti）**生效，不波及其它设备
// ============================================================
// 报告出处：reports/playwright/e2e-report.md §F-01
//   修复前：登出会递增 `pwd:version`，作废该账号**全部**令牌 —— 实测 T1 登出后 T2 也 401。
//   修复后：只拉黑当前 accessToken / refreshToken 的 jti，其它会话保持在线。
// ⚠️ 本用例刻意走**接口层**：同一账号两次登录即两台设备（两个 jti），
//    这正是报告里观测 F-01 的方式；不依赖浏览器，判据最干净。
test.describe('F-01 登出按设备生效', () => {
  test('A 设备登出后 B 设备仍在线（同账号两会话）', async () => {
    const devA = await apiLogin('comp025')
    const devB = await apiLogin('comp025')
    expect(devA.token, '两次登录应拿到不同令牌（不同 jti）').not.toBe(devB.token)

    // A 登出：带上自己的 refreshToken，顺带关掉 7 天换发窗口
    const ctxA = await apiAs(devA.token)
    try {
      const out = await (
        await ctxA.post(`${API}/auth/logout`, { data: { refreshToken: devA.refreshToken } })
      ).json()
      expect(out.code, 'A 登出应成功').toBe(200)
    } finally {
      await ctxA.dispose()
    }

    // A 的 accessToken 已进黑名单 → 受保护接口按未认证处理，返回 401
    const ctxA2 = await apiAs(devA.token)
    try {
      expect((await ctxA2.get(`${API}/auth/me`)).status(), 'A 的令牌应已失效').toBe(401)
    } finally {
      await ctxA2.dispose()
    }

    // B 不受影响 —— 这一条就是 F-01 的回归防线
    const ctxB = await apiAs(devB.token)
    try {
      const me = await ctxB.get(`${API}/auth/me`)
      expect(me.status(), 'B 设备不应被 A 的登出踢下线').toBe(200)
      expect((await me.json()).code).toBe(200)
    } finally {
      await ctxB.dispose()
    }
  })
})
