<script setup>
/**
 * 平台运营驾驶舱（V3.5 · W-11 / V3.6 数据闭环）
 *
 * 回答一个问题：「平台现在整体运行得怎么样，哪里需要管理员立刻动手」。
 *
 * 数据来源（6 个后端端点，全部 ADMIN 专属，服务端从登录态取权限）：
 *   - 平台概览    GET /api/admin/operations/overview
 *   - 异常中心    GET /api/admin/operations/exceptions
 *   - 趋势        GET /api/admin/operations/trend
 *   - 平台健康度  GET /api/admin/operations/health
 *   - 供给缺口    GET /api/admin/operations/gap      （V3.6）
 *   - 效果对比    GET /api/admin/operations/effect   （V3.6）
 *
 * 口径原则：页面上每一个数字都来自上面 6 个端点，前端只做**展示**与**单位换算**
 * （分钟 → 「N 分钟」），绝不自行汇总（与 V3.3 工作台同一原则）。
 *
 * 三态：Loading（骨架）/ Empty（空态）/ Error（可重试）。
 * 六块**各自独立**加载：任一块失败只降级该块，不会把整页拖下水
 * —— 驾驶舱最忌讳「一块挂了导致其余指标看不见」。
 *
 * 异常中心是 V3.5 的关键：**「看到了但处理不了」是最典型的假绿**。
 * 因此每张卡片的「去处理」都直接用后端下发的 `linkUrl` 跳转，
 * 前端不自己拼路由（拼了就可能指向 404）；组件单测会逐个断言这四个
 * linkUrl 真实存在于前端路由表中。
 *
 * V3.6 补两块闭环数据：**供给缺口**（哪个时段没人接单）与**效果对比**
 * （这次干预有没有用）。两块仍然各自独立三态，互不牵连。
 *
 * 合规：健康度是**六个并列客观指标**，不合成任何总分 / 指数；
 * 全页不出现医疗建议、在线支付、提现类表述。
 */
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { NlCard, NlSkeleton, NlEmpty, NlNoticeBar } from '@/components'
import {
  getOperationsOverview,
  getOperationsExceptions,
  getOperationsTrend,
  getOperationsHealth,
  getOperationsGap,
  getOperationsEffect
} from '@/api/statistics'
import { lastNDays, addDays } from '@/utils/format'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

const router = useRouter()

/* ============ 趋势维度 / 区间（切换后重新向后端要数，不在本地切分） ============ */
const DIMENSIONS = [
  { value: 'ORDER', label: '订单' },
  { value: 'COMPANION_ACTIVE', label: '陪诊员活跃' },
  { value: 'FULFILLMENT_RATE', label: '履约率' },
  { value: 'COMPLAINT', label: '投诉' },
  { value: 'REVIEW', label: '评价' },
  { value: 'MEDICATION', label: '用药任务' }
]
const RANGES = [
  { value: 'TODAY', label: '今日' },
  { value: 'LAST_7D', label: '近 7 天' },
  { value: 'LAST_30D', label: '近 30 天' },
  { value: 'CUSTOM', label: '自定义' }
]

const dimension = ref('ORDER')
const range = ref('LAST_7D')
const startDate = ref('')
const endDate = ref('')

/* ============ 四块各自的状态 ============ */
const overview = ref(null)
const overviewLoading = ref(true)
const overviewFailed = ref(false)

const exceptions = ref(null)
const exceptionsLoading = ref(true)
const exceptionsFailed = ref(false)

const trend = ref(null)
const trendLoading = ref(true)
const trendFailed = ref(false)

const health = ref(null)
const healthLoading = ref(true)
const healthFailed = ref(false)

/* ============ V3.6 · 供给缺口（独立三态） ============ */
const gap = ref(null)
const gapLoading = ref(true)
const gapFailed = ref(false)

/* ============ V3.6 · 效果对比（独立三态） ============ */
const effect = ref(null)
const effectLoading = ref(true)
const effectFailed = ref(false)

/* ============ 平台概览（八项） ============ */
const overviewMetrics = computed(() => {
  const o = overview.value
  if (!o) return []
  return [
    { key: 'today', label: '今日订单', value: String(o.todayOrderCount ?? 0), hint: '今日新增下单' },
    {
      key: 'completed',
      label: '完成订单',
      value: String(o.completedOrderCount ?? 0),
      hint: '已完成 + 已评价'
    },
    { key: 'inService', label: '服务中', value: String(o.inServiceCount ?? 0), hint: '正在陪诊' },
    { key: 'pending', label: '待接单', value: String(o.pendingCount ?? 0), hint: '等待陪诊员接单' },
    {
      key: 'fulfillment',
      label: '履约率',
      value: o.fulfillmentRate ?? '—',
      hint: '完成 ÷（完成 + 取消）'
    },
    {
      // ⚠️ 概览的 avgAcceptMinutes 是 Integer（分钟），与健康度里的同名字段（String）不同型，
      //    两处各写各的取值，不抽公共函数。
      key: 'accept',
      label: '平均接单时间',
      value: `${o.avgAcceptMinutes ?? 0} 分钟`,
      hint: '下单到接单'
    },
    { key: 'complaint', label: '投诉率', value: o.complaintRate ?? '—', hint: '投诉订单占比' },
    { key: 'score', label: '平均评分', value: o.averageScore ?? '—', hint: '有效评价均分' }
  ]
})

const overviewRange = computed(() => {
  const o = overview.value
  if (!o || !o.periodStart || !o.periodEnd) return ''
  return `${o.periodStart} ~ ${o.periodEnd}`
})

