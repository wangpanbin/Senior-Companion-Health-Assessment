/**
 * V3.5 平台运营驾驶舱 · 前端单测
 *
 * 锁六件事：
 *
 * 1) **概览八项指标渲染** —— 数字直接来自后端返回值（前端不另算一套）。
 *
 * 2) **异常中心四张卡片 + 链接可达性** —— 每张卡片的「去处理」用后端下发的
 *    `linkUrl`，单测逐个断言这四个 linkUrl **真实存在于前端路由表**。
 *    这是本版最典型的假绿点：卡片看得到、点进去 404。
 *
 * 3) **趋势维度 / 区间切换要重新向后端要数** —— 断言 mock 收到的 params，
 *    防止有人改成「在前端对已有数据重新切分」。
 *
 * 4) **健康度六项 + 无合成分** —— 页面文本不得出现「健康指数 / 综合评分 / 总分」。
 *
 * 5) **单块失败只降级该块** —— 一块报错，其余三块照常渲染。
 *
 * 6) **空数据走空态** —— 不是白屏，也不是报错。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { routes } from '@/router/routes'

/* ---------------- mock：后端 4 个运营端点 ---------------- */
const overviewMock = vi.fn()
const exceptionsMock = vi.fn()
const trendMock = vi.fn()
const healthMock = vi.fn()

vi.mock('@/api/statistics', () => ({
  getOperationsOverview: (...a) => overviewMock(...a),
  getOperationsExceptions: (...a) => exceptionsMock(...a),
  getOperationsTrend: (...a) => trendMock(...a),
  getOperationsHealth: (...a) => healthMock(...a)
}))

/* 路由：只验「点了会不会跳 linkUrl」，不验真实路由行为 */
const { routerPushMock } = vi.hoisted(() => ({ routerPushMock: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: (...a) => routerPushMock(...a) })
}))

