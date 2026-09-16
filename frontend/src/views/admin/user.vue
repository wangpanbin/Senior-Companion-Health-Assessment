<script setup>
/**
 * W-04 用户管理
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip } from '@/components'

const filter = ref({ role: '', status: '', keyword: '' })

const list = ref([
  { id: 1, account: 'fam001', nickname: '张丽',   role: 'FAMILY',    phone: '138****8888', idCard: '110101********4567', boundAt: '2026-08-12', status: 'ACTIVE' },
  { id: 2, account: 'fam002', nickname: '李梅',   role: 'FAMILY',    phone: '138****7777', idCard: '310101********7890', boundAt: '2026-08-25', status: 'ACTIVE' },
  { id: 3, account: 'comp001', nickname: '张三',  role: 'COMPANION', phone: '138****6543', idCard: '510101********0123', boundAt: '2026-08-01', status: 'ACTIVE' },
  { id: 4, account: 'comp002', nickname: '李四',  role: 'COMPANION', phone: '137****3456', idCard: '320101********2345', boundAt: '2026-09-10', status: 'BANNED' },
  { id: 5, account: 'elder001', nickname: '张大爷', role: 'ELDER',    phone: '138****1111', idCard: '110101********9988', boundAt: '2026-07-20', status: 'ACTIVE' },
  { id: 6, account: 'admin', nickname: '超级管理员', role: 'ADMIN', phone: '139****0000', idCard: '110101********0001', boundAt: '2026-01-01', status: 'ACTIVE' }
])

async function ban(u) {
  await ElMessageBox.confirm(
    `封禁「${u.nickname}」(${u.account})？封禁后已签发 token 立即失效，用户将无法登录。`,
    '确认封禁',
    { confirmButtonText: '确认封禁', type: 'warning' }
  )
  u.status = 'BANNED'
  ElMessage.success('已封禁')
}

async function unban(u) {
  await ElMessageBox.confirm(`解封「${u.nickname}」？用户可以重新登录。`, '确认解封', {
    confirmButtonText: '确认解封'
  })
  u.status = 'ACTIVE'
  ElMessage.success('已解封')
}

async function resetPwd(u) {
  await ElMessageBox.confirm(
    `重置「${u.nickname}」的密码为默认密码「Nl@123456」？操作记录将写入 admin_oper_log。`,
    '重置密码',
    { confirmButtonText: '确认重置', type: 'warning' }
  )
  ElMessage.success('密码已重置，默认密码已通过短信发送给用户')
}
</script>

<template>
  <NlCard title="用户管理" plain>
    <section class="filters">
      <el-select v-model="filter.role" placeholder="角色" clearable style="width: 140px">
        <el-option label="家属" value="FAMILY" />
        <el-option label="陪诊员" value="COMPANION" />
        <el-option label="老人" value="ELDER" />
        <el-option label="管理员" value="ADMIN" />
      </el-select>
      <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
        <el-option label="正常" value="ACTIVE" />
        <el-option label="已封禁" value="BANNED" />
      </el-select>
      <el-input v-model="filter.keyword" placeholder="搜索账号 / 昵称 / 手机号" clearable style="width: 240px" />
      <el-button type="primary" plain>查询</el-button>
    </section>

    <el-table :data="list">
      <el-table-column prop="account" label="账号" width="120" />
      <el-table-column prop="nickname" label="昵称" width="120" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag size="small" type="info">{{ row.role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="phone" label="手机号" width="140" />
      <el-table-column prop="idCard" label="身份证号" width="180" />
      <el-table-column prop="boundAt" label="注册时间" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <NlStatusChip :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="240" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" size="small" @click="resetPwd(row)">重置密码</el-button>
          <el-button
            v-if="row.status === 'ACTIVE'"
            text
            type="danger"
            size="small"
            @click="ban(row)"
          >
            封禁
          </el-button>
          <el-button
            v-else
            text
            type="primary"
            size="small"
            @click="unban(row)"
          >
            解封
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </NlCard>
</template>

<style scoped lang="scss">
.filters {
  display: flex;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);
}
</style>
