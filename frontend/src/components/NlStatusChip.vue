<script setup>
/**
 * NlStatusChip · 业务状态 Chip（design.md §3.3 + §2.2）
 *
 * 统一的高度 22 / 圆角 6 / padding 0 8 / 字号 12/500
 * 颜色直接走预设 enum（订单 / 用药 / 资质 / 投诉 / 用户），与后端枚举保持一致
 *
 * ⚠️ 为什么要 `scope`
 *   后端多个模块共用同一个枚举名，但中文含义完全不同：
 *     PENDING → 订单「待接单」 / 资质「待审核」 / 投诉「待处理」 / 服药「待服」
 *     REJECTED → 资质「已驳回」 / 投诉「已驳回」
 *   只按 status 查一张扁平表，必然有一处显示错文案。所以按模块分表，
 *   `scope` 缺省为 'order' 以兼容既有调用。
 *
 * 用法：
 *   <NlStatusChip status="PENDING" />                    订单待接单（默认 scope）
 *   <NlStatusChip scope="audit" status="PENDING" />      资质待审核
 *   <NlStatusChip scope="task" status="TAKEN" />         服药已服
 *   <NlStatusChip scope="complaint" status="RESOLVED" /> 投诉已结案
 *   <NlStatusChip scope="user" status="NORMAL" />        账号正常
 *   <NlStatusChip text="自定义" tone="warning" />        自定义文本
 *
 * 首选后端下发的 `*Label` 字段（后端是唯一真源）；本组件的映射只在
 * 「后端没给 label」时兜底，避免页面直接暴露 IN_SERVICE 这种裸枚举名。
 */
import { computed } from 'vue'

const props = defineProps({
  /** 业务枚举值，自动映射颜色 */
  status: { type: String, default: '' },
  /** 模块域：order / audit / complaint / task / plan / user / bind */
  scope: { type: String, default: 'order' },
  /** 自定义文本（status 未识别或想覆盖时使用） */
  text: { type: String, default: '' },
  /** 强制 tone：success / warning / danger / info / purple / neutral */
  tone: { type: String, default: '' },
  /** 圆点（订单状态用） */
  dot: { type: Boolean, default: false }
})

/** 模块域 → 枚举映射。同一枚举名在不同域下的 tone 也可能不同（如 PENDING） */
const SCOPES = {
  // 订单（OrderStatus）
  order: {
    PENDING: { tone: 'warning', text: '待接单' },
    ACCEPTED: { tone: 'purple', text: '已接单' },
    IN_SERVICE: { tone: 'info', text: '服务中' },
    COMPLETED: { tone: 'success', text: '已完成' },
    REVIEWED: { tone: 'neutral', text: '已评价' },
    CANCELLED: { tone: 'danger', text: '已取消' }
  },
  // 陪诊员资质（AuditStatus）
  audit: {
    PENDING: { tone: 'warning', text: '待审核' },
    APPROVED: { tone: 'success', text: '已通过' },
    REJECTED: { tone: 'danger', text: '已驳回' }
  },
  // 投诉（ComplaintStatus）—— 注意是 RESOLVED 不是 CLOSED
  complaint: {
    PENDING: { tone: 'warning', text: '待处理' },
    PROCESSING: { tone: 'info', text: '处理中' },
    RESOLVED: { tone: 'success', text: '已结案' },
    REJECTED: { tone: 'danger', text: '已驳回' }
  },
  // 服药任务（MedicationTaskStatus）
  task: {
    PENDING: { tone: 'warning', text: '待服' },
    TAKEN: { tone: 'success', text: '已服' },
    MISSED: { tone: 'danger', text: '漏服' }
  },
  // 用药计划（MedicationPlanStatus）
  plan: {
    ACTIVE: { tone: 'success', text: '进行中' },
    DISABLED: { tone: 'neutral', text: '已停用' }
  },
  // 账号状态（AccountStatus）
  user: {
    NORMAL: { tone: 'success', text: '正常' },
    DISABLED: { tone: 'danger', text: '已封禁' }
  },
  // 老人绑定状态（BindStatus）
  bind: {
    BOUND: { tone: 'success', text: '已绑定' },
    UNBOUND: { tone: 'warning', text: '未绑定' }
  },
  // 结算状态（PaymentStatus）—— 注意是 SETTLED 不是 PAID
  payment: {
    UNPAID: { tone: 'warning', text: '未结算' },
    SETTLED: { tone: 'success', text: '已结算' }
  }
}

const resolved = computed(() => {
  if (props.tone) return { tone: props.tone, text: props.text || props.status }
  const table = SCOPES[props.scope] || SCOPES.order
  const p = table[props.status]
  if (p) return p
  // 未识别：保持中性灰，且优先显示调用方给的 text，其次裸枚举名。
  // 刻意不回退到 order 表 —— 那会让别的模块的 PENDING 显示成「待接单」
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
