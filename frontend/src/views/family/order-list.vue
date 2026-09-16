<script setup>
/**
 * 订单列表家属版（M-15）
 *
 * - 状态分段筛选：全部 / 待接单 / 服务中 / 已完成 / 已取消
 * - 订单卡：状态 chip + 医院 + 时间 + 老人 + 陪诊员 + 服务费
 * - 空态：「暂无订单，去帮父母下单吧」
 * - 进入订单详情：M-16（family/order-detail）
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { NlPhoneShell, NlCard, NlStatusChip, NlEmpty } from '@/components'

const router = useRouter()

const tab = ref('all')

const orders = ref([
  { id: 'OD20250916001', status: 'IN_SERVICE', hospital: '市第一人民医院', dept: '心血管内科', time: '09-16 09:00-11:00', elder: '张大爷', companion: '李师傅', fee: 120 },
  { id: 'OD20250918002', status: 'PENDING',    hospital: '市中医院',       dept: '中医骨伤科', time: '09-18 14:00-16:00', elder: '张大爷', companion: null, fee: 100 },
  { id: 'OD20250910003', status: 'REVIEWED',   hospital: '市妇幼保健院',   dept: '内科',       time: '09-10 09:30-11:00', elder: '王奶奶', companion: '陈师傅', fee: 150 },
  { id: 'OD20250908004', status: 'COMPLETED',  hospital: '社区卫生服务中心', dept: '全科',     time: '09-08 14:30-15:30', elder: '张大爷', companion: '李师傅', fee: 80 },
  { id: 'OD20250905005', status: 'CANCELLED',  hospital: '市第一人民医院', dept: '口腔科',     time: '09-05 10:00-11:30', elder: '张大爷', companion: null, fee: 120 }
])

const tabs = [
  { key: 'all', label: '全部' },
  { key: 'PENDING', label: '待接单' },
  { key: 'IN_SERVICE', label: '服务中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CANCELLED', label: '已取消' }
]

const filtered = computed(() => {
  if (tab.value === 'all') return orders.value
  return orders.value.filter((o) => o.status === tab.value)
})

function view(o) {
  router.push(`/family/order/${o.id}`)
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '我的订单' }">
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

    <NlEmpty
      v-if="!filtered.length"
      type="empty"
      title="暂无订单"
      description="去帮父母下单吧，3 步搞定"
      action-text="去下单"
      @action="router.push('/family/order/step1')"
    />

    <ul v-else class="orders">
      <li v-for="o in filtered" :key="o.id" class="order" @click="view(o)">
        <div class="order__head">
          <NlStatusChip :status="o.status" :dot="o.status === 'PENDING' || o.status === 'IN_SERVICE'" />
          <span class="order__fee is-num">¥{{ o.fee }}</span>
        </div>
        <div class="order__hospital">{{ o.hospital }}</div>
        <div class="nl-caption nl-text-muted order__meta">{{ o.dept }} · {{ o.elder }} · {{ o.time }}</div>
        <div class="order__foot">
          <span v-if="o.companion" class="nl-caption nl-text-muted">陪诊员：{{ o.companion }}</span>
          <span v-else class="nl-caption nl-text-weak">尚无陪诊员</span>
          <span class="order__id is-num">{{ o.id }}</span>
        </div>
      </li>
    </ul>
  </NlPhoneShell>
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
