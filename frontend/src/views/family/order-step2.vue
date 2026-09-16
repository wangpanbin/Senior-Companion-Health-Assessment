<script setup>
/**
 * 下单 · Step2（M-08 · design.md §4）
 *
 * - 顶部 NlStepHeader 2-2-3
 * - 医院列表（名称 + 「三甲」chip + 距离文字，禁止地图）
 * - 日期横向滚动：今天 + 6 天
 * - 时段网格：3×2（08-10 / 10-12 / 13-15 / 15-17 / 19-21）
 * - 底部固定「下一步」
 */
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlStepHeader } from '@/components'

const route = useRoute()
const router = useRouter()

/** mock 医院数据（M4 / M11 上线后由 /api/order/hospitals 提供） */
const hospitals = [
  { id: 1, name: '市第一人民医院', level: '三甲', distance: '2.1km', price: 120 },
  { id: 2, name: '市中医院',       level: '三甲', distance: '3.5km', price: 100 },
  { id: 3, name: '市妇幼保健院',   level: '三甲', distance: '4.8km', price: 150 },
  { id: 4, name: '社区卫生服务中心', level: '社区', distance: '1.0km', price: 80 }
]

const selectedHospital = ref(1)
const selectedDate = ref(0) // index
const selectedSlot = ref(1) // 09:00-11:00 网格索引

/* ====== 日期 7 天滚动 ====== */
const dates = computed(() => {
  const arr = []
  const today = new Date()
  for (let i = 0; i < 7; i++) {
    const d = new Date(today.getTime() + i * 86400000)
    arr.push({
      idx: i,
      label: i === 0 ? '今天' : i === 1 ? '明天' : i === 2 ? '后天' : '',
      date: `${d.getMonth() + 1}/${d.getDate()}`,
      week: '日一二三四五六'[d.getDay()]
    })
  }
  return arr
})

const slots = [
  { idx: 1, label: '09:00',  range: '09:00 - 11:00' },
  { idx: 2, label: '11:00',  range: '11:00 - 13:00', full: false },
  { idx: 3, label: '13:00',  range: '13:00 - 15:00' },
  { idx: 4, label: '15:00',  range: '15:00 - 17:00' },
  { idx: 5, label: '19:00',  range: '19:00 - 21:00' }
]

function pickHospital(h) {
  selectedHospital.value = h.id
}

function pickDate(idx) {
  selectedDate.value = idx
}

function pickSlot(s) {
  if (s.full) return
  selectedSlot.value = s.idx
}

function next() {
  if (!selectedHospital.value) {
    ElMessage.warning('请选择医院')
    return
  }
  if (selectedDate.value == null) {
    ElMessage.warning('请选择日期')
    return
  }
  if (!selectedSlot.value) {
    ElMessage.warning('请选择时段')
    return
  }
  const hospital = hospitals.find((h) => h.id === selectedHospital.value)
  const date = dates.value[selectedDate.value]
  const slot = slots.find((s) => s.idx === selectedSlot.value)
  router.push({
    path: '/family/order/step3',
    query: {
      elderId: route.query.elderId,
      hospitalId: hospital.id,
      date: date.date,
      slot: slot.idx,
      hospitalName: hospital.name,
      slotRange: slot.range
    }
  })
}
</script>

