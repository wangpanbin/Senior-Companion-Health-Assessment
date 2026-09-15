import { createRouter, createWebHistory } from 'vue-router'
import routes from './routes'
import { useUserStore } from '@/store/modules/user'

/**
 * 路由实例 + 全局守卫。
 *
 * ⚠️ 骨架阶段：AUTH_ENABLED = false
 *    因为 M2（登录 / JWT 鉴权）尚未交付，若开启守卫会导致所有页面被踢回登录页，
 *    无法验证布局与样式。M2 交付后把该开关改为 true 即可，守卫逻辑已写好。
 */
const AUTH_ENABLED = false

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
