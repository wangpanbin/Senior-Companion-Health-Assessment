<script setup>
/**
 * 我要投诉（M-18 · docs/design/design.md §4 / PRD §M7）
 *
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
 *
 * 数据来源（全部在 @/api/order + @/api/review，已核对后端 DTO/VO）：
 *   - getOrder(orderId)              → 被投诉订单摘要（只读）
 *   - createComplaint(data)          → 提交投诉
 *   - listMyComplaints({page,size})  → 我的历史投诉（分页，data.records）
 *
 * ⚠️ 字段真值（已读 ComplaintCreateDTO / ComplaintVO / ComplaintController / ComplaintType，未猜测）：
 *   - 投诉类型 type 为枚举：LATE / ATTITUDE / INCOMPLETE / FEE_DISPUTE / PRIVACY / OTHER（必填）
 *   - content 必填、10–1000 字
 *   - evidence 为「已上传的证据 URL 列表」（最多 6 张，选填）；现走通用上传端点 POST /api/file/upload（bizType=COMPLAINT）
 *   - 投诉状态：PENDING 待处理 / PROCESSING 处理中 / RESOLVED 已结案 / REJECTED 已驳回
 *     （注意是 RESOLVED 不是 CLOSED），用 NlStatusChip scope="complaint"
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPageShell, NlCard, NlStatusChip, NlNoticeBar, NlEmpty, NlSkeleton } from '@/components'
import { getOrder } from '@/api/order'
import { createComplaint, listMyComplaints } from '@/api/review'
import { uploadFile } from '@/api/file'
import { formatVisitTime, formatMoney, formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
/**
 * 订单 id 必须是**响应式**的：Vue Router 4 在「同一条路由记录只换 params」时会
 * 复用组件实例（onMounted 不再触发）。快照成常量会让「URL 是 B、页面还显示 A」，
 * 家属点「提交投诉」就把投诉写给了上一单。
 */
const orderId = computed(() => route.params.id)

const order = ref(null)
const orderLoading = ref(false)
/** R4：订单头加载失败（不能展示成「订单不存在」） */
const orderError = ref(false)

/** 投诉类型枚举（来源：后端 ComplaintType，label 与后端保持一致） */
const COMPLAINT_TYPES = [
  { value: 'LATE', label: '迟到 / 未按时到达' },
  { value: 'ATTITUDE', label: '服务态度差' },
  { value: 'INCOMPLETE', label: '服务未完成' },
  { value: 'FEE_DISPUTE', label: '费用纠纷' },
  { value: 'PRIVACY', label: '隐私泄露' },
  { value: 'OTHER', label: '其他' }
]

const form = ref({
  type: '',
  content: '',
  evidence: []
})

/* ==================== 表单校验（落到字段，而不是一次性 toast） ==================== */

/*
 * 校验规则与文案语义与改造前完全一致，只是把「一次性 toast」换成字段级错误：
 *   - 投诉类型必填
 *   - 投诉描述必填，10–1000 字（原文先 trim 再判长度）
 *
 * ⚠️ 描述用自定义 validator 而不是 min/max，是为了保留原先「先 trim 再判长度」的口径：
 *    纯空格在改造前会被算成「不足 10 个字」，用 min/max 反而会因原始长度不足而放过。
 */
const formRef = ref(null)

const rules = {
  type: [
    { required: true, message: '请选择投诉类型', trigger: 'change' }
  ],
  content: [
    {
      required: true,
      validator: (_rule, value, cb) => {
        const text = (value || '').trim()
        if (text.length < 10) {
          cb(new Error('投诉描述至少 10 个字'))
          return
        }
        if (text.length > 1000) {
          cb(new Error('投诉描述不能超过 1000 个字'))
          return
        }
        cb()
      },
      trigger: 'blur'
    }
  ]
}

const submitting = ref(false)

/** 证据上传中（串行，避免并发冲掉同一个 input） */
const uploadingEvidence = ref(false)

/* 历史投诉 */
const history = ref([])
const historyLoading = ref(false)
const historyError = ref(false)
const historyTotal = ref(0)

async function loadOrder() {
  // 记下本次请求针对的订单：请求在途时 URL 可能又换了订单，回来时要能判断响应是否过期
  const target = orderId.value
  orderLoading.value = true
  try {
    const data = await getOrder(target)
    // 过期响应直接丢弃：否则会把上一单的数据显示在当前 URL 下
    if (String(target) !== String(orderId.value)) return
    order.value = data || null
    orderError.value = false
  } catch {
    if (String(target) !== String(orderId.value)) return
    order.value = null
    orderError.value = true
  } finally {
    if (String(target) === String(orderId.value)) orderLoading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const data = await listMyComplaints({ page: 1, size: 20 })
    history.value = data?.records || []
    historyTotal.value = data?.total || 0
    historyError.value = false
  } catch {
    history.value = []
    historyTotal.value = 0
    historyError.value = true
  } finally {
    historyLoading.value = false
  }
}

