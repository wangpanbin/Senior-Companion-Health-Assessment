<script setup>
/**
 * 用药管理家属版（M-12 + M-13 · design.md §4 · PRD §2.2 §6.1 合规红线）
 *
 * 合规口径（design.md §6.1 / 后端 docs/api/05-medication.md）：
 *   - 药品卡禁止出现营销/引导性表述（含可能被视为医疗建议的话）
 *   - 剂量输入框 label 固定为「家属录入剂量」
 *   - caption 写明「剂量由家属按医嘱录入，系统不提供建议」
 *   - 选药后展示后端下发的 disclaimer 免责声明（抽屉内 NlNoticeBar）
 *   - 页面底部固定免责声明条（NlComplianceBar）
 *
 * 数据来源（全部在 @/api/medication + @/api/user，已核对后端 DTO/VO）：
 *   - listElder({page,size})                 → 选老人（多老人可切换，默认第一个）
 *   - getTodayTasks(elderId)                  → 今日待服（返回 List<MedicationTaskVO>，直接是数组）
 *   - getMedicationCalendar({elderId,startDate,endDate}) → 服药日历（按天分组 + 区间汇总）
 *   - listMedicationPlans({page,size,elderId})→ 用药计划（分页，data.records）
 *   - createMedicationPlan(data) / updateMedicationPlan(id,data) / disableMedicationPlan(id)
 *   - confirmMedication(taskId, data)         → 家属代确认服药（无「标记漏服」接口，漏服由定时任务判定）
 *   - listMedicineDict({page,size,keyword})   → 选药 + disclaimer
 *
 * ⚠️ 字段真值（已读后端源码，未猜测）：
 *   - 服药任务的备注列是 `confirmRemark`（VO 字段名），不是 `remark`
 *   - 计划「结束日期」为空表示长期；frequency 为整数，timePoints 为 HH:mm 字符串数组
 *   - 日历响应为「区间元信息 + summary + days[]」，不是扁平数组；days 只含有任务的日期
 */
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  NlPageShell, NlAppTabBar, NlCard, NlStatusChip, NlComplianceBar, NlNoticeBar, NlEmpty, NlSkeleton
} from '@/components'
import { useResponsive } from '@/composables/useResponsive'
import { listElder } from '@/api/user'
import {
  getTodayTasks, getMedicationCalendar, listMedicationPlans,
  createMedicationPlan, updateMedicationPlan, disableMedicationPlan,
  confirmMedication, listMedicineDict
} from '@/api/medication'
import { today, formatDate, formatTime, MEAL_RELATION_TEXT, labelOf } from '@/utils/format'

/* ====== 当前老人（多老人切换，默认第一个） ====== */
const elders = ref([])
const currentElderId = ref(null)
const elderLoading = ref(false)

const pad = (n) => String(n).padStart(2, '0')
const dateKey = (y, m, d) => `${y}-${pad(m)}-${pad(d)}`

/* ====== 当前月份 / 月历 ====== */
const [ty, tm] = today().split('-')
const curYear = ref(Number(ty))
const curMonth = ref(Number(tm)) // 1-12

const monthName = computed(() => `${curYear.value} 年 ${curMonth.value} 月`)

function prev() {
  if (curMonth.value === 1) {
    curYear.value -= 1
    curMonth.value = 12
  } else {
    curMonth.value -= 1
  }
}
function next() {
  if (curMonth.value === 12) {
    curYear.value += 1
    curMonth.value = 1
  } else {
    curMonth.value += 1
  }
}

/* 日历数据：dateKey -> { hasTaken, hasPending, hasMissed } */
const calendarData = ref({})
const calendarSummary = ref(null)
const calendarLoading = ref(false)

const weeks = ['一', '二', '三', '四', '五', '六', '日']

