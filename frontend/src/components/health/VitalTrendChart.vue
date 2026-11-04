<script setup>
/**
 * VitalTrendChart · 体征趋势折线（V4 · P2-2）
 *
 * 内联 SVG 绘制，不引入任何图表依赖。
 *
 * 合规红线：
 *   1. 不画阈值线、目标线、区间带 —— 画出来就等于在判定；
 *   2. 点位不给颜色分级，只用同一个主色描线；
 *   3. 文案只陈述「记录了多少个点」，不评价趋势走向。
 */
import { computed } from 'vue'

const props = defineProps({
  /** GET .../vitals/trend 返回的 VitalTrendVO */
  trend: { type: Object, default: null }
})

const PLOT = { x0: 44, x1: 344, y0: 16, y1: 124 }

const points = computed(() => props.trend?.points || [])

/** 血压画两条线（收缩压 / 舒张压），其它体征画一条 */
const series = computed(() => {
  if (props.trend?.vitalType === 'BLOOD_PRESSURE') {
    return [
      { key: 'systolic', label: '收缩压', className: 'primary' },
      { key: 'diastolic', label: '舒张压', className: 'soft' }
    ]
  }
  const key = props.trend?.vitalType === 'BLOOD_GLUCOSE' ? 'glucoseValue' : 'weightKg'
  return [{ key, label: props.trend?.vitalTypeLabel || '', className: 'primary' }]
})

/** 所有数值共用一把尺子，避免两条线各自缩放导致视觉误导 */
const scale = computed(() => {
  const values = series.value.flatMap((item) =>
    points.value.map((point) => Number(point[item.key])).filter((value) => Number.isFinite(value))
  )
  if (values.length === 0) return { min: 0, max: 1 }
  const min = Math.min(...values)
  const max = Math.max(...values)
  if (min === max) {
    const delta = Math.abs(min) * 0.1 || 1
    return { min: min - delta, max: max + delta }
  }
  return { min, max }
})

/** 纵轴四条网格线的数值（上 → 下） */
const ticks = computed(() =>
  [0, 1, 2, 3].map((index) => {
    const value = scale.value.max - (index / 3) * (scale.value.max - scale.value.min)
    const rounded = Math.round(value * 10) / 10
    return { y: PLOT.y0 + (index / 3) * (PLOT.y1 - PLOT.y0), label: `${rounded}` }
  })
)

/** 横轴三个日期标签（首 / 中 / 末） */
const xLabels = computed(() => {
  const total = points.value.length
  if (total === 0) return []
  const indexes = total <= 2 ? [0, total - 1] : [0, Math.floor((total - 1) / 2), total - 1]
  return [...new Set(indexes)].map((index) => ({
    x: xOf(index, total),
    label: dateLabel(points.value[index]?.measuredAt)
  }))
})

/** 折线坐标串 */
function polyline(item) {
  const total = points.value.length
  return points.value
    .map((point, index) => `${xOf(index, total)},${yOf(Number(point[item.key]))}`)
    .join(' ')
}

function xOf(index, total) {
  if (total <= 1) return (PLOT.x0 + PLOT.x1) / 2
  return PLOT.x0 + (index * (PLOT.x1 - PLOT.x0)) / (total - 1)
}

function yOf(value) {
  const { min, max } = scale.value
  return PLOT.y1 - ((value - min) / (max - min)) * (PLOT.y1 - PLOT.y0)
}

/** '2026-10-06 08:10:00' → '10/06' */
function dateLabel(measuredAt) {
  if (!measuredAt) return ''
  const day = String(measuredAt).slice(5, 10)
  return day.replace('-', '/')
}

const ariaLabel = computed(() => {
  const range = props.trend?.range
  const span = range ? `${range.startDate} 至 ${range.endDate}` : ''
  return `${span} ${props.trend?.vitalTypeLabel || ''}已记录测量数值折线图，共 ${points.value.length} 个点`
})
</script>

<template>
  <div class="vital-chart">
    <svg
      v-if="points.length > 0"
      class="vital-chart__svg"
      viewBox="0 0 360 168"
      role="img"
      :aria-label="ariaLabel"
      xmlns="http://www.w3.org/2000/svg"
    >
      <!-- 网格：仅作读数辅助，不带任何区间含义 -->
      <g class="vital-chart__grid">
        <line
          v-for="tick in ticks"
          :key="`grid-${tick.y}`"
          :x1="PLOT.x0"
          :x2="PLOT.x1"
          :y1="tick.y"
          :y2="tick.y"
        />
      </g>
      <g class="vital-chart__ticks">
        <text v-for="tick in ticks" :key="`tick-${tick.y}`" :x="PLOT.x0 - 8" :y="tick.y + 4">
          {{ tick.label }}
        </text>
      </g>

      <!-- 折线 -->
      <g v-for="item in series" :key="item.key">
        <polyline
          class="vital-chart__line"
          :class="`vital-chart__line--${item.className}`"
          :points="polyline(item)"
        />
        <circle
          v-for="(point, index) in points"
          :key="`${item.key}-${index}`"
          class="vital-chart__dot"
          :class="`vital-chart__dot--${item.className}`"
          :cx="xOf(index, points.length)"
          :cy="yOf(Number(point[item.key]))"
          r="3.2"
        >
          <title>{{ `${point.measuredAt} · ${point.valueText}` }}</title>
        </circle>
      </g>

      <!-- 横轴日期 -->
      <g class="vital-chart__dates">
        <text v-for="(label, index) in xLabels" :key="`date-${index}`" :x="label.x" y="150">
          {{ label.label }}
        </text>
      </g>
    </svg>

    <div v-if="series.length > 1" class="vital-chart__legend">
      <span v-for="item in series" :key="`legend-${item.key}`">
        <i :class="`vital-chart__swatch vital-chart__swatch--${item.className}`"></i>
        {{ item.label }}
      </span>
    </div>

    <p class="vital-chart__note">
      共 {{ points.length }} 个点
      <template v-if="trend?.truncated">；区间内记录较多，仅展示最近 500 个点</template>
    </p>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.vital-chart {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-2);

  &__svg {
    display: block;
    width: 100%;
    height: auto;
    background: var(--nl-bg-sunken);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-chip);
  }

  &__grid line {
    stroke: var(--nl-border);
    stroke-width: 1;
    stroke-dasharray: 3 4;
  }

  &__ticks text {
    fill: var(--nl-text-3);
    font-size: var(--nl-font-micro);
    font-family: var(--nl-font-num);
    text-anchor: end;
  }

  &__line {
    fill: none;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;

    &--primary {
      stroke: var(--nl-primary);
    }

    &--soft {
      stroke: var(--nl-primary);
      stroke-opacity: 0.45;
    }
  }

  &__dot {
    &--primary {
      fill: var(--nl-primary);
    }

    &--soft {
      fill: var(--nl-primary);
      fill-opacity: 0.5;
    }
  }

  &__dates text {
    fill: var(--nl-text-3);
    font-size: var(--nl-font-micro);
    text-anchor: middle;
  }

  &__legend {
    display: flex;
    flex-wrap: wrap;
    gap: var(--nl-space-4);
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }

  &__swatch {
    display: inline-block;
    width: 8px;
    height: 8px;
    margin-right: 6px;
    border-radius: 50%;
    background: var(--nl-primary);

    &--soft {
      opacity: 0.45;
    }
  }

  &__note {
    margin: 0;
    color: var(--nl-text-3);
    font-size: var(--nl-font-caption);
    line-height: 18px;
  }
}
</style>
