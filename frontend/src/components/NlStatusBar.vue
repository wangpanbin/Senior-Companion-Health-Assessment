<script setup>
/**
 * NlStatusBar · 移动端状态栏占位（design.md §2.6）
 *
 * 高度 62px，时间居左（Inter 15/600），信号/WiFi/电量居右
 * 由于 H5 嵌入在浏览器壳内，真正系统状态信息由宿主提供，这里只做占位 + 模拟
 */
import { computed } from 'vue'

const props = defineProps({
  /** 时间字符串（默认读取当前时刻 13:25 格式） */
  time: { type: String, default: '' },
  /** 浅色文字（深色背景时使用） */
  inverse: { type: Boolean, default: false }
})

const now = computed(() => {
  if (props.time) return props.time
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
})
</script>

<template>
  <div :class="['nl-statusbar', { 'is-inverse': inverse }]">
    <span class="nl-statusbar__time is-num">{{ now }}</span>
    <span class="nl-statusbar__sys">
      <svg width="18" height="14" viewBox="0 0 18 14" fill="currentColor">
        <rect x="0" y="9" width="3" height="5" rx="1" />
        <rect x="5" y="6" width="3" height="8" rx="1" />
        <rect x="10" y="3" width="3" height="11" rx="1" />
        <rect x="15" y="0" width="3" height="14" rx="1" />
      </svg>
      <svg width="16" height="14" viewBox="0 0 16 14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
        <path d="M1 5a10 10 0 0 1 14 0" />
        <path d="M4 8a6 6 0 0 1 8 0" />
        <path d="M7 11a2 2 0 0 1 2 0" />
      </svg>
      <svg width="22" height="14" viewBox="0 0 22 14" fill="none" stroke="currentColor" stroke-width="1.2">
        <rect x="0.5" y="0.5" width="18" height="13" rx="3" />
        <rect x="2" y="2" width="13" height="10" rx="1.5" fill="currentColor" stroke="none" />
        <rect x="20" y="5" width="2" height="4" rx="1" fill="currentColor" stroke="none" />
      </svg>
    </span>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-statusbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--nl-statusbar-height);
  padding: 0 var(--nl-space-5);
  color: var(--nl-text-1);

  &.is-inverse {
    color: var(--nl-text-inverse);
  }

  &__time {
    font-family: var(--nl-font-num);
    font-size: 15px;
    font-weight: 600;
  }

  &__sys {
    display: flex;
    align-items: center;
    gap: 6px;
  }
}
</style>