<template>
  <NlPhoneShell
    :nav="{ title: '选择医院与时间' }"
    :has-cta="true"
  >
    <NlStepHeader :model-value="2" />

    <!-- 医院列表 -->
    <section class="block">
      <h2 class="nl-h2 block__title">附近医院</h2>
      <ul class="hospital-list">
        <li
          v-for="h in hospitals"
          :key="h.id"
          :class="['hospital-item', { 'is-active': selectedHospital === h.id }]"
          @click="pickHospital(h)"
        >
          <div class="hospital-item__main">
            <div class="hospital-item__head">
              <span class="nl-h3">{{ h.name }}</span>
              <span class="hospital-item__level">{{ h.level }}</span>
            </div>
            <p class="nl-caption nl-text-muted">距离您 {{ h.distance }} · 服务费 ¥{{ h.price }}</p>
          </div>
          <span class="hospital-item__price is-num">¥{{ h.price }}</span>
        </li>
      </ul>
    </section>

    <!-- 日期 -->
    <section class="block">
      <h2 class="nl-h2 block__title">就诊日期</h2>
      <ul class="date-track">
        <li
          v-for="d in dates"
          :key="d.idx"
          :class="['date-item', { 'is-active': selectedDate === d.idx }]"
          @click="pickDate(d.idx)"
        >
          <div class="date-item__label">{{ d.label || d.week }}</div>
          <div class="date-item__date is-num">{{ d.date }}</div>
        </li>
      </ul>
    </section>

    <!-- 时段 -->
    <section class="block">
      <h2 class="nl-h2 block__title">就诊时段</h2>
      <ul class="slot-grid">
        <li
          v-for="s in slots"
          :key="s.idx"
          :class="['slot-item', { 'is-active': selectedSlot === s.idx, 'is-full': s.full }]"
          @click="pickSlot(s)"
        >
          <div class="slot-item__time is-num">{{ s.label }}</div>
          <div class="nl-caption nl-text-muted is-num">{{ s.range.split(' - ')[1] }}</div>
        </li>
      </ul>
    </section>

    <template #cta>
      <el-button type="primary" size="large" round class="step__cta" @click="next">
        下一步
      </el-button>
    </template>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.step__cta {
  width: 100%;
  font-size: 16px;
  font-weight: 500;
}

.block {
  padding: 0 var(--nl-gutter);

  &__title {
    margin: 0 0 var(--nl-space-3);
  }
}

.hospital-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.hospital-item {
  display: flex;
  align-items: center;
  gap: var(--nl-space-3);
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1.5px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  cursor: pointer;

  &.is-active {
    border-color: var(--nl-primary);
    background: var(--nl-primary-ghost);
  }

  &__main {
    flex: 1;
    min-width: 0;
  }

  &__head {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
  }

  &__level {
    padding: 1px 6px;
    font-size: 11px;
    font-weight: 500;
    color: var(--nl-primary);
    background: var(--nl-primary-light);
    border-radius: 4px;
  }

  &__price {
    font-size: 20px;
    font-weight: 700;
    color: var(--nl-primary);
  }
}

.date-track {
  display: flex;
  gap: var(--nl-space-2);
  padding: 0;
  margin: 0;
  overflow-x: auto;
  list-style: none;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.date-item {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 64px;
  background: var(--nl-bg-card);
  border: 1.5px solid var(--nl-border);
  border-radius: var(--nl-radius-btn);
  cursor: pointer;

  &.is-active {
    color: var(--nl-text-inverse);
    background: var(--nl-primary);
    border-color: var(--nl-primary);
  }

  &__label {
    font-size: 11px;
  }

  &__date {
    margin-top: 2px;
    font-size: 16px;
    font-weight: 600;
  }
}

.slot-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-2);
  padding: 0;
  margin: 0;
  list-style: none;
}

.slot-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--nl-space-3) 0;
  background: var(--nl-bg-card);
  border: 1.5px solid var(--nl-border);
  border-radius: var(--nl-radius-btn);
  cursor: pointer;

  &.is-active {
    color: var(--nl-text-inverse);
    background: var(--nl-primary);
    border-color: var(--nl-primary);

    .nl-caption {
      color: var(--nl-text-inverse);
      opacity: 0.8;
    }
  }

  &.is-full {
    color: var(--nl-text-3);
    background: var(--nl-bg-sunken);
    border-color: var(--nl-divider);
    cursor: not-allowed;
  }

  &__time {
    font-size: 16px;
    font-weight: 600;
  }
}
</style>
