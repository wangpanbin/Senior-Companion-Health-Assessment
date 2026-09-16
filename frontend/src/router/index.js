import { createRouter, createWebHistory } from 'vue-router'
import routes from './routes'
import { useUserStore } from '@/store/modules/user'

/**
 * 路由实例 + 全局守卫
 *
 * 设计依据：AGENTS.md §3.9 / design.md §1.4
 *
 * - 公开页（meta.public）直接放行
 * - 未登录访问业务页 → /login?redirect=<原地址>
 * - 已登录访问 /login → 回角色主页
 * - 角色不匹配 → 回该角色主页（移动端）或 /admin/dashboard（管理员）
 */

const AUTH_ENABLED = true

/** 4 角色主页 */
const ROLE_HOME = {
  ELDER: '/elder/home',
  FAMILY: '/family/home',
  COMPANION: '/companion/hall',
  ADMIN: '/admin/dashboard'
}

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

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
      next({ path: ROLE_HOME[userStore.role] || '/login', replace: true })
    } else {
      next({ path: '/login', replace: true })
    }
    return
  }

  // 公开页面（登录/注册/找回/错误页）直接放行
  if (to.meta.public) {
    if (userStore.isLogin && to.name === 'Login') {
      next({ path: ROLE_HOME[userStore.role] || '/', replace: true })
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
      next({ path: ROLE_HOME[userStore.role] || '/login', replace: true })
      return
    }
  }

  next()
})

export default router
