<script setup>
/**
 * 老人端健康中心（V3.2 · FR-Overview / FR-Timeline · 老人本人）
 *
 * 数据来源：
 *   - `GET /elder/health/overview`（getMyHealthOverview）→ 我的概览（不含费用 / 催单 / 履约风险）
 *   - `GET /elder/health/timeline`（getMyHealthTimeline）→ 我的健康时间线
 *
 * 适老化（AGENTS.md §3.5）：
 *   - 正文 ≥ 18px、点击区 ≥ 48px、对比度 ≥ 4.5:1、核心流程 ≤ 3 次点击；
 *   - 老人端只留 4 件事：今日用药 / 今日完成的事 / 近期就医 / 待办；
 *   - 时间线只显示「图标 + 一句话 + 时间」，不显示多行说明 ——
 *     老人端信息密度低优先于完整性。
 *
 * 所有口径由后端计算（docs/spec/metric-dictionary.md），前端不做再计算。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NlPageShell, NlEmpty, NlSkeleton, NlCard, NlComplianceBar } from '@/components'
import VitalTrendChart from '@/components/health/VitalTrendChart.vue'
import { getMyHealthOverview, getMyHealthTimeline } from '@/api/elderHealth'
import { getMyVitals, getMyVitalTrend } from '@/api/vitalSign'
import { timelineCategoryTone, eventTimeText } from '@/utils/elderHealth'

const router = useRouter()

const overview = ref(null)
const loading = ref(false)
const loadError = ref(false)

const items = ref([])
const timelineLoading = ref(false)
const timelineError = ref(false)
const hasMore = ref(false)
const nextCursor = ref(null)
/** 后端告知「触及取数上限、这段时间没全给」——必须如实显示，不能让人以为看到的就是全部 */
const truncated = ref(false)

const isEmptyTimeline = computed(
  () => !timelineLoading.value && !timelineError.value && !items.value.length
)

/* ==================== 我的体征（V4 · P2-2，只读） ==================== */

/**
 * 老人端不提供任何录入 / 撤回入口 —— 老人账号为只读模式，
 * 记录由家人或陪诊员代录（后端对 ELDER 的写请求统一 403）。
 */
const VITAL_PAGE_SIZE = 10

const vitalRecords = ref([])
const vitalsLoading = ref(false)
const vitalsError = ref(false)
const vitalsHasMore = ref(false)

const trendType = ref('BLOOD_PRESSURE')
const trend = ref(null)
const trendLoading = ref(false)
const trendError = ref(false)

const trendPoints = computed(() => trend.value?.points || [])

/**
 * 最近一次读数：趋势点按测量时间升序返回，末项即最近一次。
 *
 * 老人端不放大绘图区，改用这张大字卡片让关键读数一眼可见；
 * 只陈述数值与时间，不做任何判断（合规红线-1）。
 */
const latestReading = computed(() => {
  if (!trend.value || trend.value.vitalType !== trendType.value) return null
  const points = trendPoints.value
  if (!points.length) return null
  const last = points[points.length - 1]
  const text = String(last.valueText || '')
  const index = text.indexOf(' ')
  return {
    value: index < 0 ? text : text.slice(0, index),
    suffix: index < 0 ? '' : text.slice(index + 1),
    measuredAt: last.measuredAt
  }
})

async function loadVitals(page = 1) {
  vitalsLoading.value = true
  try {
    const data = await getMyVitals({ page, size: VITAL_PAGE_SIZE })
    const records = data?.records || []
    vitalRecords.value = page === 1 ? records : [...vitalRecords.value, ...records]
    vitalsHasMore.value = (data?.pages ?? 0) > page
    vitalsError.value = false
  } catch {
    if (page === 1) {
      vitalRecords.value = []
      vitalsHasMore.value = false
    }
    vitalsError.value = true
  } finally {
    vitalsLoading.value = false
  }
}

function loadMoreVitals() {
  const loadedPages = Math.ceil(vitalRecords.value.length / VITAL_PAGE_SIZE)
  loadVitals(loadedPages + 1)
}

async function loadTrend() {
  trendLoading.value = true
  try {
    trend.value = await getMyVitalTrend({ vitalType: trendType.value })
    trendError.value = false
  } catch {
    trend.value = null
    trendError.value = true
  } finally {
    trendLoading.value = false
  }
}

