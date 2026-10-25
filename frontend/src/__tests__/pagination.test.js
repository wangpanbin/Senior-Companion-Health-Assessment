/**
 * P1-8 分页回退判据 · 前端单测
 *
 * <h3>锁的是什么</h3>
 *
 * <p>后台 7 个列表页共用一份判据（{@code utils/pagination.js#shouldRollbackPage}），
 * 它决定「写操作之后要不要把页码退回去」。判据错一次，用户看到的就是
 * 「本页已空，上一页还有」的空态，却翻不回去 —— 只能自己一页页点回来。</p>
 *
 * <p>⚠️ 判据必须是「<b>本页是否被掏空</b>」，**不是**「刚处理的那条还在不在本页」：
 * 后者在本页还有别的行时也会跳页，把管理员从正在看的内容上拽走 ——
 * 那是用一个毛病换另一个毛病。</p>
 *
 * @author 银龄伴诊团队
 */
import { describe, expect, it } from 'vitest'
import { shouldRollbackPage } from '@/utils/pagination'

describe('shouldRollbackPage · 回退判据真值表', () => {
  it('本页还有记录 → 不回退', () => {
    expect(shouldRollbackPage(1, [{ id: 1 }]), '第 1 页有记录').toBe(false)
    expect(shouldRollbackPage(3, [{ id: 1 }, { id: 2 }]), '第 3 页有记录').toBe(false)
  })

  it('本页已空但在第 1 页 → 不回退（没有上一页可退）', () => {
    expect(shouldRollbackPage(1, []), '第 1 页空了也退不了').toBe(false)
  })

  it('本页已空且不在第 1 页 → 回退', () => {
    expect(shouldRollbackPage(2, []), '第 2 页被掏空').toBe(true)
    expect(shouldRollbackPage(9, []), '第 9 页被掏空').toBe(true)
  })

  it('records 不是数组 → 判为「无法判定本页为空」，不回退', () => {
    // 保守口径：宁可多停在空页，也不要把管理员莫名其妙地拽到上一页。
    expect(shouldRollbackPage(2, null)).toBe(false)
    expect(shouldRollbackPage(2, undefined)).toBe(false)
  })

  it('判据只认「本页空不空」，不认「刚处理的那条还在不在本页」', () => {
    // 本页还有别的行 → 不回退。用「刚处理的那条还在不在本页」当判据会在这里跳页。
    expect(shouldRollbackPage(2, [{ id: 999 }]), '本页还有别的行就不该跳页').toBe(false)
  })
})

/**
 * 判据本身的体检。
 *
 * <p>没有这一组，判据写错了（比如退化成「恒真」或「恒假」）测试也会全绿 ——
 * 那就等于没测。</p>
 */
describe('判据本身有效（防恒真 / 恒假）', () => {
  it('同一组输入必须给出「有 true 也有 false」的结论', () => {
    const results = [
      shouldRollbackPage(1, [{ id: 1 }]),
      shouldRollbackPage(1, []),
      shouldRollbackPage(2, [])
    ]
    expect(results, '判据不能恒真').toContain(false)
    expect(results, '判据不能恒假').toContain(true)
  })

  it('「本页空 + 非第 1 页」这一格必须与相邻两格结论不同', () => {
    const drained = shouldRollbackPage(2, [])
    const samePageNotEmpty = shouldRollbackPage(2, [{ id: 1 }])
    const firstPageEmpty = shouldRollbackPage(1, [])
    expect(drained, '本页空且非首页 → 与「本页有记录」不同').not.toBe(samePageNotEmpty)
    expect(drained, '本页空且非首页 → 与「首页空」不同').not.toBe(firstPageEmpty)
  })

  it('坏实现「本页空就退」（忘了「不在第 1 页」）必须被真判据区分出来', () => {
    const broken = (page, records) => page >= 1 && Array.isArray(records) && records.length === 0
    expect(broken(1, []), '坏判据在第 1 页会误判成要回退').toBe(true)
    expect(shouldRollbackPage(1, []), '真判据在第 1 页不回退').toBe(false)
    expect(broken(1, [])).not.toBe(shouldRollbackPage(1, []))
  })

  it('坏实现「只要本页有记录就退」必须被真判据区分出来', () => {
    const broken = (page, records) => page > 1 && Array.isArray(records)
    expect(broken(2, [{ id: 1 }]), '坏判据会把管理员从正在看的内容上拽走').toBe(true)
    expect(shouldRollbackPage(2, [{ id: 1 }]), '真判据在本页有记录时不退').toBe(false)
  })
})