/* ============ 异常中心（四类） ============ */
const exceptionItems = computed(() => (exceptions.value && exceptions.value.items) || [])
const exceptionTotal = computed(() => (exceptions.value && exceptions.value.total) ?? 0)

/** 等级 → 色调。后端只给 DANGER / WARNING / INFO 三档，前端只做展示映射 */
const LEVEL_TONE = { DANGER: 'danger', WARNING: 'warning', INFO: 'info' }
function levelTone(level) {
  return LEVEL_TONE[level] || 'info'
}

/**
 * 「去处理」：直接跳后端下发的 linkUrl。
 * 前端不自己拼路由 —— 拼出来的地址可能根本不存在，就是典型的假绿。
 */
function goHandle(item) {
  if (!item || !item.linkUrl) return
  router.push(item.linkUrl)
}

/* ============ 趋势 ============ */
const trendCategories = computed(() => (trend.value && trend.value.categories) || [])
const trendSeries = computed(() => (trend.value && trend.value.series) || [])

/**
 * 趋势明细：分类 + 数值一一对应。
 * 后端保证缺口补 0；这里对 null / undefined 再兜一层，保证「空桶显示 0」而不是空白。
 */
const trendPoints = computed(() => {
  const cats = trendCategories.value
  const s = trendSeries.value[0]
  const data = (s && s.data) || []
  const points = []
  for (let i = 0; i < cats.length; i += 1) {
    points.push({ key: `${cats[i]}`, category: cats[i], value: data[i] ?? 0 })
  }
  return points
})

const trendRangeText = computed(() => {
  const t = trend.value
  if (!t || !t.periodStart || !t.periodEnd) return ''
  return `${t.periodStart} ~ ${t.periodEnd}`
})

/** 请求参数：只有 CUSTOM 才带日期，其余区间由后端按 range 解析 */
function trendParams() {
  const params = { dimension: dimension.value, range: range.value }
  if (range.value === 'CUSTOM') {
    params.startDate = startDate.value
    params.endDate = endDate.value
  }
  return params
}

/** 自定义区间的两个日期都齐了才能发请求 */
const customReady = computed(() => Boolean(startDate.value && endDate.value))

/* ============ 平台健康度（六项并列，不合成总分） ============ */
const healthMetrics = computed(() => {
  const h = health.value
  if (!h) return []
  return [
    { key: 'fulfillment', label: '履约率', value: h.fulfillmentRate ?? '—', hint: '最终履约比例' },
    {
      // ⚠️ 健康度的 avgAcceptMinutes 是 String（与概览的 Integer 同名不同型），单独处理。
      key: 'accept',
      label: '接单速度',
      value: h.avgAcceptMinutes ? `${h.avgAcceptMinutes} 分钟` : '—',
      hint: '平均接单时长'
    },
    {
      key: 'completion',
      label: '完成率（按时）',
      value: h.completionRate ?? '—',
      hint: '按时完成 ÷ 已完成'
    },
    { key: 'complaint', label: '投诉率', value: h.complaintRate ?? '—', hint: '回溯期投诉占比' },
    { key: 'score', label: '平均评分', value: h.averageScore ?? '—', hint: '有效评价均分' },
    {
      key: 'companion',
      label: '活跃陪诊员数',
      value: String(h.activeCompanionCount ?? 0),
      hint: '区间内有接单'
    }
  ]
})

/* ============ V3.6 · 供给时段缺口（7 天 × 24 小时 = 168 桶） ============ */
const GAP_RANGES = [
  { value: 'LAST_7D', label: '近 7 天' },
  { value: 'LAST_30D', label: '近 30 天' },
  { value: 'CUSTOM', label: '自定义' }
]

/**
 * 桶的粒度固定为「周内第几天 × 小时」，**与查询区间的长度无关** ——
 * 只有这样「这一周和上一周的同一时段」才可比（迭代计划 §47 的闭环数据基础）。
 * 7 天 × 24 小时 = 168 个桶，空桶也占位。
 */
const GAP_WEEKDAYS = [1, 2, 3, 4, 5, 6, 7]
const GAP_HOURS = Array.from({ length: 24 }, (_, i) => i)
/** 后端 weekday：1 = 周一 … 7 = 周日 */
const WEEKDAY_LABELS = {
  1: '周一',
  2: '周二',
  3: '周三',
  4: '周四',
  5: '周五',
  6: '周六',
  7: '周日'
}

const gapRange = ref('LAST_30D')
const gapStartDate = ref('')
const gapEndDate = ref('')
/** 当前选中的桶（明细行）；为空时默认落在缺口最大的桶上 */
const gapSelectedKey = ref('')

/** 请求参数：只有 CUSTOM 才带日期，其余区间由后端按 range 解析 */
function gapParams() {
  const params = { range: gapRange.value }
  if (gapRange.value === 'CUSTOM') {
    params.startDate = gapStartDate.value
    params.endDate = gapEndDate.value
  }
  return params
}

/** 自定义区间的两个日期都齐了才能发请求 */
const gapCustomReady = computed(() => Boolean(gapStartDate.value && gapEndDate.value))

/**
 * 缺口矩阵：按 7 × 24 铺满，后端没给的桶补 0。
 *
 * ⚠️ 这一步不是「前端重新分桶」，只是**把后端给的桶摆进固定网格**：
 * 缺口数（pendingUnaccepted / availableCompanions / gap）一个都不在前端算。
 * 补 0 是为了「空桶显示 0 而不是空白」—— 空白格子会被读成「没数据」，
 * 而实际上这个时段确实没有缺口。
 */
