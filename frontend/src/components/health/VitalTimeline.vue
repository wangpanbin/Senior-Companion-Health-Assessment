<script setup>
/**
 * VitalTimeline · 体征时间线（V4 · P2-2）
 *
 * 按日期分组、按测量时间倒序展示。每条只显示「数值 / 单位 / 时间 / 录入来源」，
 * 不附带任何解释性文案（合规红线：只记录事实）。
 *
 * 撤回按钮由父级用 `withdrawable(record)` 决定是否出现：
 * 家属只能撤回本人代录，陪诊员只能撤回本单在服务中的代录。
 */
import { computed } from 'vue'
import NlEmpty from '@/components/NlEmpty.vue'

const props = defineProps({
  records: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  /** 请求失败时的错误文案；非空即展示错误态 */
  error: { type: String, default: '' },
  /** 是否还有下一页 */
  hasMore: { type: Boolean, default: false },
  /** (record) => boolean，决定该条是否显示撤回 */
  withdrawable: { type: Function, default: null },
  /** 正在撤回的记录 ID，用于按钮 loading */
  withdrawingId: { type: [String, Number], default: null },
  emptyTitle: { type: String, default: '还没有体征记录' },
  emptyDescription: { type: String, default: '录入测量数值后，会按时间记录在这里' }
})

const emit = defineEmits(['load-more', 'retry', 'withdraw'])

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

/** 今天的日期串，用于「今天」前缀 */
const todayText = computed(() => dateText(new Date()))

