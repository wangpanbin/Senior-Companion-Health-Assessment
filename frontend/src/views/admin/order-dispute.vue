<script setup>
/**
 * 订单纠纷仲裁（W-03 · design.md §4 / PRD §M9）
 *
 * 数据来源：纠纷列表直接复用 listAllOrders（与订单管理同源，便于管理员对任意订单强制改终态）。
 * 仲裁动作走 arbitrateOrder，入参严格对齐后端 ArbitrateDTO：
 *   - targetStatus 只能是 COMPLETED / CANCELLED（后端 isAdminForceable 兜死，传别的会被拒）
 *   - result 是「处理结果说明」，必填且 10–500 字（和 mock 里「仅取消时填原因」不同，后端要求每次都填）
 *   - refundToFamily / penaltyToCompanion 是可选的记账标记（不做在线退款）
 *
 * 注意：后端没有「订单时间线」接口，原模板里那根自定义 timeline 没有真实数据源，
 * 这里用订单关键字段（医院/科室/就诊时间/费用/状态）替代展示。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { useResponsive } from '@/composables/useResponsive'
import { listAllOrders, arbitrateOrder } from '@/api/admin'
import { formatDateTime, formatMoney } from '@/utils/format'

const list = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(10)

async function loadList() {
  loading.value = true
  try {
    const data = await listAllOrders({ page: page.value, size: size.value })
    list.value = data?.records || []
    total.value = data?.total || 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const isEmpty = computed(() => !loading.value && list.value.length === 0)

function onPageChange(p) {
  page.value = p
  loadList()
}

const dialogVisible = ref(false)
const detail = ref(null)
const decision = ref('COMPLETED')
const decisionNote = ref('')
const refundToFamily = ref(false)
const penaltyToCompanion = ref(false)

function open(o) {
  detail.value = o
  decision.value = 'COMPLETED'
  decisionNote.value = ''
  refundToFamily.value = false
  penaltyToCompanion.value = false
  dialogVisible.value = true
}

async function forceTerminate() {
  if (!decision.value) {
    ElMessage.warning('请选择仲裁决定')
    return
  }
  const note = decisionNote.value.trim()
  // 后端 result 要求 10–500 字，前端先拦一道，避免白跑一次请求才拿到校验错误
  if (note.length < 10) {
    ElMessage.warning('请填写处理结果说明（至少 10 字，将通知双方）')
    return
  }
  await ElMessageBox.confirm(
    `执行「${decision.value === 'CANCELLED' ? '强制取消' : '强制完成'}」仲裁决定？该操作将同时通知家属与陪诊员。`,
    '确认仲裁',
    { confirmButtonText: '执行并通知双方', type: 'warning' }
  )
  await arbitrateOrder(detail.value.id, {
    targetStatus: decision.value,
    result: note,
    refundToFamily: refundToFamily.value,
    penaltyToCompanion: penaltyToCompanion.value
  })
  ElMessage.success('已执行仲裁，将自动通知家属与陪诊员（站内信）')
  dialogVisible.value = false
  loadList()
}

/** 列定义（desktop 8 列；mobile 关键 4 字段：订单号 / 就诊人 / 状态 / 操作） */
const columns = [
  { key: 'orderNo',       label: '订单号',   width: 180 },
  { key: 'elderName',     label: '就诊人',   width: 100 },
  { key: 'companionName', label: '陪诊员',   width: 100 },
  { key: 'hospital',      label: '医院',     minWidth: 180 },
  { key: 'fee',           label: '服务费',   width: 100, formatter: (v) => formatMoney(v), align: 'right' },
  { key: 'status',        label: '状态',     width: 120 },
  { key: 'createTime',    label: '创建时间', width: 160, formatter: (v) => formatDateTime(v) }
]
const mobileFields = ['orderNo', 'elderName', 'status']

/** 仲裁弹窗 < 768 fullscreen（admin 平板 fallback，与 T-02 admin/order 同款策略；用 isXs(≤767)替代 !isMd 修复大屏 PC bug） */
const { isXs } = useResponsive()

onMounted(loadList)
</script>

<template>
  <NlCard title="订单纠纷仲裁" plain>
    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无订单"
      description="当前没有可仲裁的订单"
    />

    <NlAdminTable
      v-else
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      empty-text="暂无订单"
    >
      <template #cell-status="{ row }">
        <NlStatusChip :status="row.status" :text="row.statusLabel" />
      </template>
      <template #actions="{ row }">
        <el-button type="primary" text size="small" @click="open(row)">处理</el-button>
      </template>
    </NlAdminTable>

    <el-pagination
      v-if="total > size"
      class="dispute-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPageChange"
    />
  </NlCard>

  <el-dialog
    v-model="dialogVisible"
    :title="`订单纠纷 - ${detail?.orderNo || ''}`"
    width="720px"
    :fullscreen="isXs"
    align-center
  >
    <div v-if="detail" class="dispute">
      <!-- 双方 -->
      <section class="dispute__parties">
        <div class="party-card party-card--family">
          <span class="party-card__label">就诊人（家属侧）</span>
          <span class="party-card__name">{{ detail.elderName }}</span>
        </div>
        <div class="party-card party-card--companion">
          <span class="party-card__label">陪诊员（被处理侧）</span>
          <span class="party-card__name">{{ detail.companionName || '未接单' }}</span>
        </div>
      </section>

      <p class="nl-body dispute__desc">
        医院：{{ detail.hospital }} · 科室：{{ detail.department || '—' }} · 就诊时间：{{ formatDateTime(detail.visitTime) }}
      </p>
      <p class="nl-body dispute__desc">
        服务费：{{ formatMoney(detail.fee) }} · 当前状态：
        <NlStatusChip :status="detail.status" :text="detail.statusLabel" />
      </p>

      <!-- 仲裁决策 -->
      <section class="dispute__decision">
        <h4 class="nl-h3">仲裁决定</h4>
        <el-radio-group v-model="decision" class="dispute__decision-radio">
          <el-radio-button label="COMPLETED">强制完成</el-radio-button>
          <el-radio-button label="CANCELLED">强制取消</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="decisionNote"
          type="textarea"
          :rows="3"
          placeholder="处理结果说明（必填，10–500 字，将通知双方）"
          maxlength="500"
          show-word-limit
        />
        <el-checkbox v-model="refundToFamily">退还家属费用（记账标记，不做在线退款）</el-checkbox>
        <el-checkbox v-model="penaltyToCompanion">对陪诊员计违规</el-checkbox>
        <p class="nl-caption nl-text-muted">
          执行后系统将自动给家属 + 陪诊员各发一条站内信，写明仲裁决定与依据。
        </p>
      </section>
    </div>

    <template #footer>
      <el-button @click="dialogVisible = false">关闭</el-button>
      <el-button type="danger" @click="forceTerminate">执行仲裁 + 通知双方</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.dispute-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}

.dispute {
  display: flex;
  flex-direction: column;
  gap: $nl-space-5;

  &__parties {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: $nl-space-3;
  }

  &__desc {
    padding: $nl-space-3;
    background: var(--nl-bg-sunken);
    border-radius: $nl-radius-input;
  }

  &__decision {
    display: flex;
    flex-direction: column;
    gap: $nl-space-3;
  }

  &__decision-radio {
    margin-bottom: $nl-space-2;
  }
}

.party-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: $nl-space-3;
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: $nl-radius-card;

  &__label {
    font-size: $nl-font-caption;
    color: $nl-text-3;
  }

  &__name {
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &--family {
    border-left: 3px solid var(--nl-primary);
  }

  &--companion {
    border-left: 3px solid var(--nl-warning);
  }
}
</style>