/** 切换趋势类型：只读视图，不需要任何写操作 */
function switchTrendType(next) {
  if (trendType.value === next) return
  trendType.value = next
  loadTrend()
}

async function loadOverview() {
  loading.value = true
  try {
    overview.value = await getMyHealthOverview()
    loadError.value = false
  } catch {
    overview.value = null
    loadError.value = true
  } finally {
    loading.value = false
  }
}

async function loadTimeline(cursor) {
  timelineLoading.value = true
  try {
    const data = await getMyHealthTimeline(cursor ? { cursor } : undefined)
    const records = data?.items || []
    items.value = cursor ? [...items.value, ...records] : records
    hasMore.value = data?.hasMore === true
    nextCursor.value = data?.nextCursor || null
    truncated.value = data?.truncated === true
    timelineError.value = false
  } catch {
    if (!cursor) {
      items.value = []
      hasMore.value = false
      nextCursor.value = null
      truncated.value = false
    }
    timelineError.value = true
  } finally {
    timelineLoading.value = false
  }
}

function loadMore() {
  if (nextCursor.value) {
    loadTimeline(nextCursor.value)
  }
}

function go(linkUrl) {
  if (linkUrl) {
    router.push(linkUrl)
  }
}

/**
 * 空态也要给出路（P2-18）：「还没有记录」之后如果无路可走，老人就只能干等。
 * 导向用药页 —— 那是老人端信息密度最高、也确实可能已有内容的地方。
 */
function goMedication() {
  router.push('/elder/medication')
}

onMounted(() => {
  loadOverview()
  loadTimeline()
  loadVitals()
  loadTrend()
})
</script>

