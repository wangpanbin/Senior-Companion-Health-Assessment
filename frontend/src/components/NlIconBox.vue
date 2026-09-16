<script setup>
/**
 * NlIconBox · 浅蓝底图标容器（design.md §3.4 + 快捷入口）
 *
 * 48×48 圆角 12，浅蓝底，中心放 24×24 SVG 图标
 * tone 用于切换底色（快捷入口常用 primary，但预约/用药/进度 三色不同）
 */
defineProps({
  size: { type: [String, Number], default: 48 },
  tone: { type: String, default: 'primary' },
  /** 圆度 12 / 14 / 999 */
  radius: { type: [String, Number], default: 12 }
})
</script>

<template>
  <span
    :class="['nl-iconbox', `is-${tone}`]"
    :style="{ width: `${size}px`, height: `${size}px`, borderRadius: `${radius}px` }"
  >
    <slot />
  </span>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-iconbox {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;

  :deep(svg) {
    width: 24px;
    height: 24px;
  }

  &.is-primary { background: var(--nl-primary-light); color: var(--nl-primary); }
  &.is-success { background: var(--nl-success-bg);   color: var(--nl-success); }
  &.is-warning { background: var(--nl-warning-bg);   color: var(--nl-warning); }
  &.is-danger  { background: var(--nl-danger-bg);    color: var(--nl-danger); }
  &.is-purple  { background: var(--nl-purple-bg);    color: var(--nl-purple); }
  &.is-info    { background: var(--nl-info-bg);      color: var(--nl-info); }
  &.is-neutral { background: var(--nl-neutral-chip); color: var(--nl-text-2); }
}
</style>
