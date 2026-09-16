<script setup>
/**
 * W-04 用户管理
 *
 * 数据来自 listUsers（分页 page/size，响应 { total, page, size, pages, records }）。
 * 注意用户状态是 NORMAL / DISABLED（不是 ACTIVE/BANNED），NlStatusChip 用 scope="user"。
 * 封禁 / 解封都不能作用在 ADMIN 账号上（后端返回 8002），前端先拦一道给出更明确的提示。
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty } from '@/components'
import { listUsers, disableUser, enableUser, resetUserPassword } from '@/api/admin'
import { formatDateTime } from '@/utils/format'

const filter = ref({ role: '', status: '', keyword: '' })

const list = ref([])
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(10)

function buildParams() {
  const p = { page: page.value, size: size.value }
  if (filter.value.role) p.role = filter.value.role
  if (filter.value.status) p.status = filter.value.status
  if (filter.value.keyword && filter.value.keyword.trim()) p.keyword = filter.value.keyword.trim()
  return p
}

async function loadList() {
  loading.value = true
  try {
    const data = await listUsers(buildParams())
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

function onFilterChange() {
  page.value = 1
  loadList()
}

function onPageChange(p) {
  page.value = p
  loadList()
}

/** 封禁 / 解封 / 重置密码都禁止作用在 ADMIN 上，提前拦掉而不是等后端 8002 */
function assertNotAdmin(u) {
  if (u.role === 'ADMIN') {
    ElMessage.warning('管理员账号不可执行该操作')
    return false
  }
  return true
}

async function ban(u) {
  if (!assertNotAdmin(u)) return
  try {
    // disableUser 需要 reason，用 prompt 收集并写入操作日志
    const { value } = await ElMessageBox.prompt(
      `封禁「${u.nickname}」(${u.username})？封禁后该用户将无法登录，请填写原因。`,
      '确认封禁',
      {
        confirmButtonText: '确认封禁',
        type: 'warning',
        inputPattern: /\S+/,
        inputErrorMessage: '请填写封禁原因'
      }
    )
    await disableUser(u.id, { reason: value })
    ElMessage.success('已封禁')
    loadList()
  } catch {
    // 用户点取消（reject 'cancel'）或后端报错（拦截器已提示），这里不重复弹
  }
}

async function unban(u) {
  if (!assertNotAdmin(u)) return
  await ElMessageBox.confirm(`解封「${u.nickname}」？用户可以重新登录。`, '确认解封', {
    confirmButtonText: '确认解封'
  })
  await enableUser(u.id)
  ElMessage.success('已解封')
  loadList()
}

async function resetPwd(u) {
  if (!assertNotAdmin(u)) return
  await ElMessageBox.confirm(
    `重置「${u.nickname}」的密码为默认密码？操作记录将写入 admin_oper_log。`,
    '重置密码',
    { confirmButtonText: '确认重置', type: 'warning' }
  )
  await resetUserPassword(u.id)
  ElMessage.success('密码已重置，默认密码已通过短信发送给用户')
  loadList()
}

onMounted(loadList)
</script>

<template>
  <NlCard title="用户管理" plain>
    <section class="filters">
      <el-select
        v-model="filter.role"
        placeholder="角色"
        clearable
        style="width: 140px"
        @change="onFilterChange"
      >
        <el-option label="家属" value="FAMILY" />
        <el-option label="陪诊员" value="COMPANION" />
        <el-option label="老人" value="ELDER" />
        <el-option label="管理员" value="ADMIN" />
      </el-select>
      <el-select
        v-model="filter.status"
        placeholder="状态"
        clearable
        style="width: 140px"
        @change="onFilterChange"
      >
        <el-option label="正常" value="NORMAL" />
        <el-option label="已封禁" value="DISABLED" />
      </el-select>
      <el-input
        v-model="filter.keyword"
        placeholder="搜索账号 / 昵称 / 手机号"
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
      title="暂无用户"
      description="当前筛选条件下没有用户"
    />

    <template v-else>
      <el-table :data="list">
        <el-table-column prop="username" label="账号" width="120" />
        <el-table-column prop="nickname" label="昵称" width="120" />
        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.roleLabel || row.role }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="140" />
        <el-table-column prop="realName" label="真实姓名" width="120" />
        <el-table-column label="注册时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <NlStatusChip :status="row.status" scope="user" :text="row.statusLabel" />
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="240" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="resetPwd(row)">重置密码</el-button>
            <el-button
              v-if="row.status === 'NORMAL'"
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

      <el-pagination
        v-if="total > size"
        class="user-pager"
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="onPageChange"
      />
    </template>
  </NlCard>
</template>

<style scoped lang="scss">
.filters {
  display: flex;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);
  flex-wrap: wrap;
}

.user-pager {
  margin-top: var(--nl-space-4);
  justify-content: flex-end;
}
</style>
