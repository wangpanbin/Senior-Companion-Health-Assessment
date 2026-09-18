import { computed, ref } from 'vue'

/**
 * useDevice · 形态（form factor）判定 composable
 *
 * 依据：`docs/adr/0007-desktop-form-factor.md` · `docs/plan/desktop-adapt-2026-09.md` Q8/Q12
 *
 * 语义：**形态与角色正交**。任何角色（ELDER / FAMILY / COMPANION）的同一个 URL，
 * 窄屏走 Mobile 形态（`NlPhoneShell`），宽屏走 Desktop 形态（`NlDesktopShell`）。
 * 因此 view 内部只判断 `isMobile`，**不判断角色**。
 *
 * ## 三条实现约定（Q12 = c）
 *
 * 1. **模块级单例** —— 所有 ref / 监听器在模块作用域创建，`useDevice()` 只返回同一份实例。
 *    若写成「每次调用 `matchMedia()` 新建 ref」，则每个 view 各持一份状态：
 *    断点跨越时只有监听器自己翻转，别的组件看不到；跨组件也无法共享 force-mobile。
 * 2. **断点只在跨越时翻转** —— 用 `MediaQueryList` 的 `change` 事件，而不是 `resize`
 *    事件 + 防抖。拖拽窗口过程中不会产生中间态重渲染，即"不闪"。
 * 3. **force-mobile 逃生舱** —— `NlMobileOnlyNotice` 的"继续查看"按钮写
 *    `sessionStorage` 后刷新；刷新后 `isMobile` 恒为 true，视图降级回 Mobile 形态。
 *
 * 纯 CSR 应用，不需要处理 SSR（`window` 一定存在）；但测试环境（jsdom +
 * 未 mock `matchMedia`）会缺 `matchMedia`，故做了一次存在性兜底。
 */

/** 断点：<= 767px 视为 Mobile，>= 768px 视为 Desktop（与计划文档 Q5/Q12 一致） */
export const MOBILE_QUERY = '(max-width: 767px)'

/** force-mobile 的 sessionStorage 键（关闭标签页即失效，不污染 localStorage） */
export const FORCE_MOBILE_KEY = 'force-mobile'

/** 当前是否命中窄屏媒体查询（由 matchMedia 的 change 事件驱动） */
const mqMatched = ref(readMediaQuery())

/** 是否被用户强制降级为 Mobile 形态（来自 sessionStorage） */
const forced = ref(readForced())

/** 媒体查询监听器（模块级只注册一次） */
let mediaQueryList = null

function readMediaQuery() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    // 无 matchMedia 的环境（jsdom 未 mock）按 Mobile 处理：手机壳是安全默认值
    return true
  }
  return window.matchMedia(MOBILE_QUERY).matches
}

function readForced() {
  try {
    return sessionStorage.getItem(FORCE_MOBILE_KEY) === 'true'
  } catch {
    // 隐私模式 / 被测环境禁用 sessionStorage
    return false
  }
}

function handleChange(event) {
  mqMatched.value = event.matches
}

function ensureListener() {
  if (mediaQueryList || typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return
  }
  mediaQueryList = window.matchMedia(MOBILE_QUERY)
  mqMatched.value = mediaQueryList.matches

  // Safari < 14 只有废弃的 addListener；两代 API 都兜住
  if (typeof mediaQueryList.addEventListener === 'function') {
    mediaQueryList.addEventListener('change', handleChange)
  } else if (typeof mediaQueryList.addListener === 'function') {
    mediaQueryList.addListener(handleChange)
  }
}

/**
 * 强制以 Mobile 形态渲染（写入 sessionStorage，需配合刷新才生效）。
 * 供 `NlMobileOnlyNotice` 的"继续查看"按钮调用。
 */
export function forceMobile() {
  forced.value = true
  try {
    sessionStorage.setItem(FORCE_MOBILE_KEY, 'true')
  } catch {
    // 写不进去也不影响本次调用：reactive 的 forced 已经翻了，刷新后回到 Desktop
  }
}

/** 清除强制降级（排障 / 测试用） */
export function clearForceMobile() {
  forced.value = false
  try {
    sessionStorage.removeItem(FORCE_MOBILE_KEY)
  } catch {
    // 忽略
  }
}

/**
 * 供 **非组件上下文**（Pinia store action 等）同步判断形态用。
 *
 * 为什么不直接用 `useDevice()`：`isMobile` 是 computed，取值要 `.value`，
 * 在 store 里写 `useDevice().isMobile.value` 语义别扭且容易漏 `.value` 造成恒真。
 * 而且老人模式的禁用判据必须是**当下**的窗口宽度（Q6 = a 行为防御），
 * 不是响应式状态 —— 一个 action 调用点查一次即可。
 *
 * ⚠️ 与 `mqMatched` 的语义差：这里**不考虑** force-mobile。
 *    被强制降级为手机版的桌面窗口，宽度仍 >= 768，老人模式**依然禁用**（符合 Q4 = II）。
 */
export function isNarrowViewport() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return true
  }
  return window.matchMedia(MOBILE_QUERY).matches
}

/** 桌面形态下是否禁用老人模式（Q4 = II 的单一判据） */
export function isElderlyModeDisabled() {
  return !isNarrowViewport()
}

export function useDevice() {
  ensureListener()

  const isMobile = computed(() => forced.value || mqMatched.value)
  const isDesktop = computed(() => !isMobile.value)

  return {
    isMobile,
    isDesktop,
    /** 'mobile' | 'desktop'，便于打点与断言 */
    deviceMode: computed(() => (isMobile.value ? 'mobile' : 'desktop')),
    /** 是否处于"被强制降级"状态 */
    isForcedMobile: computed(() => forced.value),
    forceMobile,
    clearForceMobile
  }
}
