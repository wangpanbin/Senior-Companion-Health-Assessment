<script setup>
/**
 * 陪诊员·我的收入（M-21 · design.md §1.2 P1）
 *
 * 后端没有专门的「收入」接口，复用 listMyOrders 列表：
 *   - 循环分页（size=100，按 pages 翻完）拉全量订单；
 *   - 只统计 COMPLETED / REVIEWED（不含进行中状态）；
 *   - 列表已直接返回 paymentStatus / paymentStatusLabel / actualFee，
 *     结算状态与金额直接取列表字段，不再逐单 getOrder（去 N+1）；
 *   - 合规红线：一期只有线上记账 + 线下结算，严禁「在线支付 / 提现 / 原路退回」字样。
 */
import { ref, computed, onMounted } from 'vue'
import { NlPhoneShell, NlCard, NlStatusChip, NlSkeleton, NlEmpty, NlNoticeBar } from '@/components'
import { listMyOrders } from '@/api/order'
import { formatMoney, formatDate } from '@/utils/format'

const loading = ref(true)
const bills = ref([])

/**
 * 单笔有效金额：优先 actualFee（结算金额，NON_NULL 未结算时可能缺失），
 * 缺失时回退到 always-present 的 fee（合同服务费），不凭空造数字。
 */
const effectiveAmount = (b) => {
  const v = b.actualFee != null ? b.actualFee : b.fee
  return Number(v) || 0
}

const totalIncome = computed(() =>
  bills.value.reduce((sum, b) => sum + effectiveAmount(b), 0)
)
const settledIncome = computed(() =>
  bills.value
    .filter((b) => b.paymentStatus === 'SETTLED')
    .reduce((sum, b) => sum + effectiveAmount(b), 0)
)
const unsettledIncome = computed(() => totalIncome.value - settledIncome.value)

async function loadIncome() {
  loading.value = true
  try {
    // 1) 循环分页拉全量（size ≤ 100；带终止保护，避免死循环）
    const all = []
    let page = 1
    const size = 100
    for (let guard = 0; guard < 1000; guard++) {
      const data = await listMyOrders({ page, size })
      const recs = data?.records || []
      all.push(...recs)
      const pages = data?.pages || 0
      if (page >= pages || recs.length === 0) break
      page += 1
    }

    // 2) 已完成 / 已评价 才计入收入（不含进行中状态）
    const done = all.filter(
      (o) => o.status === 'COMPLETED' || o.status === 'REVIEWED'
    )
    bills.value = done
    // 结算状态 / 金额直接来自列表字段 paymentStatus / actualFee，无需再逐单 getOrder（去 N+1）
  } catch {
    bills.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadIncome)
</script>

<template>
  <NlPhoneShell :nav="{ title: '我的收入' }">
    <NlNoticeBar tone="primary">
      一期为线上记账 + 线下结算，金额以管理员结算为准。
    </NlNoticeBar>

    <NlSkeleton v-if="loading" :count="3" class="pad" />

    <NlEmpty
      v-else-if="!bills.length"
      type="empty"
      title="暂无收入"
      description="完成订单后，服务费将在这里结算展示"
    />

    <template v-else>
      <!-- KPI -->
      <section class="kpi">
        <div class="kpi__card">
          <div class="kpi__label">累计收入</div>
          <div class="kpi__val is-num">{{ formatMoney(totalIncome.toFixed(2)) }}</div>
        </div>
        <div class="kpi__card kpi__card--success">
          <div class="kpi__label">已结算</div>
          <div class="kpi__val is-num">{{ formatMoney(settledIncome.toFixed(2)) }}</div>
        </div>
        <div class="kpi__card kpi__card--primary">
          <div class="kpi__label">未结算</div>
          <div class="kpi__val is-num">{{ formatMoney(unsettledIncome.toFixed(2)) }}</div>
        </div>
      </section>

      <!-- 账单 -->
      <NlCard title="收入明细" plain>
        <ul class="bills">
          <li v-for="b in bills" :key="b.id" class="bill">
            <div class="bill__date is-num">{{ formatDate(b.createTime) }}</div>
            <div class="bill__body">
              <div class="nl-h3 bill__hosp">{{ b.hospital }}</div>
              <div class="nl-caption nl-text-muted">
                就诊人：{{ b.elderName || '—' }}
                <NlStatusChip
                  scope="payment"
                  :status="b.paymentStatus"
                  :text="b.paymentStatusLabel"
                  class="bill__pay"
                />
              </div>
            </div>
            <div class="bill__fee is-num">{{ formatMoney(b.actualFee != null ? b.actualFee : b.fee) }}</div>
          </li>
        </ul>
      </NlCard>
    </template>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter);
}

.kpi {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-3);
  padding: 0 var(--nl-gutter);

  &__card {
    padding: var(--nl-space-3);
    background: var(--nl-bg-card);
    border-radius: var(--nl-radius-card);
    box-shadow: var(--nl-shadow-card);

    &--success {
      background: var(--nl-success-bg);
      .kpi__val { color: var(--nl-success); }
    }

    &--primary {
      background: var(--nl-primary-ghost);
      .kpi__val { color: var(--nl-primary); }
    }
  }

  &__label {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }

  &__val {
    margin-top: 6px;
    font-size: 20px;
    font-weight: 700;
    color: var(--nl-text-1);
  }
}

.bills {
  padding: 0 var(--nl-gutter);
  margin: 0;
  list-style: none;
}

.bill {
  display: grid;
  grid-template-columns: 56px 1fr auto;
  gap: var(--nl-space-3);
  align-items: center;
  padding: var(--nl-space-3) 0;
  border-bottom: 1px dashed var(--nl-divider);

  &:last-child {
    border-bottom: none;
  }

  &__date {
    width: 56px;
    padding: 6px 0;
    font-weight: 600;
    text-align: center;
    background: var(--nl-bg-sunken);
    color: var(--nl-text-2);
    border-radius: 8px;
  }

  &__hosp {
    font-size: 14px;
  }

  &__pay {
    margin-left: 6px;
  }

  &__fee {
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-primary);
  }
}
</style>