const gapRows = computed(() => {
  const map = new Map()
  const items = (gap.value && gap.value.items) || []
  for (const it of items) {
    const weekday = Number(it.weekday)
    const hour = Number(it.hour)
    if (!GAP_WEEKDAYS.includes(weekday) || !GAP_HOURS.includes(hour)) continue
    map.set(`${weekday}-${hour}`, {
      pendingUnaccepted: Number(it.pendingUnaccepted ?? 0),
      availableCompanions: Number(it.availableCompanions ?? 0),
      gap: Number(it.gap ?? 0)
    })
  }
  return GAP_WEEKDAYS.map((weekday) => ({
    weekday,
    weekdayLabel: WEEKDAY_LABELS[weekday],
    cells: GAP_HOURS.map((hour) => {
      const found = map.get(`${weekday}-${hour}`)
      return {
        key: `${weekday}-${hour}`,
        weekday,
        hour,
        weekdayLabel: WEEKDAY_LABELS[weekday],
        pendingUnaccepted: found ? found.pendingUnaccepted : 0,
        availableCompanions: found ? found.availableCompanions : 0,
        gap: found ? found.gap : 0
      }
    })
  }))
})

/** 全表最大的缺口值（色阶的相对基准） */
const gapMax = computed(() => {
  let max = 0
  for (const row of gapRows.value) {
    for (const cell of row.cells) {
      if (cell.gap > max) max = cell.gap
    }
  }
  return max
})

/**
 * 色阶：gap ≤ 0 走中性色（供给没缺口，甚至富余）；
 * gap > 0 按「相对最大值」分 4 档，越深缺口越大。
 */
function gapLevel(cell) {
  if (cell.gap <= 0) return 0
  const max = gapMax.value
  if (max <= 1) return 1
  const ratio = cell.gap / max
  if (ratio <= 0.25) return 1
  if (ratio <= 0.5) return 2
  if (ratio <= 0.75) return 3
  return 4
}

/** 明细文案：「周一 14:00 · 待接 3 单 / 可服务 1 人 / 缺口 2」 */
function gapDetail(cell) {
  if (!cell) return ''
  const hh = String(cell.hour).padStart(2, '0')
  return `${cell.weekdayLabel} ${hh}:00 · 待接 ${cell.pendingUnaccepted} 单 / 可服务 ${cell.availableCompanions} 人 / 缺口 ${cell.gap}`
}

/** 当前明细桶：优先用户点选的那个，否则默认落在缺口最大的桶上 */
const gapSelected = computed(() => {
  const rows = gapRows.value
  const key = gapSelectedKey.value
  for (const row of rows) {
    for (const cell of row.cells) {
      if (cell.key === key) return cell
    }
  }
  let best = null
  for (const row of rows) {
    for (const cell of row.cells) {
      if (!best || cell.gap > best.gap) best = cell
    }
  }
  return best
})

/**
 * 没有可解析可服务时段的陪诊员数 —— **如实披露的数据质量**。
 * 供给数算得偏低时，管理员需要知道这是「真的没人」还是「没人登记时段」。
 */
const gapWithoutSlots = computed(() => Number((gap.value && gap.value.companionsWithoutSlots) ?? 0))

const gapRangeText = computed(() => {
  const g = gap.value
  if (!g || !g.periodStart || !g.periodEnd) return ''
  return `${g.periodStart} ~ ${g.periodEnd}`
})

/* ============ V3.6 · 干预效果对比（两期同口径） ============ */

/**
 * 可选指标 —— **只列后端已注册的 key**。
 *
 * ⚠️ 未注册的 key 会被后端 400 拒掉（不许前端「猜一个算法算出来」），
 *    所以这个清单就是「能选的范围」本身。
 * 清单来源：后端 `service/support/metrics/` 下各 `MetricCalculator#metricKey()`
 * （= `MetricCalculatorRegistry` 的注册内容，13 个计算器）；
 * 名称取自指标字典（backend/src/main/resources/metrics/metric-dictionary.yml），
 * 与后端返回的 metricName 同源，不另写一份名表。
 *
 * ⚠️ 注册表另有 2 个**别名**（operations.companion_count /
 *    companion.workbench.completion_rate），它们与上面的某一项**指向同一个计算器**，
 *    列进下拉会出现「两个名字、同一组数」的重复项，因此不列。
 * ⚠️ 字典里有但**没有计算器**的指标不在此列 —— 它没注册进注册表，
 *    点了会被后端 400 拒掉。
 */
const EFFECT_METRICS = [
  { value: 'operations.fulfillment_rate', label: '履约率' },
  { value: 'operations.completion_rate', label: '完成率（按时）' },
  { value: 'operations.complaint_rate', label: '投诉率' },
  { value: 'operations.average_score', label: '平均评分' },
  { value: 'operations.avg_accept_minutes', label: '平均接单时间' },
  { value: 'operations.active_companion_count', label: '活跃陪诊员数' },
  { value: 'operations.completed_order_count', label: '区间完成订单数' },
  { value: 'operations.timeout_rate', label: '超时率' },
  { value: 'operations.manual_intervention_rate', label: '人工介入率' },
  { value: 'operations.reassign_rate', label: '改派率' },
  { value: 'operations.service_duration_minutes', label: '平均服务时长（分钟）' },
  { value: 'operations.medication_task_count', label: '区间用药任务数' },
  { value: 'operations.medication_missed_rate', label: '漏服率' }
]

