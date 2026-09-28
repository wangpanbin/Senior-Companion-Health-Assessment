<script setup>
/**
 * NlNoticeBar · 提示条
 *
 * tone: primary / warning / success / danger
 * 内容 slot 例如：本服务为线下结算
 */
defineProps({
  tone: { type: String, default: 'primary' },
  icon: { type: Boolean, default: true }
})
</script>

<template>
  <div :class="['nl-noticebar', `is-${tone}`]" role="status">
    <svg v-if="icon" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="8" cy="8" r="7" />
      <path d="M8 5v3.5" />
      <circle cx="8" cy="11" r="0.6" fill="currentColor" />
    </svg>
    <span class="nl-noticebar__text">
      <slot />
    </span>
    <slot name="extra" />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-noticebar {
  display: flex;
  align-items: center;
  gap: var(--nl-space-2);
  padding: var(--nl-space-3) var(--nl-space-4);
  font-size: var(--nl-font-caption);
  border-radius: var(--nl-radius-card);

  // 文字色走 *-text 令牌：原色在自身浅底上只有 2.1-4.3:1，全部达不到 AA
  &.is-primary { color: var(--nl-primary-text);  background: var(--nl-primary-ghost); }
  &.is-warning { color: var(--nl-warning-text); background: var(--nl-warning-bg); }
  &.is-success { color: var(--nl-success-text); background: var(--nl-success-bg); }
  &.is-danger  { color: var(--nl-danger-text);  background: var(--nl-danger-bg); }

  &__text {
    flex: 1;
  }

  svg {
    flex-shrink: 0;
  }
}
</style>
