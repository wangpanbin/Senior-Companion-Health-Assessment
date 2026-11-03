<script setup>
/**
 * VitalSignForm · 体征录入卡（V4 · P2-2）
 *
 * 三端共用一张卡：家属端 POST /api/family/elder/{elderId}/vitals，
 * 陪诊员端 POST /api/execution/{orderId}/vitals（仅服务中）。
 *
 * 合规红线：
 *   1. 只有数值、时间、测量场景，没有备注输入框 —— 不给判断性描述留入口；
 *   2. 不在界面上展示任何上下限 / 区间 / 目标值，避免被读成参考区间；
 *   3. 错误文案只说「填错了」，不说「数值好不好」。
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createCompanionVital, createElderVital } from '@/api/vitalSign'

const props = defineProps({
  /** family / companion */
  mode: { type: String, default: 'family' },
  /** 家属端必传：老人档案 ID */
  elderId: { type: [String, Number], default: null },
  /** 陪诊员端必传：订单 ID */
  orderId: { type: [String, Number], default: null }
})

const emit = defineEmits(['created'])

/** 三类体征；顺序固定，不随数据变化 */
const TYPES = [
  { key: 'BLOOD_PRESSURE', label: '血压' },
  { key: 'BLOOD_GLUCOSE', label: '血糖' },
  { key: 'WEIGHT', label: '体重' }
]

/** 血糖测量场景：描述测量时的进食状态，不是对数值的判断 */
const CONTEXTS = [
  { key: 'FASTING', label: '空腹' },
  { key: 'POSTPRANDIAL', label: '餐后 2 小时' },
  { key: 'RANDOM', label: '随机' }
]

const type = ref('BLOOD_PRESSURE')
const measuredAt = ref(nowText())
const systolic = ref('')
const diastolic = ref('')
const glucoseValue = ref('')
const glucoseContext = ref('FASTING')
const weightKg = ref('')
const submitting = ref(false)
const inlineError = ref('')
const fieldErrors = ref({})

/** 当前类型的单位提示：只写单位，不写区间 */
const unitHint = computed(() => {
  if (type.value === 'BLOOD_PRESSURE') return 'mmHg'
  if (type.value === 'BLOOD_GLUCOSE') return 'mmol/L'
  return 'kg'
})