function dateText(date) {
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** '2026-11-03' → '今天 · 11 月 3 日' / '11 月 3 日 周一' */
function dayHeading(dateString) {
  if (!dateString) return ''
  const monthDay = `${Number(dateString.slice(5, 7))} 月 ${Number(dateString.slice(8, 10))} 日`
  if (dateString === todayText.value) return `今天 · ${monthDay}`
  const weekday = WEEKDAYS[new Date(`${dateString}T00:00:00`).getDay()]
  return `${monthDay} ${weekday}`
}

/** 按测量日期分组，保持后端返回的倒序 */
const groups = computed(() => {
  const grouped = []
  for (const record of props.records) {
    const dateString = String(record.measuredAt || '').slice(0, 10)
    const last = grouped[grouped.length - 1]
    if (last && last.date === dateString) {
      last.items.push(record)
    } else {
      grouped.push({ date: dateString, label: dayHeading(dateString), items: [record] })
    }
  }
  return grouped
})

/** '2026-11-03 08:10:00' → '08:10' */
function timeText(measuredAt) {
  return String(measuredAt || '').slice(11, 16)
}

/** 把 valueText 拆成「数值」与「单位 + 场景」，让数字更醒目 */
function valueParts(record) {
  const text = String(record.valueText || '')
  const unit = String(record.unit || '')
  if (!unit) return { value: text, suffix: '' }
  const index = text.indexOf(` ${unit}`)
  if (index < 0) return { value: text, suffix: '' }
  return { value: text.slice(0, index), suffix: text.slice(index + 1) }
}

function canWithdraw(record) {
  return typeof props.withdrawable === 'function' && props.withdrawable(record)
}

function typeClass(record) {
  if (record.vitalType === 'BLOOD_GLUCOSE') return 'glucose'
  if (record.vitalType === 'WEIGHT') return 'weight'
  return 'pressure'
}
</script>

<template>
  <div class="vital-timeline">
    <el-skeleton v-if="loading" :rows="3" animated />

    <NlEmpty
      v-else-if="error"
      type="network"
      :description="error"
      action-text="重试"
      @action="emit('retry')"
    />

    <NlEmpty v-else-if="records.length === 0" :title="emptyTitle" :description="emptyDescription" />

    <template v-else>
      <section v-for="group in groups" :key="group.date" class="vital-timeline__group">
        <h4 class="vital-timeline__day">{{ group.label }}</h4>
        <ul class="vital-timeline__list">
          <li v-for="record in group.items" :key="record.id" class="vital-timeline__item">
            <span class="vital-timeline__icon" :class="`vital-timeline__icon--${typeClass(record)}`">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <template v-if="record.vitalType === 'BLOOD_GLUCOSE'">
                  <path
                    d="M12 4c3 4 5.5 6.6 5.5 9.6A5.5 5.5 0 0 1 12 19a5.5 5.5 0 0 1-5.5-5.4C6.5 10.6 9 8 12 4Z"
                    stroke="currentColor"
                    stroke-width="1.6"
                  />
                </template>
                <template v-else-if="record.vitalType === 'WEIGHT'">
                  <rect
                    x="4"
                    y="6"
                    width="16"
                    height="12"
                    rx="4"
                    stroke="currentColor"
                    stroke-width="1.6"
                  />
                  <path
                    d="M9 10a3 3 0 0 1 6 0"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                  />
                </template>
                <template v-else>
                  <path
                    d="M3 12h4l2-4.5 3 9 2-4.5h7"
                    stroke="currentColor"
                    stroke-width="1.6"
                    stroke-linecap="round"
                  />
                </template>
              </svg>
            </span>

            <div class="vital-timeline__body">
              <div class="vital-timeline__top">
                <b>{{ record.vitalTypeLabel }}</b>
                <span class="vital-timeline__value">
                  {{ valueParts(record).value }}
                  <small>{{ valueParts(record).suffix }}</small>
                </span>
              </div>
              <p class="vital-timeline__meta">
                {{ timeText(record.measuredAt) }} · {{ record.sourceTypeLabel }}
                <template v-if="record.orderId"> · 订单 {{ record.orderId }}</template>
              </p>
            </div>

            <el-button
              v-if="canWithdraw(record)"
              link
              type="danger"
              class="vital-timeline__withdraw"
              :loading="withdrawingId === record.id"
              @click="emit('withdraw', record)"
            >
              撤回
            </el-button>
          </li>
        </ul>
      </section>

      <div v-if="hasMore" class="vital-timeline__more">
        <el-button plain round :loading="loading" @click="emit('load-more')">查看更多</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.vital-timeline {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  &__day {
    margin: 0 0 var(--nl-space-2);
    color: var(--nl-text-3);
    font-size: var(--nl-font-caption);
    font-weight: 400;
    letter-spacing: 0.3px;
  }

  &__list {
    margin: 0;
    padding: 0;
    list-style: none;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: var(--nl-space-3);
    min-height: 56px;
    padding: var(--nl-space-3) 0;
    border-bottom: 1px solid var(--nl-border);

    &:last-child {
      border-bottom: 0;
      padding-bottom: var(--nl-space-1);
    }
  }

  &__icon {
    display: grid;
    place-items: center;
    flex: none;
    width: 38px;
    height: 38px;
    border-radius: 10px;
    background: var(--nl-primary-ghost);
    color: var(--nl-primary-dark);

    svg {
      width: 20px;
      height: 20px;
    }

    &--glucose {
      background: var(--nl-warning-bg);
      color: var(--nl-warning-text);
    }

    &--weight {
      background: var(--nl-success-bg);
      color: var(--nl-success-text);
    }
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__top {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: var(--nl-space-2);

    b {
      font-size: var(--nl-font-body);
      font-weight: 600;
    }
  }

  &__value {
    font-family: var(--nl-font-num);
    font-size: var(--nl-font-h3);
    font-weight: 650;
    color: var(--nl-text-1);

    small {
      margin-left: 2px;
      color: var(--nl-text-2);
      font-family: var(--nl-font-cn);
      font-size: var(--nl-font-caption);
      font-weight: 400;
    }
  }

  &__meta {
    margin: 2px 0 0;
    color: var(--nl-text-3);
    font-size: var(--nl-font-caption);
    line-height: 18px;
  }

  &__withdraw {
    flex: none;
    font-size: var(--nl-font-caption);
  }

  &__more {
    display: flex;
    justify-content: center;
    padding-top: var(--nl-space-1);
  }
}
</style>
