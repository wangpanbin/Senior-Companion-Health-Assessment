<script setup>
/**
 * 陪诊员工作台（V3.3 · M-32）
 *
 * 回答一个问题：「我这段时间做得怎么样、赚了多少」。
 *
 * 数据来源（4 个后端端点，全部 COMPANION 自助，服务端从登录态取本人）：
 *   - 经营指标（区间）  GET /statistics/companion/me
 *   - 趋势              GET /statistics/companion/me/trend
 *   - 服务画像（累计）  GET /statistics/companion/me/profile
 *   - 收入摘要（区间）  GET /statistics/companion/me/income
 *
 * ⚠️ 口径原则：页面上的每一个经营数字都来自上面 4 个端点，
 *    前端只做「展示」与「单位换算」（分钟 → 小时、金额加 ¥），
 *    绝不自行汇总订单数据（roadmap 决策 G8「前端统计零自行计算」）。
 *
 * 三态：Loading（骨架）/ Empty（空态）/ Error（可重试）。
 * 四块并行拉取，单块失败只降级该块，不会让整页白屏。
 *
 * 合规：一期为线上记账 + 线下结算，页面不出现「在线支付 / 提现」字样；
 * 本页不涉及任何医疗建议，不出现诊断类表述。
 */
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { NlPageShell, NlAppTabBar, NlCard, NlSkeleton, NlEmpty, NlNoticeBar } from '@/components'
import {
  getCompanionWorkbenchOverview,
  getCompanionWorkbenchTrend,
  getCompanionWorkbenchProfile,
  getCompanionWorkbenchIncome
} from '@/api/statistics'
import { formatMoney } from '@/utils/format'

echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

/* ============ 快捷入口（P1-7） ============ */
/**
 * 「站内信」与「收到的评价」此前**全仓没有任何一处 router.push 指向它们** ——
 * 路由和页面都写好了，入口没接上：陪诊员收不到系统站内信、看不到收到的评价，
 * 连会话列表 /chat 都进不去（只能从某个订单页单点进某个会话）。
 *
 * 入口放在工作台：工作台本就是聚合页，且设计稿只给了陪诊员 4 个底部 Tab，
 * 不往 Tab 里硬塞第 5 个。
 */
const router = useRouter()

const SHORTCUTS = [
  {
    key: 'message',
    label: '消息中心',
    desc: '系统通知、审核结果与漏服提醒',
    to: '/companion/message'
  },
  {
    key: 'reviews',
    label: '收到的评价',
    desc: '家属评价，以及我的回复',
    to: '/companion/reviews'
  }
]

/* ============ 趋势维度 / 粒度（切换后重新向后端要数，不在本地切分） ============ */
const DIMENSIONS = [
  { value: 'ORDER', label: '订单' },
  { value: 'INCOME', label: '收入' },
  { value: 'RATING', label: '评分' }
]
const GRANULARITIES = [
  { value: 'DAY', label: '日' },
  { value: 'WEEK', label: '周' },
  { value: 'MONTH', label: '月' }
]
const dimension = ref('ORDER')
const granularity = ref('DAY')

/* ============ 数据 ============ */
const loading = ref(true) // 首屏加载
const overview = ref(null) // 区间经营指标
const trend = ref(null) // 趋势
const profile = ref(null) // 服务画像
const income = ref(null) // 收入摘要
/** 每块各自的失败标记：单块失败只降级该块 */
const failed = ref({ overview: false, trend: false, profile: false, income: false })

/**
 * 拉取单个数据块。
 * 失败只把该块置为失败态，不向上抛 —— 四块互不牵连。
 */
async function loadBlock(key, loader, target) {
  try {
    const data = await loader()
    target.value = data ?? null
    failed.value[key] = false
  } catch {
    target.value = null
    failed.value[key] = true
  }
}

/** 趋势单独一个入口：切换维度 / 粒度时只重拉趋势 */
let trendSeq = 0
async function loadTrend() {
  const seq = ++trendSeq
  try {
    const data = await getCompanionWorkbenchTrend({
      dimension: dimension.value,
      granularity: granularity.value
    })
    if (seq !== trendSeq) return // 已有更新的请求，丢弃这次过期响应
    trend.value = data ?? null
    failed.value.trend = false
  } catch {
    if (seq !== trendSeq) return
    trend.value = null
    failed.value.trend = true
  }
  await nextTick()
  renderTrendChart()
}

async function loadAll() {
  loading.value = true
  await Promise.allSettled([
    loadBlock('overview', () => getCompanionWorkbenchOverview(), overview),
    loadTrend(),
    loadBlock('profile', () => getCompanionWorkbenchProfile(), profile),
    loadBlock('income', () => getCompanionWorkbenchIncome(), income)
  ])
  loading.value = false
  // 图表容器是在 loading 骨架收起之后才挂载的，
  // 所以必须等骨架换掉再量尺寸初始化，否则画布尺寸为 0。
  await nextTick()
  renderTrendChart()
}

