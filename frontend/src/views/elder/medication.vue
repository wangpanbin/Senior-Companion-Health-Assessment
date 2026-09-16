<script setup>
/**
 * 老人端用药管理（只读视图 · M-12 ELDER 视角）
 *
 * 数据来源（均未被 @PreAuthorize 拦 ELDER，但都需要 elderId）：
 *  - getTodayTasks(elderId)            → 今日待服任务
 *  - getMedicationCalendar({...})      → 服药日历（按月区间，≤31 天）
 *  - listMedicationPlans({...})        → 我的用药计划
 *
 * ⚠️ 合规红线：本页只做「记录与提醒」，不开方、不给用药建议。
 *    严禁出现「建议服用 / 推荐剂量 / 对症 / 可替代」等表述。
 * ⚠️ ELDER 默认只读：不渲染「确认服药」等写操作入口（写操作由家属 / 陪诊员代做）。
 * ⚠️ 后端 NON_NULL：空字段会消失，模板已用可选链 / != null 兜底。
 * ⚠️ elderId 由 `/user/profile`（UserInfoVO）直接下发，仅 ELDER 且已建立档案时有值；
 *    其他角色 / 未建档的 ELDER 该字段不存在（NON_NULL）。为空时页面降级为入口引导空态。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { NlPhoneShell, NlCard, NlStatusChip, NlNoticeBar, NlComplianceBar, NlSkeleton, NlEmpty } from '@/components'
import { getProfile } from '@/api/user'
import { getTodayTasks, getMedicationCalendar, listMedicationPlans } from '@/api/medication'
import { formatTime, today } from '@/utils/format'

const pad2 = (n) => String(n).padStart(2, '0')
const toDateStr = (y, m, d) => `${y}-${pad2(m)}-${pad2(d)}`

/* ===== 当前老人资料（用于取得 elderId） ===== */
const profile = ref({})
const elderId = computed(() => profile.value?.elderId ?? null)
const hasElderId = computed(() => elderId.value != null)

/* ===== Tab ===== */
const tab = ref('today')

/* ===== 今日待服 ===== */
const todayLoading = ref(false)
const todayMeds = ref([])

/* ===== 服药日历 ===== */
const curYear = ref(new Date().getFullYear())
const curMonth = ref(new Date().getMonth() + 1)
const monthName = computed(() => `${curYear.value} 年 ${curMonth.value} 月`)

const calLoading = ref(false)
const calData = ref(null) // MedicationCalendarVO
const calMap = computed(() => {
  const map = {}
  const days = calData.value?.days || []
  for (const d of days) {
    if (!d?.date) continue
    const tasks = d.tasks || []
    let missed = 0, pending = 0, taken = 0
    for (const t of tasks) {
      if (t?.wasMissed || t?.status === 'MISSED') missed++
      else if (t?.status === 'PENDING') pending++
      else taken++
    }
    map[d.date] = { count: tasks.length, missed, pending, taken, hasMissed: missed > 0, hasPending: pending > 0 }
  }
  return map
})

const calendarCells = computed(() => {
  const y = curYear.value
  const m = curMonth.value
  const first = new Date(y, m - 1, 1)
  const offset = (first.getDay() + 6) % 7 // 周一为一周开始
  const daysInMonth = new Date(y, m, 0).getDate()
  const cells = []
  for (let i = 0; i < offset; i++) cells.push(null)
  for (let d = 1; d <= daysInMonth; d++) {
    const ds = toDateStr(y, m, d)
    cells.push({ d, dateStr: ds, isToday: ds === today(), info: calMap.value[ds] || null })
  }
  while (cells.length % 7 !== 0) cells.push(null)
  return cells
})

const weeks = ['一', '二', '三', '四', '五', '六', '日']

function prev() {
  if (curMonth.value === 1) { curYear.value--; curMonth.value = 12 }
  else curMonth.value--
}
function next() {
  if (curMonth.value === 12) { curYear.value++; curMonth.value = 1 }
  else curMonth.value++
}

/* ===== 我的用药计划 ===== */
const planLoading = ref(false)
const plans = ref([])

/* ===== 加载 ===== */
async function loadProfile() {
  try {
    const data = await getProfile()
    if (data) profile.value = data
  } catch {
    // 拦截器已弹错
  }
}

