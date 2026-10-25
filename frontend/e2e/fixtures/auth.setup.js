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

    // 防假绿：不仅看 URL，还看**实际存放登录态的那个存储**里的角色是否与账号一致
    // ⚠️ 令牌可能在 localStorage（「7 天内自动登录」勾选，默认）或 sessionStorage（不勾选），
    //    两个都要读 —— 只读 localStorage 会在「不勾选」时读到 undefined，误判成登录失败。
    const role = await page.evaluate(() => {
      try {
        const raw =
          localStorage.getItem('nianglin_user_info') ||
          sessionStorage.getItem('nianglin_user_info')
        return JSON.parse(raw || '{}').role
      } catch {
        return null
      }
    })
    expect(role, `${acc.account} 登录后 storageState 的 role 应为 ${acc.role}`).toBe(acc.role)

    await context.storageState({ path: authFile(acc.account) })
  })
}
