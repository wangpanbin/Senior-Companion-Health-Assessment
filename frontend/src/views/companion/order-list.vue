<script setup>
/**
 * 陪诊员订单列表（M-15 · design.md §1.2 P1）
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { NlPhoneShell, NlCard, NlStatusChip, NlEmpty } from '@/components'

const router = useRouter()
const tab = ref('all')

const orders = ref([
  { id: 'OD20250916001', status: 'IN_SERVICE', hospital: '市第一人民医院', elder: '张大爷', time: '09-16 09:00-11:00', fee: 120 },
  { id: 'OD20250915002', status: 'COMPLETED',  hospital: '市中医院',       elder: '王奶奶', time: '09-15 14:00-16:00', fee: 100 },
  { id: 'OD20250914003', status: 'COMPLETED',  hospital: '市妇幼保健院',   elder: '陈七',   time: '09-14 10:00-12:00', fee: 150 },
  { id: 'OD20250912004', status: 'REVIEWED',   hospital: '社区卫生服务中心', elder: '赵八', time: '09-12 09:30-10:30', fee: 80 },
  { id: 'OD20250910005', status: 'CANCELLED',  hospital: '市第一人民医院', elder: '李大爷', time: '09-10 14:00-15:30', fee: 120 }
])

const tabs = [
  { key: 'all', label: '全部' },
  { key: 'IN_SERVICE', label: '进行中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'REVIEWED', label: '已评价' }
]

const filtered = computed(() => {
  if (tab.value === 'all') return orders.value
  return orders.value.filter((o) => o.status === tab.value)
})

function view(o) {
  router.push(`/companion/execute/${o.id}`)
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
      title="暂无订单"
      description="去大厅看看有没有合适的订单"
    />

    <ul v-else class="orders">
      <li v-for="o in filtered" :key="o.id" class="order" @click="view(o)">
        <div class="order__head">
          <NlStatusChip :status="o.status" :dot="o.status === 'IN_SERVICE'" />
          <span class="order__fee is-num">¥{{ o.fee }}</span>
        </div>
        <div class="order__hosp">{{ o.hospital }}</div>
        <div class="nl-caption nl-text-muted order__meta">{{ o.elder }} · {{ o.time }}</div>
        <div class="order__id is-num">{{ o.id }}</div>
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
    color: var(--nl-text-3);
    font-size: 11px;
    margin-top: var(--nl-space-2);
  }
}
</style>
