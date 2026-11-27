<script setup>
/**
 * 某位老人的健康中心（V3.2 · FR-Overview / FR-Timeline · 家属端；V4 · P2-2 体征记录）
 *
 * 数据来源：
 *   - `GET /family/elder/{id}/health/overview`（getElderHealthOverview）→ 概览 + 待处理事项 + 时间线预览
 *   - `GET /family/elder/{id}/health/timeline`（getElderHealthTimeline）→ 健康时间线（游标翻页）
 *   - `GET /family/elder/{id}/vitals` / `.../vitals/trend` → 体征时间线与趋势（V4 · P2-2）
 *
 * 页面只呈现「结论与状态」：就医次数、陪诊人数、用药完成率、漏服、待办、时间线。
 * 所有口径由后端计算（docs/spec/metric-dictionary.md），前端不做再计算。
 * 文案一律是事实陈述，不出现任何健康判断（合规红线-1）。
 *
 * 体征区块只做「记录 + 展示趋势」：不展示任何区间 / 判定标尺，不做任何评价。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPageShell, NlEmpty, NlSkeleton, NlCard, NlComplianceBar } from '@/components'
import VitalSignForm from '@/components/health/VitalSignForm.vue'
import VitalTrendChart from '@/components/health/VitalTrendChart.vue'
import VitalTimeline from '@/components/health/VitalTimeline.vue'
import { getElderHealthOverview, getElderHealthTimeline } from '@/api/elderHealth'
import { getElderVitals, getElderVitalTrend, withdrawElderVital } from '@/api/vitalSign'
import {
  timelineCategoryTone,
  completionRateText,
  eventTimeText
} from '@/utils/elderHealth'
import { confirmThen } from '@/utils/confirm'

const route = useRoute()
const router = useRouter()

const elderId = computed(() => route.params.id)

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

/* ==================== 体征记录（V4 · P2-2） ==================== */

/** 体征时间线每页条数，与后端 PageQuery 默认口径一致 */
const VITAL_PAGE_SIZE = 10

const vitalRecords = ref([])
const vitalsLoading = ref(false)
const vitalsError = ref('')
const vitalsHasMore = ref(false)
const withdrawingVitalId = ref(null)

/** 趋势一次只看一种体征，默认血压 */
const trendType = ref('BLOOD_PRESSURE')
const trend = ref(null)
const trendLoading = ref(false)
const trendError = ref(false)

const trendPoints = computed(() => trend.value?.points || [])

/** 趋势区间文案：只陈述查询范围，不做任何解释 */
const trendRangeText = computed(() => {
  const range = trend.value?.range
  if (!range) return ''
  return `${range.startDate} 至 ${range.endDate} · ${range.days} 天`
})

async function loadVitals(page = 1) {
  vitalsLoading.value = true
  try {
    const data = await getElderVitals(elderId.value, { page, size: VITAL_PAGE_SIZE })
    const records = data?.records || []
    vitalRecords.value = page === 1 ? records : [...vitalRecords.value, ...records]
    vitalsHasMore.value = (data?.pages ?? 0) > page
    vitalsError.value = ''
  } catch (error) {
    if (page === 1) {
      vitalRecords.value = []
      vitalsHasMore.value = false
    }
    vitalsError.value = error?.message || '体征记录加载失败'
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
    trend.value = await getElderVitalTrend(elderId.value, { vitalType: trendType.value })
    trendError.value = false
  } catch {
    trend.value = null
    trendError.value = true
  } finally {
    trendLoading.value = false
  }
}

/** 录入成功：时间线与趋势都要刷新，否则新记录看不到 */
function handleVitalCreated() {
  loadVitals(1)
  loadTrend()
}

/** 家属只能撤回本人代录；陪诊员代录的记录由后端 2010 兜底 */
function canWithdrawVital(record) {
  return record.sourceType === 'FAMILY_MANUAL'
}

/** 撤回需二次确认：撤回后行不留展示，但可重新录入 */
async function handleWithdrawVital(record) {
  const ok = await confirmThen(() =>
    ElMessageBox.confirm(
      '撤回后这条记录将不再显示，可重新录入。确认撤回？',
      '撤回体征记录',
      { confirmButtonText: '撤回', cancelButtonText: '取消', type: 'warning' }
    )
  )
  if (!ok) return
  withdrawingVitalId.value = record.id
  try {
    await withdrawElderVital(record.id)
    ElMessage.success('记录已撤回')
    loadVitals(1)
    loadTrend()
  } finally {
    withdrawingVitalId.value = null
  }
}

