<script setup>
/**
 * NlEmpty · 空状态（docs/design/design.md §3.6）
 *
 * 80px 线性插画（用 SVG）+ 「暂无数据」+ 一句行动引导
 * 错误状态切换文案：403 / 404 / 网络异常
 */
import { computed, useAttrs, useSlots } from 'vue'
import { guardComponentApi } from '@/utils/componentGuard'

const props = defineProps({
  /** empty / 403 / 404 / network */
  type: { type: String, default: 'empty' },
  /** 行动按钮文字（与 #action 插槽二选一；插槽优先） */
  actionText: { type: String, default: '' },
  /** 主标题 */
  title: { type: String, default: '' },
  /** 自定义副标题文案 */
  description: { type: String, default: '' }
})

const emit = defineEmits(['action'])

const slots = useSlots()

/**
 * 开发期 prop / 插槽自检 —— 详见 {@code utils/componentGuard.js} 的说明。
 *
 * <p>本组件真实踩过的两个坑：{@code text=} 写成 prop 名（静默退化成默认文案）、
 * {@code #action} 插槽名写错（整段按钮被 Vue 丢弃，403 页一个按钮都没有）。</p>
 */
guardComponentApi(
  'NlEmpty',
  { props: ['type', 'actionText', 'title', 'description'], slots: ['action'] },
  useAttrs(),
  Object.keys(slots)
)

/** 有没有可渲染的行动区（有插槽内容，或有 actionText） */
const hasAction = computed(() => !!slots.action || !!props.actionText)

const meta = computed(() => {
  const map = {
    // ⚠️ 默认副标题不再提「下拉刷新」—— 全站并没有实现下拉刷新，
    // 这句话会把用户引到一个不存在的交互上（P2-9）。
    empty: { title: '暂无数据', desc: '当前没有可展示的内容' },
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
    <!--
      行动区：#action 插槽优先（可放多个按钮）；没有插槽时回退到 actionText + @action。
      ⚠️ 这里必须真的把插槽渲染出来 —— 插槽名写错时 Vue 会静默丢弃整段内容，
      页面看上去「只是空了一点」，实际是所有按钮都没了。
    -->
    <div v-if="hasAction" class="nl-empty__actions">
      <slot name="action">
        <el-button v-if="actionText" type="primary" round @click="emit('action')">
          {{ actionText }}
        </el-button>
      </slot>
    </div>
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
  color: var(--nl-text-2);

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

  /* 行动区：插槽里可能有多个按钮，横向排布并居中 */
  &__actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--nl-space-3);
    align-items: center;
    justify-content: center;
  }
}
</style>