const EFFECT_RANGE_MODES = [
  { value: 'DEFAULT', label: '默认区间' },
  { value: 'CUSTOM', label: '自定义' }
]

/** 默认后一期 = 最近 15 天；前一期由后端按「等长且紧邻其前」自动推导 */
const EFFECT_DEFAULT_DAYS = 15

const effectMetric = ref(EFFECT_METRICS[0].value)
const effectRangeMode = ref('DEFAULT')
const effectFrom = ref('')
const effectTo = ref('')
const effectCompareFrom = ref('')
const effectCompareTo = ref('')

/**
 * 预填两期日期。
 *
 * ⚠️ 默认模式下**请求里不带** compareFrom / compareTo ——
 *    缺省推导由后端做（ADR-0017 决策 4），前端不自己算一遍区间再发过去，
 *    免得两边的「等长且紧邻其前」写法漂移。
 *    这里算出来只是给「自定义」模式做表单初值。
 */
function initEffectRange() {
  const preset = lastNDays(EFFECT_DEFAULT_DAYS)
  effectFrom.value = preset.startDate
  effectTo.value = preset.endDate
  const prevEnd = addDays(preset.startDate, -1)
  effectCompareFrom.value = addDays(prevEnd, -(EFFECT_DEFAULT_DAYS - 1))
  effectCompareTo.value = prevEnd
}

/** 请求参数：自定义模式才带前一期区间 */
function effectParams() {
  const params = {
    metric: effectMetric.value,
    from: effectFrom.value,
    to: effectTo.value
  }
  if (effectRangeMode.value === 'CUSTOM') {
    params.compareFrom = effectCompareFrom.value
    params.compareTo = effectCompareTo.value
  }
  return params
}

/** 四个日期都齐了、且起止不颠倒才能发请求 */
const effectCustomReady = computed(() => {
  const { compareFrom: cf, compareTo: ct, from: f, to: t } = {
    compareFrom: effectCompareFrom.value,
    compareTo: effectCompareTo.value,
    from: effectFrom.value,
    to: effectTo.value
  }
  if (!cf || !ct || !f || !t) return false
  if (cf > ct || f > t) return false
  return true
})

const effectCurrent = computed(() => (effect.value && effect.value.current) || {})
const effectPrevious = computed(() => (effect.value && effect.value.previous) || {})

/** 两期都没有值 → 空态（不是白屏，也不是报错） */
const effectEmpty = computed(() => {
  const e = effect.value
  if (!e) return true
  return !e.current?.value && !e.previous?.value
})

/* ============ 数据加载（六块独立，互不牵连） ============ */
async function loadOverview() {
  overviewLoading.value = true
  try {
    overview.value = (await getOperationsOverview()) ?? null
    overviewFailed.value = false
  } catch {
    overview.value = null
    overviewFailed.value = true
  } finally {
    overviewLoading.value = false
  }
}

async function loadExceptions() {
  exceptionsLoading.value = true
  try {
    exceptions.value = (await getOperationsExceptions()) ?? null
    exceptionsFailed.value = false
  } catch {
    exceptions.value = null
    exceptionsFailed.value = true
  } finally {
    exceptionsLoading.value = false
  }
}

/** 趋势单独一个入口：切换维度 / 区间时只重拉趋势 */
let trendSeq = 0
async function loadTrend() {
  const seq = ++trendSeq
  trendLoading.value = true
  try {
    const data = await getOperationsTrend(trendParams())
    if (seq !== trendSeq) return // 已有更新的请求，丢弃这次过期响应
    trend.value = data ?? null
    trendFailed.value = false
  } catch {
    if (seq !== trendSeq) return
    trend.value = null
    trendFailed.value = true
  } finally {
    if (seq === trendSeq) trendLoading.value = false
  }
  await nextTick()
  renderTrendChart()
}

async function loadHealth() {
  healthLoading.value = true
  try {
    health.value = (await getOperationsHealth()) ?? null
    healthFailed.value = false
  } catch {
    health.value = null
    healthFailed.value = true
  } finally {
    healthLoading.value = false
  }
}

/** 供给缺口单独一个入口：切换区间时只重拉缺口 */
async function loadGap() {
  gapLoading.value = true
  try {
    gap.value = (await getOperationsGap(gapParams())) ?? null
    gapFailed.value = false
  } catch {
    gap.value = null
    gapFailed.value = true
  } finally {
    gapLoading.value = false
  }
}

/** 效果对比单独一个入口：换指标 / 换区间时只重拉效果对比 */
async function loadEffect() {
  effectLoading.value = true
  try {
    effect.value = (await getOperationsEffect(effectParams())) ?? null
    effectFailed.value = false
  } catch {
    effect.value = null
    effectFailed.value = true
  } finally {
    effectLoading.value = false
  }
}

async function loadAll() {
  await Promise.allSettled([
    loadOverview(),
    loadExceptions(),
    loadTrend(),
    loadHealth(),
    loadGap(),
    loadEffect()
  ])
}

/* ============ ECharts（写法对齐 companion/workbench.vue） ============ */
const trendChartRef = ref(null)
let trendChart = null

/**
 * 图表取色：canvas 画布不解析 CSS 变量，这里从元素上读取 --nl-* 令牌的计算值。
 * JS 里不另写一套色值，与 styles/index.scss 的设计令牌保持单一真源。
 */
function chartToken(el, name) {
  const style = window.getComputedStyle(el)
  const value = style.getPropertyValue(name).trim()
  return value || style.color
}

