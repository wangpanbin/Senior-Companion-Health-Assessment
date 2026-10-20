<script setup>
/**
 * 某位老人的健康中心（V3.2 · FR-Overview / FR-Timeline · 家属端）
 *
 * 数据来源：
 *   - `GET /family/elder/{id}/health/overview`（getElderHealthOverview）→ 概览 + 待处理事项 + 时间线预览
 *   - `GET /family/elder/{id}/health/timeline`（getElderHealthTimeline）→ 健康时间线（游标翻页）
 *
 * 页面只呈现「结论与状态」：就医次数、陪诊人数、用药完成率、漏服、待办、时间线。
 * 所有口径由后端计算（docs/spec/metric-dictionary.md），前端不做再计算。
 * 文案一律是事实陈述，不出现任何健康判断（合规红线-1）。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NlPageShell, NlEmpty, NlSkeleton, NlCard } from '@/components'
import { getElderHealthOverview, getElderHealthTimeline } from '@/api/elderHealth'
import {
  timelineCategoryTone,
  completionRateText,
  eventTimeText
} from '@/utils/elderHealth'

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

onMounted(() => {
  loadOverview()
  loadTimeline()
})

watch(elderId, () => {
  loadOverview()
  loadTimeline()
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
</style>