<template>
  <NlPageShell title="我的健康" :back="false">
    <NlSkeleton v-if="loading" :count="3" class="pad" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="加载失败"
      action-text="重试"
      @action="loadOverview"
    />

    <template v-else-if="overview">
      <!-- 今日用药：老人端最重要的一个数 -->
      <section class="pad">
        <div class="today">
          <span class="today__v is-num">{{ overview.todayMedication?.taken ?? 0 }}</span>
          <span class="today__k">今日已服药</span>
          <span v-if="(overview.todayMedication?.pending ?? 0) > 0" class="today__pending">
            还有 {{ overview.todayMedication.pending }} 次没吃
          </span>
        </div>
      </section>

      <!-- 近期就医 / 待办 -->
      <section class="pad">
        <ul role="list" class="grid">
          <li class="metric">
            <span class="metric__v is-num">{{ overview.visit?.orderCount ?? 0 }}</span>
            <span class="metric__k">近期就医</span>
          </li>
          <li class="metric">
            <span class="metric__v is-num">{{ overview.pendingItems?.length ?? 0 }}</span>
            <span class="metric__k">待办</span>
          </li>
        </ul>
      </section>

      <!-- 待办 -->
      <section v-if="overview.pendingItems?.length" class="pad">
        <NlCard title="要办的事" :padding="12">
          <ul role="list" class="todo">
            <li
              v-for="item in overview.pendingItems"
              :key="item.type"
              class="todo__row"
              role="button"
              tabindex="0"
              @click="go(item.linkUrl)"
              @keydown.enter.prevent="go(item.linkUrl)"
            >
              <span class="todo__label">{{ item.typeLabel }}</span>
              <span class="todo__count is-num">{{ item.count }}</span>
            </li>
          </ul>
        </NlCard>
      </section>

      <!-- 我的体征：只读，没有任何录入 / 撤回入口 -->
      <section class="pad">
        <NlCard title="我的体征" :padding="12">
          <p class="vital-hint">这里是自己测过的数值，只做记录</p>

          <div class="vital-types" role="tablist" aria-label="体征类型">
            <button
              v-for="item in [
                { key: 'BLOOD_PRESSURE', label: '血压' },
                { key: 'BLOOD_GLUCOSE', label: '血糖' },
                { key: 'WEIGHT', label: '体重' }
              ]"
              :key="item.key"
              type="button"
              role="tab"
              class="vital-type"
              :class="{ 'is-active': trendType === item.key }"
              :aria-selected="trendType === item.key"
              @click="switchTrendType(item.key)"
            >
              {{ item.label }}
            </button>
          </div>

          <div v-if="latestReading" class="vital-latest">
            <span class="vital-latest__k">最近一次 · {{ trend?.vitalTypeLabel || '' }}</span>
            <div class="vital-latest__row">
              <span class="vital-latest__v is-num">{{ latestReading.value }}</span>
              <span v-if="latestReading.suffix" class="vital-latest__u">{{ latestReading.suffix }}</span>
            </div>
            <span class="vital-latest__t">{{ eventTimeText(latestReading.measuredAt) }}</span>
          </div>

          <NlSkeleton v-if="trendLoading" :count="2" />
          <NlEmpty
            v-else-if="trendError"
            type="network"
            title="加载失败"
            action-text="重试"
            @action="loadTrend"
          />
          <NlEmpty
            v-else-if="!trendPoints.length"
            title="还没有记录"
            description="家人或陪诊员录入后会显示在这里"
          />
          <VitalTrendChart v-else :trend="trend" />

          <ul v-if="vitalRecords.length" role="list" class="vital-list">
            <li v-for="v in vitalRecords" :key="v.id" class="vital-row">
              <span class="vital-row__label">{{ v.vitalTypeLabel }}</span>
              <span class="vital-row__value is-num">{{ v.valueText }}</span>
              <span class="vital-row__time">{{ eventTimeText(v.measuredAt) }}</span>
            </li>
          </ul>
          <p v-else-if="vitalsLoading" class="vital-hint">正在加载…</p>
          <p v-else-if="vitalsError" class="vital-hint">记录加载失败，可稍后再看</p>

          <div v-if="vitalsHasMore" class="more">
            <el-button plain round size="large" :loading="vitalsLoading" @click="loadMoreVitals">
              看更早的记录
            </el-button>
          </div>
        </NlCard>
      </section>

      <!-- 今日完成的事：时间线 -->
      <section class="pad">
        <NlCard title="做过的事" :padding="12">
          <NlSkeleton v-if="timelineLoading && !items.length" :count="3" />

          <NlEmpty
            v-else-if="timelineError && !items.length"
            type="network"
            title="加载失败"
            action-text="重试"
            @action="loadTimeline()"
          />

          <NlEmpty
            v-else-if="isEmptyTimeline"
            title="还没有记录"
            action-text="查看用药"
            @action="goMedication"
          />

          <ul v-else role="list" class="events">
            <li
              v-for="e in items"
              :key="e.eventId"
              class="event"
              :class="`is-${timelineCategoryTone(e.category).key}`"
              role="button"
              tabindex="0"
              @click="go(e.linkUrl)"
              @keydown.enter.prevent="go(e.linkUrl)"
            >
              <span class="event__dot" aria-hidden="true" />
              <span class="event__title">{{ e.title }}</span>
              <span class="event__time is-num" :title="e.occurredAt">{{ eventTimeText(e.occurredAt) }}</span>
            </li>
          </ul>

          <p v-if="truncated" class="event__detail trunc-note">
            记录较多，仅显示最近部分
          </p>

          <div v-if="hasMore" class="more">
            <el-button plain round size="small" :loading="timelineLoading" @click="loadMore">
              加载更多
            </el-button>
          </div>
        </NlCard>
      </section>
    </template>
    <!-- 合规声明（最终复盘 FE-03）：本页展示/录入血压、血糖、体重这类医疗相邻数据，
         必须明确「只记录数值、不做判断」。全站合规条只出现在用药页是漏挂，不是设计。 -->
    <NlComplianceBar text="本页仅记录您录入的体征数值，不做任何健康判断，也不构成医疗建议；数值异常请及时就医。" />
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter) var(--nl-space-4);
}

.today {
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  padding: var(--nl-space-5) var(--nl-space-4);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--nl-space-1);
  margin-top: var(--nl-space-4);
}

.today__v {
  font-size: 56px;
  line-height: 1.1;
  color: var(--nl-primary);
  font-weight: 700;
}

.today__k {
  font-size: var(--nl-font-h3);
  color: var(--nl-text-1);
}

.today__pending {
  font-size: var(--nl-font-body);
  color: var(--nl-warning);
}

.grid {
  list-style: none;
  padding: var(--nl-space-4);
  margin: 0;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  align-items: center;
}