const calendar = computed(() => {
  const first = new Date(curYear.value, curMonth.value - 1, 1)
  const firstDay = first.getDay() // 0-6, 日=0
  const last = new Date(curYear.value, curMonth.value, 0)
  const days = last.getDate()
  const offset = (firstDay + 6) % 7 // 周一 0, 周日 6

  const list = []
  for (let i = 0; i < offset; i++) list.push(null)
  for (let d = 1; d <= days; d++) {
    const isToday =
      d === Number(tm) && curMonth.value === Number(tm) && curYear.value === Number(ty)
    const key = dateKey(curYear.value, curMonth.value, d)
    const info = calendarData.value[key]
    const dots = info
      ? [
          info.hasTaken && 'taken',
          info.hasPending && 'pending',
          info.hasMissed && 'missed'
        ].filter(Boolean)
      : []
    list.push({ d, isToday, dots })
  }
  while (list.length % 7 !== 0) list.push(null)
  return list
})

/* ====== tab ====== */
const tab = ref('today') // calendar / today / plan

/* ====== 今日药品（真实接口） ====== */
const todayTasks = ref([])
const todayLoading = ref(false)

/* ====== 计划（真实接口） ====== */
const plans = ref([])
const plansLoading = ref(false)
const planTotal = ref(0)

/* ====== 新建 / 编辑计划抽屉 ====== */
const planDialog = ref(false)
const planMode = ref('create') // create | edit
const editingPlanId = ref(null)
const planSaving = ref(false)
const medicineOptions = ref([])

/** < 768 让新建/编辑计划弹窗 fullscreen（T-08） */
const { isMd } = useResponsive()
const medicineLoading = ref(false)
const selectedDisclaimer = ref('')

const MEAL_OPTIONS = [
  { value: 'BEFORE_MEAL', label: '饭前' },
  { value: 'AFTER_MEAL', label: '饭后' },
  { value: 'ANY', label: '不限' }
]

const planForm = ref({
  medicineId: null,
  dosage: '',
  timePoints: ['08:00'],
  startDate: '',
  endDate: '',
  mealRelation: 'AFTER_MEAL',
  remark: ''
})

/* ====== 加载逻辑 ====== */
async function loadElders() {
  elderLoading.value = true
  try {
    const data = await listElder({ page: 1, size: 100 })
    elders.value = data?.records || []
    if (currentElderId.value == null && elders.value.length) {
      currentElderId.value = elders.value[0].id
    }
  } catch {
    elders.value = []
  } finally {
    elderLoading.value = false
  }
}

async function loadToday() {
  if (currentElderId.value == null) return
  todayLoading.value = true
  try {
    const data = await getTodayTasks(currentElderId.value)
    todayTasks.value = Array.isArray(data) ? data : []
  } catch {
    todayTasks.value = []
  } finally {
    todayLoading.value = false
  }
}

async function loadCalendar() {
  if (currentElderId.value == null) return
  calendarLoading.value = true
  try {
    const startDate = dateKey(curYear.value, curMonth.value, 1)
    const lastDay = new Date(curYear.value, curMonth.value, 0).getDate()
    const endDate = dateKey(curYear.value, curMonth.value, lastDay)
    const data = await getMedicationCalendar({ elderId: currentElderId.value, startDate, endDate })
    const map = {}
    ;(data?.days || []).forEach((day) => {
      const key = day.date != null ? String(day.date) : ''
      const tasks = day.tasks || []
      const info = { hasTaken: false, hasPending: false, hasMissed: false }
      tasks.forEach((t) => {
        if (t.wasMissed || t.status === 'MISSED') info.hasMissed = true
        else if (t.status === 'PENDING') info.hasPending = true
        else info.hasTaken = true
      })
      if (key) map[key] = info
    })
    calendarData.value = map
    calendarSummary.value = data?.summary || null
  } catch {
    calendarData.value = {}
    calendarSummary.value = null
  } finally {
    calendarLoading.value = false
  }
}

