<script setup>
/**
 * 陪诊员资质审核（W-02 · design.md §4 / PRD §M9）
 *
 * - 顶部 Tab 切换：待审 / 通过 / 驳回
 * - 表格：编号 / 账号 / 姓名 / 手机号(脱敏) / 身份证号(脱敏) / 证件数 / 提交时间 / 状态 / 操作
 * - 证件预览对话框：拉详情接口拿到完整证件与驳回原因（列表口径故意不下发这些字段）
 * - 通过 / 驳回：驳回时 rejectReason 必填（后端 Service 校验，空了返回 8003）
 *
 * 注意：资质状态的 PENDING 是「待审核」，因此 NlStatusChip 必须传 scope="audit"，
 * 否则会错误地显示成订单语义的「待接单」。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty } from '@/components'
import { listAuditApplications, getAuditDetail, auditCompanion } from '@/api/admin'
import { formatDateTime } from '@/utils/format'

const tab = ref('PENDING')
const list = ref([])
const loading = ref(false)
const total = ref(0)
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
  } catch {
    // 拦截器已弹错误提示，这里只兜底清空，交给空态展示
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const isEmpty = computed(() => !loading.value && list.value.length === 0)

// 切 tab 回到第一页重新拉数，避免停留在上一页的页码上看到旧数据
watch(tab, () => {
  page.value = 1
  loadList()
})

function onPageChange(p) {
  page.value = p
  loadList()
}

const reviewRef = ref(null)
const reviewItem = ref(null)
const reviewFiles = ref([])
const rejectReason = ref('')

async function openReview(item) {
  try {
    // 列表接口不下发驳回原因 / 内部备注 / 证件材料（后端用 includeAuditNote 开关区分），
    // 审核或查看详情必须再拉一次详情接口才能拿到全量信息
    const detail = await getAuditDetail(item.id)
    reviewItem.value = detail
    rejectReason.value = detail?.rejectReason || ''
    reviewFiles.value = (detail?.certificates || []).map((c) => ({ name: c.name, url: c.url }))
    reviewRef.value.open()
  } catch {
    // 拦截器已提示，无需重复弹
  }
}

async function approve() {
  await ElMessageBox.confirm(
    `确认通过「${reviewItem.value.realName}」的资质审核？通过后陪诊员可开始接单。`,
    '审核通过',
    { confirmButtonText: '通过审核' }
  )
  // 通过时不传 rejectReason，避免后端日志里出现一条假原因
  await auditCompanion(reviewItem.value.id, { approved: true })
  ElMessage.success('已通过审核')
  reviewRef.value.close()
  loadList()
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
  await ElMessageBox.confirm(
    `确认驳回「${reviewItem.value.realName}」的资质申请？驳回后将通知陪诊员。`,
    '审核驳回',
    { confirmButtonText: '确认驳回', type: 'warning' }
  )
  await auditCompanion(reviewItem.value.id, { approved: false, rejectReason: reason })
  ElMessage.success('已驳回')
  reviewRef.value.close()
  loadList()
}

onMounted(loadList)
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
      v-else-if="isEmpty"
      type="empty"
      :title="tab === 'PENDING' ? '暂无待审申请' : tab === 'APPROVED' ? '尚无通过记录' : '尚无驳回记录'"
    />

    <template v-else>
      <el-table :data="list" style="width: 100%" :empty-text="tab === 'PENDING' ? '暂无待审申请' : tab === 'APPROVED' ? '尚无通过记录' : '尚无驳回记录'">
        <el-table-column prop="id" label="编号" width="100" />
        <el-table-column prop="username" label="账号" width="110" />
        <el-table-column prop="realName" label="姓名" width="80" />
        <el-table-column prop="phone" label="手机号" width="120" />
        <el-table-column prop="idCard" label="身份证号" width="170" />
        <el-table-column label="证件数" width="80">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ (row.certificates || []).length }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.submitTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <NlStatusChip :status="row.auditStatus" scope="audit" :text="row.auditStatusLabel" />
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" text size="small" @click="openReview(row)">
              {{ row.auditStatus === 'PENDING' ? '审核' : '查看' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-if="total > size"
        class="audit-pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPageChange"
      />
    </template>
  </NlCard>

  <!-- 审核对话框 -->
  <el-dialog
    ref="reviewRef"
    :title="`陪诊员资质审核 - ${reviewItem?.realName || ''}`"
    width="640px"
    align-center
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
      <section class="audit-dialog__row" v-if="reviewItem.serviceArea">
        <span class="audit-dialog__label">服务区域</span>
        <span class="nl-body">{{ reviewItem.serviceArea }}</span>
      </section>
      <section class="audit-dialog__row" v-if="reviewItem.availableTime">
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
        <el-button @click="reviewRef.close()">取消</el-button>
        <el-button type="danger" @click="reject">驳 回</el-button>
        <el-button type="primary" @click="approve">通 过</el-button>
      </span>
      <el-button v-else @click="reviewRef.close()">关闭</el-button>
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
    font-size: $nl-font-caption;
    color: $nl-text-3;
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
    font-size: $nl-font-caption;
    color: $nl-text-2;
  }

  &__thumb {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 80px;
    height: 56px;
    overflow: hidden;
    color: var(--nl-primary);
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
