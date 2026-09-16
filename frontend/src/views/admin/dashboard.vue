<script setup>
/**
 * 管理后台 · 数据看板（W-01 · design.md §4）
 *
 * - 顶部日期筛选（今日 / 7 天 / 30 天 / 90 天）
 * - 4 个 KPI 卡：订单总数 / 完成订单 / 完成率 / 用户数
 * - 订单趋势：ECharts 折线（按后端返回的序列渲染，可能含新增/完成/取消三条）
 * - 完成率：ECharts 环形（直接用后端算好的完成率，避免前端再算一遍口径漂移）
 * - 漏服率：ECharts 折线（由漏服统计接口按周期算出的漏服率）
 * - 用户构成：ECharts 柱状（后端总览里的各角色人数）
 * - 陪诊员排行 Top 10（横向条 + 前三名奖牌色）
 *
 * 所有数字都来自 statistics 接口，前端不再本地造数据。
 * 后端没有「按月的用户增长曲线」这个接口，原模板的「本年累计增长」无真实数据源，
 * 因此把右下角那个图改成了「用户构成（角色分布）」，用总览里真实存在的角色人数字段。
 */
import { ref, onMounted, onBeforeUnmount, watch, nextTick, computed } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, GridComponent, LegendComponent, DataZoomComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { NlCard, NlSkeleton, NlEmpty } from '@/components'
import {
  getOverview, getOrderTrend, getCompanionRank, getMedicationMissedStat
} from '@/api/statistics'
import { lastNDays } from '@/utils/format'

echarts.use([
  LineChart, BarChart, PieChart,
  TitleComponent, TooltipComponent, GridComponent, LegendComponent, DataZoomComponent,
  CanvasRenderer
])

/* ============ 时间窗 ============ */
const range = ref('7d') // 1d / 7d / 30d / 90d
const rangeMap = { '1d': '今日', '7d': '近 7 天', '30d': '近 30 天', '90d': '近 90 天' }
// 不同时间窗对应不同统计粒度：90 天用周粒度，否则按天，避免横轴点过密
const RANGE_DAYS = { '1d': 1, '7d': 7, '30d': 30, '90d': 90 }
const RANGE_GRAN = { '1d': 'DAY', '7d': 'DAY', '30d': 'DAY', '90d': 'WEEK' }

/** 把前端的时间窗翻译成后端要的 startDate/endDate/granularity */
function rangeParams() {
  const n = RANGE_DAYS[range.value] || 30
  const { startDate, endDate } = lastNDays(n)
  return { startDate, endDate, granularity: RANGE_GRAN[range.value] || 'DAY' }
}

/* ============ 数据来源（全部来自后端，不再写死） ============ */
const loading = ref(true)
const overview = ref(null)          // 总览（嵌套结构）
const orderTrend = ref(null)        // 订单趋势 ChartDataVO
const companionTop = ref([])        // 陪诊员排行

// KPI 直接从总览里取，完成率后端已经算成 "86.13%" 这种字符串，这里只做展示裁剪
const kpi = computed(() => {
  const o = overview.value
  if (!o) return { totalOrders: 0, completed: 0, completionRate: 0, users: 0 }
  const order = o.order || {}
  // 完成率是带百分号的字符串，转成数字供圆环与文字展示
  const rate = parseFloat(String(order.completedRate || '0').replace('%', '')) || 0
  return {
    totalOrders: order.totalCount || 0,
    completed: order.completedCount || 0,
    completionRate: rate,
    users: (o.user && o.user.totalCount) || 0
  }
})

/* ============ ECharts 实例 ============ */
const orderChartRef = ref(null)
const completionChartRef = ref(null)
const missedChartRef = ref(null)
const growthChartRef = ref(null)
let orderChart, completionChart, missedChart, growthChart

const ORDER_TREND_COLORS = ['#1D6FF2', '#17B26A', '#F79009']

