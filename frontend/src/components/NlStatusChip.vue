<script setup>
/**
 * NlStatusChip · 业务状态 Chip（design.md §3.3 + §2.2）
 *
 * 统一的高度 22 / 圆角 6 / padding 0 8 / 字号 12/500
 * 颜色直接走预设 enum（订单 / 用药 / 资质 / 投诉 / 用户），与后端枚举保持一致
 *
 * 用法：
 *   <NlStatusChip status="PENDING" />          订单待接单
 *   <NlStatusChip status="TAKEN" />            用药已服
 *   <NlStatusChip status="APPROVED" />         资质通过
 *   <NlStatusChip status="ACTIVE" />          用户正常
 *   <NlStatusChip status="CLOSED" />           投诉已结案
 *   <NlStatusChip text="自定义" tone="warning" /> 自定义文本
 */
import { computed } from 'vue'

const props = defineProps({
  /** 业务枚举值，自动映射颜色 */
  status: { type: String, default: '' },
  /** 自定义文本（status 未识别或想覆盖时使用） */
  text: { type: String, default: '' },
  /** 强制 tone：success / warning / danger / info / purple / neutral */
  tone: { type: String, default: '' },
  /** 圆点（订单状态用） */
  dot: { type: Boolean, default: false }
})

/** 后端枚举 → tone + 中文 */
const PRESET = {
  // 订单
  PENDING: { tone: 'warning', text: '待接单' },
  ACCEPTED: { tone: 'purple', text: '已接单' },
  IN_SERVICE: { tone: 'info', text: '服务中' },
  COMPLETED: { tone: 'success', text: '已完成' },
  REVIEWED: { tone: 'neutral', text: '已评价' },
  CANCELLED: { tone: 'danger', text: '已取消' },
  // 用药任务
  TAKEN: { tone: 'success', text: '已服' },
  MISSED: { tone: 'danger', text: '漏服' },
  // 资质
  APPROVED: { tone: 'success', text: '已认证' },
  REJECTED: { tone: 'danger', text: '已驳回' },
  // 投诉
  PROCESSING: { tone: 'info', text: '处理中' },
  CLOSED: { tone: 'success', text: '已结案' },
  // 用户
  ACTIVE: { tone: 'success', text: '正常' },
  BANNED: { tone: 'danger', text: '已封禁' }
}

const resolved = computed(() => {
  if (props.tone) return { tone: props.tone, text: props.text || props.status }
  const p = PRESET[props.status]
  if (p) return p
  return { tone: 'neutral', text: props.text || props.status }
})
</script>

<template>
  <span :class="['nl-chip', `nl-chip--${resolved.tone}`]">
    <i v-if="dot" class="nl-chip__dot" />
    {{ props.text || resolved.text }}
  </span>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 22px;
  padding: 0 8px;
  font-size: var(--nl-font-caption);
  font-weight: 500;
  line-height: 1;
  border-radius: var(--nl-radius-chip);
  white-space: nowrap;

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &--success { color: var(--nl-success); background: var(--nl-success-bg); }
  &--warning { color: var(--nl-warning); background: var(--nl-warning-bg); }
  &--danger  { color: var(--nl-danger);  background: var(--nl-danger-bg); }
  &--info    { color: var(--nl-info);    background: var(--nl-info-bg); }
  &--purple  { color: var(--nl-purple);  background: var(--nl-purple-bg); }
  &--neutral { color: var(--nl-text-2);  background: var(--nl-neutral-chip); }
}
</style>
