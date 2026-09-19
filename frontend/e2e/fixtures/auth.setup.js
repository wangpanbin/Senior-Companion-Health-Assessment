/**
 * setup 项目：为每个账号做一次**真实 UI 登录**，落 storageState 供其余 spec 复用。
 *
 * 为什么不做假令牌注入：真实登录本身就是 M2 的一个被测功能；
 * 且 storageState 会同时带上 token + userInfo，避免「有 token 没 role」的假绿（§12.1）。
 */
import { test as setup, expect } from '@playwright/test'
import { ACCOUNTS, ROLE_HOME } from '../helpers/accounts'
import { authFile } from '../helpers/paths'
import { loginViaUI } from './login'

for (const acc of ACCOUNTS) {
  setup(`登录并保存登录态：${acc.account} (${acc.role})`, async ({ page, context }) => {
    await loginViaUI(page, acc.account, undefined, acc.role)

    // 防假绿：不仅看 URL，还看 localStorage 里的角色是否与账号一致
    const role = await page.evaluate(() => {
      try {
        return JSON.parse(localStorage.getItem('nianglin_user_info') || '{}').role
      } catch {
        return null
      }
    })
    expect(role, `${acc.account} 登录后 storageState 的 role 应为 ${acc.role}`).toBe(acc.role)

    await context.storageState({ path: authFile(acc.account) })
  })
}
