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
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { listComplaints, handleComplaint } from '@/api/admin'
import { formatDateTime, labelOf, COMPLAINT_STATUS_TEXT } from '@/utils/format'
import { confirmThen } from '@/utils/confirm'
import { usePagedList } from '@/composables/usePagedList'

const filter = ref({ status: '' })

const list = ref([])
const loading = ref(false)
const total = ref(0)
/** R4：列表加载失败（与「当前筛选下没有数据」区分） */
const loadError = ref(false)
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
    loadError.value = false
  } catch {
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const isEmpty = computed(() => !loading.value && list.value.length === 0)

/**
 * 分页列表的共性逻辑（P1-8）：空态区分「本页已空」与「筛选无结果」、
 * 写操作后必要时回退一页。7 个后台页共用同一份实现 —— 此前是逐字复制。
 */
const { pageDrained, onPrevPage, reloadAfterMutate } = usePagedList({
  page,
  isEmpty,
  records: list,
  reload: loadList
})

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

const handleVisible = ref(false)
const handleItem = ref(null)
const handleStatus = ref('PROCESSING')
const handleResult = ref('')
const penaltyToTarget = ref(false)
/**
 * 确认处理的 in-flight 锁（P1-5）。
 *
 * <p>连点「确认处理」会提交两次：第一次还在途时，第二次请求已经打出去，
 * 后端对同一投诉二次处理返回业务错误，拦截器把它弹成错误 toast ——
 * 用户看到「成功 + 报错」双提示，以为系统出了问题。</p>
 */
const handleSubmitting = ref(false)

function openHandle(c) {
  handleItem.value = c
  // 默认目标状态顺着流转：待处理→处理中，处理中→已结案
  handleStatus.value = c.status === 'PENDING' ? 'PROCESSING' : 'RESOLVED'
  handleResult.value = ''
  penaltyToTarget.value = false
  handleVisible.value = true
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
  if (!handleItem.value) return
  // ⚠️ in-flight 锁必须在第一个 await 之前同步置位（P1-5）：
  //    置位晚于 await 的话，两次连点会一起穿过 await，锁形同虚设。
  if (handleSubmitting.value) return
  handleSubmitting.value = true
  try {
    // confirmThen 把「用户是否点了确认」变成布尔：取消时静默返回 false，不发请求（P1-12）
    const confirmed = await confirmThen(() =>
      ElMessageBox.confirm(
        `确认将投诉「${handleItem.value.orderNo}」处理为「${statusText(handleStatus.value)}」？`,
        '确认处理',
        { confirmButtonText: '确认', type: 'warning' }
      )
    )
    if (!confirmed) return

    const complaintId = handleItem.value.id
    await handleComplaint(complaintId, {
      status: handleStatus.value,
      handleResult: result,
      penaltyToTarget: penaltyToTarget.value
    })
    ElMessage.success('已更新投诉处理状态')
    handleVisible.value = false
    await reloadAfterMutate()
  } catch {
    // 后端报错由 request.js 拦截器统一弹提示，这里不重复弹
  } finally {
    handleSubmitting.value = false
  }
}

/** 列定义（desktop 9 列；mobile 关键 4 字段：编号 / 家属 / 状态 / 操作） */
const columns = [
  { key: 'id',              label: '投诉编号', width: 90 },
  { key: 'orderNo',         label: '关联订单', width: 180 },
  { key: 'complainantName', label: '投诉家属', width: 100 },
  { key: 'targetUserName',  label: '涉及陪诊员', width: 100 },
  { key: 'type',            label: '类型',     width: 120 },
  { key: 'content',         label: '详情',     minWidth: 220 },
  { key: 'status',          label: '状态',     width: 100 },
  { key: 'createTime',      label: '提交时间', width: 160, formatter: (v) => formatDateTime(v) }
]
const mobileFields = ['id', 'complainantName', 'status']

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
      v-else-if="loadError"
      type="network"
      title="投诉记录加载失败"
      action-text="重试"
      @action="loadList"
    />

    <NlEmpty
      v-else-if="pageDrained"
      type="empty"
      title="本页已空，上一页还有"
      description="刚处理掉的记录已不在本页，可返回上一页继续处理"
      action-text="返回上一页"
      @action="onPrevPage"
    />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无投诉"
      description="当前筛选条件下没有投诉记录"
    />

    <NlAdminTable
      v-else
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      empty-text="暂无投诉"
    >
      <template #cell-type="{ row }">{{ row.typeLabel || row.type }}</template>
      <template #cell-status="{ row }">
        <NlStatusChip
          :status="row.status"
          scope="complaint"
          :text="row.statusLabel"
          :dot="row.status === 'PENDING' || row.status === 'PROCESSING'"
        />
      </template>
      <template #actions="{ row }">
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
        <el-button v-if="row.orderId" text size="small" @click="$router.push({ name: 'AdminChatViewer', query: { orderId: row.orderId } })">查看沟通记录</el-button>
      </template>
    </NlAdminTable>

    <el-pagination
      v-if="total > size"
      class="complaint-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPageChange"
    />
  </NlCard>

  <!-- 投诉处理对话框 -->
  <el-dialog
    v-model="handleVisible"
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
          <el-radio-button value="PROCESSING">处理中</el-radio-button>
          <el-radio-button value="RESOLVED">已结案</el-radio-button>
          <el-radio-button value="REJECTED">已驳回</el-radio-button>
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
      <el-button @click="handleVisible = false">取消</el-button>
      <el-button type="primary" :loading="handleSubmitting" @click="submitHandle">确认处理</el-button>
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
    font-size: var(--nl-font-caption);
    color: $nl-text-2;
    text-align: right;
  }

  &__block {
    display: flex;
    flex-direction: column;
    gap: $nl-space-3;
  }
}
</style>
