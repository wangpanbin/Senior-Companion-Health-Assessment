/**
 * Vitest setup · jsdom 环境通用 mock
 *
 * jsdom 默认不实现 window.matchMedia，useDevice / useResponsive 都依赖它。
 * 这里提供一个可控的实现：
 *
 * - 默认行为：max-width 类的查询默认 false，min-width 类的默认 true
 * - 调用方通过 __setMatchMedia({query: bool}) 翻转
 * - 翻转后会 dispatch 已注册的 listener，模拟浏览器真实 change 事件
 * - 多次 useResponsive() 共享同一份 ref（模块级单例,composable 自己保证）
 *
 * ⚠️ 这份 mock 只服务 useDevice / useResponsive 的单测，不试图还原
 *    完整的 MediaQueryList 行为（addListener 废弃路径、onchange 等不实现）。
 */
import { vi } from 'vitest'

/** query -> Set<callback>，每个 query 独立的监听器集合 */
const listenersByQuery = new Map()

function makeMql(query, matches) {
  return {
    matches,
    media: query,
    onchange: null,
    addEventListener: (_event, cb) => {
      if (!listenersByQuery.has(query)) listenersByQuery.set(query, new Set())
      listenersByQuery.get(query).add(cb)
    },
    removeEventListener: (_event, cb) => {
      listenersByQuery.get(query)?.delete(cb)
    },
    // Safari < 14 旧 API
    addListener: (cb) => {
      if (!listenersByQuery.has(query)) listenersByQuery.set(query, new Set())
      listenersByQuery.get(query).add(cb)
    },
    removeListener: (cb) => {
      listenersByQuery.get(query)?.delete(cb)
    },
    dispatchEvent: () => true
  }
}

function matchMedia(query) {
  if (typeof window === 'undefined') return makeMql(query, false)
  const map = window.__currentMatchMedia
  if (map && Object.prototype.hasOwnProperty.call(map, query)) {
    return makeMql(query, Boolean(map[query]))
  }
  // 没显式登记的查询：max-* 默认 false，min-* 默认 true（中性兜底）
  const isMin = query.includes('min-width')
  return makeMql(query, isMin)
}

if (typeof window !== 'undefined') {
  window.matchMedia = matchMedia

  // 辅助：测试用例在 beforeEach 设置窗口尺寸
  window.__setViewport = (width, height) => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: width })
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: height })
  }

  /**
   * 设置若干 query 的命中结果，并 dispatch change 事件给该 query 已注册的 listener。
   * 模拟浏览器真实断点跨越行为。
   */
  window.__setMatchMedia = (map) => {
    window.__currentMatchMedia = { ...(window.__currentMatchMedia || {}), ...map }
    for (const [query, cbSet] of listenersByQuery) {
      const newVal = window.__currentMatchMedia[query]
      if (newVal === undefined) continue
      const event = { matches: Boolean(newVal), media: query }
      for (const cb of cbSet) cb(event)
    }
  }

  window.__resetMatchMedia = () => {
    window.__currentMatchMedia = {}
    // ⚠️ 不要清 listenersByQuery：
    //   useResponsive 是模块级单例，listener 在首次 ensureInit 时一次性注册，
    //   跨测试 case 复用即可。每个 case 用 setMatchMedia 触发 dispatch 即翻转 ref。
    //   清掉 listener 会让后续 setMatchMedia 的 dispatch 落空，模块级 ref 拿不到新值。
  }
}

export {}

export const __mockHelpers = { vi }