<script setup>
/**
 * W-12 结算核对（V4 · P2-1 结算闭环）
 *
 * 数据来自 listSettlement（分页 page/size，响应 { total, page, size, pages, records }）。
 *
 * 三条不可动摇的规则：
 *   1. 页面只做展示与确认，**结算金额由服务端计算**（actual_fee 非空取之，否则取 fee），
 *      前端不传、不改、不重算；
 *   2. 只有「未结算」的订单可以核定；已结算的行只展示结果，按钮置灰；
 *   3. 核定是幂等的：重复点同一单不会产生第二条审计记录，页面提示「该单已结算」。
 *
 * 文案口径：结算核对 / 记账 / 核定 / 未结算 / 已结算 / 导出结算单。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { listSettlement, confirmSettlement, batchConfirmSettlement } from '@/api/admin'
import { exportSettlement } from '@/api/statistics'
import { formatMoney, today } from '@/utils/format'
import { usePagedList } from '@/composables/usePagedList'
import { confirmThen } from '@/utils/confirm'

const filter = ref({ paymentStatus: '', date: null, keyword: '' })

const list = ref([])
const loading = ref(false)
const total = ref(0)
const loadError = ref(false)
const page = ref(1)
const size = ref(10)

/** 当前勾选的订单 ID（只允许勾未结算行） */
const selectedIds = ref([])
const submitting = ref(false)
const exporting = ref(false)

/** 列表参数与导出参数共用筛选条件，导出去掉分页 */
function buildFilterParams() {
  const p = {}
  if (filter.value.paymentStatus) p.paymentStatus = filter.value.paymentStatus
  if (filter.value.keyword && filter.value.keyword.trim()) p.keyword = filter.value.keyword.trim()
  if (filter.value.date && filter.value.date.length === 2) {
    p.startDate = filter.value.date[0]
    p.endDate = filter.value.date[1]
  }
  return p
}

function buildParams() {
  return { page: page.value, size: size.value, ...buildFilterParams() }
}

