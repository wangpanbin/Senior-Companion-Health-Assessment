/**
 * sidebar 状态机单测（desktop-adapt-v2 T-03）
 *
 * 覆盖 resolveSidebarMode × toggleSidebarMode 的全部分支。
 */
import { describe, expect, it } from 'vitest'
import {
  resolveSidebarMode,
  toggleSidebarMode,
  SIDEBAR_MODE_AUTO,
  SIDEBAR_MODE_EXPANDED,
  SIDEBAR_MODE_COLLAPSED,
  SIDEBAR_WIDTH_EXPANDED,
  SIDEBAR_WIDTH_COLLAPSED
} from '@/utils/sidebar'

describe('resolveSidebarMode · auto 模式按 isMd 翻转', () => {
  it('auto + isMd=true（≥1280）→ expanded 220px', () => {
    expect(resolveSidebarMode('auto', true)).toEqual({
      width: SIDEBAR_WIDTH_EXPANDED,
      collapsed: false,
      mode: SIDEBAR_MODE_EXPANDED
    })
  })

  it('auto + isMd=false（<1280）→ collapsed 64px', () => {
    expect(resolveSidebarMode('auto', false)).toEqual({
      width: SIDEBAR_WIDTH_COLLAPSED,
      collapsed: true,
      mode: SIDEBAR_MODE_COLLAPSED
    })
  })

  it('null / undefined 视为 auto', () => {
    expect(resolveSidebarMode(null, true).mode).toBe(SIDEBAR_MODE_EXPANDED)
    expect(resolveSidebarMode(undefined, false).mode).toBe(SIDEBAR_MODE_COLLAPSED)
    expect(resolveSidebarMode('', true).mode).toBe(SIDEBAR_MODE_EXPANDED)
  })
})

describe('resolveSidebarMode · 手动态覆盖断点', () => {
  it('expanded + isMd=false（<1280）仍展开', () => {
    expect(resolveSidebarMode('expanded', false).collapsed).toBe(false)
    expect(resolveSidebarMode('expanded', false).width).toBe(SIDEBAR_WIDTH_EXPANDED)
  })

  it('collapsed + isMd=true（≥1280）仍收起', () => {
    expect(resolveSidebarMode('collapsed', true).collapsed).toBe(true)
    expect(resolveSidebarMode('collapsed', true).width).toBe(SIDEBAR_WIDTH_COLLAPSED)
  })
})

describe('resolveSidebarMode · 非法输入回退到 auto', () => {
  it('未知 mode 字符串走 auto（避免手抖导致永久展示）', () => {
    // 防呆：未来加 mode 时忘了改这里，至少不会卡死
    const result = resolveSidebarMode('weird-mode', true)
    expect(result.width).toBe(SIDEBAR_WIDTH_EXPANDED)
  })
})

describe('toggleSidebarMode · 用户手动 toggle 推进', () => {
  it('auto + isMd=true → collapsed（手动收起当前展开的）', () => {
    expect(toggleSidebarMode('auto', true)).toBe(SIDEBAR_MODE_COLLAPSED)
  })

  it('auto + isMd=false → expanded（手动展开当前收起的）', () => {
    expect(toggleSidebarMode('auto', false)).toBe(SIDEBAR_MODE_EXPANDED)
  })

  it('manual expanded → 回到 auto（让断点接管）', () => {
    expect(toggleSidebarMode('expanded', true)).toBe(SIDEBAR_MODE_AUTO)
    expect(toggleSidebarMode('expanded', false)).toBe(SIDEBAR_MODE_AUTO)
  })

  it('manual collapsed → 回到 auto（让断点接管）', () => {
    expect(toggleSidebarMode('collapsed', true)).toBe(SIDEBAR_MODE_AUTO)
    expect(toggleSidebarMode('collapsed', false)).toBe(SIDEBAR_MODE_AUTO)
  })

  it('null / undefined 视为 auto', () => {
    expect(toggleSidebarMode(null, true)).toBe(SIDEBAR_MODE_COLLAPSED)
    expect(toggleSidebarMode(undefined, false)).toBe(SIDEBAR_MODE_EXPANDED)
  })
})