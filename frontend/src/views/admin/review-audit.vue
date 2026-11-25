<script setup>
/**
 * W-14 评价巡查（管理员）
 *
 * 数据来自 listAdminReviews（`GET /api/admin/review`），分页响应 { total, page, size, pages, records }。
 *
 * ## 这个页面解决什么
 *
 * 后端的 `POST /api/admin/review/{id}/validity`（评价有效性裁定，E4）早就上线了，
 * 但在它落地之前**没有任何管理端页面能调用它** ——
 * 管理员看得见评价，却没有一个入口说「这条评价不算数」。
 *
 * 更关键的是：公开的评价列表（`GET /api/review/companion/{id}`）硬编码只返回
 * **有效**评价。也就是说，**被裁定无效的评价在公开列表里是看不见的** ——
 * 如果巡查页也走那条链路，「裁完就消失」的评价等于从来没被巡查过。
 * 所以本页面走独立的管理端端点，用 `isValid` 三态把两种都露出来。
 *
 * ## 与申诉入口的关系
 *
 * 另一个入口在「投诉管理」页的 `REVIEW_APPEAL` 行（陪诊员对评价发起申诉）。
 * 两个入口的裁定动作**共用同一个对话框组件**
 * （`components/admin/ReviewRulingDialog.vue`）—— 裁定的口径、校验、in-flight 锁
 * 必须两处完全一致，否则管理员会靠回忆去判断哪个入口才是对的。
 *
 * ## 筛选维度
 *
 * 只做 PRD 要求的四类（陪诊员 / 评分 / 有效性 / 时间），不额外加维度 ——
 * 巡查页的动线是「扫一遍异常」，维度越多越扫不完。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { NlCard, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import ReviewRulingDialog from '@/components/admin/ReviewRulingDialog.vue'
import { listAdminReviews } from '@/api/admin'
import { formatDateTime } from '@/utils/format'
import { usePagedList } from '@/composables/usePagedList'

/** 有效性筛选的三个选项；null = 不限（后端不传该参数） */
const VALIDITY_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'valid', label: '有效' },
  { value: 'invalid', label: '已裁定无效' }
]

const filter = ref({
  companionId: '',
  minScore: null,
  maxScore: null,
  validity: '',
  keyword: '',
  date: []
})

const list = ref([])
const loading = ref(false)
const total = ref(0)
/** 列表加载失败（与「当前筛选下没有数据」区分） */
const loadError = ref(false)
const page = ref(1)
const size = ref(10)

/** 把界面上的筛选态翻译成后端查询参数 */
function buildParams() {
  const p = { page: page.value, size: size.value }
  if (filter.value.companionId) p.companionId = filter.value.companionId
  if (filter.value.minScore) p.minScore = filter.value.minScore
  if (filter.value.maxScore) p.maxScore = filter.value.maxScore
  if (filter.value.keyword && filter.value.keyword.trim()) {
    p.keyword = filter.value.keyword.trim()
  }
  // ⚠️ isValid 是三态：'valid' → true、'invalid' → false、'' → 不传（不限）。
  //    传空串会让后端按 false 处理，把所有有效评价都藏掉。
  if (filter.value.validity === 'valid') p.isValid = true
  if (filter.value.validity === 'invalid') p.isValid = false
  if (filter.value.date && filter.value.date.length === 2) {
    p.startDate = filter.value.date[0]
    p.endDate = filter.value.date[1]
  }
  return p
}