async function loadToday() {
  if (!hasElderId.value) return
  todayLoading.value = true
  try {
    todayMeds.value = (await getTodayTasks(elderId.value)) || []
  } catch {
    todayMeds.value = []
  } finally {
    todayLoading.value = false
  }
}

async function loadCalendar() {
  if (!hasElderId.value) return
  calLoading.value = true
  try {
    const start = toDateStr(curYear.value, curMonth.value, 1)
    const end = toDateStr(curYear.value, curMonth.value, new Date(curYear.value, curMonth.value, 0).getDate())
    calData.value = await getMedicationCalendar({ elderId: elderId.value, startDate: start, endDate: end }) || null
  } catch {
    calData.value = null
  } finally {
    calLoading.value = false
  }
}

async function loadPlans() {
  if (!hasElderId.value) return
  planLoading.value = true
  try {
    const data = await listMedicationPlans({ elderId: elderId.value, page: 1, size: 50 })
    plans.value = data?.records || []
  } catch {
    plans.value = []
  } finally {
    planLoading.value = false
  }
}

watch([curYear, curMonth], () => { if (tab.value === 'calendar') loadCalendar() })

onMounted(async () => {
  await loadProfile()
  if (!hasElderId.value) return // 确实推不出 elderId，全部降级为入口引导
  await Promise.all([loadToday(), loadCalendar(), loadPlans()])
})

/** 日历格子圆点配色：漏服 > 待服 > 已服 */
function dotTone(info) {
  if (!info) return ''
  if (info.hasMissed) return 'danger'
  if (info.hasPending) return 'warning'
  return 'success'
}

const calSummary = computed(() => calData.value?.summary || null)
</script>

<template>
  <NlPhoneShell :nav="{ title: '用药管理' }">
    <NlNoticeBar tone="warning">
      您正在使用「老人模式」只读视图，服药确认将由家属代为完成。
    </NlNoticeBar>

    <section class="med-tabs">
      <button :class="['med-tabs__btn', { 'is-active': tab === 'today' }]" @click="tab = 'today'">今日</button>
      <button :class="['med-tabs__btn', { 'is-active': tab === 'calendar' }]" @click="tab = 'calendar'">月历</button>
      <button :class="['med-tabs__btn', { 'is-active': tab === 'plan' }]" @click="tab = 'plan'">计划</button>
    </section>

    <!-- 入口引导：无 elderId，所有用药数据无法取得 -->
    <NlEmpty
      v-if="!hasElderId"
      type="empty"
      title="暂无用药数据"
      description="您的用药计划由家属代为维护，暂无可展示的用药信息"
    />

    <template v-else>
      <!-- 今日 -->
      <NlCard v-if="tab === 'today'" title="今日服药">
        <NlSkeleton v-if="todayLoading" :count="3" />
        <ul v-else-if="todayMeds.length" class="med-list">
          <li v-for="m in todayMeds" :key="m.id" class="med">
            <div class="med__row">
              <span class="med__time is-num">{{ formatTime(m.planTime) }}</span>
              <span class="med__name">{{ m.medicineName }}</span>
              <span class="med__spec">{{ m.dosage }}</span>
              <NlStatusChip scope="task" :status="m.status" :text="m.statusLabel" :dot="m.status === 'PENDING'" />
            </div>
            <p v-if="m.mealRelationLabel" class="nl-caption nl-text-muted">{{ m.mealRelationLabel }}</p>
          </li>
        </ul>
        <NlEmpty
          v-else
          type="empty"
          title="今日无服药安排"
          description="家属暂未为您安排今天的服药计划"
        />
        <p class="nl-caption nl-text-muted">
          服药确认请家属在「家属端 · 用药管理」完成。
        </p>
      </NlCard>

      <!-- 月历 -->
      <NlCard v-else-if="tab === 'calendar'" class="calendar-card">
        <div class="cal-head">
          <button class="cal-head__nav" @click="prev" aria-label="上个月">
            <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M10 4L6 8l4 4" /></svg>
          </button>
          <span class="nl-h2 is-num cal-head__title">{{ monthName }}</span>
          <button class="cal-head__nav" @click="next" aria-label="下个月">
            <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M6 4l4 4-4 4" /></svg>
          </button>
        </div>

        <div v-if="calSummary" class="cal-summary nl-caption nl-text-muted">
          本月共 {{ calSummary.totalCount ?? 0 }} 次 · 已服 {{ calSummary.takenCount ?? 0 }} · 待服 {{ calSummary.pendingCount ?? 0 }} · 漏服 {{ calSummary.missedCount ?? 0 }}（漏服率 {{ calSummary.missedRate || '0.00%' }}）
        </div>

        <div class="cal-weeks">
          <div v-for="w in weeks" :key="w" class="cal-weeks__cell">{{ w }}</div>
        </div>
        <NlSkeleton v-if="calLoading" :count="5" />
        <div v-else class="cal-grid">
          <div v-for="(c, i) in calendarCells" :key="i" :class="['cal-cell', { 'is-today': c?.isToday, 'is-empty': !c }]">
            <template v-if="c">
              <span class="cal-cell__day is-num">{{ c.d }}</span>
              <i v-if="c.info" :class="['cal-cell__dot', `cal-cell__dot--${dotTone(c.info)}`]" />
            </template>
          </div>
        </div>
      </NlCard>

      <!-- 计划 -->
      <NlCard v-else-if="tab === 'plan'" title="我的用药计划">
        <NlSkeleton v-if="planLoading" :count="3" />
        <ul v-else-if="plans.length" class="plan-list">
          <li v-for="p in plans" :key="p.id" class="plan">
            <div class="plan__row">
              <span class="plan__name">{{ p.medicineName }}</span>
              <NlStatusChip scope="plan" :status="p.status" :text="p.statusLabel" />
            </div>
            <p class="nl-caption nl-text-muted">
              {{ p.dosage }}<template v-if="p.timePoints?.length"> · {{ p.timePoints.join(' / ') }}</template><template v-if="p.mealRelationLabel"> · {{ p.mealRelationLabel }}</template>
            </p>
          </li>
        </ul>
        <NlEmpty
          v-else
          type="empty"
          title="暂无用药计划"
          description="家属暂未为您建立用药计划"
        />
      </NlCard>
    </template>

    <NlComplianceBar text="本页仅提供药品通用信息，不构成任何用药建议，请遵医嘱。" />
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.med-tabs {
  display: flex;
  gap: 6px;
  padding: 0 var(--nl-gutter);

  &__btn {
    flex: 1;
    padding: 10px 0;
    font-size: 14px;
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 10px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-text-inverse);
      background: var(--nl-primary);
      border-color: var(--nl-primary);
    }
  }
}