/** 当前时间的分钟文本（秒固定 00，与后端截断口径一致） */
function nowText() {
  const now = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:00`
}

/** 字符串 → 数值；空值与非数字都归为 null */
function toNumber(value) {
  if (value === '' || value === null || value === undefined) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

/** 切换类型时清空另一类的输入，避免把「血压值」误带进「体重」 */
function switchType(next) {
  type.value = next
  systolic.value = ''
  diastolic.value = ''
  glucoseValue.value = ''
  weightKg.value = ''
  fieldErrors.value = {}
  inlineError.value = ''
}

/** 组装请求体；不合法返回 null 并写出行内错误 */
function buildPayload() {
  const errors = {}
  if (!measuredAt.value) errors.measuredAt = '请选择测量时间'

  const payload = { vitalType: type.value, measuredAt: measuredAt.value }

  if (type.value === 'BLOOD_PRESSURE') {
    const high = toNumber(systolic.value)
    const low = toNumber(diastolic.value)
    if (!Number.isInteger(high)) errors.systolic = '请填写收缩压整数值'
    if (!Number.isInteger(low)) errors.diastolic = '请填写舒张压整数值'
    payload.systolic = high
    payload.diastolic = low
  } else if (type.value === 'BLOOD_GLUCOSE') {
    const glucose = toNumber(glucoseValue.value)
    if (glucose === null) errors.glucoseValue = '请填写血糖值'
    if (!glucoseContext.value) errors.glucoseContext = '请选择测量场景'
    payload.glucoseValue = glucose
    payload.glucoseContext = glucoseContext.value
  } else {
    const weight = toNumber(weightKg.value)
    if (weight === null) errors.weightKg = '请填写体重'
    payload.weightKg = weight
  }

  fieldErrors.value = errors
  if (Object.keys(errors).length > 0) {
    inlineError.value = '请检查必填项后再提交'
    return null
  }
  return payload
}

/** 提交；成功回调父级刷新时间线与趋势 */
async function submit() {
  inlineError.value = ''
  const payload = buildPayload()
  if (!payload) return

  submitting.value = true
  try {
    const created =
      props.mode === 'companion'
        ? await createCompanionVital(props.orderId, payload)
        : await createElderVital(props.elderId, payload)
    ElMessage.success('记录已保存')
    systolic.value = ''
    diastolic.value = ''
    glucoseValue.value = ''
    weightKg.value = ''
    measuredAt.value = nowText()
    // 把服务端返回的那条记录交给父级：陪诊员页据此就地展示本次录入
    emit('created', created)
  } catch (error) {
    // 2008 是「同一分钟同类记录已存在」—— 就地提示，避免用户以为没保存上
    if (error?.code === 2008) {
      inlineError.value = '这一分钟已有同类记录，请调整测量时间，或先撤回原记录'
    } else if (error?.code === 400 || error?.message) {
      inlineError.value = error.message || '请求参数有误'
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="vital-form">
    <div class="vital-form__types" role="tablist" aria-label="体征类型">
      <button
        v-for="item in TYPES"
        :key="item.key"
        type="button"
        role="tab"
        class="vital-form__type"
        :class="{ 'is-active': type === item.key }"
        :aria-selected="type === item.key"
        @click="switchType(item.key)"
      >
        {{ item.label }}
      </button>
    </div>

    <div class="vital-form__field">
      <label class="vital-form__label" for="vital-measured-at">
        <b>测量时间</b>
        <span>按分钟记录</span>
      </label>
      <el-date-picker
        id="vital-measured-at"
        v-model="measuredAt"
        type="datetime"
        format="YYYY-MM-DD HH:mm"
        value-format="YYYY-MM-DD HH:mm:ss"
        placeholder="选择测量时间"
        class="vital-form__picker"
      />
      <p v-if="fieldErrors.measuredAt" class="vital-form__error">{{ fieldErrors.measuredAt }}</p>
    </div>

    <template v-if="type === 'BLOOD_PRESSURE'">
      <div class="vital-form__field">
        <label class="vital-form__label" for="vital-systolic">
          <b>收缩压</b>
          <span>{{ unitHint }}</span>
        </label>
        <el-input
          id="vital-systolic"
          v-model="systolic"
          inputmode="numeric"
          placeholder="请输入收缩压"
          clearable
        />
        <p v-if="fieldErrors.systolic" class="vital-form__error">{{ fieldErrors.systolic }}</p>
      </div>
      <div class="vital-form__field">
        <label class="vital-form__label" for="vital-diastolic">
          <b>舒张压</b>
          <span>{{ unitHint }}</span>
        </label>
        <el-input
          id="vital-diastolic"
          v-model="diastolic"
          inputmode="numeric"
          placeholder="请输入舒张压"
          clearable
        />
        <p v-if="fieldErrors.diastolic" class="vital-form__error">{{ fieldErrors.diastolic }}</p>
      </div>
    </template>

    <template v-else-if="type === 'BLOOD_GLUCOSE'">
      <div class="vital-form__field">
        <label class="vital-form__label" for="vital-glucose">
          <b>血糖值</b>
          <span>{{ unitHint }}</span>
        </label>
        <el-input
          id="vital-glucose"
          v-model="glucoseValue"
          inputmode="decimal"
          placeholder="请输入血糖值"
          clearable
        />
        <p v-if="fieldErrors.glucoseValue" class="vital-form__error">{{ fieldErrors.glucoseValue }}</p>
      </div>
      <div class="vital-form__field">
        <label class="vital-form__label"><b>测量场景</b><span>进餐状态</span></label>
        <el-radio-group v-model="glucoseContext" class="vital-form__contexts">
          <el-radio-button v-for="item in CONTEXTS" :key="item.key" :value="item.key">
            {{ item.label }}
          </el-radio-button>
        </el-radio-group>
        <p v-if="fieldErrors.glucoseContext" class="vital-form__error">
          {{ fieldErrors.glucoseContext }}
        </p>
      </div>
    </template>

    <template v-else>
      <div class="vital-form__field">
        <label class="vital-form__label" for="vital-weight">
          <b>体重</b>
          <span>{{ unitHint }}</span>
        </label>
        <el-input
          id="vital-weight"
          v-model="weightKg"
          inputmode="decimal"
          placeholder="请输入体重"
          clearable
        />
        <p v-if="fieldErrors.weightKg" class="vital-form__error">{{ fieldErrors.weightKg }}</p>
      </div>
    </template>

    <p v-if="inlineError" class="vital-form__error vital-form__error--block" role="alert">
      {{ inlineError }}
    </p>

    <el-button type="primary" class="vital-form__submit" :loading="submitting" @click="submit">
      保存记录
    </el-button>
    <p class="vital-form__note">仅保存测量数值与测量时间，不做任何判断</p>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.vital-form {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  /* 类型切换：三段等宽，视觉上对应设计稿的 segmented */
  &__types {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 4px;
    padding: 4px;
    background: var(--nl-bg-sunken);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-btn);
  }

  &__type {
    height: 38px;
    border: 0;
    border-radius: 10px;
    background: transparent;
    color: var(--nl-text-2);
    font-family: inherit;
    font-size: var(--nl-font-body);
    cursor: pointer;
    transition:
      background 0.18s ease,
      color 0.18s ease;

    &.is-active {
      background: var(--nl-bg-card);
      color: var(--nl-primary-dark);
      font-weight: 600;
      box-shadow: var(--nl-shadow-card);
    }

    &:focus-visible {
      outline: 2px solid var(--nl-primary);
      outline-offset: 2px;
    }
  }

  &__field {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  &__label {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);

    b {
      color: var(--nl-text-1);
      font-size: var(--nl-font-body);
      font-weight: 600;
    }
  }

  &__picker {
    width: 100%;
  }

  &__contexts {
    display: flex;
    flex-wrap: wrap;
  }

  &__error {
    margin: 0;
    color: var(--nl-danger-text);
    font-size: var(--nl-font-caption);
    line-height: 18px;

    &--block {
      padding: var(--nl-space-2) var(--nl-space-3);
      background: var(--nl-danger-bg);
      border-radius: var(--nl-radius-chip);
    }
  }

  &__submit {
    width: 100%;
    height: 44px;
    margin-top: var(--nl-space-1);
    font-weight: 600;
  }

  &__note {
    margin: 0;
    color: var(--nl-text-3);
    font-size: var(--nl-font-caption);
    line-height: 18px;
    text-align: center;
  }
}
</style>