function renderTrendChart() {
  const el = trendChartRef.value
  // 容器还没完成布局时量不到宽高，此时强行初始化只会得到 0 尺寸画布
  if (!el || !el.clientWidth) return
  trendChart = trendChart?.dispose() || echarts.init(el)

  const palette = [
    chartToken(el, '--nl-primary'),
    chartToken(el, '--nl-success'),
    chartToken(el, '--nl-warning')
  ]
  const axisLine = chartToken(el, '--nl-border')
  const axisLabel = chartToken(el, '--nl-text-3')
  const splitLine = chartToken(el, '--nl-divider')

  const series = []
  for (let i = 0; i < trendSeries.value.length; i += 1) {
    const s = trendSeries.value[i]
    const raw = s.data || []
    const data = []
    for (let j = 0; j < raw.length; j += 1) {
      const n = Number(raw[j])
      data.push(Number.isFinite(n) ? n : 0)
    }
    const color = palette[i % palette.length]
    series.push({
      name: s.name || '',
      type: 'line',
      data,
      smooth: true,
      symbol: 'circle',
      symbolSize: 5,
      itemStyle: { color },
      lineStyle: { width: 2, color }
    })
  }

  trendChart.setOption(
    {
      grid: { top: 24, left: 40, right: 12, bottom: 24 },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'category',
        data: trendCategories.value,
        boundaryGap: false,
        axisLine: { lineStyle: { color: axisLine } },
        axisLabel: { color: axisLabel, fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        axisLine: { show: false },
        axisLabel: { color: axisLabel, fontSize: 11 },
        splitLine: { lineStyle: { color: splitLine } }
      },
      series
    },
    true
  )
}

function handleResize() {
  trendChart?.resize()
}

/* ============ 交互 ============ */
watch(dimension, loadTrend)

watch(range, (v) => {
  // 进入自定义区间时给一对默认日期（近 30 天），避免「切了就报错」
  if (v === 'CUSTOM' && (!startDate.value || !endDate.value)) {
    const preset = lastNDays(30)
    startDate.value = preset.startDate
    endDate.value = preset.endDate
  }
  loadTrend()
})

/** 自定义区间的日期被改动 → 重新要数（两个日期都齐了才发） */
function onCustomDateChange() {
  if (customReady.value) loadTrend()
}

/* ---------- V3.6 的两处交互 ---------- */

watch(gapRange, (v) => {
  // 进入自定义区间时给一对默认日期（近 30 天），避免「切了就报错」
  if (v === 'CUSTOM' && (!gapStartDate.value || !gapEndDate.value)) {
    const preset = lastNDays(30)
    gapStartDate.value = preset.startDate
    gapEndDate.value = preset.endDate
  }
  loadGap()
})

/** 缺口自定义区间的日期被改动 → 重新要数 */
function onGapCustomDateChange() {
  if (gapCustomReady.value) loadGap()
}

/** 换指标 → 重新向后端要数（不在前端对已有数据换算） */
watch(effectMetric, loadEffect)

watch(effectRangeMode, (v) => {
  if (v === 'CUSTOM' && (!effectCompareFrom.value || !effectCompareTo.value)) {
    initEffectRange()
  }
  loadEffect()
})

/** 效果对比自定义区间的日期被改动 → 重新要数（四个日期都齐了才发） */
function onEffectCustomDateChange() {
  if (effectCustomReady.value) loadEffect()
}

onMounted(() => {
  initEffectRange()
  loadAll()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  trendChart = null
})
</script>

