<script setup>
/**
 * 订单列表家属版（M-15）
 *
 * - 状态分段筛选：全部 / 待接单 / 已接单 / 服务中 / 已完成 / 已取消
 * - 订单卡：状态 chip + 医院 + 科室 + 时间 + 老人 + 陪诊员 + 服务费
 * - 空态：「暂无订单，去帮父母下单吧」
 * - 进入订单详情：M-16（family/order-detail）
 *
 * 数据来源：`GET /api/order`（M4 已交付）。
 *
 * ⚠️ 三处必须对齐后端的口径：
 *   1. 分页参数是 `page` / `size`（**不是 pageNum / pageSize**），
 *      且 `size` 上限 100 —— 见 `common/PageQuery`。
 *   2. `status` 支持逗号分隔多值，但**非法取值后端直接 400** 而不是忽略
 *      （`OrderServiceImpl#parseStatuses`）—— 所以 tab 的 key 必须是真实枚举名。
 *   3. 列表口径（`OrderVO.ofList`）**不返回地址与备注**，姓名与陪诊员名已脱敏。
 *      这是后端刻意的隐私设计，不是接口缺字段。
 *
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
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

/** tab key 直接就是后端 OrderStatus 枚举名（'all' 除外），避免再维护一张映射表 */
const tabs = [
  { key: 'all', label: '全部' },
  { key: 'PENDING', label: '待接单' },
  { key: 'ACCEPTED', label: '已接单' },
  { key: 'IN_SERVICE', label: '服务中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CANCELLED', label: '已取消' }
]

async function loadOrders() {
  loading.value = true
  try {
    const params = { page: 1, size: 50 }
    if (tab.value !== 'all') {
      params.status = tab.value
    }
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

// 切换 tab 直接查后端，不在前端做本地过滤：
// 本地过滤只能筛出「已加载的那一页」，用户会以为只有这几条待接单
watch(tab, loadOrders)

function view(o) {
  router.push(`/family/order/${o.id}`)
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
      description="去帮父母下单吧，3 步搞定"
      action-text="去下单"
      @action="router.push('/family/order/step1')"
    />

    <ul v-else class="orders">
      <li v-for="o in orders" :key="o.id" class="order" @click="view(o)">
        <div class="order__head">
          <!-- 优先用后端下发的 statusLabel；chip 的 scope 只做兜底 -->
          <NlStatusChip :status="o.status" :text="o.statusLabel" :dot="o.status === 'PENDING' || o.status === 'IN_SERVICE'" />
          <span class="order__fee is-num">{{ formatMoney(o.fee) }}</span>
        </div>
        <div class="order__hospital">{{ o.hospital }}</div>
        <div class="nl-caption nl-text-muted order__meta">
          {{ o.department }} · {{ o.elderName }}<template v-if="o.elderAge != null"> {{ o.elderAge }}岁</template>
          · {{ formatVisitTime(o.visitTime) }}
        </div>
        <div class="order__foot">
          <span v-if="o.companionName" class="nl-caption nl-text-muted">陪诊员：{{ o.companionName }}</span>
          <span v-else class="nl-caption nl-text-weak">尚无陪诊员</span>
          <span class="order__id is-num">{{ o.orderNo }}</span>
        </div>
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
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

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

.orders {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0 var(--nl-gutter);
  margin: 0;
  list-style: none;
}

.list-pad {
  padding: 0 var(--nl-gutter);
}

.list-foot {
  margin: var(--nl-space-4) 0 0;
  text-align: center;
}

.order {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  cursor: pointer;
  transition: box-shadow 0.18s;

  &:active {
    transform: scale(0.99);
  }

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

  &__hospital {
    font-size: 15px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__meta {
    margin-top: 4px;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--nl-space-3);
    padding-top: var(--nl-space-2);
    border-top: 1px dashed var(--nl-divider);
  }

  &__id {
    color: var(--nl-text-3);
    font-size: 11px;
  }
}
</style>
