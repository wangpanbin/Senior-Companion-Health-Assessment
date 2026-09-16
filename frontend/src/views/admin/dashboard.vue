<script setup>
/**
 * 管理后台 · 数据看板（W-01 · design.md §4）
 *
 * - 顶部日期筛选（今日 / 7 天 / 30 天 / 90 天）
 * - 4 个 KPI 卡：订单总数 / 完成订单 / 完成率 / 用户数
 * - 订单趋势：ECharts 折线 + 面积渐变（蓝→白渐变填充）
 * - 完成率：ECharts 环形
 * - 漏服率：ECharts 折线（橙色 warning 色）
 * - 陪诊员排行 Top 10（横向条 + 前三名奖牌色：1 金 2 银 3 铜）
 *
 * ADR-0003：单接口响应时间 < 2s（1 万条订单），本视图只读，不做导出
 */
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import {
  TitleComponent, TooltipComponent, GridComponent, LegendComponent, DataZoomComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { NlCard } from '@/components'

echarts.use([
  LineChart, BarChart, PieChart,
  TitleComponent, TooltipComponent, GridComponent, LegendComponent, DataZoomComponent,
  CanvasRenderer
])

/* ============ 时间窗 ============ */
const range = ref('7d') // 1d / 7d / 30d
const rangeMap = { '1d': '今日', '7d': '近 7 天', '30d': '近 30 天', '90d': '近 90 天' }

/* ============ KPI mock ============ */
const kpi = ref({
  totalOrders: 1284,
  completed: 1180,
  completionRate: 92,
  users: 3260
})

/* ============ ECharts 实例 ============ */
const orderChartRef = ref(null)
const completionChartRef = ref(null)
const missedChartRef = ref(null)
const growthChartRef = ref(null)
let orderChart, completionChart, missedChart, growthChart

/* ============ 渲染：订单趋势 ============ */
function renderOrderChart() {
  if (!orderChartRef.value) return
  orderChart = orderChart?.dispose() || echarts.init(orderChartRef.value)

  const days = range.value === '1d'
    ? ['00','03','06','09','12','15','18','21']
    : range.value === '7d'
      ? ['周一','周二','周三','周四','周五','周六','周日']
      : range.value === '30d'
        ? Array.from({ length: 30 }, (_, i) => `D${i + 1}`)
        : Array.from({ length: 12 }, (_, i) => `${i + 1}月`)

  const data = days.map((_, i) => 80 + Math.round(Math.sin(i * 0.7 + 1) * 40 + Math.random() * 15))

  orderChart.setOption({
    grid: { top: 20, left: 36, right: 12, bottom: 24 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: days,
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
    series: [
      {
        type: 'line',
        data,
        smooth: true,
        symbol: 'circle',
        symbolSize: 6,
        itemStyle: { color: '#1D6FF2' },
        lineStyle: { width: 2, color: '#1D6FF2' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(29,111,242,0.25)' },
            { offset: 1, color: 'rgba(29,111,242,0)' }
          ])
        }
      }
    ]
  })
}

/* ============ 渲染：完成率环形 ============ */
function renderCompletionChart() {
  if (!completionChartRef.value) return
  completionChart = completionChart?.dispose() || echarts.init(completionChartRef.value)

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
          { value: kpi.value.completionRate, name: '已完成', itemStyle: { color: '#17B26A' } },
          { value: 100 - kpi.value.completionRate, name: '进行中', itemStyle: { color: '#E7EDF5' } }
        ]
      }
    ]
  })
}

