import { defineStore } from 'pinia'
import { login as loginApi, getCurrentUser, logout as logoutApi } from '@/api/auth'
import {
  getToken,
  setToken,
  setRefreshToken,
  getUserInfo,
  setUserInfo,
  clearAuth
} from '@/utils/auth'

/** 四类角色（与后端 RoleConstants 保持一致） */
export const ROLES = {
  ELDER: 'ELDER',
  FAMILY: 'FAMILY',
  COMPANION: 'COMPANION',
  ADMIN: 'ADMIN'
}

export const ROLE_LABELS = {
  ELDER: '老年患者',
  FAMILY: '家属',
  COMPANION: '陪诊员',
  ADMIN: '管理员'
}

/**
 * 登录态与权限。
 *
 * 骨架阶段：完整实现了 state / getters / 动作骨架，
 * 但由于 M2 尚未交付，login 会返回后端错误 —— 这是预期行为。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    /** 非敏感字段：id / username / nickname / role / avatar */
    userInfo: getUserInfo(),
    /** 已加载的路由（按角色动态生成，M2 完善） */
    routesLoaded: false
  }),

  getters: {
    isLogin: (state) => !!state.token,
    role: (state) => state.userInfo?.role || '',
    roleLabel: (state) => ROLE_LABELS[state.userInfo?.role] || '未登录',
    nickname: (state) => state.userInfo?.nickname || '未登录',
    isAdmin: (state) => state.userInfo?.role === ROLES.ADMIN,
    isFamily: (state) => state.userInfo?.role === ROLES.FAMILY,
    isCompanion: (state) => state.userInfo?.role === ROLES.COMPANION,
    isElder: (state) => state.userInfo?.role === ROLES.ELDER
  },

  actions: {
    /** 登录 */
    async login(payload) {
      const data = await loginApi(payload)
      this.token = data.accessToken
      setToken(data.accessToken)
      if (data.refreshToken) {
        setRefreshToken(data.refreshToken)
      }
      this.userInfo = data.userInfo
      setUserInfo(data.userInfo)
      return data
    },

    /** 拉取当前用户信息（刷新页面后恢复） */
    async fetchCurrentUser() {
      const info = await getCurrentUser()
      this.userInfo = info
      setUserInfo(info)
      return info
    },

    /** 登出 */
    async logout() {
      try {
        if (this.token) {
          await logoutApi()
        }
      } catch {
        // 登出接口失败也要清本地，避免卡死
      } finally {
        this.reset()
      }
    },

    /** 清空登录态 */
    reset() {
      this.token = ''
      this.userInfo = null
      this.routesLoaded = false
      clearAuth()
    }
  }
})
