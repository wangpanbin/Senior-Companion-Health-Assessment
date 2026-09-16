<script setup>
/**
 * NlEmpty · 空状态（design.md §3.6）
 *
 * 80px 线性插画（用 SVG）+ 「暂无数据」+ 一句行动引导
 * 错误状态切换文案：403 / 404 / 网络异常
 */
import { computed } from 'vue'

const props = defineProps({
  /** empty / 403 / 404 / network */
  type: { type: String, default: 'empty' },
  /** 行动按钮文字 */
  actionText: { type: String, default: '' },
  /** 主标题 */
  title: { type: String, default: '' },
  /** 自定义副标题文案 */
  description: { type: String, default: '' }
})

const emit = defineEmits(['action'])

const meta = computed(() => {
  const map = {
    empty: { title: '暂无数据', desc: '还没有内容～ 下拉刷新试试' },
    '403': { title: '无权限访问', desc: '当前账号没有查看此页面的权限' },
    '404': { title: '页面不存在', desc: '您访问的页面已下线或地址错误' },
    network: { title: '网络开小差了', desc: '请检查网络后点击重试' }
  }
  return map[props.type] || map.empty
})
</script>

<template>
  <div class="nl-empty">
    <svg viewBox="0 0 80 80" class="nl-empty__svg" fill="none" xmlns="http://www.w3.org/2000/svg">
      <rect x="10" y="20" width="60" height="48" rx="8" stroke="currentColor" stroke-width="1.5" />
      <path d="M16 32h48" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
      <path d="M22 46h22" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
      <path d="M22 54h14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
      <circle cx="60" cy="56" r="10" stroke="currentColor" stroke-width="1.5" fill="white" />
      <path d="M67 63l5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
    </svg>
    <h3 class="nl-h3 nl-empty__title">{{ props.title || meta.title }}</h3>
    <p class="nl-caption nl-empty__desc">{{ props.description || meta.desc }}</p>
    <el-button v-if="actionText" type="primary" round @click="emit('action')">
      {{ actionText }}
    </el-button>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--nl-space-3);
  padding: var(--nl-space-8) var(--nl-space-4);
  color: var(--nl-text-3);

  &__svg {
    width: 80px;
    height: 80px;
    color: var(--nl-border-strong);
  }

  &__title {
    margin: 0;
    color: var(--nl-text-1);
  }

  &__desc {
    margin: 0;
    text-align: center;
  }
}
</style>
