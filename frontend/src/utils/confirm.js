/**
 * 二次确认的统一封装。
 *
 * <h3>解决什么问题</h3>
 *
 * <p>全仓有 19 处 {@code await ElMessageBox.confirm|prompt}，其中 6 处没包
 * try/catch —— 用户在确认框点「取消」时，promise 会 reject 无人捕获，
 * 产生 unhandled rejection；而紧跟其后的 API 调用也没有 catch，
 * 一旦真出错就是「静默失败」。</p>
 *
 * <p>这个封装把「用户是否确认」变成一个<b>布尔</b>，让调用方不必再写
 * try/catch 去区分「取消」和「后端报错」：</p>
 *
 * <pre>
 * const ok = await confirmThen(() => ElMessageBox.confirm('确定删除？', '确认', { type: 'warning' }))
 * if (!ok) return          // 用户取消 —— 静默返回
 * await doSomething()      // 后端报错由 request.js 拦截器统一提示
 * </pre>
 *
 * @author 银龄伴诊团队
 */

/**
 * 弹确认框并把「用户是否点了确认」变成布尔值。
 *
 * @param {() => Promise<unknown>} opener 返回 ElMessageBox 确认框的 promise
 * @returns {Promise<boolean>} 确认 → true；取消 / 关闭 → false
 */
export async function confirmThen(opener) {
  try {
    await opener()
    return true
  } catch {
    // Element Plus 在用户点「取消」时 reject('cancel')，点右上角关闭时 reject('close')。
    // 两者都是「用户主动放弃」，不是错误 —— 一律静默返回 false。
    return false
  }
}

/**
 * prompt 的对应版本：确认后返回用户输入的文本，取消返回 {@code null}。
 *
 * <p>⚠️ 返回 {@code null} 表示「用户取消」，调用方必须先判空再使用，
 * 不要和「空字符串」混淆。</p>
 *
 * @param {() => Promise<{ value: string }>} opener 返回 ElMessageBox prompt 的 promise
 * @returns {Promise<string|null>}
 */
export async function promptThen(opener) {
  try {
    const { value } = await opener()
    return value
  } catch {
    return null
  }
}