<template>
  <div class="ops">
    <NlNoticeBar tone="primary">
      全部指标由后端统一计算，页面只做展示；健康度为六个并列指标，不做合成分。
    </NlNoticeBar>

    <!-- ==================== 平台概览 ==================== -->
    <NlCard title="平台概览" class="ops__overview">
      <template #extra>
        <span v-if="overviewRange" class="nl-caption nl-text-muted is-num">
          统计区间 {{ overviewRange }}
        </span>
      </template>

      <NlSkeleton v-if="overviewLoading" :count="2" />

      <NlEmpty
        v-else-if="overviewFailed"
        type="network"
        title="平台概览加载失败"
        action-text="重试"
        @action="loadOverview"
      />

      <div v-else-if="overview" class="kpi-grid">
        <div v-for="m in overviewMetrics" :key="m.key" class="kpi">
          <div class="kpi__label">{{ m.label }}</div>
          <div class="kpi__val is-num">{{ m.value }}</div>
          <div class="kpi__hint">{{ m.hint }}</div>
        </div>
      </div>

      <NlEmpty
        v-else
        type="empty"
        title="暂无概览数据"
        description="统计区间内还没有可统计的数据"
      />
    </NlCard>

    <!-- ==================== 异常中心 ==================== -->
    <NlCard title="异常中心" class="ops__exceptions">
      <template #extra>
        <span class="nl-caption nl-text-muted is-num">共 {{ exceptionTotal }} 项待处理</span>
      </template>

      <NlSkeleton v-if="exceptionsLoading" :count="2" />

      <NlEmpty
        v-else-if="exceptionsFailed"
        type="network"
        title="异常中心加载失败"
        action-text="重试"
        @action="loadExceptions"
      />

      <div v-else-if="exceptionItems.length" class="exc-grid">
        <article
          v-for="item in exceptionItems"
          :key="item.type"
          class="exc"
          :class="`is-${levelTone(item.level)}`"
          :data-type="item.type"
        >
          <div class="exc__head">
            <span class="exc__label">{{ item.typeLabel }}</span>
            <span class="exc__count is-num">{{ item.count }}</span>
          </div>
          <p class="exc__desc">{{ item.description }}</p>
          <el-button
            type="primary"
            size="small"
            class="exc__go"
            :data-link="item.linkUrl"
            @click="goHandle(item)"
          >
            去处理
          </el-button>
        </article>
      </div>

      <NlEmpty
        v-else
        type="empty"
        title="暂无待处理异常"
        description="四类异常当前都没有命中"
      />
    </NlCard>

    <!-- ==================== 趋势 ==================== -->
    <NlCard title="趋势" class="ops__trend">
      <template #extra>
        <span v-if="trendRangeText" class="nl-caption nl-text-muted is-num">
          {{ trendRangeText }}
        </span>
      </template>

      <div class="trend__ctrls">
        <div class="seg" role="group" aria-label="趋势维度">
          <button
            v-for="d in DIMENSIONS"
            :key="d.value"
            type="button"
            class="seg__btn"
            :class="{ 'is-active': dimension === d.value }"
            :aria-pressed="dimension === d.value"
            @click="dimension = d.value"
          >
            {{ d.label }}
          </button>
        </div>
        <div class="seg" role="group" aria-label="统计区间">
          <button
            v-for="r in RANGES"
            :key="r.value"
            type="button"
            class="seg__btn"
            :class="{ 'is-active': range === r.value }"
            :aria-pressed="range === r.value"
            @click="range = r.value"
          >
            {{ r.label }}
          </button>
        </div>
      </div>

      <div v-if="range === 'CUSTOM'" class="custom">
        <el-date-picker
          v-model="startDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="开始日期"
          class="custom__picker"
          @change="onCustomDateChange"
        />
        <el-date-picker
          v-model="endDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="结束日期"
          class="custom__picker"
          @change="onCustomDateChange"
        />
        <el-button type="primary" size="small" :disabled="!customReady" @click="loadTrend">
          查询
        </el-button>
        <span v-if="!customReady" class="nl-caption nl-text-weak">请选择起止日期</span>
      </div>

      <NlSkeleton v-if="trendLoading" :count="2" />

      <NlEmpty
        v-else-if="trendFailed"
        type="network"
        title="趋势加载失败"
        action-text="重试"
        @action="loadTrend"
      />

      <template v-else-if="trend && trendCategories.length">
        <div ref="trendChartRef" class="chart" />
        <ul class="trend__list">
          <li v-for="p in trendPoints" :key="p.key" class="trend__item">
            <span class="trend__cat">{{ p.category }}</span>
            <span class="trend__val is-num">{{ p.value }}</span>
          </li>
        </ul>
      </template>

      <NlEmpty
        v-else
        type="empty"
        title="暂无趋势数据"
        description="所选维度与区间内没有可统计的数据"
      />
    </NlCard>

    <!-- ==================== 平台健康度 ==================== -->
    <NlCard title="平台健康度" class="ops__health">
      <template #extra>
        <span class="nl-caption nl-text-muted">六个并列指标</span>
      </template>

      <NlSkeleton v-if="healthLoading" :count="2" />

      <NlEmpty
        v-else-if="healthFailed"
        type="network"
        title="平台健康度加载失败"
        action-text="重试"
        @action="loadHealth"
      />

      <div v-else-if="health" class="kpi-grid">
        <div v-for="m in healthMetrics" :key="m.key" class="kpi">
          <div class="kpi__label">{{ m.label }}</div>
          <div class="kpi__val is-num">{{ m.value }}</div>
          <div class="kpi__hint">{{ m.hint }}</div>
        </div>
      </div>

      <NlEmpty
        v-else
        type="empty"
        title="暂无健康度数据"
        description="统计区间内还没有可统计的数据"
      />
    </NlCard>

    <!-- ==================== 供给缺口（V3.6） ==================== -->
    <NlCard title="供给缺口" class="ops__gap">
      <template #extra>
        <span v-if="gapRangeText" class="nl-caption nl-text-muted is-num">
          统计区间 {{ gapRangeText }}
        </span>
      </template>

      <div class="gap__ctrls">
        <div class="seg" role="group" aria-label="缺口统计区间">
          <button
            v-for="r in GAP_RANGES"
            :key="r.value"
            type="button"
            class="seg__btn"
            :class="{ 'is-active': gapRange === r.value }"
            :aria-pressed="gapRange === r.value"
            @click="gapRange = r.value"
          >
            {{ r.label }}
          </button>
        </div>
      </div>

      <div v-if="gapRange === 'CUSTOM'" class="custom">
        <el-date-picker
          v-model="gapStartDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="开始日期"
          class="custom__picker"
          @change="onGapCustomDateChange"
        />
        <el-date-picker
          v-model="gapEndDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="结束日期"
          class="custom__picker"
          @change="onGapCustomDateChange"
        />
        <el-button type="primary" size="small" :disabled="!gapCustomReady" @click="loadGap">
          查询
        </el-button>
        <span v-if="!gapCustomReady" class="nl-caption nl-text-weak">请选择起止日期</span>
      </div>

      <NlSkeleton v-if="gapLoading" :count="2" />

      <NlEmpty
        v-else-if="gapFailed"
        type="network"
        title="供给缺口加载失败"
        action-text="重试"
        @action="loadGap"
      />

      <template v-else-if="gap">
        <NlNoticeBar v-if="gapWithoutSlots > 0" tone="warning">
          有 {{ gapWithoutSlots }} 名陪诊员没有可解析的可服务时段，未参与供给统计
        </NlNoticeBar>

        <p class="gap__hint nl-caption nl-text-muted">
          桶的粒度固定为「周内第几天 × 小时」，共 168 个桶，空桶显示 0；颜色越深表示缺口越大。
        </p>

        <div class="gap__scroll">
          <div class="gap__grid">
            <div class="gap__corner" />
            <div v-for="h in GAP_HOURS" :key="`h-${h}`" class="gap__hour is-num">{{ h }}</div>
            <template v-for="row in gapRows" :key="row.weekday">
              <div class="gap__day">{{ row.weekdayLabel }}</div>
              <button
                v-for="cell in row.cells"
                :key="cell.key"
                type="button"
                class="gap__cell is-num"
                :class="[
                  `is-lv${gapLevel(cell)}`,
                  { 'is-active': gapSelected && gapSelected.key === cell.key }
                ]"
                :data-gap="cell.gap"
                :data-cell="cell.key"
                :title="gapDetail(cell)"
                @click="gapSelectedKey = cell.key"
              >
                {{ cell.gap }}
              </button>
            </template>
          </div>
        </div>

        <p class="gap__detail nl-caption">
          <span class="gap__detail-label">明细</span>
          <span v-if="gapSelected" class="is-num">{{ gapDetail(gapSelected) }}</span>
          <span v-else>暂无明细</span>
        </p>
      </template>

      <NlEmpty
        v-else
        type="empty"
        title="暂无缺口数据"
        description="所选区间内没有可统计的供给缺口数据"
      />
    </NlCard>

    <!-- ==================== 效果对比（V3.6） ==================== -->
    <NlCard title="效果对比" class="ops__effect">
      <template #extra>
        <span class="nl-caption nl-text-muted">两期同口径对比</span>
      </template>

      <div class="eff__ctrls">
        <el-select v-model="effectMetric" class="eff__select" placeholder="选择指标">
          <el-option
            v-for="m in EFFECT_METRICS"
            :key="m.value"
            :label="m.label"
            :value="m.value"
          />
        </el-select>

        <div class="seg" role="group" aria-label="对比区间">
          <button
            v-for="m in EFFECT_RANGE_MODES"
            :key="m.value"
            type="button"
            class="seg__btn"
            :class="{ 'is-active': effectRangeMode === m.value }"
            :aria-pressed="effectRangeMode === m.value"
            @click="effectRangeMode = m.value"
          >
            {{ m.label }}
          </button>
        </div>
      </div>

      <div v-if="effectRangeMode === 'CUSTOM'" class="custom">
        <el-date-picker
          v-model="effectCompareFrom"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="前一期开始"
          class="custom__picker"
          @change="onEffectCustomDateChange"
        />
        <el-date-picker
          v-model="effectCompareTo"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="前一期结束"
          class="custom__picker"
          @change="onEffectCustomDateChange"
        />
        <el-date-picker
          v-model="effectFrom"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="后一期开始"
          class="custom__picker"
          @change="onEffectCustomDateChange"
        />
        <el-date-picker
          v-model="effectTo"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="后一期结束"
          class="custom__picker"
          @change="onEffectCustomDateChange"
        />
        <el-button type="primary" size="small" :disabled="!effectCustomReady" @click="loadEffect">
          查询
        </el-button>
        <span v-if="!effectCustomReady" class="nl-caption nl-text-weak">请选择两期的起止日期</span>
      </div>

      <NlSkeleton v-if="effectLoading" :count="2" />

      <NlEmpty
        v-else-if="effectFailed"
        type="network"
        title="效果对比加载失败"
        action-text="重试"
        @action="loadEffect"
      />

      <template v-else-if="effect && !effectEmpty">
        <div class="eff__metric">{{ effect.metricName || effect.metric }}</div>
        <div class="eff__grid">
          <div class="eff__cell">
            <div class="eff__cap">前一期</div>
            <div class="eff__range is-num">
              {{ effectPrevious.from || '—' }} ~ {{ effectPrevious.to || '—' }}
            </div>
            <div class="eff__val is-num">{{ effectPrevious.value || '—' }}</div>
          </div>
          <div class="eff__cell">
            <div class="eff__cap">后一期</div>
            <div class="eff__range is-num">
              {{ effectCurrent.from || '—' }} ~ {{ effectCurrent.to || '—' }}
            </div>
            <div class="eff__val is-num">{{ effectCurrent.value || '—' }}</div>
          </div>
          <div class="eff__cell eff__cell--delta">
            <div class="eff__cap">变化</div>
            <div class="eff__val is-num">{{ effect.delta || '—' }}</div>
          </div>
        </div>
      </template>

      <NlEmpty
        v-else
        type="empty"
        title="暂无对比数据"
        description="所选指标与区间内没有可统计的数据"
      />
    </NlCard>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.ops {
  display: flex;
  flex-direction: column;
  gap: var(--nl-gap-section);
}

