/**
 * 登录态存储读取（与「令牌存在哪个存储」无关）。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>登录页的「7 天内自动登录」决定令牌落在哪儿：</p>
 * <ul>
 *   <li>勾选（默认）→ <b>localStorage</b>：配合 7 天有效的 refreshToken，关掉浏览器再打开仍在</li>
 *   <li>不勾选 → <b>sessionStorage</b>：关掉标签页即失效（共用设备更安全）</li>
 * </ul>
 *
 * <p>只读 localStorage 的断言，在「不勾选」时读到 undefined，会把
 * 「登录成功」误判成「登录失败」—— 这类**因实现细节而失效的断言**比没有断言更糟。</p>
 *
 * <p>用法（这两个函数在浏览器上下文里跑）：</p>
 * <pre>
 * const token = await page.evaluate(READ_TOKEN)
 * const info = await page.evaluate(READ_USER_INFO)
 * </pre>
 */

/** 读访问令牌；两处都没有则返回空串 */
export function READ_TOKEN() {
  return (
    localStorage.getItem('nianglin_access_token') ||
    sessionStorage.getItem('nianglin_access_token') ||
    ''
  )
}

/** 读用户信息（非敏感字段：id / username / nickname / role / avatar） */
export function READ_USER_INFO() {
  try {
    const raw =
      localStorage.getItem('nianglin_user_info') ||
      sessionStorage.getItem('nianglin_user_info')
    return JSON.parse(raw || '{}')
  } catch {
    return {}
  }
}
