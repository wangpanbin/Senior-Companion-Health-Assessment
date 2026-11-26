<script setup>
/**
 * W-15 产品反馈管理（V6 · docs/api/15-product-feedback.md）
 *
 * 骨架照抄 `views/admin/complaint.vue`（同一套 `usePagedList` 三件套 +
 * `NlAdminTable` + `NlSkeleton` / `NlEmpty` 三态），但口径有三处不同：
 *
 * 1. **状态机中文不同**：投诉是「已结案 / 已驳回」，反馈是「已采纳 / 未采纳」——
 *    `NlStatusChip` 必须传 `scope="feedback"`，否则 RESOLVED 会显示成「已结案」。
 * 2. **处理说明每次流转都必填，但只有终态才对提交人可见**：管理员在「处理中」
 *    写的那段是工作草稿，不必写给用户看；到了「已采纳 / 未采纳」才经站内信回给用户。
 *    因此提示文案要写清「这段话会发给用户」，避免管理员在里面写内部黑话。
 * 3. **处理说明过两套词表**（敏感词 + 医疗红线）：命中 6003 时错误要落在
 *    textarea 下方而不是只弹顶部 toast —— 改文案时眼睛正在输入框上。
 *
 * 合规：本页不承载任何诊断 / 用药建议类文案（红线-1）。反馈正文是用户写的，
 * 平台只是转述展示；而平台自己产出的「处理说明」由服务端词表把关。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { listAdminFeedback, handleFeedback } from '@/api/admin'
import { formatDateTime, FEEDBACK_TYPE_TEXT, FEEDBACK_STATUS_TEXT } from '@/utils/format'
import { confirmThen } from '@/utils/confirm'
import { usePagedList } from '@/composables/usePagedList'

/**
 * 反馈分类 / 状态选项（取自 utils/format.js 的 FEEDBACK_TYPE_TEXT / FEEDBACK_STATUS_TEXT）。
 *
 * <p>不在本页另抄一份：抄一份就会两处各改一次、迟早对不上。</p>
 */
const FEEDBACK_TYPES = Object.entries(FEEDBACK_TYPE_TEXT).map(([value, label]) => ({ value, label }))

const STATUS_OPTIONS = Object.entries(FEEDBACK_STATUS_TEXT).map(([value, label]) => ({ value, label }))

/** 筛选条件；date 是 [开始, 结束] 两段字符串（value-format=YYYY-MM-DD） */
const filter = ref({ status: '', type: '', keyword: '', date: [] })

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
    if (filter.value.type) params.type = filter.value.type
    if (filter.value.keyword) params.keyword = filter.value.keyword.trim()
    // Q11 的五项筛选里，日期区间此前只落在后端，管理端界面上没有入口
    if (filter.value.date && filter.value.date.length === 2) {
      params.startDate = filter.value.date[0]
      params.endDate = filter.value.date[1]
    }
    const data = await listAdminFeedback(params)
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
 * 写操作后必要时回退一页。与其余 7 个后台页共用同一份实现。
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

/** 终态（已采纳 / 未采纳）不允许再操作 */
function isTerminal(status) {
  return status === 'RESOLVED' || status === 'REJECTED'
}

/* ==================== 处理弹窗 ==================== */

const handleVisible = ref(false)
const handleItem = ref(null)
const handleStatus = ref('PROCESSING')
const handleResult = ref('')
const handleError = ref('')
/**
 * 确认处理的 in-flight 锁（P1-5）。
 *
 * 连点「确认处理」会提交两次：第一次还在途时第二次请求已经打出去，
 * 后端对同一反馈二次处理返回 409，拦截器把它弹成错误 toast ——
 * 用户看到「成功 + 报错」双提示。
 */
const handleSubmitting = ref(false)

function openHandle(row) {
  handleItem.value = row
  // 默认目标状态顺着流转：待处理 → 处理中，处理中 → 已采纳
  handleStatus.value = row.status === 'PENDING' ? 'PROCESSING' : 'RESOLVED'
  handleResult.value = ''
  handleError.value = ''
  handleVisible.value = true
}

