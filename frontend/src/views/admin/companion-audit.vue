<script setup>
/**
 * 陪诊员资质审核（W-02 · docs/design/design.md §4 / PRD §M9）
 *
 * - 顶部 Tab 切换：待审 / 通过 / 驳回
 * - 表格：编号 / 账号 / 姓名 / 手机号(脱敏) / 身份证号(脱敏) / 证件数 / 提交时间 / 状态 / 操作
 * - 证件预览对话框：拉详情接口拿到完整证件与驳回原因（列表口径故意不下发这些字段）
 * - 通过 / 驳回：驳回时 rejectReason 必填（后端 Service 校验，空了返回 8003）
 *
 * 注意：资质状态的 PENDING 是「待审核」，因此 NlStatusChip 必须传 scope="audit"，
 * 否则会错误地显示成订单语义的「待接单」。
 */
import { ref, computed, onMounted, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { useResponsive } from '@/composables/useResponsive'
import { listAuditApplications, getAuditDetail, auditCompanion } from '@/api/admin'
import { fetchCertObjectUrl } from '@/api/file'
import { formatDateTime } from '@/utils/format'
import { confirmThen } from '@/utils/confirm'
import { usePagedList } from '@/composables/usePagedList'

const tab = ref('PENDING')
const list = ref([])
const loading = ref(false)
const total = ref(0)
/** R4：列表加载失败（与「当前筛选下没有数据」区分） */
const loadError = ref(false)
const page = ref(1)
const size = ref(10)

/** 列表按 auditStatus 走后端分页查询，不在前端过滤（否则只能看到已加载的那一页） */
async function loadList() {
  loading.value = true
  try {
    const data = await listAuditApplications({
      page: page.value,
      size: size.value,
      auditStatus: tab.value
    })
    list.value = data?.records || []
    total.value = data?.total || 0
    loadError.value = false
  } catch {
    // 拦截器已弹错误提示，这里只兜底清空，交给空态展示
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

// 切 tab 回到第一页重新拉数，避免停留在上一页的页码上看到旧数据
watch(tab, () => {
  page.value = 1
  loadList()
})

function onPageChange(p) {
  page.value = p
  loadList()
}

const reviewVisible = ref(false)
const reviewItem = ref(null)
const reviewFiles = ref([])
const rejectReason = ref('')
/**
 * 审核动作的 in-flight 锁（P1-5）。
 *
 * <p>「通过」和「驳回」共用一把锁：连点会提交两次，第一次还在途时第二次已经出去，
 * 后端对同一申请二次审核返回业务错误，拦截器弹成错误 toast ——
 * 用户看到「成功 + 报错」双提示，以为系统出了问题。</p>
 */
const auditSubmitting = ref(false)
/** 当前正在提交的动作（'approve' / 'reject'），用来只给被点的那个按钮转圈 */
const auditAction = ref('')
/** 证件预览用的 blob URL 集合，关闭弹窗 / 卸载时统一回收，避免内存泄漏 */
const certObjectUrls = []

function revokeCertUrls() {
  certObjectUrls.forEach((u) => URL.revokeObjectURL(u))
  certObjectUrls.length = 0
}

async function openReview(item) {
  try {
    // 列表接口不下发驳回原因 / 内部备注 / 证件材料（后端用 includeAuditNote 开关区分），
    // 审核或查看详情必须再拉一次详情接口才能拿到全量信息
    const detail = await getAuditDetail(item.id)
    reviewItem.value = detail
    rejectReason.value = detail?.rejectReason || ''
    const certs = detail?.certificates || []
    // 证件原始 /uploads URL 已被服务端守卫拦截（未认证直连 403），
    // 因此先占位、再经受控端点逐个取 blob 并回填
    reviewFiles.value = certs.map((c) => ({ name: c.name, url: '' }))
    reviewVisible.value = true
    await Promise.all(
      certs.map(async (c, i) => {
        if (!c.url) return
        try {
          const objectUrl = await fetchCertObjectUrl(c.url)
          certObjectUrls.push(objectUrl)
          reviewFiles.value[i].url = objectUrl
        } catch {
          // 无权限 / 文件缺失：request.js 拦截器已解析 blob 中的业务错误并弹出提示，
          // 这里仅保留占位图标，不阻断审核流程
        }
      })
    )
  } catch {
    // 拦截器已提示，无需重复弹
  }
}

async function approve() {
  if (!reviewItem.value) return
  // ⚠️ in-flight 锁必须在第一个 await 之前同步置位（P1-5）
  if (auditSubmitting.value) return
  auditSubmitting.value = true
  auditAction.value = 'approve'
  try {
    // confirmThen 把「用户是否点了确认」变成布尔：取消时静默返回 false，不发请求（P1-12）
    const confirmed = await confirmThen(() =>
      ElMessageBox.confirm(
        `确认通过「${reviewItem.value.realName}」的资质审核？通过后陪诊员可开始接单。`,
        '审核通过',
        { confirmButtonText: '通过审核' }
      )
    )
    if (!confirmed) return

    const applicationId = reviewItem.value.id
    // 通过时不传 rejectReason，避免后端日志里出现一条假原因
    await auditCompanion(applicationId, { approved: true })
    ElMessage.success('已通过审核')
    reviewVisible.value = false
    await reloadAfterMutate()
  } catch {
    // 后端报错由 request.js 拦截器统一弹提示，这里不重复弹
  } finally {
    auditSubmitting.value = false
    auditAction.value = ''
  }
}

async function reject() {
  const reason = rejectReason.value.trim()
  if (!reason) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  if (reason.length < 5) {
    ElMessage.warning('驳回原因至少 5 个字')
    return
  }
  if (!reviewItem.value) return
  // ⚠️ in-flight 锁必须在第一个 await 之前同步置位（P1-5）
  if (auditSubmitting.value) return
  auditSubmitting.value = true
  auditAction.value = 'reject'
  try {
    // confirmThen 把「用户是否点了确认」变成布尔：取消时静默返回 false，不发请求（P1-12）
    const confirmed = await confirmThen(() =>
      ElMessageBox.confirm(
        `确认驳回「${reviewItem.value.realName}」的资质申请？驳回后将通知陪诊员。`,
        '审核驳回',
        { confirmButtonText: '确认驳回', type: 'warning' }
      )
    )
    if (!confirmed) return

    const applicationId = reviewItem.value.id
    await auditCompanion(applicationId, { approved: false, rejectReason: reason })
    ElMessage.success('已驳回')
    reviewVisible.value = false
    await reloadAfterMutate()
  } catch {
    // 后端报错由 request.js 拦截器统一弹提示，这里不重复弹
  } finally {
    auditSubmitting.value = false
    auditAction.value = ''
  }
}

/** 列定义（desktop 9 列；mobile 关键 4 字段：账号 / 姓名 / 状态 / 操作） */
const columns = [
  { key: 'id',         label: '编号',     width: 100 },
  { key: 'username',   label: '账号',     width: 110 },
  { key: 'realName',   label: '姓名',     width: 80 },
  { key: 'phone',      label: '手机号',   width: 120 },
  { key: 'idCard',     label: '身份证号', width: 170 },
  { key: 'certCount',  label: '证件数',   width: 80 },
  { key: 'submitTime', label: '提交时间', width: 160, formatter: (v) => formatDateTime(v) },
  { key: 'auditStatus', label: '状态',    width: 120 }
]
const mobileFields = ['username', 'realName', 'auditStatus']
/**
 * 空态标题（表格 fallback 与 NlEmpty 共用同一口径，别写两份三元）。
 */
const emptyTitle = computed(() =>
  tab.value === 'PENDING' ? '暂无待审申请' : tab.value === 'APPROVED' ? '尚无通过记录' : '尚无驳回记录'
)
/**
 * 空态说明句。
 *
 * <p>统一落在「当前筛选条件下没有 X 记录」这个口径上 ——
 * 空态要能回答「为什么空」，而不是只说「这里是空的」。</p>
 */
const emptyDesc = computed(() =>
  tab.value === 'PENDING'
    ? '当前筛选条件下没有待审申请'
    : tab.value === 'APPROVED'
      ? '当前筛选条件下没有通过记录'
      : '当前筛选条件下没有驳回记录'
)

/** 详情弹窗 < 768 fullscreen（admin 平板 fallback，与 T-02 admin/order 同款；用 isXs(≤767)替代 !isMd 修复大屏 PC bug） */
const { isXs } = useResponsive()

onMounted(loadList)
onBeforeUnmount(revokeCertUrls)
</script>

<template>
  <NlCard title="陪诊员资质审核" plain>
    <el-tabs v-model="tab" class="audit-tabs">
      <el-tab-pane label="待审" name="PENDING" />
      <el-tab-pane label="已通过" name="APPROVED" />
      <el-tab-pane label="已驳回" name="REJECTED" />
    </el-tabs>

    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="资质审核列表加载失败"
      action-text="重试"
      @action="loadList"
    />

    <NlEmpty
      v-else-if="pageDrained"
      type="empty"
      title="本页已空，上一页还有"
      description="刚审核掉的申请已不在本页，可返回上一页继续审核"
      action-text="返回上一页"
      @action="onPrevPage"
    />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      :title="emptyTitle"
      :description="emptyDesc"
    />

    <NlAdminTable
      v-else
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      :empty-text="emptyTitle"
    >
      <template #cell-certCount="{ row }">
        <el-tag size="small" type="info">{{ (row.certificates || []).length }}</el-tag>
      </template>
      <template #cell-auditStatus="{ row }">
        <NlStatusChip :status="row.auditStatus" scope="audit" :text="row.auditStatusLabel" />
      </template>
      <template #actions="{ row }">
        <el-button type="primary" text size="small" @click="openReview(row)">
          {{ row.auditStatus === 'PENDING' ? '审核' : '查看' }}
        </el-button>
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

  <!-- 审核对话框 -->
  <el-dialog
    v-model="reviewVisible"
    :title="`陪诊员资质审核 - ${reviewItem?.realName || ''}`"
    width="640px"
    :fullscreen="isXs"
    align-center
    @closed="revokeCertUrls"
  >
    <div v-if="reviewItem" class="audit-dialog">
      <section class="audit-dialog__row">
        <span class="audit-dialog__label">手机号</span>
        <span class="is-num">{{ reviewItem.phone }}</span>
      </section>
      <section class="audit-dialog__row">
        <span class="audit-dialog__label">身份证号</span>
        <span class="is-num">{{ reviewItem.idCard }}</span>
      </section>
      <section v-if="reviewItem.serviceArea" class="audit-dialog__row">
        <span class="audit-dialog__label">服务区域</span>
        <span class="nl-body">{{ reviewItem.serviceArea }}</span>
      </section>
      <section v-if="reviewItem.availableTime" class="audit-dialog__row">
        <span class="audit-dialog__label">可服务时段</span>
        <span class="nl-body">{{ reviewItem.availableTime }}</span>
      </section>
      <section class="audit-dialog__row">
        <span class="audit-dialog__label">证件资料</span>
        <ul class="certs">
          <li v-for="f in reviewFiles" :key="f.name">
            <div class="certs__thumb">
              <img v-if="f.url" :src="f.url" :alt="f.name" class="certs__img" />
              <svg v-else viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5">
                <rect x="3" y="5" width="18" height="14" rx="2" />
                <circle cx="9" cy="11" r="2" />
                <path d="M21 17l-5-5-9 9" />
              </svg>
            </div>
            <span>{{ f.name }}</span>
          </li>
        </ul>
      </section>

      <!-- 申请补充说明：详情接口才下发，列表看不到 -->
      <section v-if="reviewItem.applyRemark" class="audit-dialog__row">
        <span class="audit-dialog__label">申请备注</span>
        <span class="nl-body">{{ reviewItem.applyRemark }}</span>
      </section>

      <section v-if="reviewItem.auditStatus === 'PENDING'" class="audit-dialog__row">
        <span class="audit-dialog__label">驳回原因</span>
        <el-input
          v-model="rejectReason"
          type="textarea"
          :rows="3"
          placeholder="仅在驳回时填写；必填，至少 5 个字"
          maxlength="200"
          show-word-limit
        />
      </section>
      <!-- 已驳回：展示后端下发的驳回原因；已通过：展示内部备注（若有） -->
      <section v-else-if="reviewItem.rejectReason" class="audit-dialog__row">
        <span class="audit-dialog__label">驳回原因</span>
        <span class="nl-body">{{ reviewItem.rejectReason }}</span>
      </section>
      <section v-else-if="reviewItem.auditRemark" class="audit-dialog__row">
        <span class="audit-dialog__label">审核备注</span>
        <span class="nl-body">{{ reviewItem.auditRemark }}</span>
      </section>
    </div>

    <template #footer>
      <span v-if="reviewItem?.auditStatus === 'PENDING'" class="audit-actions">
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="auditSubmitting && auditAction === 'reject'"
          :disabled="auditSubmitting"
          @click="reject"
        >
          驳 回
        </el-button>
        <el-button
          type="primary"
          :loading="auditSubmitting && auditAction === 'approve'"
          :disabled="auditSubmitting"
          @click="approve"
        >
          通 过
        </el-button>
      </span>
      <el-button v-else @click="reviewVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.audit-tabs {
  margin-bottom: var(--nl-space-4);
}

.audit-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}

.audit-dialog {
  &__row {
    display: flex;
    gap: var(--nl-space-4);
    align-items: flex-start;
    padding: var(--nl-space-3) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }
  }

  &__label {
    flex-shrink: 0;
    width: 88px;
    padding-top: 4px;
    font-size: var(--nl-font-caption);
    color: $nl-text-2;
    text-align: right;
  }
}

.certs {
  display: flex;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: flex;
    flex-direction: column;
    gap: 6px;
    align-items: center;
    font-size: var(--nl-font-caption);
    color: $nl-text-2;
  }

  &__thumb {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 80px;
    height: 56px;
    overflow: hidden;
    color: var(--nl-primary-text);
    background: var(--nl-primary-light);
    border-radius: 6px;
    border: 1px dashed var(--nl-primary);
  }

  &__img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.audit-actions {
  display: inline-flex;
  gap: var(--nl-space-2);
}
</style>
