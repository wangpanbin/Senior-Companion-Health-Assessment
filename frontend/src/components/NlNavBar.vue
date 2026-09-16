<script setup>
/**
 * NlNavBar · 移动端顶部导航栏（design.md §4 骨架第 2 步）
 *
 * 高度 56（老人模式 64），左侧返回箭头（可选），中间标题 22/600，左对齐
 * 右侧操作图标（最多 1 个）
 */
import { useRouter } from 'vue-router'

defineProps({
  title: { type: String, default: '' },
  /** 是否显示返回按钮 */
  back: { type: Boolean, default: true },
  /** 透传右侧内容（图标按钮 / chip） */
  transparent: { type: Boolean, default: false }
})

const emit = defineEmits(['back'])
const router = useRouter()

function handleBack() {
  emit('back')
  if (window.history.length > 1) router.back()
  else router.push('/')
}
</script>

<template>
  <header :class="['nl-navbar', { 'is-transparent': transparent }]">
    <button v-if="back" class="nl-navbar__back" aria-label="返回" @click="handleBack">
      <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path d="M15 6l-6 6 6 6" />
      </svg>
    </button>
    <span v-else class="nl-navbar__placeholder" />

    <h1 class="nl-navbar__title nl-h1">{{ title }}</h1>

    <div class="nl-navbar__right">
      <slot name="right" />
    </div>
  </header>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-navbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: var(--nl-space-2);
  height: var(--nl-navbar-height);
  padding: 0 var(--nl-gutter);
  background: var(--nl-bg-card);
  border-bottom: 1px solid var(--nl-divider);

  &.is-transparent {
    background: transparent;
    border-bottom-color: transparent;
  }

  &__back {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    width: var(--nl-touch-min);
    height: var(--nl-touch-min);
    color: var(--nl-text-1);
    background: transparent;
    border: none;
    border-radius: 50%;
    cursor: pointer;
    transition: background 0.15s;

    &:active {
      background: var(--nl-primary-ghost);
    }
  }

  &__placeholder {
    flex-shrink: 0;
    width: var(--nl-touch-min);
  }

  &__title {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__right {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: flex-end;
    gap: var(--nl-space-2);
    min-width: var(--nl-touch-min);
  }
}
</style>
