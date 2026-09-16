<script setup>
/**
 * NlStepHeader · 三步步骤条（design.md §4 · 下单三步）
 *
 * 圆形序号 + 连线：已完成实心主色 / 当前描边主色 / 未开始灰
 * 当前 step 通过 modelValue 传入（从 1 开始）
 */
defineProps({
  modelValue: { type: Number, required: true },
  steps: {
    type: Array,
    default: () => ['选择就诊人', '选择医院 / 时间', '确认订单']
  }
})
</script>

<template>
  <div class="nl-steps">
    <div
      v-for="(label, idx) in steps"
      :key="idx"
      :class="[
        'nl-steps__item',
        {
          'is-done': modelValue > idx + 1,
          'is-current': modelValue === idx + 1,
          'is-todo': modelValue < idx + 1
        }
      ]"
    >
      <span class="nl-steps__dot">
        <svg v-if="modelValue > idx + 1" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 8l3.5 3.5L13 5" />
        </svg>
        <template v-else>{{ idx + 1 }}</template>
      </span>
      <span class="nl-steps__label">{{ label }}</span>
      <span v-if="idx < steps.length - 1" class="nl-steps__line" />
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-steps {
  display: flex;
  align-items: center;
  gap: 0;
  padding: var(--nl-space-5) var(--nl-gutter);
  background: var(--nl-bg-card);
  border-bottom: 1px solid var(--nl-divider);

  &__item {
    display: flex;
    flex: 1;
    align-items: center;
    gap: var(--nl-space-2);

    &:last-child {
      flex: 0 0 auto;
    }
  }

  &__dot {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-3);
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border-strong);
    border-radius: 50%;
  }

  &__label {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    white-space: nowrap;
  }

  &__line {
    flex: 1;
    height: 1.5px;
    margin: 0 var(--nl-space-2);
    background: var(--nl-border-strong);
  }

  &__item.is-done .nl-steps__dot {
    color: var(--nl-text-inverse);
    background: var(--nl-primary);
    border-color: var(--nl-primary);
  }

  &__item.is-done .nl-steps__line {
    background: var(--nl-primary);
  }

  &__item.is-done .nl-steps__label {
    color: var(--nl-text-1);
    font-weight: 500;
  }

  &__item.is-current .nl-steps__dot {
    color: var(--nl-primary);
    border-color: var(--nl-primary);
    background: var(--nl-bg-card);
  }

  &__item.is-current .nl-steps__label {
    color: var(--nl-primary);
    font-weight: 600;
  }
}
</style>
