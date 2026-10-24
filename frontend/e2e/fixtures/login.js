/**
 * 可复用的 UI 登录：填表 + 验证码旁路（Redis 取明文）+ 等待落地角色主页。
 *
 * 抽出来是为了让「注销」这类用例能**自己新建一次登录**，
 * 而不是复用共享的 storageState —— 否则登出会把共享 token 拉黑，
 * 让后续所有复用该 storageState 的 spec 全部 401（实测踩过）。
 */
import { expect } from '@playwright/test'
import { PASSWORD, ROLE_HOME } from '../helpers/accounts'
import { readCaptcha } from './captcha'

export async function loginViaUI(page, account, password = PASSWORD, role) {
  let captchaKey = null
  const route = '**/api/auth/captcha'

  await page.route(route, async (r) => {
    const response = await r.fetch()
    try {
      captchaKey = (await response.json())?.data?.captchaKey ?? captchaKey
    } catch {
      /* 非 JSON 忽略 */
    }
    // ⚠️ fulfill 偶发 `Route is already handled!`（2026-10-25 验收 1 flaky）：
    // 拦截器在 fetch/解析期间请求被导航收尾或另一层路由处理抢先落定。
    // 此时回包已有人送达页面，captchaKey 也已记到，再 fulfill 只会抛错 ——
    // 所以只容忍这一句，其它错误照常上抛。
    try {
      await r.fulfill({ response })
    } catch (e) {
      if (!String(e).includes('Route is already handled')) throw e
    }
  })

  try {
    await page.goto('/login')
    await expect.poll(() => captchaKey, { timeout: 15000 }).not.toBeNull()
    const code = await readCaptcha(captchaKey)

    await page.getByPlaceholder('请输入手机号或用户名').fill(account)
    await page.getByPlaceholder('请输入密码').fill(password)
    await page.getByPlaceholder('请输入右侧 4 位验证码').fill(code)
    await page.locator('button.login__submit').click()

    if (role) {
      await page.waitForURL((u) => u.pathname === ROLE_HOME[role], { timeout: 20000 })
    }
  } finally {
    await page.unroute(route)
  }
}