/* ============ 指标卡 ============ */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--nl-space-3);
}

.kpi {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &__label {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }

  &__val {
    margin-top: 6px;
    font-size: var(--nl-font-h2);
    font-weight: 700;
    color: var(--nl-text-1);
  }

  &__hint {
    margin-top: 4px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
  }
}

/* ============ 异常卡片 ============ */
.exc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: var(--nl-space-3);
}

.exc {
  --tone: var(--nl-text-2);
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-2);
  padding: var(--nl-space-4);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-left: 3px solid var(--tone);
  border-radius: var(--nl-radius-card);

  &.is-danger {
    --tone: var(--nl-danger-text);
  }

  &.is-warning {
    --tone: var(--nl-warning-text);
  }

  &.is-info {
    --tone: var(--nl-info-text);
  }

  &__head {
    display: flex;
    gap: var(--nl-space-2);
    align-items: baseline;
    justify-content: space-between;
  }

  &__label {
    font-size: var(--nl-font-body);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__count {
    font-size: var(--nl-font-h1);
    font-weight: 700;
    color: var(--tone);
  }

  &__desc {
    margin: 0;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }

  &__go {
    align-self: flex-start;
  }
}

/* ============ 趋势控件 ============ */
.trend__ctrls {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-3);
}

.seg {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 3px;
  background: var(--nl-bg-sunken);
  border-radius: var(--nl-radius-chip);

  &__btn {
    padding: 6px 12px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: transparent;
    border: none;
    border-radius: var(--nl-radius-chip);
    cursor: pointer;

    &.is-active {
      font-weight: 600;
      color: var(--nl-primary-text);
      background: var(--nl-bg-card);
    }
  }
}

