<script setup>
/**
 * 陪诊员资质审核（W-02 · design.md §4 / PRD §M9）
 *
 * - 顶部 Tab 切换：待审 / 通过 / 驳回（M9 管理员审核流程）
 * - 表格：账号 / 姓名 / 手机号(脱敏) / 身份证号(脱敏) / 提交时间 / 操作
 * - 证件预览对话框：身份证 + 健康证 + 其它资质
 * - 通过 / 驳回：
 *   - 通过：直接写库，Toast 成功
 *   - 驳回：弹对话框必填原因（M9 业务规则：驳回必须填原因）
 * - 复核按钮：已通过 / 已驳回可「撤销决定」（写 admin_oper_log）
 */
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip } from '@/components'

/** mock 列表 */
const list = ref([
  { id: 'A001', account: 'comp001', name: '张三', phone: '138****1234', idCard: '110101********4567', certs: 3, submitAt: '2026-09-15 10:23', status: 'PENDING' },
  { id: 'A002', account: 'comp002', name: '李四', phone: '138****5678', idCard: '310101********7890', certs: 2, submitAt: '2026-09-14 16:45', status: 'PENDING' },
  { id: 'A003', account: 'comp003', name: '王五', phone: '139****9012', idCard: '440101********0123', certs: 3, submitAt: '2026-09-14 09:10', status: 'PENDING' },
  { id: 'A004', account: 'comp010', name: '陈七', phone: '137****3456', idCard: '320101********2345', certs: 2, submitAt: '2026-09-13 18:30', status: 'APPROVED' },
  { id: 'A005', account: 'comp011', name: '赵八', phone: '136****7890', idCard: '510101********6789', certs: 1, submitAt: '2026-09-12 14:00', status: 'REJECTED', reason: '健康证已过期' }
])

const tab = ref('PENDING')

const filtered = computed(() => list.value.filter((x) => x.status === tab.value))

const reviewRef = ref(null)
const reviewItem = ref(null)
const reviewFiles = ref([])
const rejectReason = ref('')

function openReview(item) {
  reviewItem.value = item
  rejectReason.value = item.reason || ''
  reviewFiles.value = [
    { name: '身份证正面', url: '' },
    { name: '身份证反面', url: '' },
    { name: '健康证', url: '' }
  ]
  reviewRef.value.open()
}

async function approve() {
  await ElMessageBox.confirm(
    `确认通过「${reviewItem.value.name}」的资质审核？通过后陪诊员可开始接单。`,
    '审核通过',
    { confirmButtonText: '通过审核' }
  )
  const t = list.value.find((x) => x.id === reviewItem.value.id)
  if (t) t.status = 'APPROVED'
  ElMessage.success('已通过审核')
  reviewRef.value.close()
}

async function reject() {
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  await ElMessageBox.confirm(
    `确认驳回「${reviewItem.value.name}」的资质申请？驳回后将通知陪诊员。`,
    '审核驳回',
    { confirmButtonText: '确认驳回', type: 'warning' }
  )
  const t = list.value.find((x) => x.id === reviewItem.value.id)
  if (t) {
    t.status = 'REJECTED'
    t.reason = rejectReason.value
  }
  ElMessage.success('已驳回')
  reviewRef.value.close()
}

async function restore(item) {
  await ElMessageBox.confirm(
    `撤销「${item.name}」的 ${item.status === 'APPROVED' ? '通过' : '驳回'}决定？撤销后回到待审状态。`,
    '撤销审核决定',
    { type: 'warning' }
  )
  item.status = 'PENDING'
  delete item.reason
  ElMessage.success('已撤销')
}
</script>

<template>
  <NlCard title="陪诊员资质审核" plain>
    <el-tabs v-model="tab" class="audit-tabs">
      <el-tab-pane label="待审" name="PENDING" />
      <el-tab-pane label="已通过" name="APPROVED" />
      <el-tab-pane label="已驳回" name="REJECTED" />
    </el-tabs>

    <el-table :data="filtered" style="width: 100%" :empty-text="tab === 'PENDING' ? '暂无待审申请' : tab === 'APPROVED' ? '尚无通过记录' : '尚无驳回记录'">
      <el-table-column prop="id" label="编号" width="100" />
      <el-table-column prop="account" label="账号" width="110" />
      <el-table-column prop="name" label="姓名" width="80" />
      <el-table-column prop="phone" label="手机号" width="120" />
      <el-table-column prop="idCard" label="身份证号" width="170" />
      <el-table-column label="证件数" width="80">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.certs }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="submitAt" label="提交时间" width="160" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <NlStatusChip :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" text size="small" @click="openReview(row)">
            {{ row.status === 'PENDING' ? '审核' : '查看' }}
          </el-button>
          <el-button
            v-if="row.status !== 'PENDING'"
            type="warning"
            text
            size="small"
            @click="restore(row)"
          >
            撤销决定
          </el-button>
          <span v-if="row.status === 'REJECTED' && row.reason" class="nl-caption nl-text-muted">
            · {{ row.reason }}
          </span>
        </template>
      </el-table-column>
    </el-table>
  </NlCard>

  <!-- 审核对话框 -->
  <el-dialog
    ref="reviewRef"
    :title="`陪诊员资质审核 - ${reviewItem?.name || ''}`"
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
      <section class="audit-dialog__row">
        <span class="audit-dialog__label">证件资料</span>
        <ul class="certs">
          <li v-for="f in reviewFiles" :key="f.name">
            <div class="certs__thumb">
              <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5">
                <rect x="3" y="5" width="18" height="14" rx="2" />
                <circle cx="9" cy="11" r="2" />
                <path d="M21 17l-5-5-9 9" />
              </svg>
            </div>
            <span>{{ f.name }}</span>
          </li>
        </ul>
      </section>

      <section v-if="reviewItem.status === 'PENDING'" class="audit-dialog__row">
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
      <section v-else-if="reviewItem.reason" class="audit-dialog__row">
        <span class="audit-dialog__label">驳回原因</span>
        <span class="nl-body">{{ reviewItem.reason }}</span>
      </section>
    </div>

    <template #footer>
      <span v-if="reviewItem?.status === 'PENDING'" class="audit-actions">
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
    color: var(--nl-primary);
    background: var(--nl-primary-light);
    border-radius: 6px;
    border: 1px dashed var(--nl-primary);
  }
}

.audit-actions {
  display: inline-flex;
  gap: var(--nl-space-2);
}
</style>
