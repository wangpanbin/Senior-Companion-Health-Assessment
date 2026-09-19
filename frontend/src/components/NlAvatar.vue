<script setup>
/**
 * NlAvatar · 头像组件
 *
 * 支持 image url / 默认字母回退；尺寸 32 / 40 / 48 / 56 / 72
 * tone 用于默认背景色（与 age 关联：老人端绿；陪诊员蓝；管理员紫）
 */
import { computed, ref, watch } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  /** 字母回退（当 src 不存在时显示） */
  fallback: { type: String, default: '' },
  size: { type: [String, Number], default: 40 },
  tone: { type: String, default: 'primary' },
  /** 是否显示健康徽标（如老人的「健康」chip） */
  badge: { type: String, default: '' }
})

const px = computed(() => `${props.size}px`)
const fontPx = computed(() => `${Math.round(props.size * 0.42)}px`)
const imageAvailable = ref(false)

// 头像地址失效时回退到昵称首字，避免出现浏览器的破损图片图标。
watch(
  () => props.src,
  (src) => {
    imageAvailable.value = !!src
  },
  { immediate: true }
)

function handleImageError() {
  imageAvailable.value = false
}
</script>

<template>
  <span :class="['nl-avatar', `is-${tone}`]" :style="{ width: px, height: px }">
    <img v-if="imageAvailable" :src="src" :alt="fallback" @error="handleImageError" />
    <span v-else class="nl-avatar__text" :style="{ fontSize: fontPx }">{{ fallback }}</span>
    <span v-if="badge" class="nl-avatar__badge">{{ badge }}</span>
  </span>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-avatar {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
  background: var(--nl-primary-light);
  border-radius: 50%;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  &__text {
    font-family: var(--nl-font-cn);
    font-weight: 600;
    color: var(--nl-primary);
  }

  &__badge {
    position: absolute;
    right: -4px;
    bottom: -2px;
    padding: 2px 6px;
    font-size: 10px;
    font-weight: 500;
    color: #fff;
    background: var(--nl-success);
    border: 1.5px solid var(--nl-bg-card);
    border-radius: 999px;
  }

  &.is-primary {
    background: var(--nl-primary-light);
  }
  &.is-success {
    background: var(--nl-success-bg);
  }
  &.is-warning {
    background: var(--nl-warning-bg);
  }
  &.is-danger {
    background: var(--nl-danger-bg);
  }
  &.is-purple {
    background: var(--nl-purple-bg);
  }
  &.is-info {
    background: var(--nl-info-bg);
  }
  &.is-neutral {
    background: var(--nl-neutral-chip);
  }

  &.is-primary .nl-avatar__text {
    color: var(--nl-primary);
  }
  &.is-success .nl-avatar__text {
    color: var(--nl-success);
  }
  &.is-warning .nl-avatar__text {
    color: var(--nl-warning);
  }
  &.is-danger .nl-avatar__text {
    color: var(--nl-danger);
  }
  &.is-purple .nl-avatar__text {
    color: var(--nl-purple);
  }
  &.is-info .nl-avatar__text {
    color: var(--nl-info);
  }
  &.is-neutral .nl-avatar__text {
    color: var(--nl-text-2);
  }
}
</style>
