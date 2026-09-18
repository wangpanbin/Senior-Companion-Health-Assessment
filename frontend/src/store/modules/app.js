import { defineStore } from 'pinia'
import { getElderlyMode, setElderlyMode } from '@/utils/auth'
import { isElderlyModeDisabled } from '@/composables/useDevice'

/**
 * 全局 UI 状态。
 *
 * 适老化模式是 M11 的核心交付点（答辩现场一键切换），因此状态放在这里统一管理。
 */
export const useAppStore = defineStore('app', {
  state: () => ({
    /** 页面标题 */
    title: import.meta.env.VITE_APP_TITLE || '银龄伴诊',
    /** 侧边栏是否折叠 */
    sidebarCollapsed: false,
    /** 老人模式：字号放大 + 高对比 + 菜单精简 */
    elderlyMode: getElderlyMode(),
    /** 全局 loading 计数（并发请求时避免闪烁） */
    loadingCount: 0
  }),

  getters: {
    loading: (state) => state.loadingCount > 0,
    /** 传给 <el-config-provider :size> 的值 */
    elementSize: (state) => (state.elderlyMode ? 'large' : 'default')
  },

  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
    },

    /**
     * 切换老人模式。
     *
     * 实现方式：给 <html> 打 class，由 styles/elderly.scss 覆盖 CSS 自定义属性，
     * 因此切换是即时的，不需要刷新页面。
     *
     * 桌面形态下**禁用**（ADR-0007 Q4 = II / 计划 Q6 = a 行为防御）：
     * 老人模式的 18px 基线 + 56px 按钮是按 390 宽手机屏定的，在 720px 居中列里
     * 会破坏桌面版信息密度，且桌面形态本身不是给老人用的主场景。
     * 三重防御：① 本 action 直接 return；② applyElderlyClass 摘掉 class；
     * ③ elderly.scss 在 >= 768px 用 !important 兜底。
     */
    setElderlyMode(enabled) {
      if (isElderlyModeDisabled()) {
        // 桌面形态下连 localStorage 都不写，避免"宽屏误点 → 手机端打开变成大字"的串味
        this.elderlyMode = false
        this.applyElderlyClass()
        return
      }
      this.elderlyMode = !!enabled
      setElderlyMode(this.elderlyMode)
      this.applyElderlyClass()
    },

    toggleElderlyMode() {
      this.setElderlyMode(!this.elderlyMode)
    },

    /** 应用 / 移除 <html> 上的 elderly-mode class */
    applyElderlyClass() {
      const root = document.documentElement
      // 桌面形态下即使 localStorage 残留 true 也不挂 class（Q6 = a）
      const enabled = this.elderlyMode && !isElderlyModeDisabled()
      root.classList.toggle('elderly-mode', enabled)
    },

    /** 应用启动时调用一次，恢复上次的偏好 */
    initElderlyMode() {
      this.applyElderlyClass()
    },

    startLoading() {
      this.loadingCount += 1
    },

    stopLoading() {
      this.loadingCount = Math.max(0, this.loadingCount - 1)
    }
  }
})