/** 证据材料真实上传：COMPLAINT（仅图片，≤10MB，最多 6 张） */
function onEvidenceChange(e) {
  const file = e.target.files && e.target.files[0]
  if (!file) return
  // 拦住第 7 张
  if (form.value.evidence.length >= 6) {
    ElMessage.warning('证据材料最多 6 张')
    e.target.value = ''
    return
  }
  // 前端预校验大小（≤10MB，docs/design/design.md §2.6）
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('单张证据超过 10MB，无法上传')
    e.target.value = ''
    return
  }
  uploadingEvidence.value = true
  uploadFile(file, 'COMPLAINT')
    .then((res) => {
      if (res?.url) {
        form.value.evidence.push(res.url)
        ElMessage.success('证据已上传')
      }
    })
    .catch(() => {
      // 拦截器已弹错（含 403 / 参数错误）
    })
    .finally(() => {
      uploadingEvidence.value = false
      e.target.value = ''
    })
}

function removeEvidence(i) {
  form.value.evidence.splice(i, 1)
}

/** 选择投诉类型：类型是自绘的按钮列表，没有原生 change 事件，选完要主动清掉错误 */
function selectType(value) {
  form.value.type = value
  formRef.value?.clearValidate('type')
}

/**
 * URL 里的订单 id 与页面正在展示的订单 id 是否一致。
 *
 * 路由复用组件实例时存在一个竞态窗口：URL 已经换成 B，详情还没回来，
 * 页面显示的仍是 A。此时提交投诉就会把投诉写给上一单。
 */
function isDisplayedOrderCurrent() {
  const shown = order.value?.id
  // 详情里没有 id 时没有可比对的基准，不做拦截（避免把用户锁死在页面上）
  if (shown == null) return true
  return String(shown) === String(orderId.value)
}

async function submit() {
  // ⚠️ in-flight 锁必须在**任何 await 之前**同步置位（含下面的表单校验）：
  //    若等校验通过后才置位，两次调用会一起穿过 await，锁形同虚设。
  if (submitting.value) return
  submitting.value = true
  try {
    // 二次校验：页面展示的订单必须就是 URL 指向的订单，否则拒绝提交、不发请求
    if (!isDisplayedOrderCurrent()) {
      ElMessage.warning('页面已切换到其它订单，请刷新后重试')
      return
    }
    // 字段级校验：一次跑完，错误直接落在对应字段下方（不再逐条弹 toast）
    try {
      await formRef.value?.validate()
    } catch {
      return
    }
    const content = form.value.content.trim()
    await createComplaint({
      orderId: orderId.value,
      type: form.value.type,
      content,
      evidence: form.value.evidence.length ? [...form.value.evidence] : undefined
    })
    ElMessage.success('已提交投诉，将由平台介入处理')
    form.value = { type: '', content: '', evidence: [] }
    formRef.value?.clearValidate()
    router.back()
  } catch {
    // 拦截器已弹错误提示（如非相关订单 3004 / 存在未结案投诉 409 / 敏感词 6003），不再重复弹
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadOrder()
  loadHistory()
})

// 同一条路由记录只换 params 时组件实例会被复用，onMounted 不再触发 —— 这里补上重拉
watch(orderId, () => {
  // 换订单：投诉草稿是针对具体订单的，不能带到下一单去
  form.value = { type: '', content: '', evidence: [] }
  formRef.value?.clearValidate()
  loadOrder()
})
</script>

<template>
  <NlPageShell title="我要投诉">
    <NlSkeleton v-if="orderLoading" :count="2" />

    <NlEmpty
      v-else-if="orderError"
      type="network"
      title="订单信息加载失败"
      action-text="重试"
      @action="loadOrder"
    />

    <template v-else-if="order">
      <NlNoticeBar tone="warning">
        投诉将由管理员仲裁处理，提交后不可修改；请如实陈述并附事实，避免过激用语。
      </NlNoticeBar>

      <!-- 订单摘要（只读） -->
      <NlCard title="被投诉订单">
        <div class="order-sum">
          <div class="order-sum__line">
            <span class="nl-h3">{{ order.hospital }}</span>
            <NlStatusChip :status="order.status" :text="order.statusLabel" />
          </div>
          <div class="nl-caption nl-text-muted order-sum__meta">
            {{ order.department }}<template v-if="order.elderName"> · {{ order.elderName }}</template><template v-if="order.elderAge != null"> {{ order.elderAge }}岁</template>
            · {{ formatVisitTime(order.visitTime) }}
          </div>
          <div class="nl-caption nl-text-muted order-sum__meta">
            <template v-if="order.companionName">陪诊员：{{ order.companionName }}</template><template v-else>尚无陪诊员</template>
            · 服务费 {{ formatMoney(order.fee) }}
          </div>
          <div v-if="order.orderNo" class="nl-caption nl-text-weak is-num order-sum__no">{{ order.orderNo }}</div>
        </div>
      </NlCard>

      <!--
        字段级校验：错误落在对应字段下方，而不是「一次性 toast」——
        原来一次只报一个错、错在哪个字段不定位、toast 3 秒后就消失。
      -->
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <!-- 类型选择 -->
        <NlCard title="投诉类型">
          <el-form-item prop="type">
            <ul role="list" class="types">
              <li