async function submitHandle() {
  const result = handleResult.value.trim()
  // 后端 handleResult 要求 10–500 字，先在前端拦一道避免无效请求
  if (result.length < 10) {
    handleError.value = '请填写处理说明（至少 10 个字）'
    return
  }
  if (!handleItem.value) return
  // ⚠️ in-flight 锁必须在第一个 await 之前同步置位（P1-5）
  if (handleSubmitting.value) return
  handleSubmitting.value = true
  handleError.value = ''
  try {
    // confirmThen 把「用户是否点了确认」变成布尔：取消时静默返回 false，不发请求（P1-12）
    const confirmed = await confirmThen(() =>
      ElMessageBox.confirm(
        `确认将反馈 #${handleItem.value.id} 处理为「${statusLabel(handleStatus.value)}」？`,
        '确认处理',
        { confirmButtonText: '确认', type: 'warning' }
      )
    )
    if (!confirmed) return

    await handleFeedback(handleItem.value.id, {
      status: handleStatus.value,
      handleResult: result
    })
    ElMessage.success('已更新反馈处理状态')
    handleVisible.value = false
    await reloadAfterMutate()
  } catch (err) {
    // 6003（敏感词 / 医疗红线词表命中）定位到说明输入框下方：撤掉拦截器的顶部 toast，
    // 避免同一错误弹两次；其余错误（409 并发 / 12001 不存在）保留全局 toast
    if (err?.code === 6003) {
      ElMessage.closeAll()
      handleError.value = err.message || '处理说明包含违规表述，请修改后重试'
    }
  } finally {
    handleSubmitting.value = false
  }
}

function statusLabel(s) {
  return STATUS_OPTIONS.find((o) => o.value === s)?.label || s
}

/** 列定义（desktop 6 列；mobile 关键 4 字段：编号 / 分类 / 状态 / 时间） */
const columns = [
  { key: 'id', label: '编号', width: 90 },
  { key: 'typeLabel', label: '分类', width: 110 },
  { key: 'submitterName', label: '提交人', width: 100 },
  { key: 'content', label: '反馈内容', minWidth: 280 },
  { key: 'status', label: '状态', width: 100 },
  { key: 'createTime', label: '提交时间', width: 160, formatter: (v) => formatDateTime(v) }
]
const mobileFields = ['id', 'typeLabel', 'submitterName']

onMounted(loadList)
</script>

