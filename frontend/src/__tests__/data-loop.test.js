/**
 * V3.6 数据闭环 · 前端单测（供给缺口 + 效果对比）
 *
 * 锁五件事：
 *
 * 1) **缺口表铺满 168 个桶**（7 天 × 24 小时）—— 后端少给的桶前端补 0，
 *    补的是「空桶显示 0 而不是空白」。空白格子会被读成「没数据」，
 *    而这个时段确实没有缺口，两者是不同的意思。
 *
 * 2) **数据质量如实披露** —— `companionsWithoutSlots > 0` 时必须把
 *    「有 N 名陪诊员没有可解析的可服务时段」摆出来；为 0 时不摆。
 *
 * 3) **效果对比渲染两期值与 delta**，且换指标会**重新向后端要数**
 *    （断言 mock 收到的 params，防止有人改成「在前端换算」）。
 *    默认区间**不**由前端推导前一期 —— 那是后端的缺省推导（ADR-0017 决策 4）。
 *
 * 4) **两块各自失败只降级该块** —— 缺口挂了不影响效果对比，反之亦然。
 *
 * 5) **页面文本不含违禁字样** —— 不出现「健康指数 / 综合评分 / 总分」这类合成分，
 *    也不出现医疗建议、在线支付、提现类表述。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'

/* ---------------- mock：后端 6 个运营端点 ---------------- */
const overviewMock = vi.fn()
const exceptionsMock = vi.fn()
const trendMock = vi.fn()
const healthMock = vi.fn()
const gapMock = vi.fn()
const effectMock = vi.fn()

vi.mock('@/api/statistics', () => ({
  getOperationsOverview: (...a) => overviewMock(...a),
  getOperationsExceptions: (...a) => exceptionsMock(...a),
  getOperationsTrend: (...a) => trendMock(...a),
  getOperationsHealth: (...a) => healthMock(...a),
  getOperationsGap: (...a) => gapMock(...a),
  getOperationsEffect: (...a) => effectMock(...a)
}))

/* 路由：本文件不验路由行为，只是不让 useRouter 在 jsdom 里报错 */
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
  total: 1,
  items: [
    {
      type: 'MANUAL_REQUIRED',
      typeLabel: '需人工介入订单',
      count: 3,
      level: 'DANGER',
      linkUrl: '/admin/fulfillment',
      referenceId: null,
      description: '履约风险已达人工介入阈值，需要管理员协调'
    }
  ]
}

const TREND = {
  dimension: 'ORDER',
  range: 'LAST_7D',
  granularity: 'DAY',
  periodStart: '2026-10-14',
  periodEnd: '2026-10-20',
  categories: ['2026-10-14'],
  series: [{ name: '订单量', data: [12] }]
}

const HEALTH = {
  fulfillmentRate: '96.40%',
  avgAcceptMinutes: '6',
  completionRate: '88.20%',
  complaintRate: '1.20%',
  averageScore: '4.82',
  activeCompanionCount: 24
}

/**
 * 168 个桶（7 天 × 24 小时），空桶也带一行（数值为 0）。
 * 其中两个桶有内容：周六 14:00 有缺口 2，周一 09:00 供给富余（gap = -1）。
 */
function buildGapItems() {
  const items = []
  for (let weekday = 1; weekday <= 7; weekday += 1) {
    for (let hour = 0; hour < 24; hour += 1) {
      items.push({ weekday, hour, pendingUnaccepted: 0, availableCompanions: 0, gap: 0 })
    }
  }
  const saturday = items.find((i) => i.weekday === 6 && i.hour === 14)
  saturday.pendingUnaccepted = 3
  saturday.availableCompanions = 1
  saturday.gap = 2
  const monday = items.find((i) => i.weekday === 1 && i.hour === 9)
  monday.pendingUnaccepted = 1
  monday.availableCompanions = 2
  monday.gap = -1
  return items
}

const GAP = {
  periodStart: '2026-09-21',
  periodEnd: '2026-10-20',
  total: 168,
  companionsWithoutSlots: 7,
  items: buildGapItems()
}

const EFFECT = {
  metric: 'operations.fulfillment_rate',
  metricName: '履约率',
  current: { from: '2026-10-06', to: '2026-10-20', value: '96.40%' },
  previous: { from: '2026-09-21', to: '2026-10-05', value: '92.10%' },
  delta: '+4.30%'
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

/** el-select 桩：渲染成原生 select，选项由 el-option 桩渲染成原生 option */
const ElSelectStub = {
  props: ['modelValue', 'placeholder'],
  emits: ['update:modelValue', 'change'],
  template:
    '<select class="el-select-stub" :value="modelValue" ' +
    '@change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)">' +
    '<slot /></select>'
}

const ElOptionStub = {
  props: ['label', 'value'],
  template: '<option :value="value">{{ label }}</option>'
}

const GLOBAL_STUBS = {
  ElButton: ElButtonStub,
  'el-button': ElButtonStub,
  ElDatePicker: ElDatePickerStub,
  'el-date-picker': ElDatePickerStub,
  ElSelect: ElSelectStub,
  'el-select': ElSelectStub,
  ElOption: ElOptionStub,
  'el-option': ElOptionStub
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
  gapMock.mockReset().mockResolvedValue(GAP)
  effectMock.mockReset().mockResolvedValue(EFFECT)
})

