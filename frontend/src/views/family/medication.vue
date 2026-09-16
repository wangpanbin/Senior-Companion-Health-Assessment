<script setup>
/**
 * 用药管理家属版（M-12 + M-13 · design.md §4 · PRD §2.2 §6.1 合规红线）
 *
 * 铁律：
 *   - 药品卡不出现「建议服用 / 推荐剂量 / 诊断为 / 对症」等表述
 *   - 剂量输入框 label = 「家属录入剂量」
 *   - caption = 「剂量由家属按医嘱录入，系统不提供建议」
 *   - 页面底部固定免责声明条（NlComplianceBar）
 *
 * 视图：3 个 tab 切换
 *   1. 日历（月历 + 服药圆点）
 *   2. 今日（药品卡 + 确认服药按钮）
 *   3. 计划（用药计划列表 + 「+ 新建计划」）
 */
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  NlPhoneShell, NlCard, NlStatusChip, NlComplianceBar, NlNoticeBar, NlEmpty
} from '@/components'

/* ====== 当前月份 / 月历 ====== */
const now = new Date()
const curYear = ref(now.getFullYear())
const curMonth = ref(now.getMonth() + 1) // 1-12

const monthName = computed(() => `${curYear.value} 年 ${curMonth.value} 月`)

function prev() {
  if (curMonth.value === 1) {
    curYear.value -= 1
    curMonth.value = 12
  } else {
    curMonth.value -= 1
  }
}
function next() {
  if (curMonth.value === 12) {
    curYear.value += 1
    curMonth.value = 1
  } else {
    curMonth.value += 1
  }
}

const calendar = computed(() => {
  const first = new Date(curYear.value, curMonth.value - 1, 1)
  const firstDay = first.getDay() // 0-6, 日=0
  const last = new Date(curYear.value, curMonth.value, 0)
  const days = last.getDate()
  // 头部 周一为第一天
  const offset = (firstDay + 6) % 7 // 周一 0, 周日 6

  const list = []
  for (let i = 0; i < offset; i++) list.push(null)
  for (let d = 1; d <= days; d++) {
    const isToday =
      d === now.getDate() &&
      curMonth.value === now.getMonth() + 1 &&
      curYear.value === now.getFullYear()
    // mock：每天 1-3 个服药点
    const seed = (d * 31 + curMonth.value * 7) % 5
    const dotCount = seed > 3 ? 2 : seed > 1 ? 1 : 0
    list.push({ d, isToday, dotCount })
  }
  // 末尾补空填满 6 行
  while (list.length % 7 !== 0) list.push(null)
  return list
})

const weeks = ['一', '二', '三', '四', '五', '六', '日']

/* ====== tab ====== */
const tab = ref('today') // calendar / today / plan

/* ====== 今日药品（mock，M6 上线后由 /api/medication/today?elderId= 提供） ====== */
const todayMeds = ref([
  {
    id: 1,
    name: '阿司匹林',
    spec: '100mg × 30 片',
    desc: '用于心血管保护。本品为通用信息，不构成任何用药建议。',
    schedule: '上午 08:00',
    status: 'TAKEN',
    statusLabel: '已服用',
    takenAt: '08:12'
  },
  {
    id: 2,
    name: '维生素 D',
    spec: '400IU × 60 片',
    desc: '用于补钙。本品为通用信息，不构成任何用药建议。',
    schedule: '中午 12:00',
    status: 'PENDING',
    statusLabel: '待服用'
  },
  {
    id: 3,
    name: '降压药',
    spec: '5mg × 28 片',
    desc: '用于控制高血压。本品为通用信息，不构成任何用药建议。',
    schedule: '晚上 20:00',
    status: 'PENDING',
    statusLabel: '待服用'
  }
])

function confirmTaken(m) {
  ElMessageBox.confirm(
    `确认「${m.name}」已服用？记录后将推送给所有绑定家属。`,
    '确认服药',
    { confirmButtonText: '确认已服' }
  )
    .then(() => {
      m.status = 'TAKEN'
      m.statusLabel = '已服用'
      m.takenAt = new Date().toTimeString().slice(0, 5)
      ElMessage.success('已记录')
    })
    .catch(() => {})
}

function markMissed(m) {
  m.status = 'MISSED'
  m.statusLabel = '漏服'
  ElMessage.warning('已标记漏服，系统将推送家属')
}

/* ====== 计划 ====== */
const plans = ref([
  {
    id: 1, name: '阿司匹林', dose: '1 片', doseSource: '家属按医嘱录入',
    freq: '每日 1 次', period: '长期', startDate: '2026-01-01', endDate: '持续'
  },
  {
    id: 2, name: '维生素 D', dose: '1 片', doseSource: '家属按医嘱录入',
    freq: '每日 1 次', period: '长期', startDate: '2026-03-15', endDate: '持续'
  },
  {
    id: 3, name: '降压药', dose: '5mg', doseSource: '家属按医嘱录入',
    freq: '每日 1 次', period: '长期', startDate: '2026-02-20', endDate: '持续'
  }
])

