<script setup>
/**
 * W-06 投诉管理（管理员）
 *
 * 数据来自 listComplaints（分页 page/size，响应 { total, page, size, pages, records }）。
 * 后端投诉状态是 PENDING / PROCESSING / RESOLVED / REJECTED（注意是 RESOLVED 不是 CLOSED），
 * 因此 NlStatusChip 必须传 scope="complaint"，否则 RESOLVED 会被错配成别的语义。
 *
 * 处理动作走 handleComplaint，严格对齐 ComplaintHandleDTO：
 *   - status 只能是 PROCESSING / RESOLVED / REJECTED（不能退回 PENDING）
 *   - handleResult 是「处理结果说明」，必填且 10–500 字（后端每次都校验）
 *   - penaltyToTarget 可选，是否对被投诉人计违规
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty } from '@/components'
import { listComplaints, handleComplaint } from '@/api/admin'
import { formatDateTime, labelOf, COMPLAINT_STATUS_TEXT } from '@/utils/format'

const filter = ref({ status: '' })

const list = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(10)

async function loadList() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (filter.value.status) params.status = filter.value.status
    const data = await listComplaints(params)
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

function onFilterChange() {
  page.value = 1
  loadList()
}

function onPageChange(p) {
  page.value = p
  loadList()
}

// 终态（已结案 / 已驳回）不允许再操作
function isTerminal(status) {
  return status === 'RESOLVED' || status === 'REJECTED'
}

const handleRef = ref(null)
const handleItem = ref(null)
const handleStatus = ref('PROCESSING')
const handleResult = ref('')
const penaltyToTarget = ref(false)

function openHandle(c) {
  handleItem.value = c
  // 默认目标状态顺着流转：待处理→处理中，处理中→已结案
  handleStatus.value = c.status === 'PENDING' ? 'PROCESSING' : 'RESOLVED'
  handleResult.value = ''
  penaltyToTarget.value = false
  handleRef.value.open()
}

function statusText(s) {
  return labelOf(COMPLAINT_STATUS_TEXT, s, s)
}

async function submitHandle() {
  const result = handleResult.value.trim()
  // 后端 handleResult 要求 10–500 字，先在前端拦一道避免无效请求
  if (result.length < 10) {
    ElMessage.warning('请填写处理结果说明（至少 10 字）')
    return
  }
  await ElMessageBox.confirm(
    `确认将投诉「${handleItem.value.orderNo}」处理为「${statusText(handleStatus.value)}」？`,
    '确认处理',
    { confirmButtonText: '确认', type: 'warning' }
  )
  await handleComplaint(handleItem.value.id, {
    status: handleStatus.value,
    handleResult: result,
    penaltyToTarget: penaltyToTarget.value
  })
  ElMessage.success('已更新投诉处理状态')
  handleRef.value.close()
  loadList()
}

onMounted(loadList)
</script>

<template>
  <NlCard title="投诉管理" plain>
    <section class="filters">
      <el-select
        v-model="filter.status"
        placeholder="状态"
        clearable
        style="width: 160px"
        @change="onFilterChange"
      >
        <el-option label="待处理" value="PENDING" />
        <el-option label="处理中" value="PROCESSING" />
        <el-option label="已结案" value="RESOLVED" />
        <el-option label="已驳回" value="REJECTED" />
      </el-select>
      <el-button type="primary" plain @click="onFilterChange">查询</el-button>
    </section>

    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无投诉"
      description="当前筛选条件下没有投诉记录"
    />

    <template v-else>
      <el-table :data="list">
        <el-table-column prop="id" label="投诉编号" width="90" />
        <el-table-column prop="orderNo" label="关联订单" width="180" />
        <el-table-column prop="complainantName" label="投诉家属" width="100" />
        <el-table-column prop="targetUserName" label="涉及陪诊员" width="100" />
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ row.typeLabel || row.type }}</template>
        </el-table-column>
        <el-table-column prop="content" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <NlStatusChip
              :status="row.status"
              scope="complaint"
              :text="row.statusLabel"
              :dot="row.status === 'PENDING' || row.status === 'PROCESSING'"
            />
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!isTerminal(row.status)"
              type="primary"
              text
              size="small"
              @click="openHandle(row)"
            >
              处理
            </el-button>
            <span v-else class="nl-caption nl-text-muted">已结案</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > size"
        class="complaint-pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPageChange"
      />
    </template>
  </NlCard>

  <!-- 投诉处理对话框 -->
  <el-dialog
    ref="handleRef"
    :title="`投诉处理 - ${handleItem?.orderNo || ''}`"
    width="600px"
    align-center
  >
    <div v-if="handleItem" class="complaint-handle">
      <section class="complaint-handle__row">
        <span class="complaint-handle__label">投诉内容</span>
        <span class="nl-body">{{ handleItem.content }}</span>
      </section>
      <section v-if="handleItem.handleResult" class="complaint-handle__row">
        <span class="complaint-handle__label">既往处理结果</span>
        <span class="nl-body">{{ handleItem.handleResult }}</span>
      </section>

      <section class="complaint-handle__block">
        <h4 class="nl-h3">处理决定</h4>
        <el-radio-group v-model="handleStatus">
          <el-radio-button label="PROCESSING">处理中</el-radio-button>
          <el-radio-button label="RESOLVED">已结案</el-radio-button>
          <el-radio-button label="REJECTED">已驳回</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="handleResult"
          type="textarea"
          :rows="3"
          placeholder="处理结果说明（必填，10–500 字，将记录到投诉处理结论）"
          maxlength="500"
          show-word-limit
        />
        <el-checkbox v-model="penaltyToTarget">对被投诉人计违规</el-checkbox>
      </section>
    </div>

    <template #footer>
      <el-button @click="handleRef.close()">取消</el-button>
      <el-button type="primary" @click="submitHandle">确认处理</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.filters {
  display: flex;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);
}

.complaint-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}

.complaint-handle {
  display: flex;
  flex-direction: column;
  gap: $nl-space-4;

  &__row {
    display: flex;
    gap: $nl-space-3;
    align-items: flex-start;
    padding: $nl-space-3;
    background: var(--nl-bg-sunken);
    border-radius: $nl-radius-input;
  }

  &__label {
    flex-shrink: 0;
    width: 88px;
    font-size: $nl-font-caption;
    color: $nl-text-3;
    text-align: right;
  }

  &__block {
    display: flex;
    flex-direction: column;
    gap: $nl-space-3;
  }
}
</style>
