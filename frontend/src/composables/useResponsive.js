import { computed, ref } from 'vue'

/**
 * useResponsive · 断点判定 composable（desktop-adapt-v2 T-01）
 *
 * 依据：`docs/spec/desktop-adapt-v2.md` §4.3 + `docs/plan/desktop-adapt-2026-09.md` Q12
 *
 * ## 5 档断点（与 _breakpoints.scss 同步）
 *
 *   sm     < 1024       平板横屏 / 小 PC
 *   md  1024 - 1279     标准 PC(主流笔记本)
 *   lg     ≥ 1280       大屏
 *
 *   xsPhone   ≤ 360       小屏 Android
 *   landPhone ≤ 480 且高度 ≤ 480  横屏紧凑模式
 *
 * ## 三条实现约定（沿用 useDevice 的 §2.2）
 *
 * 1. **模块级单例** —— 所有 ref / mql / 监听器在模块作用域创建，
 *    `useResponsive()` 只返回同一份实例。否则每个 view 各持一份状态，
 *    跨组件看不到对方的翻转。
 * 2. **断点只在跨越时翻转** —— 用 `MediaQueryList` 的 `change` 事件,
 *    不抖,拖拽窗口过程中无中间态重渲染。
 * 3. **测试友好** —— 监听器注册是幂等的,jsdom 未实现 matchMedia 时安全降级
 *    （所有 boolean 默认为 false,breakpoint 返回 'lg' 作为最宽松档）。
 *
 * ⚠️ 与 `useDevice` 的关系：useDevice 是 v1 形态判定（mobile vs desktop 二元），
 *    useResponsive 是 v2 档位细化（5 档）。两个 composable 并存,useDevice 暂时不动。
 *    后续 view 改造陆续从 `isMobile / isDesktop` 迁到 `useResponsive()` 的 tier 接口。
 */

/** 5 档断点 → MediaQueryList query 字符串映射 */
const QUERIES = {
  sm: '(max-width: 1023px)',
  md: '(min-width: 1024px) and (max-width: 1279px)',
  lg: '(min-width: 1280px)',
  xsPhone: '(max-width: 360px)',
  landPhone: '(max-height: 480px)'
}

/** 模块级 ref：每档一个 boolean，记录当前是否命中 */
const matches = {
  sm:   ref(false),
  md:   ref(false),
  lg:   ref(false),
  xsPhone:    ref(false),
  landPhone:  ref(false)
}

/** matchMedia 实例（保留引用，方便调试 / 释放） */
const mqls = {}

/** 监听器是否已注册（模块级只跑一次） */
let initialized = false

function handleChange(key) {
  return (event) => {
    matches[key].value = event.matches
  }
}

function ensureInit() {
  if (initialized) return
  initialized = true
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    // jsdom 未 mock matchMedia：所有 boolean 维持 false，breakpoint 兜底 'lg'
    return
  }
  for (const key of Object.keys(QUERIES)) {
    const mql = window.matchMedia(QUERIES[key])
    matches[key].value = mql.matches
    mqls[key] = mql
    const handler = handleChange(key)
    // Safari < 14 只有废弃的 addListener；两代 API 都兜住
    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', handler)
    } else if (typeof mql.addListener === 'function') {
      mql.addListener(handler)
    }
  }
}

/**
 * 取当前 5 档断点状态。多次调用返回同一份 ref，保证跨组件一致。
 *
 * @returns {{
 *   isSm: ComputedRef<boolean>,
 *   isMd: ComputedRef<boolean>,
 *   isLg: ComputedRef<boolean>,
 *   isXsPhone: ComputedRef<boolean>,
 *   isLandscapePhone: ComputedRef<boolean>,
 *   breakpoint: ComputedRef<'xsPhone'|'sm'|'md'|'lg'|'landPhone'>
 * }}
 */
export function useResponsive() {
  ensureInit()
  return {
    isSm: computed(() => matches.sm.value),
    isMd: computed(() => matches.md.value),
    isLg: computed(() => matches.lg.value),
    isXsPhone: computed(() => matches.xsPhone.value),
    isLandscapePhone: computed(() => matches.landPhone.value),
    /**
     * 当前激活的断点档位。
     * 优先级：xsPhone > sm > md > lg > landPhone
     * - xsPhone 优先级最高（≤360 必须先识别出来）
     * - landPhone 优先级最低（横屏是 "在某个更宽档上的额外属性"，不替代主档位）
     */
    breakpoint: computed(() => {
      if (matches.xsPhone.value) return 'xsPhone'
      if (matches.sm.value) return 'sm'
      if (matches.md.value) return 'md'
      if (matches.lg.value) return 'lg'
      if (matches.landPhone.value) return 'landPhone'
      // jsdom 未 mock / matchMedia 全部失败：兜底 'lg'（最宽松档，避免误判成 mobile）
      return 'lg'
    })
  }
}

/**
 * 供 **非组件上下文**（Pinia store action / 模块顶层）同步判断档位用。
 *
 * 为什么不直接用 `useResponsive()`：`breakpoint` 是 computed 取值要 .value，
 * 在 store 里写 `useResponsive().breakpoint.value` 语义别扭且容易漏 .value。
 */
export function currentBreakpoint() {
  ensureInit()
  if (matches.xsPhone.value) return 'xsPhone'
  if (matches.sm.value) return 'sm'
  if (matches.md.value) return 'md'
  if (matches.lg.value) return 'lg'
  if (matches.landPhone.value) return 'landPhone'
  return 'lg'
}