.cal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--nl-space-3);

  &__nav {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: var(--nl-touch-min);
    height: var(--nl-touch-min);
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 50%;
    cursor: pointer;
  }

  &__title {
    margin: 0;
  }
}

.cal-summary {
  margin-bottom: var(--nl-space-3);
}

.cal-weeks {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  font-size: 11px;
  color: var(--nl-text-3);
  text-align: center;

  &__cell {
    padding: 6px 0;
  }
}

.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
}

.cal-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 48px;
  border-radius: 8px;

  &.is-today {
    background: var(--nl-primary-light);
    border: 1.5px solid var(--nl-primary);

    .cal-cell__day {
      color: var(--nl-primary);
    }
  }

  &__day {
    font-size: 14px;
    color: var(--nl-text-1);
  }

  &__dot {
    width: 6px;
    height: 6px;
    margin-top: 3px;
    border-radius: 50%;

    &--success { background: var(--nl-success); }
    &--warning { background: var(--nl-warning); }
    &--danger  { background: var(--nl-danger); }
  }
}

.med-list {
  padding: 0;
  margin: 0 0 var(--nl-space-3);
  list-style: none;
}

.med {
  padding: var(--nl-space-3) 0;
  border-bottom: 1px solid var(--nl-divider);

  &:last-child {
    border-bottom: none;
  }

  &__row {
    display: grid;
    grid-template-columns: 56px 1fr auto auto;
    gap: var(--nl-space-2);
    align-items: center;
  }

  &__time {
    width: 56px;
    padding: 6px 0;
    font-weight: 600;
    text-align: center;
    background: var(--nl-primary-light);
    color: var(--nl-primary);
    border-radius: 8px;
  }

  &__name {
    font-size: 15px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__spec {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
  }
}

.plan-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
}

.plan {
  padding: var(--nl-space-3);
  background: var(--nl-bg-sunken);
  border-radius: 12px;

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }

  &__name {
    font-size: 15px;
    font-weight: 600;
    color: var(--nl-text-1);
  }
}
</style>
