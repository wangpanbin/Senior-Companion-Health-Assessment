<script setup>
/**
 * NlSkeleton · 骨架屏（design.md §3.6）
 *
 * 卡片轮廓 + 灰色渐变扫光，禁用整页转圈
 */
defineProps({
  count: { type: Number, default: 1 },
  /** 行高 */
  height: { type: [String, Number], default: 16 },
  /** 是否显示圆角卡片 */
  card: { type: Boolean, default: true }
})
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