function newPlan() {
  ElMessage.info('M-13 用药计划创建页后续迭代')
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '用药管理' }">
    <!-- 顶部 Tab -->
    <section class="med-tabs">
      <button
        v-for="t in [
          { key: 'calendar', label: '日历' },
          { key: 'today', label: '今日' },
          { key: 'plan', label: '计划' }
        ]"
        :key="t.key"
        :class="['med-tabs__btn', { 'is-active': tab === t.key }]"
        @click="tab = t.key"
      >
        {{ t.label }}
      </button>
    </section>

    <!-- ==================== 日历视图 ==================== -->
    <NlCard v-if="tab === 'calendar'" class="calendar-card">
      <div class="cal-head">
        <button class="cal-head__nav" aria-label="上个月" @click="prev">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10 4L6 8l4 4" />
          </svg>
        </button>
        <span class="nl-h2 is-num cal-head__title">{{ monthName }}</span>
        <button class="cal-head__nav" aria-label="下个月" @click="next">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M6 4l4 4-4 4" />
          </svg>
        </button>
      </div>

      <div class="cal-weeks">
        <div v-for="w in weeks" :key="w" class="cal-weeks__cell">{{ w }}</div>
      </div>
      <div class="cal-grid">
        <div
          v-for="(cell, i) in calendar"
          :key="i"
          :class="['cal-cell', { 'is-today': cell?.isToday, 'is-empty': !cell }]"
        >
          <template v-if="cell">
            <span class="cal-cell__day is-num">{{ cell.d }}</span>
            <ul v-if="cell.dotCount" class="cal-cell__dots">
              <li v-for="n in cell.dotCount" :key="n" />
            </ul>
          </template>
        </div>
      </div>

      <div class="cal-legend">
        <span><i class="dot is-taken" />已服</span>
        <span><i class="dot is-pending" />待服</span>
        <span><i class="dot is-missed" />漏服</span>
      </div>
    </NlCard>

    <!-- ==================== 今日服药 ==================== -->
    <NlCard v-if="tab === 'today'">
      <template #title>
        <span class="nl-h2">今日服药</span>
      </template>
      <NlNoticeBar tone="warning" icon>
        剂量由家属按医嘱录入，系统不提供任何用药建议；服药确认将推送给所有绑定家属。
      </NlNoticeBar>

      <NlEmpty
        v-if="!todayMeds.length"
        title="今天没有服药任务"
        description="去「计划」tab 新建用药计划"
      />

      <ul v-else class="med-list">
        <li v-for="m in todayMeds" :key="m.id" class="med-item">
          <div class="med-item__head">
            <div>
              <div class="nl-h3">{{ m.name }}</div>
              <div class="nl-caption nl-text-muted">{{ m.spec }} · {{ m.schedule }}</div>
            </div>
            <NlStatusChip :status="m.status" :text="m.statusLabel" :dot="m.status !== 'TAKEN'" />
          </div>
          <p class="nl-caption nl-text-muted med-item__desc">{{ m.desc }}</p>
          <div class="med-item__row">
            <span v-if="m.status === 'TAKEN'" class="nl-caption nl-text-muted">记录时间 {{ m.takenAt }}</span>
            <span v-else></span>
            <div class="med-item__act">
              <el-button
                v-if="m.status !== 'TAKEN'"
                size="small"
                plain
                round
                @click="markMissed(m)"
              >
                标记漏服
              </el-button>
              <el-button
                type="primary"
                round
                size="small"
                :disabled="m.status === 'TAKEN'"
                @click="confirmTaken(m)"
              >
                {{ m.status === 'TAKEN' ? '已确认' : '确认已服' }}
              </el-button>
            </div>
          </div>
        </li>
      </ul>
    </NlCard>

    <!-- ==================== 计划列表 ==================== -->
    <NlCard v-if="tab === 'plan'" plain>
      <template #title>
        <span class="nl-h2">用药计划</span>
      </template>
      <p class="nl-caption nl-text-muted plan-tip">
        剂量字段为家属录入，仅作提醒用途，不构成任何用药建议。
      </p>
      <ul class="plan-list">
        <li v-for="p in plans" :key="p.id" class="plan-item">
          <div class="plan-item__head">
            <span class="nl-h3">{{ p.name }}</span>
            <el-tag size="small" type="info" effect="plain">{{ p.freq }}</el-tag>
          </div>
          <dl class="plan-item__dl">
            <dt>剂量</dt><dd>{{ p.dose }} <small>（{{ p.doseSource }}）</small></dd>
            <dt>起止</dt><dd class="is-num">{{ p.startDate }} ~ {{ p.endDate }}</dd>
          </dl>
        </li>
      </ul>
      <el-button type="primary" plain round size="large" class="plan-add" @click="newPlan">
        + 新建用药计划
      </el-button>
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

  &.is-empty {
    height: 48px;
  }

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

.cal-legend {
  display: flex;
  gap: var(--nl-space-3);
  padding: var(--nl-space-2) 0 0;
  font-size: 11px;
  color: var(--nl-text-3);

  .dot {
    display: inline-block;
    width: 8px;
    height: 8px;
    margin-right: 4px;
    border-radius: 50%;
  }
  .is-taken { background: var(--nl-success); }
  .is-pending { background: var(--nl-warning); }
  .is-missed { background: var(--nl-danger); }
}

.med-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.med-item {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }

  &__desc {
    margin: var(--nl-space-2) 0 var(--nl-space-3);
    line-height: 1.6;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__act {
    display: flex;
    gap: 6px;
  }
}

.plan-tip {
  margin: 0 0 var(--nl-space-3);
}

.plan-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.plan-item {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
    margin-bottom: var(--nl-space-3);
  }

  &__dl {
    display: grid;
    grid-template-columns: 60px 1fr;
    gap: 4px 12px;
    margin: 0;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);

    dt {
      font-weight: 500;
      color: var(--nl-text-3);
    }

    dd {
      margin: 0;

      small {
        color: var(--nl-text-3);
      }
    }
  }
}

.plan-add {
  width: 100%;
  margin-top: var(--nl-space-3);
}
</style>
