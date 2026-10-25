<script setup>
/**
 * NlSkeleton · 骨架屏（docs/design/design.md §3.6）
 *
 * 卡片轮廓 + 灰色渐变扫光，禁用整页转圈
 */
import { useAttrs } from 'vue'
import { guardComponentApi } from '@/utils/componentGuard'

defineProps({
  count: { type: Number, default: 1 },
  /** 行高 */
  height: { type: [String, Number], default: 16 },
  /** 是否显示圆角卡片 */
  card: { type: Boolean, default: true }
})

/**
 * 开发期 prop 自检 —— 详见 {@code utils/componentGuard.js} 的说明。
 *
 * <p>本组件真实踩过的坑：{@code :rows="3"} 不是声明过的 prop（只有 {@code count}），
 * 于是骨架条仍按默认的 1 条渲染，页面看起来「骨架只显示一行」。</p>
 */
guardComponentApi('NlSkeleton', { props: ['count', 'height', 'card'] }, useAttrs())
</script>

<template>
  <div class="nl-skel">
    <div
      v-for="i in count"
      :key="i"
      :class="['nl-skel__bar', { 'is-card': card }]"
      :style="{ height: typeof height === 'number' ? `${height}px` : height }"
    />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-skel {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-2);

  &__bar {
    width: 100%;
    border-radius: 6px;
    background: linear-gradient(
      90deg,
      var(--nl-bg-sunken) 0%,
      #eef2f7 50%,
      var(--nl-bg-sunken) 100%
    );
    background-size: 200% 100%;
    animation: skel 1.4s ease-in-out infinite;

    &.is-card {
      height: 88px;
      border-radius: var(--nl-radius-card);
    }
  }
}

@keyframes skel {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}
</style>
