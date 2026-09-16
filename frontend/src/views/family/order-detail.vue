<script setup>
/**
 * 订单详情（M-16 · design.md §4）
 *
 * - 订单状态条 + 编号
 * - 时间线（NlTimeline：6 节点 + 实时推送）
 * - 信息卡：就诊人 / 医院 / 时间 / 服务地址 / 服务费
 * - 陪诊员信息卡（如已接单）
 * - 操作：根据当前状态切换「去评价 / 我要投诉 / 取消订单」
 */
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPhoneShell, NlCard, NlStatusChip, NlTimeline, NlAvatar, NlNoticeBar } from '@/components'

const route = useRoute()
const router = useRouter()

const order = {
  id: route.params.id || 'OD20250916001',
  status: 'IN_SERVICE',
  hospital: '市第一人民医院',
  dept: '心血管内科',
  elder: '张大爷 · 72 岁',
  elderPhone: '138****7777',
  time: '2026-09-16 09:00 - 11:00',
  address: '北京市朝阳区幸福路 123 号（上门接老人）',
  fee: 120,
  pickupFee: 30,
  companion: {
    name: '李师傅',
    phone: '138****6543',
    avatar: '',
    arrived: '预计 10 分钟到达'
  }
}

const timeline = [
  { key: 'PENDING', label: '待接单', status: 'done', time: '09-15 18:00' },
  { key: 'ACCEPTED', label: '已接单', status: 'done', time: '09-15 18:35' },
  { key: 'DEPART', label: '陪诊员已出发', status: 'done', time: '09-16 09:05' },
  { key: 'ARRIVE', label: '已到达医院', status: 'done', time: '09-16 09:22' },
  { key: 'IN_CONSULT', label: '就诊中', status: 'current', time: '09-16 09:35' },
  { key: 'TAKE_MEDICINE', label: '取药', status: 'todo' },
  { key: 'LEAVE', label: '离院', status: 'todo' },
  { key: 'COMPLETED', label: '订单完成', status: 'todo' },
  { key: 'REVIEWED', label: '已评价', status: 'todo' }
]

function goReview() {
  router.push(`/family/order/${order.id}/review`)
}
function goComplaint() {
  router.push(`/family/order/${order.id}/complaint`)
}
async function cancelOrder() {
  await ElMessageBox.confirm(
    `确认取消订单 ${order.id}？已支付的费用将原路退回。`,
    '取消订单',
    { confirmButtonText: '确认取消', type: 'warning' }
  ).catch(() => {})
  ElMessage.success('已提交取消申请，等待陪诊员确认')
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '订单详情' }">
    <!-- 顶部状态条 -->
    <NlNoticeBar tone="info">
      订单状态实时推送中 · 陪诊员每完成一个节点会即时通知
    </NlNoticeBar>

    <!-- 订单概览 -->
    <NlCard>
      <div class="overview">
        <div>
          <div class="nl-h2 is-num">{{ order.id }}</div>
          <div class="nl-caption nl-text-muted">下单时间 2026-09-15 17:30</div>
        </div>
        <NlStatusChip :status="order.status" />
      </div>
    </NlCard>

    <!-- 陪诊员 -->
    <NlCard v-if="order.companion" plain>
      <template #title><span class="nl-h2">陪诊员</span></template>
      <div class="comp">
        <NlAvatar :fallback="order.companion.name.slice(0, 1)" :size="48" tone="warning" />
        <div class="comp__body">
          <div class="comp__name">{{ order.companion.name }}</div>
          <div class="nl-caption nl-text-muted is-num">{{ order.companion.phone }} · {{ order.companion.arrived }}</div>
        </div>
      </div>
    </NlCard>

    <!-- 时间线 -->
    <NlCard title="服务进度">
      <NlTimeline :steps="timeline" />
    </NlCard>

    <!-- 订单信息 -->
    <NlCard title="订单信息" plain>
      <ul class="info">
        <li><span>就诊人</span><span>{{ order.elder }} · {{ order.elderPhone }}</span></li>
        <li><span>陪诊医院</span><span>{{ order.hospital }} · {{ order.dept }}</span></li>
        <li><span>就诊时间</span><span class="is-num">{{ order.time }}</span></li>
        <li><span>服务地址</span><span class="info__addr">{{ order.address }}</span></li>
        <li class="info__fee">
          <span>合计（含接送 ¥{{ order.pickupFee }}）</span>
          <span class="is-num info__fee-val">¥{{ order.fee + order.pickupFee }}</span>
        </li>
        <li class="info__note">
          <span>结算方式</span>
          <span>线上记账 + 线下结算</span>
        </li>
      </ul>
    </NlCard>

    <!-- 操作栏 -->
    <section class="actions">
      <el-button v-if="order.status === 'PENDING'" round @click="cancelOrder">
        取消订单
      </el-button>
      <el-button v-if="order.status === 'COMPLETED'" round @click="goComplaint">
        我要投诉
      </el-button>
      <el-button v-if="order.status === 'COMPLETED'" type="primary" round @click="goReview">
        去评价
      </el-button>
    </section>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.overview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--nl-space-3);
}

.comp {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;

  &__body {
    flex: 1;
  }

  &__name {
    font-size: 16px;
    font-weight: 600;
  }
}

.info {
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
    padding: var(--nl-space-3) 0;
    border-bottom: 1px solid var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }

    > span:first-child {
      flex-shrink: 0;
      width: 96px;
      font-size: var(--nl-font-caption);
      color: var(--nl-text-3);
    }

    > span:last-child {
      flex: 1;
      font-size: 14px;
      color: var(--nl-text-1);
    }
  }

  &__addr {
    line-height: 1.5;
    color: var(--nl-text-2);
  }

  &__fee {
    background: var(--nl-primary-ghost);
    margin: 0 calc(var(--nl-space-3) * -1);
    padding-left: var(--nl-space-3);
    padding-right: var(--nl-space-3);
    border-radius: 8px;

    > span {
      color: var(--nl-text-1) !important;
      font-weight: 600;
    }
  }

  &__fee-val {
    font-size: 18px;
    color: var(--nl-primary) !important;
  }
}

.actions {
  display: flex;
  gap: var(--nl-space-3);
  justify-content: center;
  padding: var(--nl-space-5) var(--nl-gutter);

  :deep(.el-button) {
    flex: 1;
  }
}
</style>
