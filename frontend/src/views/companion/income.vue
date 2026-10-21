<script setup>
/**
 * 陪诊员·我的收入（M-21 · docs/design/design.md §1.2 P1 · V3.3 改造）
 *
 * V3.3 起本页分两段数据源，口径各归其位：
 *
 *   1) **区间 KPI**（区间收入 / 已结算 / 待结算）
 *      来自 GET /statistics/companion/me/income —— 口径由后端统一计算，
 *      前端只做展示（roadmap 决策 G8「前端统计零自行计算」）。
 *      页面明示当前统计区间（响应里的 periodStart / periodEnd）。
 *
 *   2) **账单明细**（每单一条：医院 / 就诊人 / 金额 / 结算状态 / 记账面板）
 *      走既有 GET /order（listMyOrders）。这一段是**账单**，不是统计指标，
 *      金额与结算状态直接取订单自带的 actualFee / fee / paymentStatus /
 *      paymentStatusLabel 字段。
 *
 * 合规红线：一期只有线上记账 + 线下结算，严禁「在线支付 / 提现 / 原路退回」字样。
 */
import { ref, computed, onMounted } from 'vue'
import { NlPageShell, NlAppTabBar, NlCard, NlStatusChip, NlSkeleton, NlEmpty, NlNoticeBar } from '@/components'
import OrderFeeItems from '@/components/OrderFeeItems.vue'
import { listMyOrders } from '@/api/order'
import { getCompanionWorkbenchIncome } from '@/api/statistics'
import { formatMoney, formatDate } from '@/utils/format'

/* ============ 区间统计（后端口径） ============ */
const statsLoading = ref(true)
const statsError = ref(false)
const stats = ref(null)

/* ============ 账单明细 ============ */
const loading = ref(true)
/** R4：收入账单加载失败（与「暂无已完成订单」区分） */
const loadError = ref(false)
const bills = ref([])
/** 当前展开记账面板的订单 ID（ADR-0009：陪诊员在收入页直接补记代垫/服务费） */
const expandedId = ref(null)

/** KPI 三张卡：数值全部取自后端，前端不参与任何汇总 */
const kpiCards = computed(() => {
  const s = stats.value
  if (!s) return []
  return [
    { key: 'income', label: '区间收入', value: formatMoney(s.actualIncome) },
    { key: 'settled', label: '已结算', value: formatMoney(s.settledAmount), tone: 'success' },
    { key: 'unsettled', label: '待结算', value: formatMoney(s.unsettledAmount), tone: 'primary' }
  ]
})

/** 当前统计区间文案（取后端返回的区间，前端不自己算日期） */
const periodText = computed(() => {
  const s = stats.value
  if (!s || !s.periodStart || !s.periodEnd) return ''
  return `统计区间 ${s.periodStart} ~ ${s.periodEnd}`
})

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await getCompanionWorkbenchIncome()
    statsError.value = false
  } catch {
    stats.value = null
    statsError.value = true
  } finally {
    statsLoading.value = false
  }
}

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

    // 2) 只挑「已完成 / 已评价」作为账单条目（这是账单挑选，不是金额统计；
    //    金额口径一律走后端，这里不累加任何金额）
    const done = []
    for (const o of all) {
      if (o.status === 'COMPLETED' || o.status === 'REVIEWED') done.push(o)
    }
    bills.value = done
    loadError.value = false
    // 结算状态 / 金额直接来自列表字段 paymentStatus / actualFee，无需再逐单 getOrder（去 N+1）
  } catch {
    bills.value = []
    loadError.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadStats()
  loadIncome()
})
</script>

<template>
  <NlPageShell title="我的收入" :has-tabs="true" :back="false">
    <NlNoticeBar tone="primary">
      一期为线上记账 + 线下结算，金额以管理员结算为准。
    </NlNoticeBar>

    <!-- ============ 区间 KPI（后端口径，前端只展示） ============ -->
    <section class="kpi">
      <NlSkeleton v-if="statsLoading" :count="3" class="pad" />

      <div v-else-if="statsError" class="pad">
        <NlEmpty
          type="network"
          title="收入统计加载失败"
          description="账单明细不受影响，可单独重试统计口径"
          action-text="重试"
          @action="loadStats"
        />
      </div>

      <div v-else class="kpi__cards">
        <div
          v-for="k in kpiCards"
          :key="k.key"
          :class="['kpi__card', k.tone && `kpi__card--${k.tone}`]"
        >
          <div class="kpi__label">{{ k.label }}</div>
          <div class="kpi__val is-num">{{ k.value }}</div>
        </div>
      </div>
    </section>

    <p v-if="periodText" class="kpi__range nl-caption nl-text-muted">{{ periodText }}</p>

    <!-- ============ 账单明细（账单，不是统计指标） ============ -->
    <NlCard title="收入明细" plain>
      <p class="nl-caption nl-text-muted bill-note">
        明细为全部已完成订单；上方金额为统计区间口径。
      </p>

      <NlSkeleton v-if="loading" :count="3" />

      <NlEmpty
        v-else-if="loadError"
        type="network"
        title="收入账单加载失败"
        action-text="重试"
        @action="loadIncome"
      />

      <NlEmpty
        v-else-if="!bills.length"
        type="empty"
        title="暂无收入"
        description="完成订单后，服务费将在这里结算展示"
      />

      <ul v-else class="bills">
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
          <el-button
            link
            type="primary"
            class="bill__toggle"
            @click="expandedId = expandedId === b.id ? null : b.id"
          >
            {{ expandedId === b.id ? '收起' : '记账' }}
          </el-button>
        </li>
        <!-- 记账面板（ADR-0009）：完成后可补记代垫/服务费，保存后刷新收入金额 -->
        <li v-if="expandedId" class="bill bill--expanded">
          <OrderFeeItems :order-id="expandedId" editable @changed="loadIncome" />
        </li>
      </ul>
    </NlCard>

    <template #tabbar>
      <NlAppTabBar />
    </template>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter);
}

.kpi {
  &__cards {
    display: grid;
    /* auto-fit + minmax(120px)：375px 窄屏下两列并排，不横向溢出 */
    grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
    gap: var(--nl-space-3);
    padding: 0 var(--nl-gutter);
  }

  &__card {
    padding: var(--nl-space-3);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);

    &--success {
      background: var(--nl-success-bg);
      .kpi__val { color: var(--nl-success-text); }
    }

    &--primary {
      background: var(--nl-primary-ghost);
      .kpi__val { color: var(--nl-primary-text); }
    }
  }

  &__label {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }

  &__val {
    margin-top: 6px;
    font-size: var(--nl-font-h2);
    font-weight: 700;
    color: var(--nl-text-1);
  }

  &__range {
    padding: var(--nl-space-2) var(--nl-gutter) 0;
  }
}

.bill-note {
  margin: 0 0 var(--nl-space-2);
}

.bills {
  padding: 0 var(--nl-gutter);
  margin: 0;
  list-style: none;
}

.bill {
  display: grid;
  grid-template-columns: 56px 1fr auto auto;
  gap: var(--nl-space-3);
  align-items: center;
  padding: var(--nl-space-3) 0;
  border-bottom: 1px dashed var(--nl-divider);

  &--expanded {
    display: block;
    padding: var(--nl-space-3);
    background: var(--nl-bg-card);
    border-radius: var(--nl-radius-card);
    border-bottom: none;
  }

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
    font-size: var(--nl-font-body);
  }

  &__pay {
    margin-left: 6px;
  }

  &__fee {
    font-size: var(--nl-font-h2);
    font-weight: 700;
    color: var(--nl-primary-text);
  }
}
</style>