async function loadOverview() {
  loading.value = true
  try {
    overview.value = await getElderHealthOverview(elderId.value)
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
    const data = await getElderHealthTimeline(elderId.value, cursor ? { cursor } : undefined)
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
 * 空态也要给出路（P2-18）：「这段时间还没有记录」之后无路可走，
 * 家属多半是看完了这位老人要接着看下一位 —— 回到老人列表。
 */
function goElderList() {
  router.push('/family/elder')
}

onMounted(() => {
  loadOverview()
  loadTimeline()
  loadVitals()
  loadTrend()
})

watch(elderId, () => {
  loadOverview()
  loadTimeline()
  vitalRecords.value = []
  loadVitals()
  trend.value = null
  loadTrend()
})
</script>

<template>
  <NlPageShell :title="overview?.elder?.name || '健康中心'">
    <NlSkeleton v-if="loading" :count="4" class="pad" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="加载失败"
      action-text="重试"
      @action="loadOverview"
    />

    <template v-else-if="overview">
      <!-- 基础信息 -->
      <section class="pad">
        <div class="who">
          <b class="who__name">{{ overview.elder?.name }}</b>
          <span class="nl-caption nl-text-muted">
            {{ overview.elder?.genderLabel }}<template v-if="overview.elder?.age != null"> · {{ overview.elder.age }} 岁</template>
            <template v-if="overview.elder?.relationLabel"> · {{ overview.elder.relationLabel }}</template>
          </span>
        </div>
      </section>

      <!-- 概览：六个客观指标 -->
      <section class="pad">
        <ul role="list" class="grid">
          <li class="metric">
            <span class="metric__v is-num">{{ overview.visit?.orderCount ?? 0 }}</span>
            <span class="metric__k">就医次数</span>
          </li>
          <li class="metric">
            <span class="metric__v is-num">{{ overview.visit?.companionCount ?? 0 }}</span>
            <span class="metric__k">陪诊人数</span>
          </li>
          <li class="metric">
            <span class="metric__v is-num">{{ completionRateText(overview.medication?.completionRate) }}</span>
            <span class="metric__k">用药完成率</span>
          </li>
          <li class="metric">
            <span class="metric__v is-num">{{ overview.medication?.missed ?? 0 }}</span>
            <span class="metric__k">漏服</span>
          </li>
          <li class="metric">
            <span class="metric__v is-num">{{ overview.medication?.taken ?? 0 }}</span>
            <span class="metric__k">已服</span>
          </li>
          <li class="metric">
            <span class="metric__v is-num">{{ overview.medication?.pending ?? 0 }}</span>
            <span class="metric__k">待服</span>
          </li>
        </ul>
      </section>

      <!-- 待处理事项 -->
      <section v-if="overview.pendingItems?.length" class="pad">
        <NlCard title="待处理" :padding="12">
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

      <!-- 体征记录（V4 · P2-2）：只记录数值与时间，不做任何判断 -->
      <section class="pad">
        <div class="sec-head">
          <h2 class="sec-title">体征记录</h2>
          <span class="chip">只记录数值</span>
        </div>
        <p class="sec-sub">血压、血糖、体重按测量时间记录；可撤回后重新录入</p>

        <NlCard title="录入体征" :padding="12" class="vital-block">
          <VitalSignForm mode="family" :elder-id="elderId" @created="handleVitalCreated" />
        </NlCard>

        <NlCard title="趋势" :padding="12" class="vital-block">
          <div class="trend-head">
            <el-radio-group v-model="trendType" size="small" @change="loadTrend">
              <el-radio-button value="BLOOD_PRESSURE">血压</el-radio-button>
              <el-radio-button value="BLOOD_GLUCOSE">血糖</el-radio-button>
              <el-radio-button value="WEIGHT">体重</el-radio-button>
            </el-radio-group>
            <span v-if="trendRangeText" class="trend-range">{{ trendRangeText }}</span>
          </div>

          <el-skeleton v-if="trendLoading" :rows="3" animated />
          <NlEmpty
            v-else-if="trendError"
            type="network"
            description="趋势加载失败"
            action-text="重试"
            @action="loadTrend"
          />
          <NlEmpty
            v-else-if="!trendPoints.length"
            title="这段时间还没有记录"
            description="录入后可在这里按时间查看数值"
          />
          <VitalTrendChart v-else :trend="trend" />
        </NlCard>

        <NlCard title="体征时间线" :padding="12" class="vital-block">
          <VitalTimeline
            :records="vitalRecords"
            :loading="vitalsLoading"
            :error="vitalsError"
            :has-more="vitalsHasMore"
            :withdrawable="canWithdrawVital"
            :withdrawing-id="withdrawingVitalId"
            @load-more="loadMoreVitals"
            @retry="loadVitals(1)"
            @withdraw="handleWithdrawVital"
          />
        </NlCard>
      </section>

      <!-- 健康时间线 -->
      <section class="pad">
        <NlCard title="健康时间线" :padding="12">
          <NlSkeleton v-if="timelineLoading && !items.length" :count="3" />

          <NlEmpty
            v-else-if="timelineError && !items.length"
            type="network"
            title="时间线加载失败"
            action-text="重试"
            @action="loadTimeline()"
          />

          <NlEmpty
            v-else-if="isEmptyTimeline"
            title="这段时间还没有记录"
            action-text="返回老人列表"
            @action="goElderList"
          />

          <ul v-else role="list" class="events">
            <li v-for="e in items" :key="e.eventId" class="event" :class="`is-${timelineCategoryTone(e.category).key}`">
              <div class="event__head">
                <span class="event__cat">{{ e.categoryLabel }}</span>
                <span class="event__time is-num" :title="e.occurredAt">{{ eventTimeText(e.occurredAt) }}</span>
              </div>
              <div class="event__title">
                {{ e.title }}
                <span v-if="e.abnormal" class="event__flag">异常</span>
              </div>
              <div v-if="e.detail" class="event__detail">{{ e.detail }}</div>
            </li>
          </ul>

          <p v-if="truncated" class="nl-caption nl-text-weak trunc-note">
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

.who {
  display: flex;
  align-items: baseline;
  gap: var(--nl-space-2);
  padding: var(--nl-space-4) 0 var(--nl-space-2);
}

.who__name {
  font-size: var(--nl-font-h2);
  color: var(--nl-text-1);
}

.grid {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-2);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  padding: var(--nl-space-4);
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.metric__v {
  font-size: var(--nl-font-h2);
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
  color: var(--nl-text-1);
}

.todo__count {
  color: var(--nl-warning);
  font-weight: 600;
}

.events {
  list-style: none;
  padding: 0;
  margin: 0;
}

.event {
  padding: var(--nl-space-3) 0 var(--nl-space-3) var(--nl-space-3);
  border-left: 3px solid var(--nl-border-strong);
  border-bottom: 1px solid var(--nl-divider);

  &:last-child {
    border-bottom: none;
  }

  &.is-order {
    border-left-color: var(--nl-primary);
  }

  &.is-checkin {
    border-left-color: var(--nl-info);
  }

  &.is-medication {
    border-left-color: var(--nl-success);
  }

  &.is-message {
    border-left-color: var(--nl-text-2);
  }
}

.event__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--nl-space-2);
}

