<script setup>
/**
 * 家庭健康协同首页（V3.2 · FR-Summary）
 *
 * 数据来源：
 *   - `GET /family/health/summary`（getFamilyHealthSummary）→ 当前家属名下每位老人的轻量摘要
 *
 * 页面只呈现「结论与状态」：今日用药、近期就医、待处理条数。
 * 口径与算法全在后端（docs/spec/metric-dictionary.md），前端不做任何再计算。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NlPageShell, NlEmpty, NlSkeleton } from '@/components'
import { getFamilyHealthSummary } from '@/api/elderHealth'
import { todayMedicationText } from '@/utils/elderHealth'

const router = useRouter()

const summaries = ref([])
const loading = ref(false)
/** R4：加载失败必须与「还没绑定老人」的空态区分开 */
const loadError = ref(false)

const isEmpty = computed(() => !loading.value && !loadError.value && !summaries.value.length)

async function loadSummary() {
  loading.value = true
  try {
    const data = await getFamilyHealthSummary()
    summaries.value = Array.isArray(data) ? data : []
    loadError.value = false
  } catch {
    summaries.value = []
    loadError.value = true
  } finally {
    loading.value = false
  }
}

function open(elderId) {
  router.push(`/family/elder/${elderId}/health`)
}

function bind() {
  router.push('/family/elder/bind')
}

onMounted(loadSummary)
</script>

<template>
  <NlPageShell title="家庭健康" :back="false">
    <NlSkeleton v-if="loading" :count="3" class="pad" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="加载失败"
      action-text="重试"
      @action="loadSummary"
    />

    <NlEmpty
      v-else-if="isEmpty"
      title="还没有绑定老人"
      action-text="去绑定"
      @action="bind"
    />

    <ul v-else role="list" class="cards">
      <li
        v-for="s in summaries"
        :key="s.elderId"
        role="button"
        tabindex="0"
        class="card"
        @click="open(s.elderId)"
        @keydown.enter.prevent="open(s.elderId)"
        @keydown.space.prevent="open(s.elderId)"
      >
        <div class="card__head">
          <b class="card__name">{{ s.elderName }}</b>
          <span class="nl-caption nl-text-muted">{{ s.relationLabel }}</span>
        </div>
        <div class="card__stats">
          <div class="stat">
            <span class="stat__v is-num">{{ todayMedicationText(s.todayMedication) }}</span>
            <span class="stat__k">今日用药</span>
          </div>
          <div class="stat">
            <span class="stat__v is-num">{{ s.recentVisitCount ?? 0 }}</span>
            <span class="stat__k">近期就医</span>
          </div>
          <div class="stat" :class="{ 'is-alert': (s.pendingItemCount ?? 0) > 0 }">
            <span class="stat__v is-num">{{ s.pendingItemCount ?? 0 }}</span>
            <span class="stat__k">待处理</span>
          </div>
        </div>
      </li>
    </ul>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: var(--nl-gutter);
}

.cards {
  list-style: none;
  padding: var(--nl-gutter);
  margin: 0;
  display: grid;
  gap: var(--nl-gap-item);
}

.card {
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  padding: var(--nl-space-4);
  cursor: pointer;

  &:focus-visible {
    outline: 2px solid var(--nl-primary);
    outline-offset: 2px;
  }
}

.card__head {
  display: flex;
  align-items: baseline;
  gap: var(--nl-space-2);
  margin-bottom: var(--nl-space-3);
}

.card__name {
  font-size: var(--nl-font-h3);
  color: var(--nl-text-1);
}

.card__stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-2);
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat__v {
  font-size: var(--nl-font-h2);
  color: var(--nl-text-1);
}

.stat__k {
  font-size: var(--nl-font-body);
  color: var(--nl-text-2);
}

.stat.is-alert .stat__v {
  color: var(--nl-warning);
}
</style>
