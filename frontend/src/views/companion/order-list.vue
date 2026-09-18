<script setup>
/**
 * 陪诊员订单列表（M-15 · design.md §1.2 P1）
 *
 * 数据来源：GET /order（listMyOrders，按当前角色自动返回陪诊员接的的单）
 *
 * 切 tab 直接查后端（传 OrderStatus 枚举名），不在前端做本地过滤；
 * 本地过滤只能筛出「已加载的那一页」。
 * 列表口径：姓名脱敏、不返回地址/备注（属后端刻意隐私设计）。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NlPageShell, NlStatusChip, NlEmpty, NlSkeleton } from '@/components'
import { listMyOrders } from '@/api/order'
import { formatVisitTime, formatMoney } from '@/utils/format'

const router = useRouter()

const tab = ref('all')
const orders = ref([])
const loading = ref(false)
const total = ref(0)

/** tab key 即后端 OrderStatus 枚举名（'all' 除外） */
const tabs = [
  { key: 'all', label: '全部' },
  { key: 'ACCEPTED', label: '已接单' },
  { key: 'IN_SERVICE', label: '服务中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'REVIEWED', label: '已评价' }
]

async function loadOrders() {
  loading.value = true
  try {
    const params = { page: 1, size: 50 }
    if (tab.value !== 'all') params.status = tab.value
    const data = await listMyOrders(params)
    orders.value = data?.records || []
    total.value = data?.total || 0
  } catch {
    orders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

watch(tab, loadOrders)

function view(o) {
  if (o.status === 'PENDING') {
    router.push('/companion/hall')
  } else {
    router.push(`/companion/execute/${o.id}`)
  }
}

const isEmpty = computed(() => !loading.value && !orders.value.length)

onMounted(loadOrders)
</script>

<template>
  <NlPageShell title="我的订单">
    <section class="filter-bar">
      <button
        v-for="t in tabs"
        :key="t.key"
        :class="['filter-bar__btn', { 'is-active': tab === t.key }]"
        @click="tab = t.key"
      >
        {{ t.label }}
      </button>
    </section>

    <NlSkeleton v-if="loading" :count="3" class="list-pad" />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无订单"
      description="去大厅看看有没有合适的订单"
    />

    <ul v-else class="orders">
      <li v-for="o in orders" :key="o.id" class="order" @click="view(o)">
        <div class="order__head">
          <NlStatusChip :status="o.status" :text="o.statusLabel" :dot="o.status === 'IN_SERVICE'" />
          <span class="order__fee is-num">{{ formatMoney(o.fee) }}</span>
        </div>
        <div class="order__hosp">{{ o.hospital }}</div>
        <div class="nl-caption nl-text-muted order__meta">
          {{ o.department }}<template v-if="o.elderName"> · {{ o.elderName }}</template>
          <template v-if="o.elderAge != null"> {{ o.elderAge }}岁</template>
          · {{ formatVisitTime(o.visitTime) }}
        </div>
        <div class="order__id is-num">{{ o.orderNo }}</div>
      </li>
    </ul>

    <p v-if="!loading && orders.length" class="nl-caption nl-text-weak list-foot">
      共 {{ total }} 单
    </p>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.filter-bar {
  display: flex;
  gap: var(--nl-space-2);
  padding: 0 var(--nl-gutter);

  &__btn {
    padding: 6px 14px;
    font-size: 13px;
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;
    white-space: nowrap;

    &.is-active {
      color: var(--nl-text-inverse);
      background: var(--nl-primary);
      border-color: var(--nl-primary);
    }
  }
}

.list-pad {
  padding: 0 var(--nl-gutter);
}

.orders {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0 var(--nl-gutter);
  margin: 0;
  list-style: none;
}

.order {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  cursor: pointer;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--nl-space-2);
  }

  &__fee {
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-primary);
  }

  &__hosp {
    font-size: 15px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__meta {
    margin-top: 4px;
  }

  &__id {
    margin-top: var(--nl-space-2);
    color: var(--nl-text-3);
    font-size: 11px;
  }
}

.list-foot {
  margin: var(--nl-space-4) 0 0;
  text-align: center;
}
</style>