.event__cat {
  font-size: var(--nl-font-body);
  color: var(--nl-text-2);
}

.event__time {
  font-size: var(--nl-font-body);
  color: var(--nl-text-2);
}

.event__title {
  margin-top: 2px;
  color: var(--nl-text-1);
}

.event__flag {
  margin-left: var(--nl-space-2);
  font-size: var(--nl-font-body);
  color: var(--nl-warning);
}

.event__detail {
  margin-top: 2px;
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

/* ---------- 体征区块（V4 · P2-2） ---------- */

.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--nl-space-2);
  padding-top: var(--nl-space-4);
}

.sec-title {
  margin: 0;
  font-size: var(--nl-font-h2);
  color: var(--nl-text-1);
}

.chip {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 var(--nl-space-2);
  border-radius: var(--nl-radius-chip);
  background: var(--nl-primary-light);
  color: var(--nl-primary-dark);
  font-size: var(--nl-font-caption);
  white-space: nowrap;
}

.sec-sub {
  margin: var(--nl-space-1) 0 var(--nl-space-3);
  color: var(--nl-text-2);
  font-size: var(--nl-font-caption);
  line-height: 18px;
}

.vital-block + .vital-block {
  margin-top: var(--nl-space-3);
}

.trend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--nl-space-2);
  margin-bottom: var(--nl-space-3);
}

.trend-range {
  color: var(--nl-text-3);
  font-size: var(--nl-font-caption);
}
</style>
