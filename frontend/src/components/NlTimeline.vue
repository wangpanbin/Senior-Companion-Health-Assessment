<script setup>
/**
 * NlTimeline · 6 节点时间线（design.md §4 · M11 陪诊员端）
 *
 * 节点状态：done / current / todo
 * done: 实心主色圆点 + 完成时间
 * current: 脉冲描边圆点（不显示完成时间，按钮由外部 slot 提供）
 * todo: 灰色圆点
 *
 * 铁律：仅当前节点可点，未完成不可回退
 */
defineProps({
  /** 节点数组：[{ key, label, status, time }] */
  steps: {
    type: Array,
    required: true
  }
})
const emit = defineEmits(['click'])
</script>

<template>
  <ol class="nl-timeline">
    <li
      v-for="(s, i) in steps"
      :key="s.key"
      :class="['nl-timeline__node', `is-${s.status}`]"
    >
      <div class="nl-timeline__dotcol">
        <span class="nl-timeline__dot">
          <svg v-if="s.status === 'done'" viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 8l3.5 3.5L13 5" />
          </svg>
        </span>
        <span v-if="i < steps.length - 1" class="nl-timeline__line" />
      </div>
      <div class="nl-timeline__body">
        <div class="nl-timeline__row">
          <span class="nl-h3 nl-timeline__label">{{ s.label }}</span>
          <span v-if="s.time" class="nl-caption is-num nl-timeline__time">{{ s.time }}</span>
        </div>
        <p v-if="s.description" class="nl-caption nl-timeline__desc">{{ s.description }}</p>
        <div v-if="$slots[s.key] || (s.status === 'current' && $slots.current)" class="nl-timeline__action">
          <slot :name="s.key" :step="s" />
        </div>
      </div>
    </li>
  </ol>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-timeline {
  list-style: none;
  padding: 0;
  margin: 0;

  &__node {
    display: grid;
    grid-template-columns: 36px 1fr;
    gap: var(--nl-space-3);
    padding: 0 0 var(--nl-space-5) 0;
  }

  &__dotcol {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  &__dot {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    color: var(--nl-text-inverse);
    background: var(--nl-border-strong);
    border-radius: 50%;

    svg {
      stroke: var(--nl-text-inverse);
    }
  }

  &__line {
    flex: 1;
    width: 2px;
    margin-top: 4px;
    background: var(--nl-divider);
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }

  &__label {
    color: var(--nl-text-2);
  }

  &__time {
    color: var(--nl-text-3);
  }

  &__desc {
    margin: 4px 0 0 0;
    color: var(--nl-text-2);
  }

  &__action {
    margin-top: var(--nl-space-2);
  }

  &__node.is-done .nl-timeline__dot {
    background: var(--nl-primary);
  }
  &__node.is-done .nl-timeline__label {
    color: var(--nl-text-1);
  }
  &__node.is-done .nl-timeline__line {
    background: var(--nl-primary);
  }

  &__node.is-current .nl-timeline__dot {
    color: var(--nl-primary);
    background: var(--nl-bg-card);
    border: 2px solid var(--nl-primary);
    box-shadow: 0 0 0 6px rgba(29, 111, 242, 0.12);
    animation: nl-timeline-pulse 1.6s ease-in-out infinite;
  }
  &__node.is-current .nl-timeline__label {
    color: var(--nl-primary);
    font-weight: 600;
  }
}

@keyframes nl-timeline-pulse {
  0%, 100% {
    box-shadow: 0 0 0 6px rgba(29, 111, 242, 0.12);
  }
  50% {
    box-shadow: 0 0 0 10px rgba(29, 111, 242, 0);
  }
}
</style>
