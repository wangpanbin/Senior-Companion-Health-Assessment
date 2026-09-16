<script setup>
/**
 * 老人端用药管理（只读视图 · M-12 ELDER 视角）
 *
 * 与 family/medication.vue 同结构但只读：
 * - 不能切换 Tab 到「计划」（隐藏）
 * - 服药确认按钮替换为「请家属帮您确认」
 * - 同样挂底部 NlComplianceBar
 */
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlCard, NlStatusChip, NlNoticeBar, NlComplianceBar } from '@/components'

const today = new Date()
const curYear = ref(today.getFullYear())
const curMonth = ref(today.getMonth() + 1)
const monthName = computed(() => `${curYear.value} 年 ${curMonth.value} 月`)

function prev() { curMonth.value === 1 ? (curYear.value--, curMonth.value = 12) : curMonth.value-- }
function next() { curMonth.value === 12 ? (curYear.value++, curMonth.value = 1) : curMonth.value++ }

const calendar = computed(() => {
  const first = new Date(curYear.value, curMonth.value - 1, 1)
  const offset = (first.getDay() + 6) % 7
  const last = new Date(curYear.value, curMonth.value, 0)
  const list = []
  for (let i = 0; i < offset; i++) list.push(null)
  for (let d = 1; d <= last.getDate(); d++) {
    const isToday = d === today.getDate() && curMonth.value === today.getMonth() + 1 && curYear.value === today.getFullYear()
    const seed = (d * 31 + curMonth.value * 7) % 5
    list.push({ d, isToday, dotCount: seed > 3 ? 2 : seed > 1 ? 1 : 0 })
  }
  while (list.length % 7 !== 0) list.push(null)
  return list
})

const weeks = ['一', '二', '三', '四', '五', '六', '日']
const tab = ref('today')

const todayMeds = ref([
  { name: '阿司匹林', spec: '100mg', schedule: '08:00', status: 'TAKEN',  label: '已服' },
  { name: '维生素 D', spec: '400IU', schedule: '12:00', status: 'PENDING', label: '待服' },
  { name: '降压药',   spec: '5mg',   schedule: '20:00', status: 'PENDING', label: '待服' }
])
</script>

<template>
  <NlPhoneShell :nav="{ title: '用药管理' }">
    <NlNoticeBar tone="warning">
      您正在使用「老人模式」只读视图，服药确认将由家属代为完成。
    </NlNoticeBar>

    <section class="med-tabs">
      <button :class="['med-tabs__btn', { 'is-active': tab === 'today' }]" @click="tab = 'today'">
        今日
      </button>
      <button :class="['med-tabs__btn', { 'is-active': tab === 'calendar' }]" @click="tab = 'calendar'">
        月历
      </button>
    </section>

    <!-- 月历 -->
    <NlCard v-if="tab === 'calendar'" class="calendar-card">
      <div class="cal-head">
        <button class="cal-head__nav" @click="prev">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10 4L6 8l4 4" />
          </svg>
        </button>
        <span class="nl-h2 is-num cal-head__title">{{ monthName }}</span>
        <button class="cal-head__nav" @click="next">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M6 4l4 4-4 4" />
          </svg>
        </button>
      </div>
      <div class="cal-weeks">
        <div v-for="w in weeks" :key="w" class="cal-weeks__cell">{{ w }}</div>
      </div>
      <div class="cal-grid">
        <div v-for="(c, i) in calendar" :key="i" :class="['cal-cell', { 'is-today': c?.isToday, 'is-empty': !c }]">
          <template v-if="c">
            <span class="cal-cell__day is-num">{{ c.d }}</span>
            <ul v-if="c.dotCount" class="cal-cell__dots">
              <li v-for="n in c.dotCount" :key="n" />
            </ul>
          </template>
        </div>
      </div>
    </NlCard>

    <!-- 今日 -->
    <NlCard v-if="tab === 'today'" title="今日服药">
      <ul class="med-list">
        <li v-for="m in todayMeds" :key="m.name" class="med">
          <div class="med__row">
            <span class="med__time is-num">{{ m.schedule }}</span>
            <span class="med__name">{{ m.name }}</span>
            <span class="med__spec">{{ m.spec }}</span>
            <NlStatusChip :status="m.status" :text="m.label" :dot="m.status === 'PENDING'" />
          </div>
        </li>
      </ul>
      <p class="nl-caption nl-text-muted">
        服药确认请家属在「家属端 · 用药管理」完成。
      </p>
    </NlCard>

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

  &__dots {
    display: flex;
    gap: 2px;
    padding: 0;
    margin: 2px 0 0;
    list-style: none;

    li {
      width: 4px;
      height: 4px;
      background: var(--nl-success);
      border-radius: 50%;
    }
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
</style>
