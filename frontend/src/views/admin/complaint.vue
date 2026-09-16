<script setup>
/**
 * W-06 投诉管理（管理员）
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip } from '@/components'

const list = ref([
  { id: 'CP20250916001', order: 'OD20250916001', family: '张丽', companion: '李师傅', reason: '陪诊员迟到', content: '约定 09:00，迟到 30 分钟', status: 'PENDING',    submitAt: '2026-09-16 11:00' },
  { id: 'CP20250912002', order: 'OD20250912005', family: '李梅', companion: '张师傅', reason: '服务态度',   content: '说话不耐烦，希望平台介入', status: 'PROCESSING', submitAt: '2026-09-12 14:00' },
  { id: 'CP20250905003', order: 'OD20250905008', family: '陈九', companion: '王师傅', reason: '费用纠纷',   content: '重复收取陪诊费',         status: 'CLOSED',     submitAt: '2026-09-05 09:00' }
])

async function next(s) {
  if (s.status === 'CLOSED') {
    ElMessage.info('该投诉已结案')
    return
  }
  await ElMessageBox.confirm(
    `将「${s.id}」的状态变更为「${s.status === 'PENDING' ? '处理中' : '已结案'}」？`,
    '推进投诉处理',
    { confirmButtonText: '确认推进' }
  )
  s.status = s.status === 'PENDING' ? 'PROCESSING' : 'CLOSED'
  ElMessage.success('已推进')
}
</script>

<template>
  <NlCard title="投诉管理" plain>
    <el-table :data="list">
      <el-table-column prop="id" label="投诉编号" width="160" />
      <el-table-column prop="order" label="关联订单" width="180" />
      <el-table-column prop="family" label="投诉家属" width="100" />
      <el-table-column prop="companion" label="涉及陪诊员" width="100" />
      <el-table-column prop="reason" label="类型" width="100" />
      <el-table-column prop="content" label="详情" min-width="220" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <NlStatusChip :status="row.status" :dot="row.status !== 'CLOSED'" />
        </template>
      </el-table-column>
      <el-table-column prop="submitAt" label="提交时间" width="160" />
      <el-table-column label="操作" min-width="120" fixed="right">
        <template #default="{ row }">
          <el-button
            :type="row.status === 'PROCESSING' ? 'success' : 'primary'"
            text
            size="small"
            :disabled="row.status === 'CLOSED'"
            @click="next(row)"
          >
            {{ row.status === 'PENDING' ? '开始处理' : row.status === 'PROCESSING' ? '结案' : '已结案' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </NlCard>
</template>
