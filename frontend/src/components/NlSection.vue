<script setup>
/**
 * NlSection · 流程页面区块包装（design.md §4）
 *
 * 用于非卡片化的页面结构（如信息确认行），与 NlCard 配套使用
 * - card 模式：白底 + 边框 + 圆角 + 投影
 * - plain 模式：仅 padding
 */
defineProps({
  /** 是否显示卡片样式 */
  card: { type: Boolean, default: true },
  /** 标题 */
  title: { type: String, default: '' },
  /** 底部说明文字 */
  subtitle: { type: String, default: '' }
})
</script>

<template>
  <section :class="['nl-section', { 'is-card': card }]">
    <header v-if="title || subtitle || $slots.title" class="nl-section__head">
      <h3 class="nl-h2">
        <slot name="title">{{ title }}</slot>
      </h3>
      <p v-if="subtitle" class="nl-caption">{{ subtitle }}</p>
    </header>
    <slot />
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-section {
  &.is-card {
    padding: var(--nl-space-4);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    box-shadow: var(--nl-shadow-card);
  }

  & + .nl-section {
    margin-top: var(--nl-gap-section);
  }

  &__head {
    margin-bottom: var(--nl-gap-item);

    p {
      margin: 4px 0 0 0;
    }
  }
}
</style>
