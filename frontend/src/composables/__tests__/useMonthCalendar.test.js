import { describe, expect, it } from 'vitest'
import { ref } from 'vue'
import { useMonthCalendar } from '../useMonthCalendar'

describe('useMonthCalendar（M6 月历/周视图共用）', () => {
  it('2026-09：9 月 1 日是周二 → offset=1，首格 null，1 号在第 2 格', () => {
    const { offset, cells } = useMonthCalendar(2026, 9)
    expect(offset.value).toBe(1)
    expect(cells.value[0]).toBeNull()
    expect(cells.value[1]).toBe(1)
  })

  it('cells 长度始终是 7 的倍数（周视图 7 列对齐），且覆盖当月每一天', () => {
    const { cells } = useMonthCalendar(2026, 9)
    expect(cells.value.length % 7).toBe(0)
    for (let d = 1; d <= 30; d++) expect(cells.value).toContain(d)
  })

  it('接受 ref 参数并响应变化（两页面按 ref 用法复用）', () => {
    const y = ref(2026)
    const m = ref(2)
    const { offset, cells } = useMonthCalendar(y, m)
    // 2026-02-01 是周日 → offset=(0+6)%7=6；28 天 + 6 补位 = 34 → 补到 35
    expect(offset.value).toBe(6)
    expect(cells.value.length).toBe(35)
    y.value = 2024
    m.value = 2
    // 2024-02-01 是周四 → offset=3；闰月 29 天 + 3 = 32 → 补到 35
    expect(offset.value).toBe(3)
    expect(cells.value.length).toBe(35)
  })
})
