<script setup>
/**
 * 订单纠纷仲裁（W-03 · design.md §4 / PRD §M9）
 *
 * - 表格：订单号 / 投诉双方 / 投诉原因 / 处理状态 / 操作
 * - 详情对话框：
 *   - 订单完整时间线（状态流转）
 *   - 双方材料（家属投诉 + 陪诊员申诉）
 *   - 强制改终态：可以选 CANCELLED / COMPLETED / 其他
 *   - 双方通知：自动站内信（提交后批量发）
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip, NlTimeline } from '@/components'

const list = ref([
  {
    id: 'OD20250911001',
    family: 'fam001 张丽',
    companion: 'comp001 张三',
    elder: '张大爷 · 72 岁',
    reason: '家属要求退款',
    description: '已支付 120 元，陪诊员接单后未能按时到达',
    status: 'PENDING',
    submitAt: '2026-09-12 09:30',
    timeline: [
      { key: 'PENDING', label: '待接单', status: 'done', time: '09-10 18:00' },
      { key: 'ACCEPTED', label: '已接单', status: 'done', time: '09-10 18:35' },
      { key: 'IN_SERVICE', label: '服务中', status: 'done', time: '09-11 09:05' },
      { key: 'DISPUTE', label: '纠纷介入', status: 'current', time: '09-12 09:30' },
      { key: 'COMPLETED', label: '已完成', status: 'todo' },
      { key: 'REVIEWED', label: '已评价', status: 'todo' }
    ]
  },
  {
    id: 'OD20250905007',
    family: 'fam005 孙七',
    companion: 'comp020 周八',
    elder: '李大爷 · 75 岁',
    reason: '服务态度问题',
    description: '陪诊员沟通不耐烦，希望平台介入',
    status: 'PROCESSING',
    submitAt: '2026-09-06 14:20',
    timeline: [
      { key: 'PENDING', label: '待接单', status: 'done', time: '09-04 09:00' },
      { key: 'ACCEPTED', label: '已接单', status: 'done', time: '09-04 09:10' },
      { key: 'IN_SERVICE', label: '服务中', status: 'done', time: '09-04 09:50' },
      { key: 'DISPUTE', label: '纠纷介入', status: 'current', time: '09-06 14:20' },
      { key: 'COMPLETED', label: '已完成', status: 'todo' }
    ]
  },
  {
    id: 'OD20250902003',
    family: 'fam022 钱十一',
    companion: 'comp011 王五',
    elder: '王奶奶 · 68 岁',
    reason: '老人走失',
    description: '就诊途中老人走失，已找回',
    status: 'CLOSED',
    submitAt: '2026-09-03 11:00',
    timeline: [
      { key: 'CLOSED', label: '已结案', status: 'done', time: '09-04 16:00' }
    ]
  }
])

const dialogRef = ref(null)
const detail = ref(null)
const decision = ref('COMPLETED')
const decisionReason = ref('')

function open(o) {
  detail.value = o
  decisionReason.value = ''
  decision.value = 'COMPLETED'
  dialogRef.value.open()
}

async function forceTerminate() {
  if (!decision.value) {
    ElMessage.warning('请选择仲裁决定')
    return
  }
  if (decision.value === 'CANCELLED' && !decisionReason.value.trim()) {
    ElMessage.warning('强制取消订单时必须填写原因（将通知双方）')
    return
  }

  await ElMessageBox.confirm(
    `执行「${decision.value === 'CANCELLED' ? '强制取消' : decision.value === 'COMPLETED' ? '强制完成' : '其他'}」仲裁决定？该操作将同时通知家属与陪诊员。`,
    '确认仲裁',
    { confirmButtonText: '执行并通知双方', type: 'warning' }
  )

  const t = list.value.find((x) => x.id === detail.value.id)
  if (t) t.status = 'CLOSED'
  ElMessage.success('已执行仲裁，将自动通知家属与陪诊员（站内信）')
  dialogRef.value.close()
}
</script>

<template>
  <NlCard title="订单纠纷仲裁" plain>
    <el-table :data="list" style="width: 100%">
      <el-table-column prop="id" label="订单号" width="180" />
      <el-table-column label="投诉方" width="140">
        <template #default="{ row }">{{ row.family }}</template>
      </el-table-column>
      <el-table-column label="被投诉陪诊员" width="140">
        <template #default="{ row }">{{ row.companion }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="投诉原因" width="160" />
      <el-table-column label="处理状态" width="120">
        <template #default="{ row }">
          <NlStatusChip :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column prop="submitAt" label="提交时间" width="180" />
      <el-table-column label="操作" min-width="120" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" text size="small" @click="open(row)">处理</el-button>
        </template>
      </el-table-column>
    </el-table>
  </NlCard>

  <el-dialog
    ref="dialogRef"
    :title="`订单纠纷 - ${detail?.id || ''}`"
    width="720px"
    align-center
  >
    <div v-if="detail" class="dispute">
      <!-- 双方 -->
      <section class="dispute__parties">
        <div class="party-card party-card--family">
          <span class="party-card__label">投诉方（家属）</span>
          <span class="party-card__name">{{ detail.family }}</span>
        </div>
        <div class="party-card party-card--companion">
          <span class="party-card__label">被投诉（陪诊员）</span>
          <span class="party-card__name">{{ detail.companion }}</span>
        </div>
      </section>

      <p class="nl-body dispute__desc">{{ detail.description }}</p>

      <!-- 时间线 -->
      <section>
        <h4 class="nl-h3">订单时间线</h4>
        <NlTimeline :steps="detail.timeline" />
      </section>

      <!-- 仲裁决策 -->
      <section class="dispute__decision">
        <h4 class="nl-h3">仲裁决定</h4>
        <el-radio-group v-model="decision" class="dispute__decision-radio">
          <el-radio-button label="COMPLETED">强制完成</el-radio-button>
          <el-radio-button label="CANCELLED">强制取消</el-radio-button>
          <el-radio-button label="PARTIAL">部分退款</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="decisionReason"
          type="textarea"
          :rows="3"
          placeholder="仲裁说明（强制取消时必填，将通知双方）"
          maxlength="200"
          show-word-limit
        />
        <p class="nl-caption nl-text-muted">
          执行后系统将自动给家属 + 陪诊员各发一条站内信，写明仲裁决定与依据。
        </p>
      </section>
    </div>

    <template #footer>
      <el-button @click="dialogRef.close()">关闭</el-button>
      <el-button type="danger" @click="forceTerminate">执行仲裁 + 通知双方</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

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
    color: $nl-text-1;
  }

  &--family {
    border-left: 3px solid var(--nl-primary);
  }

  &--companion {
    border-left: 3px solid var(--nl-warning);
  }
}
</style>
