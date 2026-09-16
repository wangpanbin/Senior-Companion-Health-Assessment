<script setup>
/**
 * NlCard · 通用卡片（design.md §3.2）
 *
 * 视觉公式：白底 + 1px --nl-border + radius 14 + padding 16 + 内部 gap 12 + --nl-shadow-card
 * 禁止：再嵌套投影卡片（层级最多 2 层）；禁止跨卡片强投影
 */
defineProps({
  /** 标题（左对齐，可省） */
  title: { type: String, default: '' },
  /** 是否纯白底无边框（嵌在另一卡片内部时使用） */
  plain: { type: Boolean, default: false },
  /** 是否可点击（hover 提影） */
  hover: { type: Boolean, default: false },
  /** 右侧操作区（如 chevron、查看更多） */
  extra: { type: [String, Boolean], default: false },
  /** 自定义 padding 间距，16 / 20 / 24 */
  padding: { type: [String, Number], default: 16 }
})
</script>

<template>
  <section
    :class="[
      'nl-card',
      { 'nl-card--plain': plain, 'nl-card--hover': hover }
    ]"
    :style="{ padding: typeof padding === 'number' ? `${padding}px` : padding }"
  >
    <header v-if="title || $slots.extra" class="nl-card__head">
      <h3 class="nl-h2 nl-card__title">
        <slot name="title">{{ title }}</slot>
      </h3>
      <div v-if="extra || $slots.extra" class="nl-card__extra">
        <slot name="extra">{{ extra }}</slot>
      </div>
    </header>
    <div v-if="!title && !$slots.title" class="nl-card__body" :class="{ 'is-first': true }">
      <slot />
    </div>
    <slot v-else />
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-card {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  box-shadow: var(--nl-shadow-card);

  &--plain {
    border-color: transparent;
    background: transparent;
    box-shadow: none;
  }

  &--hover {
    cursor: pointer;
    transition: box-shadow 0.2s, transform 0.2s;

    &:hover {
      box-shadow: var(--nl-shadow-hover);
    }

    &:active {
      transform: translateY(1px);
    }
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-3);
  }

  &__title {
    flex: 1;
    min-width: 0;
  }

  &__extra {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }
}
</style>