/* ============ 渲染：订单趋势 ============ */
function renderOrderChart() {
  if (!orderChartRef.value) return
  orderChart = orderChart?.dispose() || echarts.init(orderChartRef.value)

  const trend = orderTrend.value
  const categories = (trend && trend.categories) || []
  const seriesRaw = (trend && trend.series) || []
  // 后端保证 categories 与各 series.data 长度一致且缺口补 0，前端直接照画即可
  const series = seriesRaw.map((s, idx) => {
    const color = ORDER_TREND_COLORS[idx % ORDER_TREND_COLORS.length]
    return {
      name: s.name,
      type: 'line',
      data: s.data || [],
      smooth: true,
      symbol: 'circle',
      symbolSize: 5,
      itemStyle: { color },
      lineStyle: { width: 2, color },
      // 只有第一条序列加面积渐变，避免多条线叠在一起看不清
      areaStyle: idx === 0
        ? {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: 'rgba(29,111,242,0.25)' },
              { offset: 1, color: 'rgba(29,111,242,0)' }
            ])
          }
        : undefined
    }
  })

  orderChart.setOption({
    grid: { top: 36, left: 36, right: 12, bottom: 24 },
    tooltip: { trigger: 'axis' },
    legend: series.length > 1 ? { top: 0, right: 0, icon: 'circle', itemWidth: 8, itemHeight: 8 } : undefined,
    xAxis: {
      type: 'category',
      data: categories,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#E7EDF5' } },
      axisLabel: { color: '#93A1B5', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisLabel: { color: '#93A1B5', fontSize: 11 },
      splitLine: { lineStyle: { color: '#EFF3F8' } }
    },
    series
  }, true)
}

/* ============ 渲染：完成率环形 ============ */
function renderCompletionChart() {
  if (!completionChartRef.value) return
  completionChart = completionChart?.dispose() || echarts.init(completionChartRef.value)

  const rate = kpi.value.completionRate
  completionChart.setOption({
    series: [
      {
        type: 'pie',
        radius: ['70%', '88%'],
        avoidLabelOverlap: false,
        startAngle: 90,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
        label: { show: false },
        labelLine: { show: false },
        data: [
          { value: rate, name: '已完成', itemStyle: { color: '#17B26A' } },
          { value: Math.max(100 - rate, 0), name: '未完成', itemStyle: { color: '#E7EDF5' } }
        ]
      }
    ]
  }, true)
}