async function loadList() {
  loading.value = true
  try {
    const data = await listAdminReviews(buildParams())
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
 * 分页列表的共性逻辑：空态区分「本页已空」与「筛选无结果」、写操作后回退一页。
 * 与投诉管理页共用同一份实现（`usePagedList`）。
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

/* ================================================================== */
/* 裁定入口                                                          */
/* ================================================================== */

/** 裁定对话框是否打开 */
const rulingVisible = ref(false)
/** 待裁定的评价 */
const rulingTarget = ref(null)

function openRuling(row) {
  // 已裁定过的评价不再给裁（后端也会返 409，这里提前挡住）
  if (row.isValid === false) {
    ElMessage.info('该评价已被裁定无效')
    return
  }
  rulingTarget.value = row
  rulingVisible.value = true
}

/** 裁定成功后刷新列表（该行的有效性标记应随之翻转） */
async function handleRulingSuccess() {
  await reloadAfterMutate()
}

/** 列定义（desktop 8 列；mobile 关键 4 字段：评分 / 有效性 / 陪诊员 / 评价时间） */
const columns = [
  { key: 'id',            label: '评价编号', width: 90 },
  { key: 'orderNo',       label: '关联订单', width: 180 },
  { key: 'score',         label: '评分',     width: 80 },
  { key: 'familyName',    label: '评价家属', width: 100 },
  { key: 'companionName', label: '陪诊员',   width: 100 },
  { key: 'content',       label: '评价内容', minWidth: 200 },
  { key: 'valid',         label: '有效性',   width: 110 },
  { key: 'createTime',    label: '评价时间', width: 160, formatter: (v) => formatDateTime(v) }
]
const mobileFields = ['score', 'valid', 'companionName', 'createTime']

onMounted(loadList)
</script>

<template>
  <NlCard title="评价巡查" plain>
    <section class="filters">
      <el-input
        v-model="filter.companionId"
        placeholder="陪诊员 ID"
        clearable
        style="width: 140px"
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-select
        v-model="filter.minScore"
        placeholder="评分 ≥"
        clearable
        style="width: 110px"
        @change="onFilterChange"
      >
        <el-option v-for="s in [1, 2, 3, 4, 5]" :key="s" :label="`${s} 星及以上`" :value="s" />
      </el-select>
      <el-select
        v-model="filter.maxScore"
        placeholder="评分 ≤"
        clearable
        style="width: 110px"
        @change="onFilterChange"
      >
        <el-option v-for="s in [1, 2, 3, 4, 5]" :key="s" :label="`${s} 星及以下`" :value="s" />
      </el-select>
      <el-select
        v-model="filter.validity"
        placeholder="有效性"
        style="width: 140px"
        @change="onFilterChange"
      >
        <el-option
          v-for="opt in VALIDITY_OPTIONS"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
      <el-date-picker
        v-model="filter.date"
        type="daterange"
        range-separator="-"
        start-placeholder="开始"
        end-placeholder="结束"
        value-format="YYYY-MM-DD"
        style="width: 240px"
        @change="onFilterChange"
      />
      <el-input
        v-model="filter.keyword"
        placeholder="评价内容 / 订单号"
        clearable
        style="width: 200px"
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-button type="primary" plain @click="onFilterChange">查询</el-button>
    </section>

    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="评价记录加载失败"
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
      title="暂无评价"
      description="当前筛选条件下没有评价记录"
    />

    <NlAdminTable
      v-else
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      empty-text="暂无评价"
    >
      <template #cell-score="{ row }">{{ row.score }} 星</template>
      <template #cell-valid="{ row }">
        <span v-if="row.isValid === false" class="audit-invalid">已裁定无效</span>
        <span v-else class="audit-valid">有效</span>
      </template>
      <template #actions="{ row }">
        <el-button
          v-if="row.isValid !== false"
          type="warning"
          text
          size="small"
          @click="openRuling(row)"
        >
          裁定无效
        </el-button>
        <span v-else class="nl-caption nl-text-muted">已裁定</span>
      </template>
    </NlAdminTable>

    <el-pagination
      v-if="total > size"
      class="audit-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPageChange"
    />
  </NlCard>

  <!-- 裁定对话框（与投诉管理页共用同一个组件） -->
  <ReviewRulingDialog
    v-model="rulingVisible"
    :review="rulingTarget"
    @success="handleRulingSuccess"
  />
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);
}

.audit-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}

.audit-invalid {
  font-size: var(--nl-font-caption);
  color: var(--nl-danger-text);
}

.audit-valid {
  font-size: var(--nl-font-caption);
  color: var(--nl-success-text);
}
</style>
