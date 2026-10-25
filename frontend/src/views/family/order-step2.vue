<script setup>
/**
 * 下单 · Step2（M-08 · docs/design/design.md §4）
 *
 * - 顶部 NlStepHeader 2-2-3
 * - 医院列表（名称 + 等级 chip + 地址文字，禁止地图）
 * - 科室选择（**后端 `OrderCreateDTO.department` 是 @NotBlank，缺了必然 400**）
 * - 日期横向滚动：今天 + 6 天
 * - 时段网格
 * - 底部固定「下一步」
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
 *
 * 数据来源：`@/constants/hospitals`（真源是数据库 `sys_dict`，一期无字典接口）。
 * 每个医院都带经纬度 —— 下单时必须一并提交，否则订单坐标为 NULL，
 * M5 打卡的距离校验会被静默跳过（详见该常量文件的注释）。
 */
import { ref, computed } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPageShell, NlStepHeader } from '@/components'
import { HOSPITALS, DEPARTMENTS, estimateFee } from '@/constants/hospitals'
import { useOrderDraftStore } from '@/store/modules/orderDraft'
import { confirmThen } from '@/utils/confirm'

const route = useRoute()
const router = useRouter()
const draft = useOrderDraftStore()

/**
 * 就诊人：优先用 step1 传来的 id。
 *
 * ⚠️ **必须是 computed，不能是 setup 期求值一次的常量**。
 * 家属在 step1 选老人 A → step2 → 回 step1 改选老人 B → 再下一步：
 * 组件被复用（keep-alive，key 相同），常量仍停在 A → **订单下给了错误的人**，
 * 而 step2/step3 都没有就诊人提示，用户完全无从察觉。
 * 给错误的人下单是不可逆的 —— 这是本项目里后果最重的一类交互缺陷。
 */
const elderId = computed(() => Number(route.query.elderId) || draft.elderId || 0)

/** 就诊人姓名（step1 写入草稿时的脱敏名，如「张*海」） */
const elderName = computed(() => draft.elderName || '')

if (!elderId.value) {
  // 直接访问 /family/order/step2（比如刷新后 query 丢了）时不能瞎猜一个老人，
  // 否则会替用户给错误的人下单 —— 这是不可逆的动作，必须退回去重选
  ElMessage.warning('请先选择就诊人')
  router.replace('/family/order/step1')
}

const hospitals = HOSPITALS
const departments = DEPARTMENTS

// ⚠️ 刷新后**不预选**第一家医院：下单是要花钱的不可逆动作，
// 静默选中一家医院会让用户以为是自己选的，很可能给错的医院下单。
// draft 里已有选择（从 step3 退回 / 中途折返）则沿用。
const selectedHospital = ref(draft.hospital?.code || '')
const selectedDepartment = ref(draft.department || '')
const selectedDate = ref(0)
const selectedSlot = ref(null)

/* ====== 科室选择 ====== */
function pickDepartment(d) {
  selectedDepartment.value = d
  syncDraft()
}