async function loadPlans() {
  if (currentElderId.value == null) return
  plansLoading.value = true
  try {
    const data = await listMedicationPlans({ page: 1, size: 100, elderId: currentElderId.value })
    plans.value = data?.records || []
    planTotal.value = data?.total || 0
  } catch {
    plans.value = []
    planTotal.value = 0
  } finally {
    plansLoading.value = false
  }
}

function reloadAll() {
  loadToday()
  loadCalendar()
  loadPlans()
}

watch(currentElderId, (id) => {
  if (id != null) reloadAll()
})
watch([curYear, curMonth], () => {
  if (currentElderId.value != null) loadCalendar()
})

/* ====== 今日：确认服药（家属代确认） ====== */
const confirmingId = ref(null)

async function confirmTaken(m) {
  try {
    await ElMessageBox.confirm(
      `确认「${m.medicineName}」已服用？记录后将推送给所有绑定家属。`,
      '确认服药',
      { confirmButtonText: '确认已服', type: 'warning' }
    )
  } catch {
    return
  }
  confirmingId.value = m.id
  try {
    await confirmMedication(m.id, {})
    ElMessage.success('已记录')
    await loadToday()
  } catch {
    // 拦截器已弹错误提示，这里不再重复弹
  } finally {
    confirmingId.value = null
  }
}