/* ================================================================== *
 * 1 · 供给缺口：168 个桶 + 空桶显示 0
 * ================================================================== */
describe('V3.6 · 供给缺口表', () => {
  it('铺满 168 个桶（7 天 × 24 小时）', async () => {
    const wrapper = await mountOps()
    expect(wrapper.findAll('.ops__gap .gap__cell').length).toBe(168)
  })

  it('后端没给的桶补 0 —— 显示「0」而不是空白', async () => {
    // 只给一个桶：其余 167 个桶前端补 0
    gapMock.mockResolvedValue({
      ...GAP,
      items: [GAP.items.find((i) => i.weekday === 6 && i.hour === 14)]
    })
    const wrapper = await mountOps()
    const cells = wrapper.findAll('.ops__gap .gap__cell')
    expect(cells.length, '桶数永远是 168，与后端给了几行无关').toBe(168)
    const empty = cells.find((c) => c.attributes('data-cell') === '1-0')
    expect(empty.exists()).toBe(true)
    expect(empty.text(), '空桶要显示 0，不是空白').toBe('0')
    const zeros = cells.filter((c) => c.text() === '0')
    expect(zeros.length).toBe(167)
  })

  it('每个格子都带数值与明细（悬停可见）', async () => {
    const wrapper = await mountOps()
    const cell = wrapper
      .findAll('.ops__gap .gap__cell')
      .find((c) => c.attributes('data-cell') === '6-14')
    expect(cell.exists()).toBe(true)
    expect(cell.text()).toBe('2')
    expect(cell.attributes('title')).toBe('周六 14:00 · 待接 3 单 / 可服务 1 人 / 缺口 2')
  })

  it('明细行默认落在缺口最大的桶上', async () => {
    const wrapper = await mountOps()
    expect(wrapper.find('.ops__gap .gap__detail').text()).toContain(
      '周六 14:00 · 待接 3 单 / 可服务 1 人 / 缺口 2'
    )
  })

  it('点格子切明细', async () => {
    const wrapper = await mountOps()
    const cell = wrapper
      .findAll('.ops__gap .gap__cell')
      .find((c) => c.attributes('data-cell') === '1-9')
    await cell.trigger('click')
    await nextTick()
    expect(wrapper.find('.ops__gap .gap__detail').text()).toContain(
      '周一 09:00 · 待接 1 单 / 可服务 2 人 / 缺口 -1'
    )
  })

  it('gap ≤ 0 的桶走中性色（不标成缺口）', async () => {
    const wrapper = await mountOps()
    const neutral = wrapper
      .findAll('.ops__gap .gap__cell')
      .find((c) => c.attributes('data-cell') === '1-9')
    expect(neutral.classes()).toContain('is-lv0')
    const hot = wrapper
      .findAll('.ops__gap .gap__cell')
      .find((c) => c.attributes('data-cell') === '6-14')
    expect(hot.classes()).toContain('is-lv4')
  })
})

/* ================================================================== *
 * 2 · 数据质量披露
 * ================================================================== */
describe('V3.6 · 没有可解析可服务时段的陪诊员', () => {
  it('大于 0 时明确提示', async () => {
    const wrapper = await mountOps()
    expect(wrapper.text()).toContain('有 7 名陪诊员没有可解析的可服务时段，未参与供给统计')
  })

  it('等于 0 时不显示该提示', async () => {
    gapMock.mockResolvedValue({ ...GAP, companionsWithoutSlots: 0 })
    const wrapper = await mountOps()
    expect(wrapper.text()).not.toContain('没有可解析的可服务时段')
  })
})

/* ================================================================== *
 * 3 · 效果对比：两期值 + delta + 换指标重新要数
 * ================================================================== */
