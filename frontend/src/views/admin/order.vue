<script setup>
/**
 * W-05 订单管理（管理员视角）
 *
 * 数据来自 listAllOrders（与导出接口同源，保证「页面看到的就是能导出的」）。
 * 分页参数是 page / size，从 1 开始，size 上限 100，响应结构是 { total, page, size, pages, records }。
 *
 * 注意：订单状态 PENDING 在这里是「待接单」，NlStatusChip 用默认 scope（order）即可。
 */
import { ref, computed, onMounted } from 'vue'
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty } from '@/components'
import { listAllOrders } from '@/api/admin'
import { formatDateTime, formatMoney } from '@/utils/format'

const filter = ref({ status: '', date: null, keyword: '' })

const list = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(10)

/** 把筛选条件拍平成后端要的查询参数；空值不传，避免后端收到空字符串去精确匹配 */
function buildParams() {
  const p = { page: page.value, size: size.value }
  if (filter.value.status) p.status = filter.value.status
  if (filter.value.keyword && filter.value.keyword.trim()) p.keyword = filter.value.keyword.trim()
  // 日期选择器用 value-format="YYYY-MM-DD"，这里直接是 ['2026-09-01','2026-09-30'] 形式
  if (filter.value.date && filter.value.date.length === 2) {
    p.startDate = filter.value.date[0]
    p.endDate = filter.value.date[1]
  }
  return p
}

async function loadList() {
  loading.value = true
  try {
    const data = await listAllOrders(buildParams())
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

// 任意筛选条件变化都回到第一页重新查，避免停留在旧页码
function onFilterChange() {
  page.value = 1
  loadList()
}

function onPageChange(p) {
  page.value = p
  loadList()
}

const viewVisible = ref(false)
const viewOrder = ref(null)

function openView(row) {
  viewOrder.value = row
  viewVisible.value = true
}

onMounted(loadList)
</script>

<template>
  <NlCard title="订单管理" plain>
    <section class="filters">
      <el-select
        v-model="filter.status"
        placeholder="状态"
        clearable
        style="width: 140px"
        @change="onFilterChange"
      >
        <el-option label="待接单" value="PENDING" />
        <el-option label="已接单" value="ACCEPTED" />
        <el-option label="服务中" value="IN_SERVICE" />
        <el-option label="已完成" value="COMPLETED" />
        <el-option label="已评价" value="REVIEWED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-date-picker
        v-model="filter.date"
        type="daterange"
        range-separator="-"
        start-placeholder="开始"
        end-placeholder="结束"
        value-format="YYYY-MM-DD"
        @change="onFilterChange"
      />
      <el-input
        v-model="filter.keyword"
        placeholder="搜索订单号 / 医院 / 姓名"
        clearable
        style="width: 240px"
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-button type="primary" plain @click="onFilterChange">查询</el-button>
    </section>

    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无订单"
      description="当前筛选条件下没有订单"
    />

    <template v-else>
      <el-table :data="list">
        <el-table-column prop="orderNo" label="订单号" width="180" />
        <el-table-column prop="elderName" label="就诊人" width="100" />
        <el-table-column prop="hospital" label="医院" min-width="180" />
        <el-table-column prop="companionName" label="陪诊员" width="100" />
        <el-table-column label="服务费" width="100">
          <template #default="{ row }">
            <span class="is-num">{{ formatMoney(row.fee) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <NlStatusChip :status="row.status" :text="row.statusLabel" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="120" fixed="right">
          <template #default="{ row }">
            <el-button text size="small" type="primary" @click="openView(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > size"
        class="order-pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPageChange"
      />
    </template>
  </NlCard>

  <el-dialog v-model="viewVisible" title="订单详情" width="560px" align-center>
    <div v-if="viewOrder" class="order-view">
      <section class="ov-row"><span class="ov-label">订单号</span><span>{{ viewOrder.orderNo }}</span></section>
      <section class="ov-row"><span class="ov-label">就诊人</span><span>{{ viewOrder.elderName }}</span></section>
      <section class="ov-row"><span class="ov-label">医院</span><span>{{ viewOrder.hospital }}</span></section>
      <section v-if="viewOrder.department" class="ov-row">
        <span class="ov-label">科室</span><span>{{ viewOrder.department }}</span>
      </section>
      <section v-if="viewOrder.visitTime" class="ov-row">
        <span class="ov-label">就诊时间</span><span>{{ formatDateTime(viewOrder.visitTime) }}</span>
      </section>
      <section class="ov-row">
        <span class="ov-label">陪诊员</span><span>{{ viewOrder.companionName || '未接单' }}</span>
      </section>
      <section class="ov-row">
        <span class="ov-label">服务费</span><span class="is-num">{{ formatMoney(viewOrder.fee) }}</span>
      </section>
      <section class="ov-row">
        <span class="ov-label">状态</span>
        <NlStatusChip :status="viewOrder.status" :text="viewOrder.statusLabel" />
      </section>
      <section class="ov-row">
        <span class="ov-label">创建时间</span><span>{{ formatDateTime(viewOrder.createTime) }}</span>
      </section>
    </div>
    <template #footer>
      <el-button @click="viewVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.filters {
  display: flex;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);
  flex-wrap: wrap;
}

.order-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}

.order-view {
  display: flex;
  flex-direction: column;
  gap: 10px;

  .ov-row {
    display: flex;
    gap: 12px;
    padding: 8px 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }
  }

  .ov-label {
    flex-shrink: 0;
    width: 72px;
    color: var(--nl-text-3);
  }
}
</style>