/* ====== 计划：停用（保留历史服药记录） ====== */
async function disablePlan(p) {
  try {
    await ElMessageBox.confirm(
      `确认停用「${p.medicineName}」用药计划？停用后不再生成新的服药任务，但历史服药记录会完整保留。`,
      '停用计划',
      { confirmButtonText: '确认停用', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await disableMedicationPlan(p.id)
    ElMessage.success('已停用，历史服药记录保留')
    await loadPlans()
  } catch {
    // 拦截器已弹错误提示
  }
}

/* ====== 计划：新建 / 编辑 ====== */
async function openCreatePlan() {
  planMode.value = 'create'
  editingPlanId.value = null
  planForm.value = {
    medicineId: null,
    dosage: '',
    timePoints: ['08:00'],
    startDate: '',
    endDate: '',
    mealRelation: 'AFTER_MEAL',
    remark: ''
  }
  selectedDisclaimer.value = ''
  planDialog.value = true
  await loadMedicineOptions()
}

function openEditPlan(p) {
  planMode.value = 'edit'
  editingPlanId.value = p.id
  planForm.value = {
    medicineId: p.medicineId ?? null,
    dosage: p.dosage || '',
    timePoints: Array.isArray(p.timePoints) && p.timePoints.length ? [...p.timePoints] : ['08:00'],
    startDate: p.startDate != null ? String(p.startDate) : '',
    endDate: p.endDate != null ? String(p.endDate) : '',
    mealRelation: p.mealRelation || 'AFTER_MEAL',
    remark: p.remark || ''
  }
  selectedDisclaimer.value = ''
  planDialog.value = true
  loadMedicineOptions(p.medicineId)
}

async function loadMedicineOptions(preselectId) {
  medicineLoading.value = true
  try {
    const data = await listMedicineDict({ page: 1, size: 100 })
    medicineOptions.value = data?.records || []
    if (preselectId != null) {
      const opt = medicineOptions.value.find((x) => x.id === preselectId)
      if (opt) selectedDisclaimer.value = opt.disclaimer || ''
    }
  } catch {
    medicineOptions.value = []
  } finally {
    medicineLoading.value = false
  }
}

function onMedicineChange(id) {
  const opt = medicineOptions.value.find((x) => x.id === id)
  selectedDisclaimer.value = opt ? (opt.disclaimer || '') : ''
}

function addTimePoint() {
  if (planForm.value.timePoints.length < 4) planForm.value.timePoints.push('12:00')
}
function removeTimePoint(i) {
  if (planForm.value.timePoints.length > 1) planForm.value.timePoints.splice(i, 1)
}

function buildPlanPayload() {
  const f = planForm.value
  const payload = {
    medicineId: f.medicineId,
    dosage: f.dosage.trim(),
    frequency: f.timePoints.length,
    timePoints: [...f.timePoints],
    startDate: f.startDate,
    endDate: f.endDate || '',
    mealRelation: f.mealRelation,
    remark: f.remark ? f.remark.trim() : undefined
  }
  if (planMode.value === 'create') payload.elderId = currentElderId.value
  return payload
}

function validatePlan() {
  const f = planForm.value
  if (f.medicineId == null) {
    ElMessage.warning('请选择药品')
    return false
  }
  if (!f.dosage.trim()) {
    ElMessage.warning('请录入单次用量（按医嘱）')
    return false
  }
  if (f.dosage.trim().length > 50) {
    ElMessage.warning('单次用量不能超过 50 个字符')
    return false
  }
  if (f.timePoints.length < 1 || f.timePoints.length > 4) {
    ElMessage.warning('每日次数须为 1–4 次')
    return false
  }
  if (!f.mealRelation) {
    ElMessage.warning('请选择与饭点关系')
    return false
  }
  if (!f.startDate) {
    ElMessage.warning('请选择开始日期')
    return false
  }
  if (f.endDate && f.endDate < f.startDate) {
    ElMessage.warning('结束日期不能早于开始日期')
    return false
  }
  if (f.remark && f.remark.length > 200) {
    ElMessage.warning('备注不能超过 200 个字符')
    return false
  }
  return true
}

async function submitPlan() {
  if (!validatePlan()) return
  planSaving.value = true
  try {
    const payload = buildPlanPayload()
    if (planMode.value === 'create') {
      await createMedicationPlan(payload)
      ElMessage.success('计划已添加')
    } else {
      await updateMedicationPlan(editingPlanId.value, payload)
      ElMessage.success('已保存')
    }
    planDialog.value = false
    await loadPlans()
  } catch {
    // 拦截器已弹错误提示
  } finally {
    planSaving.value = false
  }
}

onMounted(loadElders)
</script>

<template>
  <NlPageShell title="用药管理" :has-tabs="true" :back="false">
    <!-- 老人切换（多老人） -->
    <section v-if="elders.length" class="elder-tabs">
      <button
        v-for="e in elders"
        :key="e.id"
        :class="['elder-tabs__btn', { 'is-active': e.id === currentElderId }]"
        @click="currentElderId = e.id"
      >
        {{ e.name || e.elderName || ('老人' + e.id) }}
      </button>
    </section>

    <!-- 顶部 Tab -->
    <section class="med-tabs">
      <button
        v-for="t in [
          { key: 'calendar', label: '日历' },
          { key: 'today', label: '今日' },
          { key: 'plan', label: '计划' }
        ]"
        :key="t.key"
        :class="['med-tabs__btn', { 'is-active': tab === t.key }]"
        @click="tab = t.key"
      >
        {{ t.label }}
      </button>
    </section>

    <!-- ==================== 日历视图 ==================== -->
    <NlCard v-if="tab === 'calendar'" class="calendar-card">
      <div class="cal-head">
        <button class="cal-head__nav" aria-label="上个月" @click="prev">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10 4L6 8l4 4" />
          </svg>
        </button>
        <span class="nl-h2 is-num cal-head__title">{{ monthName }}</span>
        <button class="cal-head__nav" aria-label="下个月" @click="next">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M6 4l4 4-4 4" />
          </svg>
        </button>
      </div>

      <div class="cal-weeks">
        <div v-for="w in weeks" :key="w" class="cal-weeks__cell">{{ w }}</div>
      </div>
      <NlSkeleton v-if="calendarLoading" :count="6" />
      <div v-else class="cal-grid">
        <div
          v-for="(cell, i) in calendar"
          :key="i"
          :class="['cal-cell', { 'is-today': cell?.isToday, 'is-empty': !cell }]"
        >
          <template v-if="cell">
            <span class="cal-cell__day is-num">{{ cell.d }}</span>
            <ul v-if="cell.dots.length" class="cal-cell__dots">
              <li v-for="n in cell.dots" :key="n" :class="['dot', 'is-' + n]" />
            </ul>
          </template>
        </div>
      </div>

      <div v-if="!calendarLoading && calendarSummary" class="cal-summary nl-caption nl-text-muted">
        本区间 已服 {{ calendarSummary.takenCount != null ? calendarSummary.takenCount : 0 }}
        · 待服 {{ calendarSummary.pendingCount != null ? calendarSummary.pendingCount : 0 }}
        · 漏服 {{ calendarSummary.missedCount != null ? calendarSummary.missedCount : 0 }}
        <template v-if="calendarSummary.missedRate">（漏服率 {{ calendarSummary.missedRate }}）</template>
      </div>

      <div class="cal-legend">
        <span><i class="dot is-taken" />已服</span>
        <span><i class="dot is-pending" />待服</span>
        <span><i class="dot is-missed" />漏服</span>
      </div>
    </NlCard>

    <!-- ==================== 今日服药 ==================== -->
    <NlCard v-if="tab === 'today'">
      <template #title>
        <span class="nl-h2">今日服药</span>
      </template>
      <NlNoticeBar tone="warning" icon>
        剂量由家属按医嘱录入，系统不提供任何用药建议；服药确认将推送给所有绑定家属。
      </NlNoticeBar>

      <NlSkeleton v-if="todayLoading" :count="3" />
      <NlEmpty
        v-else-if="!todayTasks.length"
        title="今天没有服药任务"
        description="去「计划」tab 新建用药计划"
      />
      <ul v-else class="med-list">
        <li v-for="m in todayTasks" :key="m.id" class="med-item">
          <div class="med-item__head">
            <div>
              <div class="nl-h3">{{ m.medicineName }}</div>
              <div class="nl-caption nl-text-muted">
                {{ m.dosage }} · {{ m.mealRelationLabel || labelOf(MEAL_RELATION_TEXT, m.mealRelation) }}<template v-if="m.planTime"> · {{ formatTime(m.planTime) }}</template>
              </div>
            </div>
            <NlStatusChip scope="task" :status="m.status" :text="m.statusLabel" :dot="m.status !== 'TAKEN'" />
          </div>
          <p v-if="m.confirmRemark" class="nl-caption nl-text-muted med-item__desc">备注：{{ m.confirmRemark }}</p>
          <div class="med-item__row">
            <span v-if="m.status === 'TAKEN'" class="nl-caption nl-text-muted">
              {{ formatTime(m.confirmTime) }}<template v-if="m.confirmByName"> · {{ m.confirmByName }}</template>
            </span>
            <span v-else></span>
            <div class="med-item__act">
              <el-button
                v-if="m.status !== 'TAKEN'"
                type="primary"
                round
                size="small"
                :loading="confirmingId === m.id"
                @click="confirmTaken(m)"
              >
                确认已服
              </el-button>
            </div>
          </div>
        </li>
      </ul>
    </NlCard>

    <!-- ==================== 计划列表 ==================== -->
    <NlCard v-if="tab === 'plan'" plain>
      <template #title>
        <span class="nl-h2">用药计划</span>
      </template>
      <p class="nl-caption nl-text-muted plan-tip">
        剂量字段为家属录入，仅作提醒用途，不构成任何用药建议。
      </p>

      <NlSkeleton v-if="plansLoading" :count="3" />
      <NlEmpty
        v-else-if="!plans.length"
        title="还没有用药计划"
        description="点击下方按钮为老人添加用药计划"
      />
      <ul v-else class="plan-list">
        <li v-for="p in plans" :key="p.id" class="plan-item">
          <div class="plan-item__head">
            <span class="nl-h3">{{ p.medicineName }}</span>
            <NlStatusChip scope="plan" :status="p.status" :text="p.statusLabel" />
          </div>
          <dl class="plan-item__dl">
            <dt>剂量</dt><dd>{{ p.dosage }}<small v-if="p.remark">（{{ p.remark }}）</small></dd>
            <dt>频次</dt>
            <dd>每日 {{ p.frequency }} 次<template v-if="p.timePoints && p.timePoints.length"> · {{ p.timePoints.join('、') }}</template></dd>
            <dt>饭点</dt><dd>{{ p.mealRelationLabel || labelOf(MEAL_RELATION_TEXT, p.mealRelation) }}</dd>
            <dt>起止</dt><dd class="is-num">{{ formatDate(p.startDate) }} ~ {{ p.endDate != null ? formatDate(p.endDate) : '长期' }}</dd>
          </dl>
          <div class="plan-item__ops">
            <el-button text size="small" @click="openEditPlan(p)">编辑</el-button>
            <el-button
              v-if="p.status === 'ACTIVE'"
              text
              type="danger"
              size="small"
              @click="disablePlan(p)"
            >
              停用
            </el-button>
          </div>
        </li>
      </ul>
      <el-button type="primary" plain round size="large" class="plan-add" @click="openCreatePlan">
        + 新建用药计划
      </el-button>
    </NlCard>

    <!-- 新建 / 编辑计划抽屉 -->
    <el-dialog
      v-model="planDialog"
      :title="planMode === 'create' ? '新建用药计划' : '编辑用药计划'"
      width="92%"
      :fullscreen="!isMd"
      top="5vh"
      append-to-body
    >
      <NlNoticeBar tone="warning" icon>
        剂量由家属按医嘱录入，系统不生成也不校验剂量是否合理。
      </NlNoticeBar>
      <el-form label-position="top" class="plan-form">
        <el-form-item label="药品">
          <el-select
            v-model="planForm.medicineId"
            placeholder="选择药品（通用信息）"
            :loading="medicineLoading"
            filterable
            style="width: 100%"
            @change="onMedicineChange"
          >
            <el-option
              v-for="opt in medicineOptions"
              :key="opt.id"
              :label="opt.tradeName ? opt.name + '（' + opt.tradeName + '）' : opt.name"
              :value="opt.id"
            />
          </el-select>
        </el-form-item>
        <NlNoticeBar v-if="selectedDisclaimer" tone="info" icon>
          {{ selectedDisclaimer }}
        </NlNoticeBar>
        <el-form-item label="家属录入剂量">
          <el-input v-model="planForm.dosage" placeholder="如「1 片」「5mg」，按医嘱填写" maxlength="50" />
        </el-form-item>
        <el-form-item label="每日次数与时间点">
          <div class="time-points">
            <el-time-picker
              v-for="(tp, i) in planForm.timePoints"
              :key="i"
              v-model="planForm.timePoints[i]"
              value-format="HH:mm"
              format="HH:mm"
              placeholder="HH:mm"
              style="width: 120px; margin-right: 8px; margin-bottom: 8px"
            />
            <el-button
              v-if="planForm.timePoints.length < 4"
              text
              type="primary"
              size="small"
              @click="addTimePoint"
            >+ 添加时间点</el-button>
            <el-button
              v-if="planForm.timePoints.length > 1"
              text
              size="small"
              @click="removeTimePoint(planForm.timePoints.length - 1)"
            >删除最后一个</el-button>
          </div>
        </el-form-item>
        <el-form-item label="与饭点关系">
          <el-select v-model="planForm.mealRelation" style="width: 100%">
            <el-option v-for="o in MEAL_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="planForm.startDate" value-format="YYYY-MM-DD" type="date" placeholder="开始日期" style="width: 100%" />
        </el-form-item>
        <el-form-item label="结束日期（留空表示长期）">
          <el-date-picker v-model="planForm.endDate" value-format="YYYY-MM-DD" type="date" placeholder="留空 = 长期用药" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="planForm.remark" type="textarea" :rows="2" placeholder="如「医生让每天早饭吃一片」" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" :loading="planSaving" @click="submitPlan">保存</el-button>
      </template>
    </el-dialog>

    <NlComplianceBar text="本页仅提供药品通用信息，不构成任何用药建议，请遵医嘱。" />

    <template #tabbar>
      <NlAppTabBar />
    </template>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.elder-tabs {
  display: flex;
  gap: 6px;
  padding: 0 var(--nl-gutter);
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

  &__btn {
    flex: 0 0 auto;
    padding: 8px 14px;
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

.med-tabs {
  display: flex;
  gap: 6px;
  padding: 0 var(--nl-gutter);

  &__btn {
    flex: 1;
    padding: 10px 0;
    font-size: 14px;
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 10px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-text-inverse);
      background: var(--nl-primary);
      border-color: var(--nl-primary);
    }
  }
}

.cal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--nl-space-3);

  &__nav {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: var(--nl-touch-min);
    height: var(--nl-touch-min);
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 50%;
    cursor: pointer;
  }

  &__title {
    margin: 0;
  }
}

