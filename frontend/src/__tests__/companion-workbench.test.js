/**
 * V3.3 陪诊员工作台 · 前端单测
 *
 * 锁两件事：
 *
 * 1) **静态扫描** —— income.vue / workbench.vue 里不得出现前端口径计算
 *    （reduce( / filter( / groupBy）。对应 roadmap 决策 G8「前端统计零自行计算」
 *    与 spec §三「Vitest：前端不再出现 reduce() 口径计算」。
 *    扫描前先剥掉注释，避免注释里的字样造成误报。
 *
 * 2) **组件渲染** —— 工作台四块内容、加载失败态、空数据态，
 *    并断言 KPI 数字直接来自接口返回值（页面没有另算一套）。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/* ---------------- mock：后端 4 个统计端点 ---------------- */
const overviewMock = vi.fn()
const trendMock = vi.fn()
const profileMock = vi.fn()
const incomeMock = vi.fn()

vi.mock('@/api/statistics', () => ({
  getCompanionWorkbenchOverview: (...args) => overviewMock(...args),
  getCompanionWorkbenchTrend: (...args) => trendMock(...args),
  getCompanionWorkbenchProfile: (...args) => profileMock(...args),
  getCompanionWorkbenchIncome: (...args) => incomeMock(...args)
}))

/* 页面壳依赖 router / store，测试里换成透传桩，只验页面自身内容 */
vi.mock('@/components', async (importOriginal) => {
  const actual = await importOriginal()
  return {
    ...actual,
    NlPageShell: { name: 'NlPageShell', template: '<div class="nl-page-shell-stub"><slot /></div>' },
    NlAppTabBar: { name: 'NlAppTabBar', template: '<div class="nl-app-tabbar-stub" />' }
  }
})

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
const { default: Workbench } = await import('@/views/companion/workbench.vue')

/* ---------------- 静态扫描 ---------------- */

const here = dirname(fileURLToPath(import.meta.url))

/** 读 src/ 下的源码文本 */
const readSource = (relPath) => readFileSync(resolve(here, '..', relPath), 'utf8')

/** 去掉块注释与行注释，避免注释里的字样造成误报 */
function stripComments(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1')
}

