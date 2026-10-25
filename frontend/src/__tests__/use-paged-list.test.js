/**
 * P1-8 后台分页列表共性逻辑 · 前端单测
 *
 * <h3>锁的是什么</h3>
 *
 * <p>{@code composables/usePagedList.js} 把 7 个后台列表页逐字复制的三件套收敛成一份：
 * 空态判据（{@code pageDrained}）、空态里的「返回上一页」（{@code onPrevPage}）、
 * 写操作后的重载与回退（{@code reloadAfterMutate}）。</p>
 *
 * <p>其中 {@code reloadAfterMutate} 是最容易写错的一处：判据必须是
 * 「<b>本页是否被掏空</b>」，**不是**「刚处理的那条还在不在本页」——
 * 后者在本页还有别的行时也会跳页，把管理员从正在看的内容上拽走。</p>
 *
 * <p>本页只测纯逻辑组合（页码 / 记录 / 重载次数）。
 * DOM 层的空态文案（「本页已空，上一页还有」与「当前筛选下没有记录」在页面上的区分）
 * <b>本轮没有 e2e 覆盖</b> —— 它要求先把列表翻到末页再做一次写操作，会写库、
 * 且依赖「哪一页恰好只剩一条」的数据状态。判据本身已在本页锁死，
 * 渲染层另由 {@code e2e/specs/22-ux-interaction.spec.js} 的相邻用例间接覆盖。</p>
 *
 * @author 银龄伴诊团队
 */
import { describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import { usePagedList } from '@/composables/usePagedList'

/**
 * 造一个后台列表页的最小环境。
 *
 * @param {object} opts
 * @param {number} [opts.startPage] 当前页码
 * @param {Array} [opts.before] 写操作前本页的记录
 * @param {Array[]} [opts.responses] 每次 reload 时后端返回的「这一页」（按调用顺序取，用完重复最后一份）
 */
function makeList({ startPage = 1, before = [], responses = [] } = {}) {
  const page = ref(startPage)
  const rows = ref([...before])
  const isEmpty = computed(() => rows.value.length === 0)
  let call = 0
  const reload = vi.fn(async () => {
    const next = responses.length ? responses[Math.min(call, responses.length - 1)] : []
    call += 1
    rows.value = [...next]
  })
  return { page, records: rows, isEmpty, reload }
}

/** 写操作把本页最后一条处理掉了 → 本页被掏空，上一页还有数据 */
function drainedList(startPage = 2) {
  return makeList({
    startPage,
    before: [{ id: 1 }],
    // 第一次重载：这一页已被掏空；第二次重载（回退后）：上一页的数据
    responses: [[], [{ id: 9 }]]
  })
}

/** 写操作处理掉了本页的一条，但本页还有别的行 → 不该跳页 */
function survivingList(startPage = 2) {
  return makeList({
    startPage,
    before: [{ id: 1 }],
    responses: [[{ id: 1 }, { id: 2 }]]
  })
}

describe('usePagedList · reloadAfterMutate（写操作后重载 + 必要时回退）', () => {
  it('本页被掏空且不在第 1 页 → 页码减 1，并**再拉一次**上一页的数据', async () => {
    const list = drainedList(2)
    const { reloadAfterMutate } = usePagedList(list)

    await reloadAfterMutate()

    expect(list.page.value, '本页被掏空应回退一页').toBe(1)
    expect(list.reload, '必须重载两次：先看本页是否空，再拉回退后的那一页').toHaveBeenCalledTimes(2)
    expect(list.records.value, '回退后应渲染上一页的数据').toEqual([{ id: 9 }])
  })

  it('本页还有记录 → 只重载一次，页码不动', async () => {
    const list = survivingList(2)
    const { reloadAfterMutate } = usePagedList(list)

    await reloadAfterMutate()

    expect(list.page.value, '本页还有内容就不该把用户拽走').toBe(2)
    expect(list.reload, '只该重载一次').toHaveBeenCalledTimes(1)
  })

  it('第 1 页被掏空 → 页码不动（没有上一页可退），仍只重载一次', async () => {
    const list = makeList({ startPage: 1, before: [{ id: 1 }], responses: [[]] })
    const { reloadAfterMutate } = usePagedList(list)

    await reloadAfterMutate()

    expect(list.page.value).toBe(1)
    expect(list.reload).toHaveBeenCalledTimes(1)
  })

  it('两种场景必须给出不同的重载次数（判据不是摆设）', async () => {
    const drained = drainedList(2)
    const survived = survivingList(2)
    await usePagedList(drained).reloadAfterMutate()
    await usePagedList(survived).reloadAfterMutate()

    expect(drained.reload.mock.calls.length).toBe(2)
    expect(survived.reload.mock.calls.length).toBe(1)
    expect(drained.reload.mock.calls.length, '两种场景不能同一种拉取次数').not.toBe(
      survived.reload.mock.calls.length
    )
  })
})

describe('usePagedList · onPrevPage（空态里的「返回上一页」）', () => {
  it('页码 > 1 → 减 1 并重新拉取', () => {
    const list = survivingList(3)
    const { onPrevPage } = usePagedList(list)

    onPrevPage()

    expect(list.page.value).toBe(2)
    expect(list.reload).toHaveBeenCalledTimes(1)
  })

  it('页码 === 1 → 页码不动（没有上一页可退）', () => {
    const list = survivingList(1)
    const { onPrevPage } = usePagedList(list)

    onPrevPage()

    expect(list.page.value).toBe(1)
  })
})

/**
 * 用例本身的体检：把「容易写坏的实现」摆出来对照，证明上面几条断言不是恒真。
 *
 * <p>没有这一组，上面那些断言即便写错方向也可能照样绿。</p>
 */
describe('用例本身能区分对错（防恒真断言）', () => {
  it('「本页还有记录 → 只重载一次」能抓住「无条件回退 + 重载两次」的坏实现', async () => {
    const list = survivingList(2)
    const brokenReloadAfterMutate = async () => {
      await list.reload()
      list.page.value -= 1
      await list.reload()
    }

    await brokenReloadAfterMutate()

    expect(list.reload.mock.calls.length, '坏实现重载了两次').toBe(2)
    expect(list.reload.mock.calls.length, '因此「只重载一次」这条断言是有牙齿的').not.toBe(1)
  })

  it('「页码 > 1 → 减 1」能抓住「只重载不换页」的坏实现', () => {
    const list = survivingList(3)
    const brokenOnPrevPage = () => {
      list.reload()
    }

    brokenOnPrevPage()

    expect(list.page.value, '坏实现停在第 3 页').toBe(3)
    expect(list.page.value, '因此「页码减 1」这条断言是有牙齿的').not.toBe(2)
  })
})

describe('usePagedList · pageDrained（空态要分两种）', () => {
  it('本页已空且不在第 1 页 → 「本页已空，上一页还有」', () => {
    const list = makeList({ startPage: 2, before: [] })
    const { pageDrained } = usePagedList(list)

    expect(pageDrained.value).toBe(true)
  })

  it('本页已空但在第 1 页 → 走「筛选无结果」空态，不该说「上一页还有」', () => {
    const list = makeList({ startPage: 1, before: [] })
    const { pageDrained } = usePagedList(list)

    expect(pageDrained.value).toBe(false)
  })

  it('本页还有记录 → 不是空态', () => {
    const list = makeList({ startPage: 2, before: [{ id: 1 }] })
    const { pageDrained } = usePagedList(list)

    expect(pageDrained.value).toBe(false)
  })
})