.metric__v {
  font-size: var(--nl-font-h1);
  color: var(--nl-text-1);
}

.metric__k {
  font-size: var(--nl-font-body);
  color: var(--nl-text-2);
}

.todo {
  list-style: none;
  padding: 0;
  margin: 0;
}

.todo__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--nl-space-3);
  min-height: var(--nl-touch-min);
  padding: var(--nl-space-2) 0;
  border-bottom: 1px solid var(--nl-divider);
  cursor: pointer;

  &:last-child {
    border-bottom: none;
  }
}

.todo__label {
  font-size: var(--nl-font-body);
  color: var(--nl-text-1);
}

.todo__count {
  font-size: var(--nl-font-h3);
  color: var(--nl-warning);
  font-weight: 600;
}

.events {
  list-style: none;
  padding: 0;
  margin: 0;
}

.event {
  display: flex;
  align-items: center;
  gap: var(--nl-space-3);
  min-height: var(--nl-touch-min);
  padding: var(--nl-space-2) 0;
  border-bottom: 1px solid var(--nl-divider);
  cursor: pointer;

  &:last-child {
    border-bottom: none;
  }
}

.event__dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--nl-border-strong);
  flex: 0 0 auto;
}

.event.is-order .event__dot {
  background: var(--nl-primary);
}

.event.is-checkin .event__dot {
  background: var(--nl-info);
}

.event.is-medication .event__dot {
  background: var(--nl-success);
}

.event__title {
  flex: 1;
  font-size: var(--nl-font-body);
  color: var(--nl-text-1);
}

.event__time {
  font-size: var(--nl-font-body);
  color: var(--nl-text-2);
}

.more {
  display: flex;
  justify-content: center;
  padding-top: var(--nl-space-3);
}

.trunc-note {
  margin-top: var(--nl-space-2);
  text-align: center;
}

/* ---------- 我的体征（V4 · P2-2，只读） ---------- */

.vital-hint {
  margin: 0 0 var(--nl-space-3);
  color: var(--nl-text-2);
  font-size: var(--nl-font-h3);
  line-height: 26px;
}

/* 老人端触控区 ≥48px、字号 ≥18px */
.vital-types {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-2);
  margin-bottom: var(--nl-space-3);
}

.vital-type {
  min-height: 48px;
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-btn);
  background: var(--nl-bg-card);
  color: var(--nl-text-2);
  font-family: inherit;
  font-size: var(--nl-font-h3);
  cursor: pointer;

  &.is-active {
    border-color: var(--nl-primary);
    background: var(--nl-primary-light);
    color: var(--nl-primary-dark);
    font-weight: 600;
  }

  &:focus-visible {
    outline: 2px solid var(--nl-primary);
    outline-offset: 2px;
  }
}

/* 最近一次读数：大字卡片，绘图区保持不变 */
.vital-latest {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--nl-space-1);
  padding: var(--nl-space-3);
  margin-bottom: var(--nl-space-3);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  background: var(--nl-bg-sunken);
}

.vital-latest__k {
  color: var(--nl-text-2);
  font-size: var(--nl-font-h3);
}

.vital-latest__row {
  display: flex;
  align-items: baseline;
  gap: var(--nl-space-2);
}

.vital-latest__v {
  color: var(--nl-primary);
  font-family: var(--nl-font-num);
  font-size: 40px;
  font-weight: 700;
  line-height: 1.1;
}

.vital-latest__u {
  color: var(--nl-text-2);
  font-size: var(--nl-font-h3);
}

.vital-latest__t {
  color: var(--nl-text-3);
  font-size: var(--nl-font-body);
}

.vital-list {
  margin: var(--nl-space-3) 0 0;
  padding: 0;
  list-style: none;
}

.vital-row {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--nl-space-2);
  min-height: 48px;
  padding: var(--nl-space-2) 0;
  border-bottom: 1px solid var(--nl-border);
  font-size: var(--nl-font-h3);

  &:last-child {
    border-bottom: 0;
  }
}

.vital-row__label {
  color: var(--nl-text-2);
}

.vital-row__value {
  color: var(--nl-text-1);
  font-family: var(--nl-font-num);
  font-size: var(--nl-font-h2);
  font-weight: 650;
}

.vital-row__time {
  margin-left: auto;
  color: var(--nl-text-3);
}

</style>