.custom {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-2);
  align-items: center;
  margin-bottom: var(--nl-space-3);

  &__picker {
    width: 160px;
  }
}

.chart {
  width: 100%;
  height: 260px;
}

.trend__list {
  padding: 0;
  margin: var(--nl-space-3) 0 0;
  list-style: none;
}

.trend__item {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  font-size: var(--nl-font-caption);
  color: var(--nl-text-2);
  border-bottom: 1px dashed var(--nl-divider);

  &:last-child {
    border-bottom: none;
  }

  &__val {
    font-weight: 600;
    color: var(--nl-text-1);
  }
}

/* ============ 供给缺口热力网格 ============ */
.gap__ctrls {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-3);
}

.gap__hint {
  margin-bottom: var(--nl-space-2);
}

.gap__scroll {
  // 24 列在 375px 宽下装不下 —— 横向滚动容器承接，
  // 容器自身 max-width: 100% 保证不把整页撑出横向溢出。
  max-width: 100%;
  overflow-x: auto;
  overscroll-behavior-x: contain;
  padding-bottom: var(--nl-space-2);
}

.gap__grid {
  // 25 列 = 1 列星期标签 + 24 列小时；width: max-content 让滚动容器自己滚
  // ⚠️ 这两个尺寸是**布局几何**（格子多大），不是颜色 / 字号；
  //    颜色走 --nl-* 令牌，字号走 --nl-font-caption。
  --gap-label-w: 40px;
  --gap-cell-w: 28px;
  --gap-cell-h: 24px;

  display: grid;
  grid-template-columns: var(--gap-label-w) repeat(24, var(--gap-cell-w));
  width: max-content;
  gap: 2px;
}

.gap__corner,
.gap__hour,
.gap__day {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--nl-font-caption);
  color: var(--nl-text-2);
}

.gap__hour {
  height: var(--gap-cell-h);
}

.gap__day {
  justify-content: flex-start;
  height: var(--gap-cell-h);
  padding-right: var(--nl-space-2);
  white-space: nowrap;
}

.gap__cell {
  display: flex;
  align-items: center;
  justify-content: center;
  height: var(--gap-cell-h);
  padding: 0;
  font-size: var(--nl-font-caption);
  font-variant-numeric: tabular-nums;
  color: var(--nl-text-1);
  background: var(--nl-bg-sunken);
  border: 1px solid transparent;
  border-radius: var(--nl-radius-chip);
  cursor: pointer;

  // gap ≤ 0：中性色（供给没缺口，甚至富余）
  &.is-lv0 {
    color: var(--nl-text-2);
  }

  // 缺口越大底色越深。色值全部来自 --nl-* 令牌，不硬编码；
  // color-mix 前一行是降级底色（老浏览器不支持时退回浅红，仍能读出数值）。
  &.is-lv1 {
    background: var(--nl-danger-bg);
  }

  &.is-lv2 {
    background: var(--nl-danger-bg);
    background: color-mix(in srgb, var(--nl-danger) 22%, var(--nl-bg-card));
  }

  &.is-lv3 {
    background: var(--nl-danger-bg);
    background: color-mix(in srgb, var(--nl-danger) 40%, var(--nl-bg-card));
  }

  &.is-lv4 {
    background: var(--nl-danger-bg);
    background: color-mix(in srgb, var(--nl-danger) 60%, var(--nl-bg-card));
  }

  &.is-active {
    border-color: var(--nl-primary-text);
  }
}

.gap__detail {
  margin-top: var(--nl-space-3);
}

.gap__detail-label {
  margin-right: var(--nl-space-2);
  color: var(--nl-text-2);
}

/* ============ 效果对比 ============ */
.eff__ctrls {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-3);
  align-items: center;
  margin-bottom: var(--nl-space-3);
}

.eff__select {
  width: 180px;
}

.eff__metric {
  margin-bottom: var(--nl-space-2);
  font-size: var(--nl-font-h3);
  font-weight: 600;
  color: var(--nl-text-1);
}

.eff__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--nl-space-3);
}

.eff__cell {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &--delta {
    border-left: 3px solid var(--nl-primary-text);
  }

  &__cap {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }

  &__range {
    margin-top: 4px;
    font-size: var(--nl-font-micro);
    color: var(--nl-text-2);
  }

  &__val {
    margin-top: 6px;
    font-size: var(--nl-font-h2);
    font-weight: 700;
    color: var(--nl-text-1);
  }
}
</style>
