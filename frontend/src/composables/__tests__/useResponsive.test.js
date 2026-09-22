/**
 * useResponsive 单测
 *
 * 覆盖 5 档断点的边界值 + 模块级单例行为。
 *
 * 设计要点（与 useDevice 文档同款）：
 * - 模块级单例：所有 ref / 监听器在模块作用域创建，调用 useResponsive() 只返回同一份
 * - 断点只在跨越时翻转：用 matchMedia change 事件，不抖
 *
 * 测试策略：
 * - 通过 setup.js 提供的 __setMatchMedia() 控制 matchMedia 行为 + dispatch change
 * - 多次 setMatchMedia 不会重跑 ensureInit（模块级缓存），状态通过 listener 翻转
 * - isXsPhone 等 boolean computed 通过 .value 取值
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { useResponsive } from '@/composables/useResponsive'

/** 断点 → MediaQueryList query 字符串映射，与 useResponsive 内部保持一致 */
const QUERIES = {
  sm: '(max-width: 1023px)',
  md: '(min-width: 1024px) and (max-width: 1279px)',
  lg: '(min-width: 1280px)',
  xsPhone: '(max-width: 360px)',
  landPhone: '(max-height: 480px)'
}

describe('useResponsive · 5 档断点判定', () => {
  beforeEach(() => {
    window.__resetMatchMedia()
  })

  afterEach(() => {
    window.__resetMatchMedia()
  })

  it('320 宽：isXsPhone + isSm，breakpoint=xsPhone', () => {
    window.__setMatchMedia({
      [QUERIES.xsPhone]: true,
      [QUERIES.sm]: true,
      [QUERIES.md]: false,
      [QUERIES.lg]: false
    })
    const r = useResponsive()
    expect(r.isXsPhone.value).toBe(true)
    expect(r.isSm.value).toBe(true)
    expect(r.isMd.value).toBe(false)
    expect(r.isLg.value).toBe(false)
    expect(r.breakpoint.value).toBe('xsPhone')
  })

  it('360 宽：边界恰好 xsPhone=false，breakpoint=sm', () => {
    window.__setMatchMedia({
      [QUERIES.xsPhone]: false,
      [QUERIES.sm]: true,
      [QUERIES.md]: false,
      [QUERIES.lg]: false
    })
    const r = useResponsive()
    expect(r.isXsPhone.value).toBe(false)
    expect(r.isSm.value).toBe(true)
    expect(r.breakpoint.value).toBe('sm')
  })

  it('768 宽：isSm 仍是 true（max-width: 1023），breakpoint=sm', () => {
    window.__setMatchMedia({
      [QUERIES.xsPhone]: false,
      [QUERIES.sm]: true,
      [QUERIES.md]: false,
      [QUERIES.lg]: false
    })
    const r = useResponsive()
    expect(r.isSm.value).toBe(true)
    expect(r.isMd.value).toBe(false)
    expect(r.isLg.value).toBe(false)
    expect(r.breakpoint.value).toBe('sm')
  })

  it('1024 宽：sm 边界翻转，breakpoint=md', () => {
    window.__setMatchMedia({
      [QUERIES.xsPhone]: false,
      [QUERIES.sm]: false,
      [QUERIES.md]: true,
      [QUERIES.lg]: true
    })
    const r = useResponsive()
    expect(r.isSm.value).toBe(false)
    expect(r.isMd.value).toBe(true)
    expect(r.isLg.value).toBe(true)
    expect(r.breakpoint.value).toBe('md')
  })

  it('1280 宽：md 边界翻转，breakpoint=lg', () => {
    window.__setMatchMedia({
      [QUERIES.xsPhone]: false,
      [QUERIES.sm]: false,
      [QUERIES.md]: false,
      [QUERIES.lg]: true
    })
    const r = useResponsive()
    expect(r.isMd.value).toBe(false)
    expect(r.isLg.value).toBe(true)
    expect(r.breakpoint.value).toBe('lg')
  })

  it('横屏 414×320：isLandscapePhone = true', () => {
    window.__setMatchMedia({
      [QUERIES.landPhone]: true
    })
    const r = useResponsive()
    expect(r.isLandscapePhone.value).toBe(true)
  })

  it('横屏 414×481：isLandscapePhone=false（高度 > 480 不算横屏紧凑）', () => {
    window.__setMatchMedia({
      [QUERIES.landPhone]: false
    })
    const r = useResponsive()
    expect(r.isLandscapePhone.value).toBe(false)
  })
})

describe('useResponsive · 模块级单例 + 断点跨越翻转', () => {
  beforeEach(() => {
    window.__resetMatchMedia()
  })

  afterEach(() => {
    window.__resetMatchMedia()
  })

  it('多次调用看到的是同一份 ref（行为一致）', () => {
    window.__setMatchMedia({ [QUERIES.lg]: true })
    const a = useResponsive()
    const b = useResponsive()
    // 行为相等即可；computed 是每次新建的，但底层 matches ref 是同一个
    expect(a.isLg.value).toBe(b.isLg.value)
    expect(a.breakpoint.value).toBe(b.breakpoint.value)
    expect(a.isLg.value).toBe(true)
  })

  it('断点跨越时两份 useResponsive 实例都更新（共享 ref）', () => {
    window.__setMatchMedia({ [QUERIES.sm]: true })
    const a = useResponsive()
    const b = useResponsive()
    expect(a.isSm.value).toBe(true)
    expect(b.isSm.value).toBe(true)

    // 跨越：sm → md/lg
    window.__setMatchMedia({
      [QUERIES.sm]: false,
      [QUERIES.md]: true,
      [QUERIES.lg]: true
    })
    expect(a.isSm.value).toBe(false)
    expect(b.isSm.value).toBe(false)
    expect(a.isMd.value).toBe(true)
    expect(b.isLg.value).toBe(true)
    expect(a.breakpoint.value).toBe('md')
    expect(b.breakpoint.value).toBe('md')
  })
})