/* ====== 日期 7 天滚动 ====== */
const dates = computed(() => {
  const arr = []
  const today = new Date()
  for (let i = 0; i < 7; i++) {
    const d = new Date(today.getTime() + i * 86400000)
    arr.push({
      idx: i,
      label: i === 0 ? '今天' : i === 1 ? '明天' : i === 2 ? '后天' : '',
      // 用于拼 visitTime 与判断时段是否已过
      iso: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`,
      date: `${d.getMonth() + 1}/${d.getDate()}`,
      week: '日一二三四五六'[d.getDay()]
    })
  }
  return arr
})

const slots = [
  { idx: 1, label: '09:00', range: '09:00 - 11:00' },
  { idx: 2, label: '11:00', range: '11:00 - 13:00' },
  { idx: 3, label: '14:00', range: '14:00 - 16:00' },
  { idx: 4, label: '16:00', range: '16:00 - 18:00' },
  { idx: 5, label: '19:00', range: '19:00 - 21:00' }
]

/**
 * 计算每个时段的可用性 + 今天是否全天已过。
 *
 * 后端 `OrderServiceImpl#create` 会校验 `visitTime.isAfter(now)`，不通过返回 3005。
 * 前端不拦的话，用户选了「今天 09:00」点下一步才被拒，而那时他已经填完了一整页 ——
 * 这类「填完才告诉你不合法」是适老化场景里最该避免的交互。
 *
 * 注意：`isToday` 必须按 `idx === 0` 判断（dates 的第 0 项就是今天），
 * 而不是拿字符串比对 —— `iso` 与本地日期在时区边界上未必一致。
 */
const slotDisabled = computed(() => {
  const map = {}
  const d = dates.value[selectedDate.value]
  const isToday = !!d && d.idx === 0

  if (!isToday) {
    // 非今天：全部可选
    slots.forEach((s) => {
      map[s.idx] = false
    })
    return { map, allOff: false }
  }

  const nowMin = new Date().getHours() * 60 + new Date().getMinutes()
  slots.forEach((s) => {
    const [h, m] = s.label.split(':').map(Number)
    map[s.idx] = h * 60 + m <= nowMin
  })
  return { map, allOff: slots.every((s) => map[s.idx]) }
})

/**
 * 把当前选择同步进下单草稿。
 *
 * <p>为什么「选中即写」：家属可能中途回 step1 改选就诊人再回来，
 * 之前选的医院 / 科室 / 时段不该丢。</p>
 *
 * <p>⚠️ 草稿只存内存（防共用手机串号，见 {@code store/modules/orderDraft.js}），
 * 页面刷新就没了 —— 这是刻意的隐私取舍，不是缺陷。</p>
 */
function syncDraft() {
  const hospital = hospitals.find((h) => h.code === selectedHospital.value)
  const date = dates.value[selectedDate.value]
  const slot = slots.find((s) => s.idx === selectedSlot.value)
  const visitTime = date && slot ? `${date.iso} ${slot.label}:00` : ''
  draft.setVisit(hospital, selectedDepartment.value, visitTime)
}

function pickHospital(h) {
  selectedHospital.value = h.code
  syncDraft()
}

function pickDate(idx) {
  selectedDate.value = idx
  // 切换日期后原来的时段可能已失效（比如从明天切回今天），清掉避免提交到过去的时间
  const disabled = slotDisabled.value.map?.[selectedSlot.value]
  if (disabled) selectedSlot.value = null
  syncDraft()
}

function pickSlot(s) {
  if (slotDisabled.value.map?.[s.idx]) return
  selectedSlot.value = s.idx
  syncDraft()
}

/** 当前选择对应的预估服务费（仅展示，提交时不传 fee 让后端算） */
const previewFee = computed(() => {
  const d = dates.value[selectedDate.value]
  const s = slots.find((x) => x.idx === selectedSlot.value)
  if (!d || !s) return null
  const [h, m] = s.label.split(':').map(Number)
  const dt = new Date(`${d.iso}T${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:00`)
  return estimateFee(dt)
})

/** 回 step1 换就诊人（step2 / step3 都要让用户看得见「这一单是给谁下的」） */
function changeElder() {
  router.push('/family/order/step1')
}

function next() {
  if (!selectedHospital.value) {
    ElMessage.warning('请选择医院')
    return
  }
  if (!selectedDepartment.value) {
    ElMessage.warning('请选择就诊科室')
    return
  }
  if (!selectedSlot.value) {
    ElMessage.warning('请选择时段')
    return
  }

  // ⚠️ 就诊人与 step1 写入草稿的必须一致。
  // 不一致说明中途换过人但草稿没同步 —— 宁可退回 step1 让他重选，
  // 也不能给错误的人下单。
  if (elderId.value !== draft.elderId) {
    ElMessage.warning('就诊人已变更，请重新选择就诊人')
    router.replace('/family/order/step1')
    return
  }

  const hospital = hospitals.find((h) => h.code === selectedHospital.value)
  const date = dates.value[selectedDate.value]
  const slot = slots.find((s) => s.idx === selectedSlot.value)
  // 后端 visitTime 是 LocalDateTime，Jackson 默认按 "yyyy-MM-dd HH:mm:ss" 反序列化
  const visitTime = `${date.iso} ${slot.label}:00`

  draft.setElder(elderId.value, draft.elderName)
  draft.setVisit(hospital, selectedDepartment.value, visitTime)

  // 把就诊人 id 带到 step3：step3 提交前要拿它与草稿交叉核对
  router.push({ path: '/family/order/step3', query: { elderId: elderId.value } })
}

/**
 * 离开 step2 前的确认。
 *
 * <p>草稿不完整就离开 = 用户填的医院 / 科室 / 时段白填了。
 * 回 step1 换就诊人是正常流程，不拦。</p>
 */
onBeforeRouteLeave(async (to) => {
  if (to.path === '/family/order/step1') return true
  if (draft.isComplete) return true
  // confirmThen 把「用户是否点了确认」变成布尔：点「继续填写」返回 false → 留在本页
  const ok = await confirmThen(() =>
    ElMessageBox.confirm(
      '本次填写的就诊信息还不完整，离开将丢失。确定离开吗？',
      '确认离开',
      { confirmButtonText: '确定离开', cancelButtonText: '继续填写', type: 'warning' }
    )
  )
  if (!ok) return false
  // 用户明确要放弃 → 清掉草稿，避免残留的选择漏进下一单
  draft.reset()
  return true
})
</script>

<template>
  <NlPageShell title="选择医院与时间" has-cta>
    <NlStepHeader :model-value="2" />

    <!--
      就诊人条：这一单是给谁下的，必须一直看得见。
      ⚠️ 此前 step2 完全不展示就诊人 —— 用户在这里无从确认「我正在给谁下单」，
      而「给错误的人下单」是不可逆的。
    -->
    <section class="block">
      <div class="elder-bar">
        <div class="elder-bar__main">
          <span class="nl-caption nl-text-muted">就诊人</span>
          <span class="nl-h3">{{ elderName || '未选择' }}</span>
        </div>
        <el-button text type="primary" @click="changeElder">更换</el-button>
      </div>
    </section>

    <!-- 医院列表 -->
    <section class="block">
      <h2 class="nl-h2 block__title">选择医院</h2>
      <ul role="list" class="hospital-list">
        <li
v-for="h in hospitals" :key="h.code" role="button" tabindex="0"
          :class="['hospital-item', { 'is-active': selectedHospital === h.code }]"
          @keydown.enter.prevent="pickHospital(h)"
          @keydown.space.prevent="pickHospital(h)"
          @click="pickHospital(h)"
        >
          <div class="hospital-item__main">
            <div class="hospital-item__head">
              <span class="nl-h3">{{ h.name }}</span>
              <span class="hospital-item__level">{{ h.level }}</span>
            </div>
            <p class="nl-caption nl-text-muted hospital-item__addr">{{ h.address }}</p>
          </div>
          <span class="hospital-item__radio" aria-hidden="true">
            <svg v-if="selectedHospital === h.code" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 8l3.5 3.5L13 5" />
            </svg>
          </span>
        </li>
      </ul>
    </section>

    <!-- 科室 -->
    <section class="block">
      <h2 class="nl-h2 block__title">就诊科室</h2>
      <div class="chips">
        <button
          v-for="d in departments"
          :key="d"
          :class="['chips__item', { 'is-active': selectedDepartment === d }]"
          type="button"
          @click="pickDepartment(d)"
        >
          {{ d }}
        </button>
      </div>
    </section>

    <!-- 日期 -->
    <section class="block">
      <h2 class="nl-h2 block__title">就诊日期</h2>
      <ul role="list" class="date-track">
        <li
v-for="d in dates" :key="d.idx" role="button" tabindex="0"
          :class="['date-item', { 'is-active': selectedDate === d.idx }]"
          @keydown.enter.prevent="pickDate(d.idx)"
          @keydown.space.prevent="pickDate(d.idx)"
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
      <p v-if="slotDisabled.allOff" class="nl-caption nl-text-muted slot-tip">
        今天的时段都已过，请选择明天及以后的日期
      </p>
      <ul role="list" class="slot-grid">
        <li
v-for="s in slots" :key="s.idx" role="button" tabindex="0"
          :class="[
            'slot-item',
            {
              'is-active': selectedSlot === s.idx,
              'is-full': slotDisabled.map?.[s.idx]
            }
          ]"
          @keydown.enter.prevent="pickSlot(s)"
          @keydown.space.prevent="pickSlot(s)"
          @click="pickSlot(s)"
        >
          <div class="slot-item__time is-num">{{ s.label }}</div>
          <div class="nl-caption nl-text-muted is-num">{{ s.range.split(' - ')[1] }}</div>
        </li>
      </ul>
    </section>

    <template #cta>
      <div class="cta">
        <span v-if="previewFee" class="cta__fee is-num">
          预估服务费 <strong>¥{{ previewFee }}</strong>
        </span>
        <el-button type="primary" size="large" round class="cta__btn" @click="next">
          下一步
        </el-button>
      </div>
    </template>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.cta {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-2);

  &__fee {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    text-align: center;

    strong {
      font-size: var(--nl-font-h3);
      color: var(--nl-primary-text);
    }
  }

  &__btn {
    width: 100%;
    font-size: var(--nl-font-h3);
    font-weight: 500;
  }
}

.block {
  padding: 0 var(--nl-gutter);
  margin-bottom: var(--nl-space-5);

  &__title {
    margin: 0 0 var(--nl-space-3);
  }
}

.slot-tip {
  margin: -8px 0 var(--nl-space-2);
}

/* ---------- 就诊人条 ---------- */
.elder-bar {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;
  justify-content: space-between;
  padding: var(--nl-space-3);
  background: var(--nl-primary-ghost);
  border: 1px solid var(--nl-primary);
  border-radius: var(--nl-radius-card);

  &__main {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }
}

/* ---------- 医院 ---------- */
.hospital-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
  max-height: 300px;
  overflow-y: auto;
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
    flex-shrink: 0;
    padding: 1px 6px;
    font-size: var(--nl-font-caption);
    font-weight: 500;
    color: var(--nl-primary-text);
    background: var(--nl-primary-light);
    border-radius: 4px;
  }

  &__addr {
    margin: 4px 0 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__radio {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    color: var(--nl-bg-card);
    background: var(--nl-border-strong);
    border-radius: 50%;

    .is-active & {
      background: var(--nl-primary);
    }
  }
}

/* ---------- 科室 chips ---------- */
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

  &__item {
    padding: 6px 14px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-primary-text);
      background: var(--nl-primary-light);
      border-color: var(--nl-primary);
    }
  }
}

/* ---------- 日期 ---------- */
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
    font-size: var(--nl-font-caption);
  }

  &__date {
    margin-top: 2px;
    font-size: var(--nl-font-h3);
    font-weight: 600;
  }
}

/* ---------- 时段 ---------- */
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
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border-color: var(--nl-divider);
    cursor: not-allowed;
  }

  &__time {
    font-size: var(--nl-font-h3);
    font-weight: 600;
  }
}
</style>
