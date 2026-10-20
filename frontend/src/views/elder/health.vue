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
import { NlPageShell, NlEmpty, NlSkeleton, NlCard } from '@/components'
import { getMyHealthOverview, getMyHealthTimeline } from '@/api/elderHealth'
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

const isEmptyTimeline = computed(
  () => !timelineLoading.value && !timelineError.value && !items.value.length
)

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
    timelineError.value = false
  } catch {
    if (!cursor) {
      items.value = []
      hasMore.value = false
      nextCursor.value = null
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

onMounted(() => {
  loadOverview()
  loadTimeline()
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
          <span class="today__v is-num">{{ overview.medication?.taken ?? 0 }}</span>
          <span class="today__k">今日已服药</span>
          <span v-if="(overview.medication?.pending ?? 0) > 0" class="today__pending">
            还有 {{ overview.medication.pending }} 次没吃
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

          <div v-if="hasMore" class="more">
            <el-button plain round size="small" :loading="timelineLoading" @click="loadMore">
              加载更多
            </el-button>
          </div>
        </NlCard>
      </section>
    </template>
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
  font-size: var(--nl-font-caption);
  color: var(--nl-text-3);
}

.more {
  display: flex;
  justify-content: center;
  padding-top: var(--nl-space-3);
}
</style>