async function loadList() {
  loading.value = true
  try {
    const data = await listSettlement(buildParams())
    list.value = data?.records || []
    total.value = data?.total || 0
    loadError.value = false
    // 列表刷新后清空勾选，避免勾到已不在当前页的订单
    selectedIds.value = []
  } catch {
    list.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}

const isEmpty = computed(() => !loading.value && list.value.length === 0)

const { pageDrained, onPrevPage } = usePagedList({
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

/* ---------------- 勾选 ---------------- */

function isSelected(id) {
  return selectedIds.value.includes(id)
}

function toggleSelect(row) {
  if (row.paymentStatus !== 'UNPAID') return
  if (isSelected(row.id)) {
    selectedIds.value = selectedIds.value.filter((id) => id !== row.id)
  } else {
    selectedIds.value = [...selectedIds.value, row.id]
  }
}

/* ---------------- 核定 ---------------- */

/** 单条核定：先确认，再提交；提交后无论成功与否都刷新列表 */
async function confirmOne(row) {
  if (row.paymentStatus !== 'UNPAID' || submitting.value) return
  const ok = await confirmThen(() =>
    ElMessageBox.confirm(
      `确认将订单 ${row.orderNo} 标记为已结算？结算金额由服务端按记账口径计算，核定后不可撤销。`,
      '结算核定',
      { confirmButtonText: '确认核定' }
    )
  )
  if (!ok) return

  submitting.value = true
  try {
    const data = await confirmSettlement(row.id)
    ElMessage.success(data?.settledNow ? '已核定' : '该单已结算')
    await loadList()
  } catch {
    // 请求层拦截器已提示具体错误（3001 / 8004 等）
  } finally {
    submitting.value = false
  }
}

/** 批量核定：逐项结果由后端返回，这里把失败项汇总给管理员 */
async function confirmBatch() {
  if (!selectedIds.value.length || submitting.value) return
  const ok = await confirmThen(() =>
    ElMessageBox.confirm(
      `本次将核定 ${selectedIds.value.length} 笔订单。结算金额由服务端按记账口径计算，核定后不可撤销。`,
      '批量结算核定',
      { confirmButtonText: '确认核定' }
    )
  )
  if (!ok) return

  submitting.value = true
  try {
    const data = await batchConfirmSettlement(selectedIds.value)
    const failed = (data?.items || []).filter((item) => !item.success)
    if (failed.length) {
      await ElMessageBox.alert(
        `成功核定 ${data.settledCount} 笔，此前已结算 ${data.alreadySettledCount} 笔，` +
          `失败 ${data.failedCount} 笔。\n\n` +
          failed.map((item) => `订单 ${item.orderId}：${item.message}`).join('\n'),
        '批量核定结果',
        { confirmButtonText: '知道了' }
      )
    } else {
      ElMessage.success(`已核定 ${data.settledCount} 笔，此前已结算 ${data.alreadySettledCount} 笔`)
    }
    await loadList()
  } catch {
    // 请求层拦截器已提示
  } finally {
    submitting.value = false
  }
}

/* ---------------- 导出 ---------------- */

function triggerDownload(blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

async function exportFile() {
  if (exporting.value) return
  const ok = await confirmThen(() =>
    ElMessageBox.confirm(
      '将按当前筛选条件导出结算单；金额由服务端计算，姓名已脱敏。',
      '导出结算单',
      { confirmButtonText: '导出 Excel' }
    )
  )
  if (!ok) return

  exporting.value = true
  try {
    const blob = await exportSettlement(buildFilterParams())
    // 超限时后端仍 200，但 Content-Type 变成 json，必须先甄别，否则会下载到报错信息
    if (blob && blob.type && blob.type.includes('json')) {
      const text = await blob.text()
      let msg = '导出失败，请缩小筛选范围后重试'
      try {
        const err = JSON.parse(text)
        if (err && err.message) msg = err.message
      } catch {
        // 非标准 JSON，沿用默认提示
      }
      ElMessage.error(msg)
      return
    }
    triggerDownload(blob, `结算单_${today()}.xlsx`)
    ElMessage.success('已开始下载')
  } catch {
    // 请求层异常拦截器已弹提示
  } finally {
    exporting.value = false
  }
}

/* ---------------- 列定义 ---------------- */

const columns = [
  { key: 'orderNo', label: '订单号', width: 180 },
  { key: 'elderName', label: '就诊人', width: 100 },
  { key: 'hospital', label: '医院', minWidth: 160 },
  { key: 'finishTime', label: '服务完成时间', width: 160 },
  { key: 'companionName', label: '陪诊员', width: 100 },
  { key: 'fee', label: '服务费', width: 100, align: 'right', formatter: (v) => formatMoney(v) },
  { key: 'settleAmount', label: '结算金额', width: 110, align: 'right', formatter: (v) => formatMoney(v) },
  { key: 'paymentStatusLabel', label: '结算状态', width: 100 },
  { key: 'statusLabel', label: '订单状态', width: 100 }
]
const mobileFields = ['orderNo', 'elderName', 'settleAmount', 'paymentStatusLabel']

onMounted(loadList)
</script>

<template>
  <NlCard title="结算核对" plain>
    <p class="nl-caption nl-text-muted">
      按服务完成时间核对线下结算结果。金额由服务端按记账口径计算，页面不接收任何金额输入；
      核定后不可撤销，重复核定不会产生第二条审计记录。
    </p>

    <section class="filters">
      <el-select
        v-model="filter.paymentStatus"
        placeholder="结算状态"
        clearable
        style="width: 140px"
        @change="onFilterChange"
      >
        <el-option label="未结算" value="UNPAID" />
        <el-option label="已结算" value="SETTLED" />
      </el-select>
      <el-date-picker
        v-model="filter.date"
        type="daterange"
        range-separator="-"
        start-placeholder="完成开始"
        end-placeholder="完成结束"
        value-format="YYYY-MM-DD"
        @change="onFilterChange"
      />
      <el-input
        v-model="filter.keyword"
        placeholder="搜索订单号 / 医院 / 科室"
        clearable
        style="width: 240px"
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-button type="primary" plain @click="onFilterChange">查询</el-button>

      <div class="filters__actions">
        <el-button
          type="primary"
          :disabled="!selectedIds.length"
          :loading="submitting"
          @click="confirmBatch"
        >
          批量核定{{ selectedIds.length ? `（${selectedIds.length}）` : '' }}
        </el-button>
        <el-button :loading="exporting" @click="exportFile">导出结算单</el-button>
      </div>
    </section>

    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="结算台账加载失败"
      action-text="重试"
      @action="loadList"
    />

    <NlEmpty
      v-else-if="pageDrained"
      type="empty"
      title="本页已空，上一页还有"
      description="当前页码上没有结算记录，可返回上一页查看"
      action-text="返回上一页"
      @action="onPrevPage"
    />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无结算记录"
      description="当前筛选条件下没有可核对的订单"
    />

    <NlAdminTable
      v-else
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      :action-width="200"
      empty-text="暂无结算记录"
    >
      <template #cell-paymentStatusLabel="{ row }">
        <el-tag size="small" :type="row.paymentStatus === 'SETTLED' ? 'success' : 'warning'">
          {{ row.paymentStatusLabel }}
        </el-tag>
      </template>
      <template #actions="{ row }">
        <el-checkbox
          v-if="row.paymentStatus === 'UNPAID'"
          :model-value="isSelected(row.id)"
          @change="toggleSelect(row)"
        >
          选择
        </el-checkbox>
        <el-button
          v-if="row.paymentStatus === 'UNPAID'"
          text
          size="small"
          type="primary"
          :loading="submitting"
          @click="confirmOne(row)"
        >
          核定
        </el-button>
        <span v-else class="settled-text">已核定</span>
      </template>
    </NlAdminTable>

    <el-pagination
      v-if="total > size"
      class="settlement-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPageChange"
    />
  </NlCard>
</template>

<style scoped lang="scss">
.filters {
  display: flex;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);
  flex-wrap: wrap;
  align-items: center;

  &__actions {
    display: flex;
    gap: var(--nl-space-2);
    margin-left: auto;
  }
}

.settlement-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}

.settled-text {
  font-size: var(--nl-font-caption);
  color: var(--nl-text-3);
}
</style>
