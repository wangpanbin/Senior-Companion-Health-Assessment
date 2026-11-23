/**
 * auth.js 存储辅助单测
 *
 * 覆盖：管理端窄屏提示条的关闭状态读写
 *
 * 为什么单独盯键名：localStorage 的键写错**不会报错**，只会静默失效
 * —— 老人模式当年就栽过同一个坑（design.md §5「写规范时踩过的坑」：
 * 文档写 nl_elderly_mode、代码用 nianglin_elderly_mode → 开关完全不生效）。
 * 所以这里把键名本身也断言住。
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { getAdminMobileHintClosed, setAdminMobileHintClosed } from '@/utils/auth'

const HINT_KEY = 'nianglin_admin_mobile_hint_closed'

describe('admin 窄屏提示条 · 关闭状态', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('默认未关闭', () => {
    expect(getAdminMobileHintClosed()).toBe(false)
  })

  it('关闭后读回 true，且落在约定的键上', () => {
    setAdminMobileHintClosed(true)
    expect(getAdminMobileHintClosed()).toBe(true)
    expect(localStorage.getItem(HINT_KEY)).toBe('true')
  })

  it('取消关闭 → 键被移除，回到 false', () => {
    setAdminMobileHintClosed(true)
    setAdminMobileHintClosed(false)
    expect(getAdminMobileHintClosed()).toBe(false)
    expect(localStorage.getItem(HINT_KEY)).toBeNull()
  })

  it('脏值（"1"）视为未关闭 —— 只认 "true"', () => {
    localStorage.setItem(HINT_KEY, '1')
    expect(getAdminMobileHintClosed()).toBe(false)
  })
})