<template>
  <NlCard title="产品反馈" plain>
    <p class="nl-caption nl-text-muted">
      用户提的是<strong>产品本身</strong>的建议（界面 / 流程 / 功能缺失），与陪诊订单的服务纠纷无关 ——
      后者在「投诉管理」里。处理说明会经站内信原样发给提交人，请写成用户能看懂的话。
    </p>

    <section class="filters">
      <el-select
        v-model="filter.status"
        placeholder="状态"
        clearable
        style="width: 140px"
        @change="onFilterChange"
      >
        <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
      <el-select
        v-model="filter.type"
        placeholder="分类"
        clearable
        style="width: 140px"
        @change="onFilterChange"
      >
        <el-option v-for="t in FEEDBACK_TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-date-picker
        v-model="filter.date"
        type="daterange"
        range-separator="-"
        start-placeholder="提交时间起"
        end-placeholder="提交时间止"
        value-format="YYYY-MM-DD"
        style="width: 240px"
        @change="onFilterChange"
      />
      <el-input
        v-model="filter.keyword"
        placeholder="内容 / 提交人姓名"
        clearable
        style="width: 200px"
        @keyup.enter="onFilterChange"
      />
      <el-button type="primary" plain @click="onFilterChange">查询</el-button>
    </section>

    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="反馈列表加载失败"
      action-text="重试"
      @action="loadList"
    />

    <NlEmpty
      v-else-if="pageDrained"
      type="empty"
      title="本页已空，上一页还有"
      description="刚处理完的记录已不在本页，可返回上一页继续处理"
      action-text="返回上一页"
      @action="onPrevPage"
    />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无反馈"
      description="当前筛选条件下没有反馈记录"
    />

    <NlAdminTable
      v-else
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      empty-text="暂无反馈"
    >
      <template #cell-content="{ row }">
        <span class="cell-content">{{ row.content }}</span>
        <el-tag v-if="row.images && row.images.length" size="small" type="info" class="cell-tag">
          {{ row.images.length }} 张图
        </el-tag>
      </template>
      <template #cell-status="{ row }">
        <NlStatusChip
          scope="feedback"
          :status="row.status"
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
        <span v-else class="nl-caption nl-text-muted">已处理</span>
      </template>
    </NlAdminTable>

    <el-pagination
      v-if="total > size"
      class="feedback-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPageChange"
    />
  </NlCard>

  <!-- 处理对话框 -->
  <el-dialog
    v-model="handleVisible"
    :title="`反馈处理 - #${handleItem?.id || ''}`"
    width="600px"
    align-center
  >
    <div v-if="handleItem" class="feedback-handle">
      <section class="feedback-handle__row">
        <span class="feedback-handle__label">分类</span>
        <span class="nl-body">{{ handleItem.typeLabel || handleItem.type }}</span>
      </section>
      <section class="feedback-handle__row">
        <span class="feedback-handle__label">提交人</span>
        <span class="nl-body">{{ handleItem.submitterName || '—' }}</span>
      </section>
      <section class="feedback-handle__row">
        <span class="feedback-handle__label">反馈内容</span>
        <span class="nl-body feedback-handle__content">{{ handleItem.content }}</span>
      </section>
      <section v-if="handleItem.images && handleItem.images.length" class="feedback-handle__row">
        <span class="feedback-handle__label">截图</span>
        <span class="feedback-handle__shots">
          <a v-for="u in handleItem.images" :key="u" :href="u" target="_blank" rel="noopener">
            <img :src="u" alt="反馈截图" class="feedback-handle__shot" />
          </a>
        </span>
      </section>
      <section v-if="handleItem.handleResult" class="feedback-handle__row">
        <span class="feedback-handle__label">既往说明</span>
        <span class="nl-body">{{ handleItem.handleResult }}</span>
      </section>

      <section class="feedback-handle__block">
        <h4 class="nl-h3">处理决定</h4>
        <el-radio-group v-model="handleStatus">
          <el-radio-button value="PROCESSING">处理中</el-radio-button>
          <el-radio-button value="RESOLVED">已采纳</el-radio-button>
          <el-radio-button value="REJECTED">未采纳</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="handleResult"
          type="textarea"
          :rows="3"
          placeholder="处理说明（必填，10–500 字）。到达「已采纳 / 未采纳」时会经站内信发给提交人"
          maxlength="500"
          show-word-limit
        />
        <p v-if="handleError" class="feedback-handle__error">{{ handleError }}</p>
      </section>
    </div>

    <template #footer>
      <el-button @click="handleVisible = false">取消</el-button>
      <el-button type="primary" :loading="handleSubmitting" @click="submitHandle">
        确认处理
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-3);
  margin: var(--nl-space-4) 0;
}

.feedback-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}

.cell-content {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.cell-tag {
  margin-left: var(--nl-space-2);
}

.feedback-handle {
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
    width: 72px;
    font-size: var(--nl-font-caption);
    color: $nl-text-2;
    text-align: right;
  }

  &__content {
    white-space: pre-wrap;
  }

  &__shots {
    display: flex;
    gap: var(--nl-space-2);
  }

  &__shot {
    width: 72px;
    height: 72px;
    object-fit: cover;
    border-radius: $nl-radius-input;
    border: 1px solid var(--nl-border);
  }

  &__block {
    display: flex;
    flex-direction: column;
    gap: $nl-space-3;
  }

  &__error {
    margin: 0;
    font-size: var(--nl-font-caption);
    color: $nl-danger;
  }
}
</style>