/* ============ 单块重试入口（模板里不写内联箭头函数） ============ */
function retryOverview() {
  return loadBlock('overview', () => getCompanionWorkbenchOverview(), overview)
}

function retryProfile() {
  return loadBlock('profile', () => getCompanionWorkbenchProfile(), profile)
}

function retryIncome() {
  return loadBlock('income', () => getCompanionWorkbenchIncome(), income)
}

/* ============ 展示层格式化（只改单位，不改口径） ============ */

/**
 * 分钟 → 人类可读时长（纯单位换算）。
 * 2520 分钟 → 「42 小时」；不是统计口径，分钟原值仍由后端给出。
 */
function formatDuration(minutes) {
  const m = Number(minutes)
  if (!m || m <= 0) return '0 小时'
  const hours = Math.floor(m / 60)
  const rest = m % 60
  return rest ? `${hours} 小时 ${rest} 分钟` : `${hours} 小时`
}

/* ============ 概览 KPI ============ */
const overviewKpis = computed(() => {
  const o = overview.value
  if (!o) return []
  return [
    {
      key: 'orders',
      label: '区间完成单数',
      value: String(o.completedOrderCount ?? 0),
      hint: '已完成 + 已评价'
    },
    {
      key: 'duration',
      label: '区间服务时长',
      value: formatDuration(o.serviceDurationMinutes),
      hint: `共 ${o.serviceDurationMinutes ?? 0} 分钟`
    },
    { key: 'rate', label: '区间完成率', value: o.completionRate ?? '—', hint: '拒单不计入分母' },
    { key: 'score', label: '平均评分', value: o.averageScore ?? '—', hint: '累计口径' },
    {
      key: 'income',
      label: '区间收入',
      value: formatMoney(o.periodIncome),
      hint: '区间内已完成订单',
      tone: 'primary'
    }
  ]
})

const overviewRange = computed(() => {
  const o = overview.value
  if (!o || !o.periodStart || !o.periodEnd) return ''
  return `${o.periodStart} ~ ${o.periodEnd}`
})

/* ============ 趋势 ============ */
const trendCategories = computed(() => (trend.value && trend.value.categories) || [])
const trendSeries = computed(() => (trend.value && trend.value.series) || [])

const trendSeriesName = computed(() => {
  const s = trendSeries.value[0]
  return (s && s.name) || ''
})

const granularityLabel = computed(() => {
  const map = { DAY: '日', WEEK: '周', MONTH: '月' }
  return map[granularity.value] || ''
})

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

/* ============ 服务画像 ============ */
const profileMetrics = computed(() => {
  const p = profile.value
  if (!p) return []
  return [
    { key: 'orders', label: '累计完成单数', value: String(p.totalCompletedOrderCount ?? 0) },
    { key: 'elders', label: '累计服务老人数', value: String(p.totalServedElderCount ?? 0) },
    { key: 'punctual', label: '准时率', value: p.punctualityRate ?? '—' },
    { key: 'rate', label: '累计完成率', value: p.totalCompletionRate ?? '—' },
    { key: 'duration', label: '累计服务时长', value: formatDuration(p.totalServiceDurationMinutes) }
  ]
})

const expertise = computed(() => (profile.value && profile.value.expertise) || [])

/* ============ 收入摘要 ============ */
const incomeMetrics = computed(() => {
  const i = income.value
  if (!i) return []
  return [
    { key: 'order', label: '订单金额', value: formatMoney(i.orderAmount) },
    { key: 'actual', label: '实际收入', value: formatMoney(i.actualIncome), tone: 'primary' },
    { key: 'unsettled', label: '待结算', value: formatMoney(i.unsettledAmount) },
    { key: 'settled', label: '已结算', value: formatMoney(i.settledAmount), tone: 'success' }
  ]
})

const incomeRange = computed(() => {
  const i = income.value
  if (!i || !i.periodStart || !i.periodEnd) return ''
  return `${i.periodStart} ~ ${i.periodEnd}`
})

/* ============ 页面级三态 ============ */
const allFailed = computed(
  () =>
    failed.value.overview &&
    failed.value.trend &&
    failed.value.profile &&
    failed.value.income
)
const hasAnyData = computed(
  () =>
    Boolean(overview.value) ||
    Boolean(profile.value) ||
    Boolean(income.value) ||
    trendCategories.value.length > 0
)

/* ============ ECharts（写法对齐 admin/dashboard.vue） ============ */
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

  const categories = trendCategories.value
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
        data: categories,
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

watch([dimension, granularity], loadTrend)

