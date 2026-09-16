<script setup>
/**
 * W-08 操作日志（admin_oper_log 只读查询）
 */
import { ref } from 'vue'
import { NlCard } from '@/components'

const list = ref([
  { ts: '2026-09-16 14:32:11', operator: 'admin', action: 'AUDIT', target: '陪诊员 comp002', detail: '驳回资质，原因：健康证已过期' },
  { ts: '2026-09-16 11:00:30', operator: 'admin', action: 'BAN',   target: '陪诊员 comp002', detail: '封禁账号，原因：连续被投诉 3 次' },
  { ts: '2026-09-15 18:42:05', operator: 'admin', action: 'FORCE', target: '订单 OD20250911001', detail: '强制取消订单，已通知双方' },
  { ts: '2026-09-15 09:30:00', operator: 'admin', action: 'PWD',   target: '家属 fam005', detail: '重置密码为默认密码' },
  { ts: '2026-09-14 14:20:18', operator: 'admin', action: 'CLOSE', target: '投诉 CP20250905003', detail: '结案，处理完成' }
])

const actionMap = {
  AUDIT: '资质审核',
  BAN: '封禁',
  FORCE: '强制改终态',
  PWD: '重置密码',
  UNBAN: '解封',
  CLOSE: '投诉结案'
}
</script>

<template>
  <NlCard title="操作日志" plain>
    <p class="nl-caption nl-text-muted">
      所有管理动作都会写入 admin_oper_log，本页面只读，用于审计与追溯。
    </p>

    <el-table :data="list">
      <el-table-column prop="ts" label="时间" width="170" />
      <el-table-column prop="operator" label="操作人" width="100" />
      <el-table-column label="动作" width="100">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ actionMap[row.action] || row.action }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="target" label="对象" width="180" />
      <el-table-column prop="detail" label="详情" min-width="280" />
    </el-table>
  </NlCard>
</template>
