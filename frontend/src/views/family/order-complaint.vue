<script setup>
/**
 * 我要投诉（M-18 · design.md §4 / PRD §M7）
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
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlCard, NlStatusChip, NlNoticeBar, NlEmpty, NlSkeleton } from '@/components'
import { getOrder } from '@/api/order'
import { createComplaint, listMyComplaints } from '@/api/review'
import { uploadFile } from '@/api/file'
import { formatVisitTime, formatMoney, formatDateTime } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const orderId = route.params.id

const order = ref(null)
const orderLoading = ref(false)

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

const submitting = ref(false)

/** 证据上传中（串行，避免并发冲掉同一个 input） */
const uploadingEvidence = ref(false)

/* 历史投诉 */
const history = ref([])
const historyLoading = ref(false)
const historyTotal = ref(0)

async function loadOrder() {
  orderLoading.value = true
  try {
    const data = await getOrder(orderId)
    order.value = data || null
  } catch {
    order.value = null
  } finally {
    orderLoading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    const data = await listMyComplaints({ page: 1, size: 20 })
    history.value = data?.records || []
    historyTotal.value = data?.total || 0
  } catch {
    history.value = []
    historyTotal.value = 0
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
  // 前端预校验大小（≤10MB，design.md §2.6）
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

function submit() {
  if (!form.value.type) {
    ElMessage.warning('请选择投诉类型')
    return
  }
  const content = form.value.content.trim()
  if (content.length < 10) {
    ElMessage.warning('投诉描述至少 10 个字')
    return
  }
  if (content.length > 1000) {
    ElMessage.warning('投诉描述不能超过 1000 个字')
    return
  }
  submitting.value = true
  createComplaint({
    orderId,
    type: form.value.type,
    content,
    evidence: form.value.evidence.length ? [...form.value.evidence] : undefined
  })
    .then(() => {
      ElMessage.success('已提交投诉，将由平台介入处理')
      form.value = { type: '', content: '', evidence: [] }
      router.back()
    })
    .catch(() => {
      // 拦截器已弹错误提示（如非相关订单 3004 / 存在未结案投诉 409 / 敏感词 6003），不再重复弹
    })
    .finally(() => {
      submitting.value = false
    })
}

onMounted(() => {
  loadOrder()
  loadHistory()
})
</script>

<template>
  <NlPhoneShell :nav="{ title: '我要投诉' }">
    <NlSkeleton v-if="orderLoading" :count="2" />

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

      <!-- 类型选择 -->
      <NlCard title="投诉类型">
        <ul class="types">
          <li
            v-for="t in COMPLAINT_TYPES"
            :key="t.value"
            :class="['types__item', { 'is-active': form.type === t.value }]"
            @click="form.type = t.value"
          >
            {{ t.label }}
          </li>
        </ul>
      </NlCard>

      <!-- 描述 -->
      <NlCard title="投诉描述">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="6"
          placeholder="请详细描述事件经过、影响、期望处理结果（10–1000 字）"
          maxlength="1000"
          show-word-limit
        />
      </NlCard>

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
        <p v-else-if="!history.length" class="nl-caption nl-text-muted">暂无历史投诉</p>
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
  </NlPhoneShell>
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
    font-size: 13px;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-primary);
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
  font-size: 13px;
  color: var(--nl-primary);
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
