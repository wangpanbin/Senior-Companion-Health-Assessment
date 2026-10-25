<script setup>
/**
 * W-08 操作日志（admin_oper_log 只读查询）
 *
 * 数据来自 listOperLogs（分页 page/size，响应 { total, page, size, pages, records }）。
 * 操作类型中文名后端已通过 operTypeLabel 下发，直接用它，不再在前端维护 actionMap，
 * 避免「代码里两套中文」对不上号。
 *
 * desktop-adapt-v2（T-05c）：表格改用 NlAdminTable，<1280 走卡片 fallback。
 * mobile 关键 4 字段：时间 / 操作人 / 动作 / 对象（详情省略，靠卡片 max-height 内联显示）。
 */
import { ref, computed, onMounted } from 'vue'
import { NlCard, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { listOperLogs } from '@/api/admin'
import { formatDateTime } from '@/utils/format'
import { usePagedList } from '@/composables/usePagedList'

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
    const data = await listOperLogs({ page: page.value, size: size.value })
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
const { pageDrained, onPrevPage } = usePagedList({
  page,
  isEmpty,
  records: list,
  reload: loadList
})

function onPageChange(p) {
  page.value = p
  loadList()
}

/** 列定义（desktop 5 列；mobile 关键 4 字段：时间 / 操作人 / 动作 / 对象） */
const columns = [
  { key: 'operTime',      label: '时间',     width: 170, formatter: (v) => formatDateTime(v) },
  { key: 'operatorName',  label: '操作人',   width: 100 },
  { key: 'operTypeLabel', label: '动作',     width: 140 },
  { key: 'targetDesc',    label: '对象',     width: 200 },
  { key: 'remark',        label: '详情',     minWidth: 280 }
]
const mobileFields = ['operTime', 'operatorName', 'operTypeLabel', 'targetDesc']

onMounted(loadList)
</script>

<template>
  <NlCard title="操作日志" plain>
    <p class="nl-caption nl-text-muted">
      所有管理动作都会写入 admin_oper_log，本页面只读，用于审计与追溯。
    </p>

    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="操作日志加载失败"
      action-text="重试"
      @action="loadList"
    />

    <NlEmpty
      v-else-if="pageDrained"
      type="empty"
      title="本页已空，上一页还有"
      description="当前页码上没有操作日志，可返回上一页查看"
      action-text="返回上一页"
      @action="onPrevPage"
    />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无操作日志"
      description="当前还没有任何管理操作记录"
    />

    <NlAdminTable
      v-else
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      empty-text="暂无操作日志"
    >
      <template #cell-operTypeLabel="{ row }">
        <el-tag size="small" type="info">{{ row.operTypeLabel || row.operType }}</el-tag>
      </template>
    </NlAdminTable>

    <el-pagination
      v-if="total > size"
      class="oper-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPageChange"
    />
  </NlCard>
</template>

<style scoped lang="scss">
.oper-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}
</style>