v-for="t in COMPLAINT_TYPES" :key="t.value" role="button" tabindex="0"
                :class="['types__item', { 'is-active': form.type === t.value }]"
                @keydown.enter.prevent="selectType(t.value)"
                @keydown.space.prevent="selectType(t.value)"
                @click="selectType(t.value)"
              >
                {{ t.label }}
              </li>
            </ul>
          </el-form-item>
        </NlCard>

        <!-- 描述 -->
        <NlCard title="投诉描述">
          <el-form-item prop="content">
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="6"
              placeholder="请详细描述事件经过、影响、期望处理结果（10–1000 字）"
              maxlength="1000"
              show-word-limit
            />
          </el-form-item>
        </NlCard>
      </el-form>

      <!-- 证据材料 -->
      <NlCard title="证据材料（选填）">
        <p class="nl-caption nl-text-muted">支持图片，单张 ≤ 10MB，最多 6 张</p>
        <ul class="evi">
          <li v-for="(u, i) in form.evidence" :key="u" class="evi__item">
            <span class="evi__url nl-caption is-num">{{ u }}</span>
            <el-button text size="small" type="danger" @click="removeEvidence(i)">删除</el-button>
          </li>
        </ul>
        <label class="evi-upload" :class="{ 'is-disabled': form.evidence.length >= 6 || uploadingEvidence }">
          <input
            type="file"
            accept="image/*"
            :disabled="form.evidence.length >= 6 || uploadingEvidence"
            @change="onEvidenceChange"
          />
          <span v-if="uploadingEvidence">上传中…</span>
          <span v-else-if="form.evidence.length >= 6">已达上限（6 张）</span>
          <span v-else>+ 添加证据（{{ form.evidence.length }}/6）</span>
        </label>
      </NlCard>

      <!-- 提交 -->
      <div class="submit-bar">
        <el-button type="primary" size="large" round class="submit-bar__btn" :loading="submitting" @click="submit">
          提交投诉
        </el-button>
      </div>

      <!-- 历史 -->
      <NlCard title="我的投诉记录" plain>
        <NlSkeleton v-if="historyLoading" :count="2" />
        <NlEmpty
          v-else-if="historyError"
          type="network"
          title="投诉记录加载失败"
          action-text="重试"
          @action="loadHistory"
        />
        <NlEmpty
          v-else-if="!history.length"
          type="empty"
          title="暂无历史投诉"
          description="该订单还没有其它投诉记录"
        />
        <ul v-else class="hist">
          <li v-for="h in history" :key="h.id" class="hist__item">
            <div class="hist__head">
              <span class="nl-h3">{{ h.typeLabel || h.type }}</span>
              <NlStatusChip scope="complaint" :status="h.status" :text="h.statusLabel" />
            </div>
            <p class="nl-caption nl-text-muted">{{ h.content }}</p>
            <p v-if="h.handleResult" class="nl-caption nl-text-2 hist__result">处理结果：{{ h.handleResult }}</p>
            <span class="nl-caption nl-text-weak is-num">
              {{ h.orderNo || ('#' + h.id) }}<template v-if="h.createTime"> · {{ formatDateTime(h.createTime) }}</template>
            </span>
          </li>
        </ul>
      </NlCard>
    </template>

    <NlEmpty
      v-else
      type="empty"
      title="订单不存在"
      description="无法加载该订单，请返回订单列表重试"
    />
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.order-sum {
  &__line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }

  &__meta {
    margin-top: 6px;
  }

  &__no {
    margin-top: 4px;
  }
}

.types {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;

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

.submit-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}

.evi {
  padding: 0;
  margin: 0 0 var(--nl-space-3);
  list-style: none;

  &__item {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
    padding: var(--nl-space-2) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }
  }

  &__url {
    flex: 1;
    word-break: break-all;
  }
}

.evi-upload {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 14px;
  height: 40px;
  font-size: var(--nl-font-caption);
  color: var(--nl-primary-text);
  background: var(--nl-bg-card);
  border: 1.5px solid var(--nl-primary);
  border-radius: 10px;
  cursor: pointer;
  white-space: nowrap;

  input {
    display: none;
  }

  &.is-disabled {
    color: var(--nl-text-3);
    border-color: var(--nl-border);
    cursor: default;
  }
}

.hist {
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    padding: var(--nl-space-3) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
  }

  &__result {
    margin: 4px 0 0;
  }
}
</style>
