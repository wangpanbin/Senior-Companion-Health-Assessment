import { createRouter, createWebHistory } from 'vue-router'
import routes from './routes'
import { useUserStore } from '@/store/modules/user'

/**
 * 路由实例 + 全局守卫
 *
 * 设计依据：AGENTS.md §3.9 / docs/design/design.md §1.4
 *
 * - 公开页（meta.public）直接放行
 * - 未登录访问业务页 → /login?redirect=<原地址>
 * - 已登录访问 /login → 回角色主页（带合法 redirect 时先过白名单再回跳）
 * - 角色不匹配 → /error/403?from=<原地址>（不再静默弹回主页，P1-6）
 *
 * ⚠️ ROLE_HOME 是「角色 → 主页」的**唯一真源**：守卫、403 页的「回首页」按钮、
 *    登录页的 redirect 兜底都从这里取，别处不要再抄一份。
 */

/** 4 角色主页 */
export const ROLE_HOME = {
  ELDER: '/elder/home',
  FAMILY: '/family/home',
  COMPANION: '/companion/hall',
  ADMIN: '/admin/dashboard'
}

const AUTH_ENABLED = true

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/**
 * 取某角色的主页；未知 / 未登录角色兜底到登录页。
 *
 * @param {string} role 角色名（ELDER / FAMILY / COMPANION / ADMIN）
 * @returns {string} 站内路径
 */
export function roleHomePath(role) {
  return ROLE_HOME[role] || '/login'
}

/**
 * 登录回跳地址（?redirect=）的白名单校验（P2-13）。
 *
 * redirect 来自 URL query，可能指向三类东西，都必须挡掉：
 *   1. 站外地址（http://…、//evil.com）→ 一律拒绝（不透传）
 *   2. 不存在的路径 → 回角色主页，而不是落进 404
 *   3. 当前角色无权访问的页（如 FAMILY 指向 /admin/dashboard）→ 回角色主页
 *
 * @param {string} rawPath URL query 里的原始 redirect 值
 * @param {string} role    当前登录角色
 * @returns {string} 站内 fullPath（可直接 router.push(...)）；不合法时返回角色主页
 */
export function resolveRedirect(rawPath, role) {
  const fallback = roleHomePath(role)
  if (typeof rawPath !== 'string') return fallback
  const raw = rawPath.trim()
  // 只接受站内绝对路径：必须以单个 '/' 开头（'//evil.com' 是协议相对地址，排除）
  if (!raw.startsWith('/') || raw.startsWith('//')) return fallback

  let resolved
  try {
    resolved = router.resolve(raw)
  } catch {
    return fallback
  }
  const matched = resolved.matched || []
  if (!matched.length) return fallback
  const last = matched[matched.length - 1]
  // 兜底路由 /:pathMatch(.*)* 不是真实页面（它只会 302 到 /error/404）
  if (String(last.path || '').includes(':pathMatch')) return fallback
  // 路由没写 meta.roles = 所有已登录角色可访问；写了就必须包含当前角色
  const allow = last.meta?.roles
  if (Array.isArray(allow) && allow.length > 0 && !allow.includes(role)) return fallback
  return resolved.fullPath
}

router.beforeEach(async (to, from, next) => {
  const appTitle = import.meta.env.VITE_APP_TITLE || '银龄伴诊'
  document.title = to.meta.title ? `${to.meta.title} · ${appTitle}` : appTitle

  if (!AUTH_ENABLED) {
    next()
    return
  }

  const userStore = useUserStore()

  // 已登录用户访问根路径 → 自动跳角色主页
  if (to.path === '/' || to.path === '') {
    if (userStore.isLogin) {
      next({ path: roleHomePath(userStore.role), replace: true })
    } else {
      next({ path: '/login', replace: true })
    }
    return
  }

  // 公开页面（登录/注册/找回/错误页）直接放行
  if (to.meta.public) {
    if (userStore.isLogin && to.name === 'Login') {
      // 已登录又回到登录页：带 redirect 时先过白名单（P2-13），否则回角色主页
      const target = resolveRedirect(to.query.redirect, userStore.role)
      // 目标就是当前页时不再跳：否则「有 token 但还没有角色信息」时会与
      // 根路径分支来回弹，形成死循环
      if (target === to.path) {
        next()
        return
      }
      const loc = router.resolve(target)
      next({ path: loc.path, query: loc.query, hash: loc.hash, replace: true })
      return
    }
    next()
    return
  }

  // 未登录 → 跳登录页，带原路径做回跳
  if (!userStore.isLogin) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 已登录但没有用户信息（刷新页面）→ 补拉一次
  if (!userStore.userInfo) {
    try {
      await userStore.fetchCurrentUser()
    } catch {
      userStore.reset()
      next({ path: '/login', query: { redirect: to.fullPath } })
      return
    }
  }

  // 角色校验
  const allowRoles = to.meta.roles
  if (Array.isArray(allowRoles) && allowRoles.length > 0) {
    if (!allowRoles.includes(userStore.role)) {
      // 越权不再静默弹回角色主页：落 403 页并带上原路径，
      // 让用户分得清「被拦住了」而不是「点了没反应」（P1-6）
      next({ path: '/error/403', query: { from: to.fullPath }, replace: true })
      return
    }
  }

  next()
})

export default router