/* ============ 渲染：漏服率 ============ */
function renderMissedChart() {
  if (!missedChartRef.value) return
  missedChart = missedChart?.dispose() || echarts.init(missedChartRef.value)

  const data = [3.1, 2.8, 4.2, 3.5, 2.6, 2.1, 1.9]
  missedChart.setOption({
    grid: { top: 16, left: 28, right: 8, bottom: 22 },
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${v}%` },
    xAxis: {
      type: 'category',
      data: ['周一','周二','周三','周四','周五','周六','周日'],
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
        type: 'line',
        data,
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
  })
}

/* ============ 陪诊员 Top 10 mock ============ */
const companionTop = ref([
  { rank: 1, name: '张三', avatar: '', count: 248, rate: 99 },
  { rank: 2, name: '李四', avatar: '', count: 236, rate: 98 },
  { rank: 3, name: '王五', avatar: '', count: 210, rate: 97 },
  { rank: 4, name: '赵六', avatar: '', count: 198, rate: 96 },
  { rank: 5, name: '孙七', avatar: '', count: 186, rate: 95 },
  { rank: 6, name: '周八', avatar: '', count: 174, rate: 95 },
  { rank: 7, name: '吴九', avatar: '', count: 162, rate: 94 },
  { rank: 8, name: '郑十', avatar: '', count: 158, rate: 94 },
  { rank: 9, name: '钱十一', avatar: '', count: 149, rate: 93 },
  { rank: 10, name: '孔十二', avatar: '', count: 142, rate: 92 }
])

/* ============ 用户增长 ============ */
function renderGrowthChart() {
  if (!growthChartRef.value) return
  growthChart = growthChart?.dispose() || echarts.init(growthChartRef.value)

  const data = [120, 145, 168, 192, 220, 245, 268, 290, 320, 348, 372, 395]
  growthChart.setOption({
    grid: { top: 20, left: 36, right: 12, bottom: 24 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: ['1月','2月','3月','4月','5月','6月','7月','8月','9月','10月','11月','12月'],
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
        type: 'line',
        data,
        smooth: true,
        symbol: 'circle',
        symbolSize: 5,
        itemStyle: { color: '#7A5AF8' },
        lineStyle: { width: 2, color: '#7A5AF8' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(122,90,248,0.25)' },
            { offset: 1, color: 'rgba(122,90,248,0)' }
          ])
        }
      }
    ]
  })
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

onMounted(async () => {
  await nextTick()
  renderAll()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
})

watch(range, () => {
  renderOrderChart()
})
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

    <!-- KPI -->
    <section class="kpi-grid">
      <div class="kpi">
        <div class="kpi__label">订单总数</div>
        <div class="kpi__val is-num">{{ kpi.totalOrders.toLocaleString() }}</div>
        <div class="kpi__trend is-num">较上期 +12.4% ↑</div>
      </div>
      <div class="kpi">
        <div class="kpi__label">完成订单</div>
        <div class="kpi__val is-num">{{ kpi.completed.toLocaleString() }}</div>
        <div class="kpi__trend is-num">较上期 +8.1% ↑</div>
      </div>
      <div class="kpi">
        <div class="kpi__label">完成率</div>
        <div class="kpi__val kpi__val--success is-num">{{ kpi.completionRate }}%</div>
        <div class="kpi__trend">进行中 {{ (100 - kpi.completionRate) }}%</div>
      </div>
      <div class="kpi">
        <div class="kpi__label">用户数</div>
        <div class="kpi__val is-num">{{ kpi.users.toLocaleString() }}</div>
        <div class="kpi__trend is-num">较上期 +5.2% ↑</div>
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

    <!-- 第二行：漏服率 + 用户增长 -->
    <div class="row">
      <NlCard title="漏服率（%）">
        <div ref="missedChartRef" class="chart chart--sm" />
      </NlCard>
      <NlCard title="用户增长（本年累计）">
        <div ref="growthChartRef" class="chart chart--lg" />
      </NlCard>
    </div>

    <!-- 陪诊员 Top 10 -->
    <NlCard title="陪诊员排行 · Top 10（按完成订单数）" plain>
      <ol class="rank-list">
        <li v-for="c in companionTop" :key="c.rank" class="rank-item">
          <span :class="['rank-item__num', `is-top-${Math.min(c.rank, 3)}`]">{{ c.rank }}</span>
          <span class="rank-item__name">{{ c.name }}</span>
          <div class="rank-item__bar">
            <div
              class="rank-item__fill"
              :style="{ width: `${(c.count / companionTop[0].count) * 100}%` }"
            />
          </div>
          <span class="rank-item__count is-num">{{ c.count }}</span>
          <span class="rank-item__rate is-num">{{ c.rate }}%</span>
        </li>
      </ol>
    </NlCard>
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
