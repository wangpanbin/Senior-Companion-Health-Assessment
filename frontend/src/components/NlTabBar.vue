<script setup>
/**
 * NlTabBar · 底部胶囊 Tab Bar（design.md §3.5）
 *
 * 容器：全宽 · padding 12 21 21 · 白底 + --nl-shadow-float
 * 胶囊：高 62 · fill_container 宽 · 圆角 36 · 1px --nl-border · 内 padding 4
 * Tab 项：等分宽高 · 圆角 26 · 竖向 gap 4 · 图标 18 + 标签 10/500
 * 激活态：实心 --nl-primary 底 + 白色图标文字
 * Tab 数量 3-5
 */
import { computed } from 'vue'

const props = defineProps({
  /** 模型：[{ key, label, icon (Component), to }] */
  tabs: { type: Array, required: true },
  /** 当前激活 key */
  modelValue: { type: String, required: true }
})
const emit = defineEmits(['update:modelValue', 'change'])

const current = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

function pick(key) {
  current.value = key
  emit('change', key)
}
</script>

<template>
  <nav class="nl-tabbar" aria-label="主导航">
    <div class="nl-tabbar__pill">
      <button
        v-for="t in tabs"
        :key="t.key"
        :class="['nl-tabbar__item', { 'is-active': current === t.key }]"
        :aria-label="t.label"
        @click="pick(t.key)"
      >
        <span class="nl-tabbar__icon">
          <component :is="t.icon" v-if="t.icon" />
        </span>
        <span class="nl-tabbar__label">{{ t.label }}</span>
      </button>
    </div>
  </nav>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-tabbar {
  padding: $nl-tabbar-padding-v $nl-tabbar-padding-h calc(#{$nl-tabbar-padding-v} + env(safe-area-inset-bottom, 0));
  background: var(--nl-bg-card);
  box-shadow: var(--nl-shadow-float);

  &__pill {
    display: grid;
    grid-auto-flow: column;
    grid-auto-columns: 1fr;
    height: $nl-tabbar-height;
    padding: 4px;
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: $nl-tabbar-radius;
  }

  &__item {
    display: inline-flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
    justify-content: center;
    color: var(--nl-text-3);
    background: transparent;
    border: none;
    border-radius: 32px;
    cursor: pointer;
    transition: background 0.18s, color 0.18s;
    -webkit-tap-highlight-color: transparent;

    &:active {
      transform: scale(0.97);
    }

    &.is-active {
      color: var(--nl-text-inverse);
      background: var(--nl-primary);
    }
  }

  &__icon {
    display: inline-flex;

    :deep(svg) {
      width: 18px;
      height: 18px;
    }
  }

  &__label {
    font-size: var(--nl-font-micro);
    font-weight: 500;
    letter-spacing: 0.5px;
  }
}

/* 老人模式放大 */
:global(html.elderly-mode) .nl-tabbar {
  &__item {
    min-height: 56px;
  }

  &__icon :deep(svg) {
    width: 22px;
    height: 22px;
  }

  &__label {
    font-size: 14px;
  }
}
</style>