.cal-weeks {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  font-size: 11px;
  color: var(--nl-text-3);
  text-align: center;

  &__cell {
    padding: 6px 0;
  }
}

.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 2px;
}

.cal-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 48px;
  border-radius: 8px;

  &.is-empty {
    height: 48px;
  }

  &.is-today {
    background: var(--nl-primary-light);
    border: 1.5px solid var(--nl-primary);

    .cal-cell__day {
      color: var(--nl-primary);
    }
  }

  &__day {
    font-size: 14px;
    color: var(--nl-text-1);
  }

  &__dots {
    display: flex;
    gap: 2px;
    padding: 0;
    margin: 2px 0 0;
    list-style: none;

    li {
      width: 4px;
      height: 4px;
      border-radius: 50%;
      background: var(--nl-success);

      &.is-taken { background: var(--nl-success); }
      &.is-pending { background: var(--nl-warning); }
      &.is-missed { background: var(--nl-danger); }
    }
  }
}

.cal-summary {
  padding: var(--nl-space-2) 0 0;
  font-size: 12px;
}

.cal-legend {
  display: flex;
  gap: var(--nl-space-3);
  padding: var(--nl-space-2) 0 0;
  font-size: 11px;
  color: var(--nl-text-3);

  .dot {
    display: inline-block;
    width: 8px;
    height: 8px;
    margin-right: 4px;
    border-radius: 50%;
  }
  .is-taken { background: var(--nl-success); }
  .is-pending { background: var(--nl-warning); }
  .is-missed { background: var(--nl-danger); }
}

.med-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.med-item {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &__head {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }

  &__desc {
    margin: var(--nl-space-2) 0 var(--nl-space-3);
    line-height: 1.6;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--nl-space-2);
  }

  &__act {
    display: flex;
    gap: 6px;
  }
}

.plan-tip {
  margin: 0 0 var(--nl-space-3);
}

.plan-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.plan-item {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
    margin-bottom: var(--nl-space-3);
  }

  &__ops {
    display: flex;
    justify-content: flex-end;
    gap: var(--nl-space-2);
    margin-top: var(--nl-space-2);
    padding-top: var(--nl-space-2);
    border-top: 1px dashed var(--nl-divider);
  }

  &__dl {
    display: grid;
    grid-template-columns: 60px 1fr;
    gap: 4px 12px;
    margin: 0;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);

    dt {
      font-weight: 500;
      color: var(--nl-text-3);
    }

    dd {
      margin: 0;

      small {
        color: var(--nl-text-3);
      }
    }
  }
}

.plan-form {
  margin-top: var(--nl-space-3);
}

.time-points {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}

.plan-add {
  width: 100%;
  margin-top: var(--nl-space-3);
}
</style>