/* canvas 画布在 jsdom 下不可用：图表只验「不崩 + 用的是后端给的数据」 */
const chartInitMock = vi.fn(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }))
vi.mock('echarts/core', () => ({
  init: (...args) => chartInitMock(...args),
  use: vi.fn()
}))
vi.mock('echarts/charts', () => ({ LineChart: {} }))
vi.mock('echarts/components', () => ({ GridComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

// 动态 import：确保 mock 注册后再加载组件
const { default: Operations } = await import('@/views/admin/operations.vue')

/* ---------------- 路由表展开（把嵌套 children 拼成绝对路径） ---------------- */

/**
 * 把路由树展开成绝对路径集合。
 * /admin 的 children 在 routes.js 里写的是相对路径（'operations'），
 * 不拼父路径就会误判「路由不存在」。
 */
function flattenPaths(list, parent = '') {
  const out = []
  for (const r of list) {
    const full = r.path.startsWith('/') ? r.path : `${parent.replace(/\/$/, '')}/${r.path}`
    out.push(full)
    if (r.children) out.push(...flattenPaths(r.children, full))
  }
  return out
}

const ROUTE_PATHS = new Set(flattenPaths(routes))

/* ---------------- 夹具 ---------------- */

const OVERVIEW = {
  periodStart: '2026-09-21',
  periodEnd: '2026-10-20',
  todayOrderCount: 128,
  completedOrderCount: 113,
  inServiceCount: 9,
  pendingCount: 6,
  fulfillmentRate: '96.40%',
  avgAcceptMinutes: 6,
  complaintRate: '1.20%',
  averageScore: '4.82'
}

const EXCEPTIONS = {
  total: 16,
  items: [
    {
      type: 'MANUAL_REQUIRED',
      typeLabel: '需人工介入订单',
      count: 3,
      level: 'DANGER',
      linkUrl: '/admin/fulfillment',
      referenceId: null,
      description: '履约风险已达人工介入阈值，需要管理员协调'
    },
    {
      type: 'MATCH_WARNING',
      typeLabel: '超匹配预警时间订单',
      count: 6,
      level: 'WARNING',
      linkUrl: '/admin/fulfillment',
      referenceId: null,
      description: '等待时间超过预警阈值，系统正在扩大匹配范围'
    },
    {
      type: 'COMPANION_COMPLAINT_RATE',
      typeLabel: '投诉率异常陪诊员',
      count: 2,
      level: 'WARNING',
      linkUrl: '/admin/complaint',
      referenceId: null,
      description: '回溯期内投诉率超过阈值，需关注服务质量'
    },
    {
      type: 'MEDICATION_MISSED',
      typeLabel: '用药漏服异常',
      count: 5,
      level: 'WARNING',
      linkUrl: '/admin/order',
      referenceId: 1019,
      description: '区间内漏服任务数超过阈值，最近一条见最近订单'
    }
  ]
}

const TREND = {
  dimension: 'ORDER',
  range: 'LAST_7D',
  granularity: 'DAY',
  periodStart: '2026-10-14',
  periodEnd: '2026-10-20',
  categories: ['2026-10-14', '2026-10-15'],
  series: [{ name: '订单量', data: [12, 0] }]
}

const HEALTH = {
  fulfillmentRate: '96.40%',
  avgAcceptMinutes: '6',
  completionRate: '88.20%',
  complaintRate: '1.20%',
  averageScore: '4.82',
  activeCompanionCount: 24
}

/* ---------------- 桩组件 ---------------- */

const ElButtonStub = {
  props: ['type', 'size', 'round', 'link', 'plain', 'loading', 'disabled'],
  template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
}

const ElDatePickerStub = {
  props: ['modelValue', 'type', 'valueFormat', 'placeholder'],
  emits: ['update:modelValue', 'change'],
  template:
    '<input class="el-date-picker-stub" :placeholder="placeholder" :value="modelValue" ' +
    '@input="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)" />'
}

const GLOBAL_STUBS = {
  ElButton: ElButtonStub,
  'el-button': ElButtonStub,
  ElDatePicker: ElDatePickerStub,
  'el-date-picker': ElDatePickerStub
}

async function mountOps() {
  const wrapper = mount(Operations, { global: { stubs: GLOBAL_STUBS } })
  await flushPromises()
  await nextTick()
  return wrapper
}

beforeEach(() => {
  routerPushMock.mockReset()
  overviewMock.mockReset().mockResolvedValue(OVERVIEW)
  exceptionsMock.mockReset().mockResolvedValue(EXCEPTIONS)
  trendMock.mockReset().mockResolvedValue(TREND)
  healthMock.mockReset().mockResolvedValue(HEALTH)
})

/* ================================================================== *
 * 1 · 平台概览八项指标
 * ================================================================== */
describe('运营驾驶舱 · 平台概览', () => {
  it('八项指标全部渲染', async () => {
    const wrapper = await mountOps()
    const labels = wrapper.findAll('.ops__overview .kpi__label').map((el) => el.text())
    expect(labels).toEqual([
      '今日订单',
      '完成订单',
      '服务中',
      '待接单',
      '履约率',
      '平均接单时间',
      '投诉率',
      '平均评分'
    ])
  })

  it('数字直接来自接口返回值（前端没有另算一套）', async () => {
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('128')
    expect(text).toContain('113')
    expect(text).toContain('96.40%')
    expect(text).toContain('6 分钟')
    expect(text).toContain('1.20%')
    expect(text).toContain('4.82')
  })

  it('改了接口返回值，页面数字跟着变', async () => {
    overviewMock.mockResolvedValue({ ...OVERVIEW, todayOrderCount: 999 })
    const wrapper = await mountOps()
    expect(wrapper.text()).toContain('999')
  })
})

/* ================================================================== *
 * 2 · 异常中心四张卡片 + 链接可达
 * ================================================================== */
describe('运营驾驶舱 · 异常中心', () => {
  it('四张卡片都渲染：typeLabel + count + description + 去处理按钮', async () => {
    const wrapper = await mountOps()
    const cards = wrapper.findAll('.ops__exceptions .exc')
    expect(cards.length).toBe(4)
    for (const c of EXCEPTIONS.items) {
      const card = cards.find((el) => el.attributes('data-type') === c.type)
      expect(card, `缺少异常卡片 ${c.type}`).toBeTruthy()
      expect(card.text()).toContain(c.typeLabel)
      expect(card.text()).toContain(String(c.count))
      expect(card.text()).toContain(c.description)
      expect(card.find('.exc__go').text()).toContain('去处理')
    }
  })

  it('每张卡片的「去处理」linkUrl 在前端路由表里真实存在', async () => {
    const wrapper = await mountOps()
    const links = wrapper.findAll('.ops__exceptions .exc__go').map((el) => el.attributes('data-link'))
    expect(links.length).toBe(4)
    for (const link of links) {
      expect(link, '卡片必须带后端下发的 linkUrl').toBeTruthy()
      expect(ROUTE_PATHS.has(link), `linkUrl「${link}」在路由表里不存在`).toBe(true)
    }
  })

  it('路由表确实包含这四个异常入口（防止上面的断言恒真）', () => {
    for (const url of ['/admin/fulfillment', '/admin/complaint', '/admin/order']) {
      expect(ROUTE_PATHS.has(url), `${url} 应在路由表里`).toBe(true)
    }
    expect(ROUTE_PATHS.has('/admin/operations')).toBe(true)
    expect(ROUTE_PATHS.has('/admin/does-not-exist')).toBe(false)
  })

  it('点「去处理」跳的是后端给的 linkUrl（前端不自己拼路由）', async () => {
    const wrapper = await mountOps()
    const buttons = wrapper.findAll('.ops__exceptions .exc__go')
    await buttons[0].trigger('click')
    expect(routerPushMock).toHaveBeenCalledWith('/admin/fulfillment')
    await buttons[2].trigger('click')
    expect(routerPushMock).toHaveBeenLastCalledWith('/admin/complaint')
  })
})

/* ================================================================== *
 * 3 · 趋势：切换维度 / 区间要重新向后端要数
 * ================================================================== */
describe('运营驾驶舱 · 趋势', () => {
  it('维度切换会重新请求，并带上新的 dimension', async () => {
    const wrapper = await mountOps()
    const before = trendMock.mock.calls.length
    const btn = wrapper.findAll('.ops__trend .seg__btn').find((b) => b.text() === '投诉')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()
    await nextTick()
    expect(trendMock.mock.calls.length).toBeGreaterThan(before)
    const last = trendMock.mock.calls[trendMock.mock.calls.length - 1][0]
    expect(last.dimension).toBe('COMPLAINT')
  })

  it('区间切换会重新请求，并带上新的 range', async () => {
    const wrapper = await mountOps()
    const before = trendMock.mock.calls.length
    const btn = wrapper.findAll('.ops__trend .seg__btn').find((b) => b.text() === '近 30 天')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()
    await nextTick()
    expect(trendMock.mock.calls.length).toBeGreaterThan(before)
    const last = trendMock.mock.calls[trendMock.mock.calls.length - 1][0]
    expect(last.range).toBe('LAST_30D')
  })

  it('切到自定义区间会带上 startDate / endDate', async () => {
    const wrapper = await mountOps()
    const btn = wrapper.findAll('.ops__trend .seg__btn').find((b) => b.text() === '自定义')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()
    await nextTick()
    const last = trendMock.mock.calls[trendMock.mock.calls.length - 1][0]
    expect(last.range).toBe('CUSTOM')
    expect(last.startDate, '自定义区间必须带 startDate').toBeTruthy()
    expect(last.endDate, '自定义区间必须带 endDate').toBeTruthy()
  })

  it('空桶显示 0 而不是空白', async () => {
    const wrapper = await mountOps()
    const items = wrapper.findAll('.ops__trend .trend__item')
    expect(items.length).toBe(2)
    expect(items[0].find('.trend__cat').text()).toBe('2026-10-14')
    expect(items[0].find('.trend__val').text()).toBe('12')
    expect(items[1].find('.trend__val').text()).toBe('0')
  })
})

/* ================================================================== *
 * 4 · 平台健康度：六项并列，无合成分
 * ================================================================== */
describe('运营驾驶舱 · 平台健康度', () => {
  it('六项指标全部渲染', async () => {
    const wrapper = await mountOps()
    const labels = wrapper.findAll('.ops__health .kpi__label').map((el) => el.text())
    expect(labels).toEqual([
      '履约率',
      '接单速度',
      '完成率（按时）',
      '投诉率',
      '平均评分',
      '活跃陪诊员数'
    ])
  })

  it('健康度的六个数值来自接口返回值', async () => {
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('96.40%')
    expect(text).toContain('6 分钟')
    expect(text).toContain('88.20%')
    expect(text).toContain('1.20%')
    expect(text).toContain('4.82')
    expect(text).toContain('24')
  })

  it('页面不得出现「健康指数 / 综合评分 / 总分」这类合成分', async () => {
    const wrapper = await mountOps()
    const text = wrapper.text()
    for (const word of ['健康指数', '综合评分', '总分']) {
      expect(text, `页面出现了「${word}」`).not.toContain(word)
    }
  })
})

/* ================================================================== *
 * 5 · 单块失败只降级该块
 * ================================================================== */
describe('运营驾驶舱 · 单块失败', () => {
  it('异常中心失败只影响该块，其余三块照常渲染', async () => {
    exceptionsMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('异常中心加载失败')
    expect(text).toContain('重试')
    // 其余三块仍在
    expect(text).toContain('平台概览')
    expect(text).toContain('趋势')
    expect(text).toContain('平台健康度')
    expect(text).toContain('128')
    expect(text).toContain('88.20%')
  })

  it('概览失败只影响该块', async () => {
    overviewMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('平台概览加载失败')
    expect(text).toContain('异常中心')
    expect(text).toContain('需人工介入订单')
  })

  it('趋势失败只影响该块', async () => {
    trendMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('趋势加载失败')
    expect(text).toContain('平台概览')
    expect(text).toContain('平台健康度')
  })

  it('健康度失败只影响该块', async () => {
    healthMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('平台健康度加载失败')
    expect(text).toContain('平台概览')
    expect(text).toContain('需人工介入订单')
  })
})

/* ================================================================== *
 * 6 · 空数据
 * ================================================================== */
describe('运营驾驶舱 · 空数据', () => {
  it('四个端点都无数据时，各块走空态而不是白屏', async () => {
    overviewMock.mockResolvedValue(null)
    exceptionsMock.mockResolvedValue(null)
    trendMock.mockResolvedValue(null)
    healthMock.mockResolvedValue(null)
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('暂无概览数据')
    expect(text).toContain('暂无待处理异常')
    expect(text).toContain('暂无趋势数据')
    expect(text).toContain('暂无健康度数据')
  })

  it('异常中心返回空 items 时走空态', async () => {
    exceptionsMock.mockResolvedValue({ total: 0, items: [] })
    const wrapper = await mountOps()
    expect(wrapper.text()).toContain('暂无待处理异常')
    expect(wrapper.findAll('.ops__exceptions .exc').length).toBe(0)
  })
})