onMounted(() => {
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
  <NlPageShell title="工作台" :has-tabs="true" :back="false">
    <NlNoticeBar tone="primary">
      统计口径由后端统一计算，页面只做展示；统计区间默认最近 30 天。
    </NlNoticeBar>

    <!-- ============ 快捷入口（P1-7） ============
         放在工作台顶部：即使下面四块都还没数据（新陪诊员零订单），
         入口也照样在，不会被空态吞掉。 -->
    <NlCard title="快捷入口" class="block">
      <div class="entries">
        <button
          v-for="e in SHORTCUTS"
          :key="e.key"
          type="button"
          class="entry"
          @click="router.push(e.to)"
        >
          <span class="entry__body">
            <span class="entry__title">{{ e.label }}</span>
            <span class="nl-caption nl-text-muted">{{ e.desc }}</span>
          </span>
          <span class="entry__chev" aria-hidden="true">›</span>
        </button>
      </div>
    </NlCard>

    <NlSkeleton v-if="loading" :count="4" class="pad" />

    <NlEmpty
      v-else-if="allFailed"
      type="network"
      title="工作台加载失败"
      description="请检查网络后重试"
      action-text="重试"
      @action="loadAll"
    />

    <NlEmpty
      v-else-if="!hasAnyData"
      type="empty"
      title="暂无数据"
      description="完成订单后，这里会显示你的经营数据"
    />

    <template v-else>
      <!-- ============ 经营指标（区间口径） ============ -->
      <NlCard title="经营指标" class="block">
        <template #extra>
          <span v-if="overviewRange" class="nl-caption nl-text-muted is-num">
            统计区间 {{ overviewRange }}
          </span>
        </template>

        <NlEmpty
          v-if="failed.overview"
          type="network"
          title="经营指标加载失败"
          action-text="重试"
          @action="retryOverview"
        />

        <div v-else-if="overview" class="kpi-grid">
          <div v-for="k in overviewKpis" :key="k.key" class="kpi">
            <div class="kpi__label">{{ k.label }}</div>
            <div :class="['kpi__val', 'is-num', k.tone && `kpi__val--${k.tone}`]">
              {{ k.value }}
            </div>
            <div class="kpi__hint">{{ k.hint }}</div>
          </div>
        </div>

        <NlEmpty
          v-else
          type="empty"
          title="暂无经营数据"
          description="完成订单后，这里会显示你的经营数据"
        />
      </NlCard>

      <!-- ============ 趋势 ============ -->
      <NlCard title="趋势" class="block">
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
          <div class="seg" role="group" aria-label="统计粒度">
            <button
              v-for="g in GRANULARITIES"
              :key="g.value"
              type="button"
              class="seg__btn"
              :class="{ 'is-active': granularity === g.value }"
              :aria-pressed="granularity === g.value"
              @click="granularity = g.value"
            >
              {{ g.label }}
            </button>
          </div>
        </div>

        <NlEmpty
          v-if="failed.trend"
          type="network"
          title="趋势加载失败"
          action-text="重试"
          @action="loadTrend"
        />

        <template v-else-if="trend">
          <p v-if="trendSeriesName" class="trend__name nl-caption nl-text-muted">
            {{ trendSeriesName }} · 按{{ granularityLabel }}
          </p>
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
          description="有订单之后，这里会显示每日趋势"
        />
      </NlCard>

      <!-- ============ 服务画像（累计口径） ============ -->
      <NlCard title="服务画像" class="block">
        <template #extra>
          <span class="nl-caption nl-text-muted">历史累计</span>
        </template>

        <NlEmpty
          v-if="failed.profile"
          type="network"
          title="服务画像加载失败"
          action-text="重试"
          @action="retryProfile"
        />

        <template v-else-if="profile">
          <div class="kpi-grid">
            <div v-for="m in profileMetrics" :key="m.key" class="kpi">
              <div class="kpi__label">{{ m.label }}</div>
              <div class="kpi__val is-num">{{ m.value }}</div>
            </div>
          </div>

          <div class="expertise">
            <div class="expertise__label nl-caption nl-text-muted">擅长服务</div>
            <div v-if="expertise.length" class="expertise__tags">
              <span v-for="e in expertise" :key="e.department" class="tag">
                {{ e.department }}
                <span class="tag__count is-num">{{ e.orderCount }}</span>
              </span>
            </div>
            <NlEmpty
              v-else
              type="empty"
              title="暂无擅长服务标签"
              description="接单之后，这里会自动统计你服务最多的科室"
            />
          </div>
        </template>

        <NlEmpty
          v-else
          type="empty"
          title="暂无画像数据"
          description="完成订单后，这里会显示服务画像"
        />
      </NlCard>

      <!-- ============ 收入摘要（区间口径） ============ -->
      <NlCard title="收入摘要" class="block">
        <template #extra>
          <span v-if="incomeRange" class="nl-caption nl-text-muted is-num">
            统计区间 {{ incomeRange }}
          </span>
        </template>

        <NlEmpty
          v-if="failed.income"
          type="network"
          title="收入摘要加载失败"
          action-text="重试"
          @action="retryIncome"
        />

        <template v-else-if="income">
          <div class="kpi-grid">
            <div v-for="m in incomeMetrics" :key="m.key" class="kpi">
              <div class="kpi__label">{{ m.label }}</div>
              <div :class="['kpi__val', 'is-num', m.tone && `kpi__val--${m.tone}`]">
                {{ m.value }}
              </div>
            </div>
          </div>
          <p class="nl-caption nl-text-muted income__fee">
            费用构成：代垫 {{ formatMoney(income.advanceTotal) }} · 服务费
            {{ formatMoney(income.serviceTotal) }}
          </p>
        </template>

        <NlEmpty
          v-else
          type="empty"
          title="暂无收入数据"
          description="完成订单后，这里会显示收入构成"
        />
      </NlCard>
    </template>

    <template #tabbar>
      <NlAppTabBar />
    </template>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter);
}