describe('V3.6 · 干预效果对比', () => {
  it('渲染指标名、两期区间与取值、delta', async () => {
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('履约率')
    expect(text).toContain('2026-09-21 ~ 2026-10-05')
    expect(text).toContain('92.10%')
    expect(text).toContain('2026-10-06 ~ 2026-10-20')
    expect(text).toContain('96.40%')
    expect(text).toContain('+4.30%')
  })

  it('delta 原样展示（后端给什么显示什么）', async () => {
    effectMock.mockResolvedValue({ ...EFFECT, delta: '—' })
    const wrapper = await mountOps()
    const delta = wrapper.find('.ops__effect .eff__cell--delta .eff__val')
    expect(delta.text()).toBe('—')
  })

  it('默认区间只发后一期 —— 前一期由后端推导', async () => {
    await mountOps()
    const params = effectMock.mock.calls[0][0]
    expect(params.metric).toBe('operations.fulfillment_rate')
    expect(params.from).toBeTruthy()
    expect(params.to).toBeTruthy()
    expect(params.compareFrom, '默认区间不该由前端推导前一期').toBeUndefined()
    expect(params.compareTo).toBeUndefined()
  })

  it('换指标会重新请求，并带上新的 metric', async () => {
    const wrapper = await mountOps()
    const before = effectMock.mock.calls.length
    await wrapper.find('.ops__effect .eff__select').setValue('operations.completion_rate')
    await flushPromises()
    await nextTick()
    expect(effectMock.mock.calls.length).toBeGreaterThan(before)
    const last = effectMock.mock.calls[effectMock.mock.calls.length - 1][0]
    expect(last.metric).toBe('operations.completion_rate')
  })

  it('切到自定义区间会带上两期的四个日期', async () => {
    const wrapper = await mountOps()
    const btn = wrapper.findAll('.ops__effect .seg__btn').find((b) => b.text() === '自定义')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()
    await nextTick()
    const last = effectMock.mock.calls[effectMock.mock.calls.length - 1][0]
    expect(last.from).toBeTruthy()
    expect(last.to).toBeTruthy()
    expect(last.compareFrom, '自定义区间必须带前一期起点').toBeTruthy()
    expect(last.compareTo, '自定义区间必须带前一期终点').toBeTruthy()
  })

  it('下拉只列已注册的指标', async () => {
    const wrapper = await mountOps()
    const options = wrapper.findAll('.ops__effect .el-select-stub option')
    const values = options.map((o) => o.attributes('value'))
    // 清单 = 后端 MetricCalculatorRegistry 里 13 个计算器的主 key；
    // 注册表另有 2 个别名（operations.companion_count /
    // companion.workbench.completion_rate），与主 key 指向同一个计算器，
    // 列进下拉会变成「两个名字、同一组数」，因此不列。
    expect(values).toEqual([
      'operations.fulfillment_rate',
      'operations.completion_rate',
      'operations.complaint_rate',
      'operations.average_score',
      'operations.avg_accept_minutes',
      'operations.active_companion_count',
      'operations.completed_order_count',
      'operations.timeout_rate',
      'operations.manual_intervention_rate',
      'operations.reassign_rate',
      'operations.service_duration_minutes',
      'operations.medication_task_count',
      'operations.medication_missed_rate'
    ])
    expect(values, '别名不列，避免出现重复项').not.toContain('operations.companion_count')
    expect(values, '别名不列，避免出现重复项').not.toContain('companion.workbench.completion_rate')
  })
})

/* ================================================================== *
 * 4 · 三态：失败只降级该块
 * ================================================================== */
describe('V3.6 · 两块各自独立三态', () => {
  it('缺口失败只降级缺口块', async () => {
    gapMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('供给缺口加载失败')
    expect(text).toContain('重试')
    // 其余块照常
    expect(text).toContain('效果对比')
    expect(text).toContain('平台概览')
    expect(text).toContain('88.20%')
  })

  it('效果对比失败只降级该块', async () => {
    effectMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountOps()
    const text = wrapper.text()
    expect(text).toContain('效果对比加载失败')
    expect(text).toContain('供给缺口')
    expect(text).toContain('周六 14:00')
  })

  it('缺口返回 null 走空态，不是白屏', async () => {
    gapMock.mockResolvedValue(null)
    const wrapper = await mountOps()
    expect(wrapper.text()).toContain('暂无缺口数据')
  })

  it('效果对比返回 null 走空态，不是白屏', async () => {
    effectMock.mockResolvedValue(null)
    const wrapper = await mountOps()
    expect(wrapper.text()).toContain('暂无对比数据')
  })
})

/* ================================================================== *
 * 5 · 合规：违禁字样
 * ================================================================== */
describe('V3.6 · 违禁字样', () => {
  it('页面文本不得出现合成分 / 医疗建议 / 支付提现', async () => {
    const wrapper = await mountOps()
    const text = wrapper.text()
    const forbidden = [
      '健康指数',
      '综合评分',
      '总分',
      '建议服用',
      '推荐剂量',
      '诊断为',
      '可能是',
      '在线支付',
      '提现'
    ]
    for (const word of forbidden) {
      expect(text, `页面出现了「${word}」`).not.toContain(word)
    }
  })
})
