import { createRouter, createWebHistory } from 'vue-router'
import routes from './routes'
import { useUserStore } from '@/store/modules/user'

/**
 * 路由实例 + 全局守卫。
 *
 * ✅ M2（认证与多角色鉴权）已交付，AUTH_ENABLED 置为 true：
 *    未登录访问业务页面会被重定向到 /login?redirect=<原地址>，登录后自动回跳。
 *    角色不足时（meta.roles 未包含当前角色）回首页，不做 403 页面
 *    —— 老人的主界面只有三个入口，跳到一个错误页反而更让人困惑。
 */
const AUTH_ENABLED = true

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

/* ==================== 全局前置守卫 ==================== */

router.beforeEach(async (to, from, next) => {
  const appTitle = import.meta.env.VITE_APP_TITLE || '银龄伴诊'
  document.title = to.meta.title ? `${to.meta.title} · ${appTitle}` : appTitle

  if (!AUTH_ENABLED) {
    next()
    return
  }

  const userStore = useUserStore()

  // 公开页面直接放行
  if (to.meta.public) {
    // 已登录还去登录页 → 回首页
    if (userStore.isLogin && to.name === 'Login') {
      next({ path: '/' })
      return
    }
    next()
    return
  }

  // 未登录 → 去登录页，并记住来源
  if (!userStore.isLogin) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 已登录但没有用户信息（刷新页面场景）→ 补拉一次
  if (!userStore.userInfo) {
    try {
      await userStore.fetchCurrentUser()
    } catch {
      userStore.reset()
      next({ path: '/login', query: { redirect: to.fullPath } })
      return
    }
  }

  // 角色校验：meta.roles 为空表示所有登录角色可访问
  const allowRoles = to.meta.roles
  if (Array.isArray(allowRoles) && allowRoles.length > 0) {
    if (!allowRoles.includes(userStore.role)) {
      next({ path: '/dashboard' })
      return
    }
  }

  next()
})

export default router
