<script setup>
/**
 * 陪诊员·我的收入与排行（M-21 · design.md §1.2 P1）
 *
 * - 顶部 3 个 KPI：本周接单 / 平均评分 / 本周收入
 * - 排名卡：我排第 3 名（奖牌色）
 * - 收入明细列表
 */
import { ref } from 'vue'
import { NlPhoneShell, NlCard } from '@/components'

const kpi = ref({
  weekOrders: 12,
  avgRating: 4.9,
  weekIncome: 1480,
  rank: 3
})

const bills = ref([
  { date: '09-16', hospital: '市第一人民医院', elder: '张大爷', fee: 120, status: '已结算' },
  { date: '09-15', hospital: '市中医院',       elder: '王奶奶', fee: 150, status: '已结算' },
  { date: '09-14', hospital: '社区卫生服务中心', elder: '李大爷', fee: 80,  status: '已结算' },
  { date: '09-13', hospital: '市第一人民医院', elder: '陈大爷', fee: 120, status: '已结算' },
  { date: '09-12', hospital: '市妇幼保健院',   elder: '赵奶奶', fee: 180, status: '已结算' }
])
</script>

<template>
  <NlPhoneShell :nav="{ title: '我的收入' }">
    <!-- KPI -->
    <section class="kpi">
      <div class="kpi__card">
        <div class="kpi__label">本周接单</div>
        <div class="kpi__val is-num">{{ kpi.weekOrders }}</div>
      </div>
      <div class="kpi__card kpi__card--success">
        <div class="kpi__label">平均评分</div>
        <div class="kpi__val is-num">{{ kpi.avgRating }}</div>
      </div>
      <div class="kpi__card kpi__card--primary">
        <div class="kpi__label">本周收入</div>
        <div class="kpi__val is-num">¥{{ kpi.weekIncome }}</div>
      </div>
    </section>

    <!-- 排名 -->
    <NlCard class="rank-card" plain>
      <div class="rank">
        <span class="rank__medal rank__medal--bronze">{{ kpi.rank }}</span>
        <div class="rank__body">
          <div class="nl-h3 rank__title">本周陪诊员排行 No.{{ kpi.rank }}</div>
          <p class="nl-caption nl-text-muted">距上一名还差 1 单，加油！</p>
        </div>
        <el-button type="primary" plain round size="small">查看完整排行</el-button>
      </div>
    </NlCard>

    <!-- 账单 -->
    <NlCard title="本周账单" plain>
      <ul class="bills">
        <li v-for="b in bills" :key="b.date + b.elder" class="bill">
          <div class="bill__date is-num">{{ b.date }}</div>
          <div class="bill__body">
            <div class="nl-h3 bill__hosp">{{ b.hospital }}</div>
            <div class="nl-caption nl-text-muted">就诊人：{{ b.elder }} · {{ b.status }}</div>
          </div>
          <div class="bill__fee is-num">¥{{ b.fee }}</div>
        </li>
      </ul>
    </NlCard>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.kpi {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-3);

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
    font-size: 24px;
    font-weight: 700;
    color: var(--nl-text-1);
  }
}

.rank-card {
  background: linear-gradient(135deg, var(--nl-warning-bg) 0%, var(--nl-bg-card) 70%);
}

.rank {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;

  &__medal {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    font-size: 18px;
    font-weight: 700;
    color: #fff;
    border-radius: 50%;

    &--gold { background: #F2A900; }
    &--silver { background: #B1B5BB; }
    &--bronze { background: #D78A4E; }
  }

  &__body {
    flex: 1;
  }
}

.bills {
  padding: 0;
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

  &__fee {
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-primary);
  }
}
</style>
