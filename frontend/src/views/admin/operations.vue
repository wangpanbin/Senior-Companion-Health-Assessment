<script setup>
/**
 * 平台运营驾驶舱（V3.5 · W-11）
 *
 * 回答一个问题：「平台现在整体运行得怎么样，哪里需要管理员立刻动手」。
 *
 * 数据来源（4 个后端端点，全部 ADMIN 专属，服务端从登录态取权限）：
 *   - 平台概览    GET /api/admin/operations/overview
 *   - 异常中心    GET /api/admin/operations/exceptions
 *   - 趋势        GET /api/admin/operations/trend
 *   - 平台健康度  GET /api/admin/operations/health
 *
 * 口径原则：页面上每一个数字都来自上面 4 个端点，前端只做**展示**与**单位换算**
 * （分钟 → 「N 分钟」），绝不自行汇总（与 V3.3 工作台同一原则）。
 *
 * 三态：Loading（骨架）/ Empty（空态）/ Error（可重试）。
 * 四块**各自独立**加载：任一块失败只降级该块，不会把整页拖下水
 * —— 驾驶舱最忌讳「一块挂了导致其余指标看不见」。
 *
 * 异常中心是本版的关键：**「看到了但处理不了」是最典型的假绿**。
 * 因此每张卡片的「去处理」都直接用后端下发的 `linkUrl` 跳转，
 * 前端不自己拼路由（拼了就可能指向 404）；组件单测会逐个断言这四个
 * linkUrl 真实存在于前端路由表中。
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
  getOperationsHealth
} from '@/api/statistics'
import { lastNDays } from '@/utils/format'

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

/* ============ 数据加载（四块独立，互不牵连） ============ */
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

async function loadAll() {
  await Promise.allSettled([loadOverview(), loadExceptions(), loadTrend(), loadHealth()])
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
</style>