const FORBIDDEN = [
  { name: 'reduce(', pattern: /\breduce\s*\(/ },
  { name: 'filter(', pattern: /\bfilter\s*\(/ },
  { name: 'groupBy', pattern: /\bgroupBy\b/ }
]

describe('静态扫描 · 前端不自行计算口径指标', () => {
  const files = ['views/companion/income.vue', 'views/companion/workbench.vue']

  files.forEach((rel) => {
    it(`${rel} 不含 reduce( / filter( / groupBy`, () => {
      const code = stripComments(readSource(rel))
      FORBIDDEN.forEach((f) => {
        expect(code, `${rel} 出现了 ${f.name}`).not.toMatch(f.pattern)
      })
    })
  })

  it('income.vue 的区间 KPI 来自接口字段，而不是本地累加', () => {
    const code = stripComments(readSource('views/companion/income.vue'))
    // KPI 三个数字分别对应后端的 actualIncome / settledAmount / unsettledAmount
    expect(code).toContain('actualIncome')
    expect(code).toContain('settledAmount')
    expect(code).toContain('unsettledAmount')
  })

  it('扫描器本身有效：合成的违规片段能被抓出来', () => {
    const bad = 'const total = list.reduce((s, x) => s + x, 0)\nconst a = list.filter((x) => x.ok)'
    const code = stripComments(bad)
    expect(code).toMatch(FORBIDDEN[0].pattern)
    expect(code).toMatch(FORBIDDEN[1].pattern)
  })

  it('扫描器只看代码：注释里的字样不算违规', () => {
    const src = '// 说明：这里曾经用过 reduce( 之类的前端口径\nconst total = 100'
    const code = stripComments(src)
    FORBIDDEN.forEach((f) => {
      expect(code, `注释里的 ${f.name} 被误判了`).not.toMatch(f.pattern)
    })
  })

  it('剥注释不会把真实代码一起删掉（扫描不是空转）', () => {
    const code = stripComments(readSource('views/companion/workbench.vue'))
    expect(code).toContain('getCompanionWorkbenchOverview')
    expect(code).toContain('formatDuration')
  })
})

/* ---------------- 组件渲染 ---------------- */

const OVERVIEW = {
  periodStart: '2026-09-21',
  periodEnd: '2026-10-20',
  completedOrderCount: 28,
  serviceDurationMinutes: 2520,
  completionRate: '93.00%',
  averageScore: '4.80',
  periodIncome: '3680.00'
}

const TREND = {
  dimension: 'ORDER',
  granularity: 'DAY',
  periodStart: '2026-09-21',
  periodEnd: '2026-10-20',
  categories: ['2026-09-21', '2026-09-22'],
  series: [{ name: '完成订单', data: [3, 0] }]
}

const PROFILE = {
  averageScore: '4.80',
  totalCompletedOrderCount: 28,
  totalServedElderCount: 12,
  punctualityRate: '92.00%',
  totalCompletionRate: '93.00%',
  totalServiceDurationMinutes: 2520,
  expertise: [{ department: '普通门诊', orderCount: 8 }]
}

const INCOME = {
  periodStart: '2026-09-21',
  periodEnd: '2026-10-20',
  orderAmount: '4000.00',
  actualIncome: '3680.00',
  unsettledAmount: '1200.00',
  settledAmount: '2480.00',
  advanceTotal: '150.00',
  serviceTotal: '3650.00'
}

const ElButtonStub = {
  props: ['type', 'round', 'link', 'size'],
  template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
}

async function mountWorkbench() {
  const wrapper = mount(Workbench, {
    global: {
      stubs: { ElButton: ElButtonStub, 'el-button': ElButtonStub }
    }
  })
  await flushPromises()
  await nextTick()
  return wrapper
}

describe('工作台 · 正常路径', () => {
  beforeEach(() => {
    overviewMock.mockReset().mockResolvedValue(OVERVIEW)
    trendMock.mockReset().mockResolvedValue(TREND)
    profileMock.mockReset().mockResolvedValue(PROFILE)
    incomeMock.mockReset().mockResolvedValue(INCOME)
  })

  it('四个区块都渲染出关键文案', async () => {
    const wrapper = await mountWorkbench()
    const text = wrapper.text()
    expect(text).toContain('经营指标')
    expect(text).toContain('趋势')
    expect(text).toContain('服务画像')
    expect(text).toContain('收入摘要')
    // 明细标签
    expect(text).toContain('区间完成单数')
    expect(text).toContain('准时率')
    expect(text).toContain('擅长服务')
    expect(text).toContain('普通门诊')
    // 统计区间明示
    expect(text).toContain('统计区间')
    expect(text).toContain('2026-09-21')
    expect(text).toContain('2026-10-20')
  })

  it('KPI 数字直接来自接口返回值', async () => {
    const wrapper = await mountWorkbench()
    const text = wrapper.text()
    expect(text).toContain('28') // 区间完成单数 / 累计完成单数
    expect(text).toContain('42 小时') // 2520 分钟的单位换算
    expect(text).toContain('93.00%') // 区间完成率 / 累计完成率
    expect(text).toContain('92.00%') // 准时率
    expect(text).toContain('4.80') // 平均评分
    expect(text).toContain('12') // 累计服务老人数
    expect(text).toContain('3680.00') // 区间收入 / 实际收入
    expect(text).toContain('4000.00') // 订单金额
    expect(text).toContain('1200.00') // 待结算
    expect(text).toContain('2480.00') // 已结算
  })

  it('改了接口返回值，页面数字跟着变（前端没有另算一套）', async () => {
    overviewMock.mockResolvedValue({ ...OVERVIEW, periodIncome: '1111.11', completionRate: '88.00%' })
    incomeMock.mockResolvedValue({ ...INCOME, actualIncome: '2222.22' })
    const wrapper = await mountWorkbench()
    const text = wrapper.text()
    expect(text).toContain('1111.11')
    expect(text).toContain('2222.22')
    expect(text).toContain('88.00%')
  })

  it('趋势按后端给的分类渲染，空桶显示 0 而不是空白', async () => {
    const wrapper = await mountWorkbench()
    const items = wrapper.findAll('.trend__item')
    expect(items.length).toBe(2)
    expect(items[0].find('.trend__cat').text()).toBe('2026-09-21')
    expect(items[0].find('.trend__val').text()).toBe('3')
    expect(items[1].find('.trend__cat').text()).toBe('2026-09-22')
    expect(items[1].find('.trend__val').text()).toBe('0')
    expect(wrapper.text()).toContain('完成订单')
  })

  it('趋势图用后端返回的分类与序列渲染', async () => {
    // jsdom 没有布局引擎，clientWidth 恒为 0，图表不会初始化；
    // 这里给容器一个宽度，才能断言「图表拿到了后端的数据」。
    const widthSpy = vi.spyOn(Element.prototype, 'clientWidth', 'get').mockReturnValue(320)
    chartInitMock.mockClear()
    try {
      await mountWorkbench()
      expect(chartInitMock).toHaveBeenCalled()
      const chart = chartInitMock.mock.results[0].value
      const option = chart.setOption.mock.calls[0][0]
      expect(option.xAxis.data).toEqual(['2026-09-21', '2026-09-22'])
      expect(option.series[0].data).toEqual([3, 0])
      expect(option.series[0].name).toBe('完成订单')
    } finally {
      widthSpy.mockRestore()
    }
  })

  it('维度 / 粒度切换会重新向后端要数', async () => {
    const wrapper = await mountWorkbench()
    const before = trendMock.mock.calls.length
    const weekBtn = wrapper.findAll('.seg__btn').find((b) => b.text() === '周')
    expect(weekBtn).toBeTruthy()
    await weekBtn.trigger('click')
    await flushPromises()
    await nextTick()
    expect(trendMock.mock.calls.length).toBeGreaterThan(before)
    expect(trendMock.mock.calls[trendMock.mock.calls.length - 1][0]).toMatchObject({
      dimension: 'ORDER',
      granularity: 'WEEK'
    })
  })
})

describe('工作台 · 加载失败', () => {
  beforeEach(() => {
    overviewMock.mockReset().mockRejectedValue(new Error('boom'))
    trendMock.mockReset().mockRejectedValue(new Error('boom'))
    profileMock.mockReset().mockRejectedValue(new Error('boom'))
    incomeMock.mockReset().mockRejectedValue(new Error('boom'))
  })

  it('整页走错误态并给出重试入口', async () => {
    const wrapper = await mountWorkbench()
    const text = wrapper.text()
    expect(text).toContain('工作台加载失败')
    expect(text).toContain('重试')
    expect(wrapper.find('.el-button-stub').exists()).toBe(true)
  })

  it('点重试会重新请求', async () => {
    const wrapper = await mountWorkbench()
    const before = overviewMock.mock.calls.length
    await wrapper.find('.el-button-stub').trigger('click')
    await flushPromises()
    expect(overviewMock.mock.calls.length).toBeGreaterThan(before)
  })

  it('单块失败只降级该块，其余区块照常渲染', async () => {
    overviewMock.mockResolvedValue(OVERVIEW)
    trendMock.mockRejectedValue(new Error('boom'))
    profileMock.mockResolvedValue(PROFILE)
    incomeMock.mockResolvedValue(INCOME)
    const wrapper = await mountWorkbench()
    const text = wrapper.text()
    expect(text).toContain('趋势加载失败')
    expect(text).toContain('经营指标')
    expect(text).toContain('服务画像')
    expect(text).toContain('收入摘要')
    expect(text).toContain('3680.00')
  })
})

describe('工作台 · 空数据', () => {
  beforeEach(() => {
    overviewMock.mockReset().mockResolvedValue(null)
    trendMock.mockReset().mockResolvedValue(null)
    profileMock.mockReset().mockResolvedValue(null)
    incomeMock.mockReset().mockResolvedValue(null)
  })

  it('四个端点都无数据时走空态', async () => {
    const wrapper = await mountWorkbench()
    const text = wrapper.text()
    expect(text).toContain('暂无数据')
    expect(text).not.toContain('经营指标')
  })
})