.block {
  margin-bottom: var(--nl-gap-section);
}

/* ============ 快捷入口 ============ */
/* 沿用本页 .kpi 的卡片质感（白底 + 1px 边框 + 圆角），只是改成可点的整行 */
.entries {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--nl-space-3);
}

.entry {
  display: flex;
  align-items: center;
  gap: var(--nl-space-2);
  padding: var(--nl-space-3);
  text-align: left;
  color: var(--nl-text-1);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  cursor: pointer;

  &:hover {
    border-color: var(--nl-primary);
  }

  &:focus-visible {
    outline: 2px solid var(--nl-primary);
    outline-offset: 2px;
  }

  &__body {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }

  &__title {
    font-size: var(--nl-font-h3);
    font-weight: 600;
  }

  &__chev {
    flex-shrink: 0;
    font-size: var(--nl-font-h2);
    color: var(--nl-text-2);
  }
}

/* ============ KPI 卡 ============ */
.kpi-grid {
  display: grid;
  /* auto-fit + minmax(140px) 让指标卡随容器宽度自适应：
     < 320px 挤成 1 列，375px 两列，≥ 720px 四列以上，不会横向溢出 */
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
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

    &--primary {
      color: var(--nl-primary-text);
    }

    &--success {
      color: var(--nl-success-text);
    }
  }

  &__hint {
    margin-top: 4px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
  }
}

/* ============ 趋势控件 ============ */
.trend__ctrls {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-2);
}

.seg {
  display: flex;
  gap: var(--nl-space-1);
  padding: 2px;
  background: var(--nl-bg-sunken);
  border-radius: var(--nl-radius-btn);

  &__btn {
    /* 触控区不低于适老化基线，老人模式下会由 elderly.scss 再放大 */
    min-height: var(--nl-touch-min);
    padding: 0 var(--nl-space-3);
    font-size: var(--nl-font-body);
    color: var(--nl-text-2);
    background: transparent;
    border: none;
    border-radius: var(--nl-radius-btn);
    cursor: pointer;

    &.is-active {
      color: var(--nl-text-inverse);
      background: var(--nl-primary);
    }

    &:focus-visible {
      outline: 2px solid var(--nl-primary);
      outline-offset: 2px;
    }
  }
}

.trend__name {
  margin: 0;
}

/* 图表容器：颜色一律走 --nl-* 令牌（canvas 侧读取其计算值） */
.chart {
  width: 100%;
  height: 220px;
  color: var(--nl-primary);
}

.trend__list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-2);
  max-height: 132px;
  padding: 0;
  margin: var(--nl-space-2) 0 0;
  overflow-y: auto;
  list-style: none;
}

.trend__item {
  display: flex;
  gap: var(--nl-space-2);
  align-items: baseline;
  padding: 2px var(--nl-space-2);
  font-size: var(--nl-font-caption);
  background: var(--nl-bg-sunken);
  border-radius: var(--nl-radius-chip);

  &__cat {
    color: var(--nl-text-2);
  }

  &__val {
    font-weight: 700;
    color: var(--nl-text-1);
  }
}

/* ============ 擅长服务 ============ */
.expertise {
  margin-top: var(--nl-space-3);

  &__label {
    margin-bottom: var(--nl-space-2);
  }

  &__tags {
    display: flex;
    flex-wrap: wrap;
    gap: var(--nl-space-2);
  }
}

.tag {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 4px var(--nl-space-2);
  font-size: var(--nl-font-caption);
  color: var(--nl-primary-text);
  background: var(--nl-primary-ghost);
  border-radius: var(--nl-radius-chip);

  &__count {
    color: var(--nl-text-2);
  }
}

.income__fee {
  margin: var(--nl-space-3) 0 0;
}
</style>