/* ============ 渲染：漏服率 ============ */
function renderMissedChart() {
  if (!missedChartRef.value) return
  missedChart = missedChart?.dispose() || echarts.init(missedChartRef.value)

  // medication-missed 返回的是「任务总数 / 已服用 / 漏服」三条计数的序列，
  // 而卡片标题是「漏服率（%）」，所以这里按周期现算漏服率 = 漏服 / 任务总数 * 100
  const chart = (medicationStat.value && medicationStat.value.chart) || null
  const categories = (chart && chart.categories) || []
  const seriesRaw = (chart && chart.series) || []
  const total = seriesRaw[0] && seriesRaw[0].data ? seriesRaw[0].data : []
  const missed = seriesRaw[2] && seriesRaw[2].data ? seriesRaw[2].data : []
  const rateData = categories.map((_, i) => {
    const t = Number(total[i]) || 0
    const m = Number(missed[i]) || 0
    return t > 0 ? Number(((m / t) * 100).toFixed(2)) : 0
  })

  missedChart.setOption({
    grid: { top: 16, left: 36, right: 12, bottom: 22 },
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${v}%` },
    xAxis: {
      type: 'category',
      data: categories,
      axisLine: { lineStyle: { color: '#E7EDF5' } },
      axisLabel: { color: '#93A1B5', fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisLabel: { color: '#93A1B5', fontSize: 10, formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#EFF3F8' } }
    },
    series: [
      {
        name: '漏服率',
        type: 'line',
        data: rateData,
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        itemStyle: { color: '#F79009' },
        lineStyle: { width: 2, color: '#F79009' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(247,144,9,0.25)' },
            { offset: 1, color: 'rgba(247,144,9,0)' }
          ])
        }
      }
    ]
  }, true)
}

/* ============ 渲染：用户构成（角色分布柱状图） ============ */
function renderGrowthChart() {
  if (!growthChartRef.value) return
  growthChart = growthChart?.dispose() || echarts.init(growthChartRef.value)

  // 后端没有「按月用户增长」接口，这里用总览里真实存在的角色人数字段画构成
  const u = (overview.value && overview.value.user) || {}
  const cats = ['老人', '家属', '陪诊员', '管理员']
  const data = [u.elderCount || 0, u.familyCount || 0, u.companionCount || 0, u.adminCount || 0]

  growthChart.setOption({
    grid: { top: 20, left: 36, right: 12, bottom: 24 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: {
      type: 'category',
      data: cats,
      axisLine: { lineStyle: { color: '#E7EDF5' } },
      axisLabel: { color: '#93A1B5', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisLabel: { color: '#93A1B5', fontSize: 11 },
      splitLine: { lineStyle: { color: '#EFF3F8' } }
    },
    series: [
      {
        type: 'bar',
        data,
        barWidth: '46%',
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#7A5AF8' },
            { offset: 1, color: '#A18BFF' }
          ])
        }
      }
    ]
  }, true)
}

function renderAll() {
  renderOrderChart()
  renderCompletionChart()
  renderMissedChart()
  renderGrowthChart()
}

function handleResize() {
  orderChart?.resize()
  completionChart?.resize()
  missedChart?.resize()
  growthChart?.resize()
}

/* ============ 数据加载 ============ */
const medicationStat = ref(null)
// 空态：不是 loading 且总览为空（后端返回 null 或完全无数据）
const isEmpty = computed(() => !loading.value && !overview.value)

async function loadDashboard() {
  loading.value = true
  const params = rangeParams()
  try {
    // 五个统计接口并行拉取，互不依赖
    const [ov, trend, rank, med] = await Promise.all([
      getOverview(params),
      getOrderTrend(params),
      getCompanionRank({ ...params, limit: 10, metric: 'ORDER_COUNT' }),
      getMedicationMissedStat(params)
    ])
    overview.value = ov
    orderTrend.value = trend
    companionTop.value = rank || []
    medicationStat.value = med
  } catch {
    // 拦截器已弹过错误提示，这里只兜底把数据清空，交给空态/骨架展示
    overview.value = null
    orderTrend.value = null
    companionTop.value = []
    medicationStat.value = null
  } finally {
    loading.value = false
    // DOM 此刻才真正渲染出来，等下一帧再量尺寸画 ECharts，否则拿不到宽高
    await nextTick()
    renderAll()
  }
}

onMounted(() => {
  loadDashboard()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
})

// 切换时间窗重新拉数并重绘，不在前端本地切分（本地切分只能切已加载的那一段）
watch(range, loadDashboard)
</script>

<template>
  <div class="dash">
    <!-- 筛选 -->
    <section class="dash__filter">
      <el-radio-group v-model="range" size="default">
        <el-radio-button label="1d">今日</el-radio-button>
        <el-radio-button label="7d">近 7 天</el-radio-button>
        <el-radio-button label="30d">近 30 天</el-radio-button>
        <el-radio-button label="90d">近 90 天</el-radio-button>
      </el-radio-group>
      <span class="nl-caption nl-text-muted">范围 · {{ rangeMap[range] }}</span>
    </section>

    <NlSkeleton v-if="loading" :count="6" />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无数据"
      description="当前时间范围内还没有可统计的数据"
    />

    <template v-else>
      <!-- KPI -->
      <section class="kpi-grid">
        <div class="kpi">
          <div class="kpi__label">订单总数</div>
          <div class="kpi__val is-num">{{ kpi.totalOrders.toLocaleString() }}</div>
          <div class="kpi__trend is-num">本周期全部订单</div>
        </div>
        <div class="kpi">
          <div class="kpi__label">完成订单</div>
          <div class="kpi__val is-num">{{ kpi.completed.toLocaleString() }}</div>
          <div class="kpi__trend is-num">含已评价</div>
        </div>
        <div class="kpi">
          <div class="kpi__label">完成率</div>
          <div class="kpi__val kpi__val--success is-num">{{ kpi.completionRate }}%</div>
          <div class="kpi__trend">未完成 {{ (100 - kpi.completionRate).toFixed(1) }}%</div>
        </div>
        <div class="kpi">
          <div class="kpi__label">用户数</div>
          <div class="kpi__val is-num">{{ kpi.users.toLocaleString() }}</div>
          <div class="kpi__trend is-num">全量累计</div>
        </div>
      </section>

      <!-- 第一行：订单趋势 + 完成率 -->
      <div class="row">
        <NlCard title="订单趋势" class="row__main">
          <div ref="orderChartRef" class="chart chart--lg" />
        </NlCard>
        <NlCard title="完成率" class="row__side">
          <div class="completion">
            <div ref="completionChartRef" class="chart chart--ring" />
            <div class="completion__center">
              <div class="completion__val is-num">{{ kpi.completionRate }}%</div>
              <div class="completion__lbl">已完成</div>
            </div>
          </div>
        </NlCard>
      </div>

      <!-- 第二行：漏服率 + 用户构成 -->
      <div class="row">
        <NlCard title="漏服率（%）">
          <div ref="missedChartRef" class="chart chart--sm" />
        </NlCard>
        <NlCard title="用户构成（角色分布）">
          <div ref="growthChartRef" class="chart chart--lg" />
        </NlCard>
      </div>

      <!-- 陪诊员 Top 10 -->
      <NlCard title="陪诊员排行 · Top 10（按接单数）" plain>
        <ol class="rank-list">
          <li v-for="c in companionTop" :key="c.rank" class="rank-item">
            <span :class="['rank-item__num', `is-top-${Math.min(c.rank, 3)}`]">{{ c.rank }}</span>
            <span class="rank-item__name">{{ c.companionName }}</span>
            <div class="rank-item__bar">
              <div
                class="rank-item__fill"
                :style="{ width: `${(c.orderCount / (companionTop[0]?.orderCount || 1)) * 100}%` }"
              />
            </div>
            <span class="rank-item__count is-num">{{ c.orderCount }}</span>
            <span class="rank-item__rate is-num">{{ c.completedRate }}</span>
          </li>
        </ol>
      </NlCard>
    </template>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.dash {
  display: flex;
  flex-direction: column;
  gap: $nl-space-5;

  &__filter {
    display: flex;
    gap: $nl-space-3;
    align-items: center;
  }
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: $nl-space-4;
}

.kpi {
  padding: $nl-space-4;
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: $nl-radius-card;
  box-shadow: var(--nl-shadow-card);

  &__label {
    font-size: $nl-font-caption;
    color: var(--nl-text-2);
  }

  &__val {
    margin-top: 6px;
    font-size: 28px;
    font-weight: 700;
    color: var(--nl-text-1);

    &--success {
      color: var(--nl-success);
    }
  }

  &__trend {
    margin-top: 6px;
    font-size: $nl-font-caption;
    color: var(--nl-success);
  }
}

.row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: $nl-space-4;

  &__main, &__side {
    margin: 0;
  }
}

.chart {
  width: 100%;
  height: 280px;

  &--lg { height: 300px; }
  &--sm { height: 220px; }
  &--ring { height: 220px; }
}

.completion {
  position: relative;
  height: 220px;

  &__center {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
  }

  &__val {
    font-size: 36px;
    font-weight: 700;
    color: var(--nl-success);
  }

  &__lbl {
    font-size: $nl-font-caption;
    color: var(--nl-text-2);
  }
}

.rank-list {
  padding: 0;
  margin: 0;
  list-style: none;
}

.rank-item {
  display: grid;
  grid-template-columns: 32px 80px 1fr 60px 50px;
  gap: $nl-space-3;
  align-items: center;
  padding: 8px 0;
  font-size: $nl-font-body;

  & + .rank-item {
    border-top: 1px dashed var(--nl-divider);
  }

  &__num {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    font-size: $nl-font-caption;
    font-weight: 700;
    color: var(--nl-text-2);
    background: var(--nl-neutral-chip);
    border-radius: 50%;

    &.is-top-1 { color: #fff;     background: #F2A900; }
    &.is-top-2 { color: #fff;     background: #B1B5BB; }
    &.is-top-3 { color: #fff;     background: #D78A4E; }
  }

  &__name {
    font-weight: 500;
  }

  &__bar {
    height: 8px;
    background: var(--nl-bg-sunken);
    border-radius: 999px;
    overflow: hidden;
  }

  &__fill {
    height: 100%;
    background: var(--nl-primary-gradient);
    border-radius: 999px;
  }

  &__count {
    color: var(--nl-text-1);
    text-align: right;
    font-weight: 600;
  }

  &__rate {
    color: var(--nl-success);
    text-align: right;
    font-size: $nl-font-caption;
  }
}
</style>
