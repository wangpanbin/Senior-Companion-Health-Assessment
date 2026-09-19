<script setup>
/**
 * NlListRow · 列表行（design.md §3.4）
 *
 * 公式：[图标容器 36×36 圆角 10 + 20px SVG] ← gap 12 → [标题 + 副标题] ← SPACE_BETWEEN → [chevron]
 * 最小高度 56（老人模式 68），分隔线 --nl-divider
 */
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  /** 是否显示右侧 chevron */
  chevron: { type: Boolean, default: true },
  /** 是否显示上方分隔线（首行一般不显示） */
  divider: { type: Boolean, default: false },
  /** 自定义主色（图标容器 / 文字） */
  tone: { type: String, default: 'primary' }
})
</script>

<template>
  <div :class="['nl-listrow', { 'is-divider': divider }]">
    <span v-if="$slots.icon" :class="['nl-listrow__icon', `is-${tone}`]">
      <slot name="icon" />
    </span>
    <div class="nl-listrow__body">
      <div class="nl-listrow__title nl-h3">{{ title }}</div>
      <div v-if="subtitle" class="nl-listrow__subtitle nl-caption">{{ subtitle }}</div>
    </div>
    <div class="nl-listrow__trail">
      <div v-if="$slots.extra" class="nl-listrow__extra">
        <slot name="extra" />
      </div>
      <span v-if="chevron" class="nl-listrow__chev" aria-hidden="true">
        <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M6 4l4 4-4 4" />
        </svg>
      </span>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-listrow {
  display: flex;
  align-items: center;
  gap: var(--nl-space-3);
  min-height: var(--nl-listrow-min-height);
  padding: var(--nl-space-3) 0;
  background: var(--nl-bg-card);

  &.is-divider {
    border-top: 1px solid var(--nl-divider);
  }

  &__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    width: 36px;
    height: 36px;
    border-radius: 10px;
    background: var(--nl-primary-light);

    :deep(svg) {
      width: 20px;
      height: 20px;
    }

    &.is-primary { background: var(--nl-primary-light); color: var(--nl-primary); }
    &.is-success { background: var(--nl-success-bg); color: var(--nl-success); }
    &.is-warning { background: var(--nl-warning-bg); color: var(--nl-warning); }
    &.is-danger  { background: var(--nl-danger-bg);  color: var(--nl-danger); }
    &.is-purple  { background: var(--nl-purple-bg);  color: var(--nl-purple); }
    &.is-info    { background: var(--nl-info-bg);    color: var(--nl-info); }
    &.is-neutral { background: var(--nl-neutral-chip); color: var(--nl-text-2); }
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__subtitle {
    margin-top: 2px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__trail {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    flex-shrink: 0;
    min-width: 20px;
  }

  &__extra {
    display: inline-flex;
    flex-shrink: 0;
    font-size: var(--nl-font-body);
    color: var(--nl-text-2);
  }

  &__chev {
    flex-shrink: 0;
    display: inline-flex;
    color: var(--nl-text-3);
  }
}
</style>
