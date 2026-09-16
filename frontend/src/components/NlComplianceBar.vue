<script setup>
/**
 * NlComplianceBar · 合规底部固定免责声明（design.md §6.1 · PRD §2.2）
 *
 * 用药页 / 药品详情页底部固定（M6 必出）：
 * "本页仅提供药品通用信息，不构成任何用药建议，请遵医嘱。"
 * 同时显示「线下结算」「本服务不提供地图导航」等柔性合规语。
 */
defineProps({
  /** 默认药品免责声明 */
  text: {
    type: String,
    default: '本页仅提供药品通用信息，不构成任何用药建议，请遵医嘱。'
  }
})
</script>

<template>
  <footer class="nl-compliance" role="note">
    <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="8" cy="8" r="7" />
      <path d="M8 5v3.5" />
      <circle cx="8" cy="11" r="0.6" fill="currentColor" />
    </svg>
    <span>{{ text }}</span>
    <slot />
  </footer>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-compliance {
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  gap: var(--nl-space-2);
  padding: var(--nl-space-3) var(--nl-gutter);
  padding-bottom: calc(var(--nl-space-3) + env(safe-area-inset-bottom, 0));
  font-size: var(--nl-font-caption);
  color: var(--nl-text-2);
  text-align: left;
  background: var(--nl-primary-ghost);
  border-top: 1px solid var(--nl-border);

  svg {
    flex-shrink: 0;
    color: var(--nl-text-2);
  }
}
</style>
