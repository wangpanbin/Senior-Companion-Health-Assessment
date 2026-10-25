<script setup>
/**
 * W-04 用户管理
 *
 * 数据来自 listUsers（分页 page/size，响应 { total, page, size, pages, records }）。
 * 注意用户状态是 NORMAL / DISABLED（不是 ACTIVE/BANNED），NlStatusChip 用 scope="user"。
 * 封禁 / 解封都不能作用在 ADMIN 账号上（后端返回 8002），前端先拦一道给出更明确的提示。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlCard, NlStatusChip, NlSkeleton, NlEmpty, NlAdminTable } from '@/components'
import { listUsers, disableUser, enableUser, resetUserPassword } from '@/api/admin'
import { formatDateTime } from '@/utils/format'
import { confirmThen, promptThen } from '@/utils/confirm'
import { PASSWORD_RE, PASSWORD_MSG } from '@/constants/validation'
import { usePagedList } from '@/composables/usePagedList'

const filter = ref({ role: '', status: '', keyword: '' })

const list = ref([])
const loading = ref(false)
const total = ref(0)
/** R4：列表加载失败（与「当前筛选下没有数据」区分） */
const loadError = ref(false)
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

/** 封禁 / 解封 / 重置密码都禁止作用在 ADMIN 上，提前拦掉而不是等后端 8002 */
function assertNotAdmin(u) {
  if (u.role === 'ADMIN') {
    ElMessage.warning('管理员账号不可执行该操作')
    return false
  }
  return true
}

/**
 * 正在执行写操作的行 ID（封禁 / 解封共用）。
 *
 * <p>作用是给行内按钮绑 {@code :loading}，避免连点提交两次
 * （P1-5：写操作按钮必须绑定 in-flight 锁）。</p>
 *
 * <p>⚠️ 置位必须发生在 <b>await 之前</b>：若等请求返回后再置位，
 * 两次调用会一起穿过 await，锁就形同虚设。</p>
 */
const busyUserId = ref(null)

async function ban(u) {
  if (!assertNotAdmin(u)) return
  // ⚠️ in-flight 锁必须在**任何 await 之前**同步置位（含下面那个 prompt）：
  //    若等 prompt 回来才置位，两次点击会一起穿过 await，锁形同虚设。
  if (busyUserId.value !== null) return
  busyUserId.value = u.id
  try {
    // disableUser 需要 reason，用 prompt 收集并写入操作日志
    // promptThen 返回用户输入的文本；用户点取消时返回 null（不发请求）
    const reason = await promptThen(() =>
      ElMessageBox.prompt(
        `封禁「${u.nickname}」(${u.username})？封禁后该用户将无法登录，请填写原因。`,
        '确认封禁',
        {
          confirmButtonText: '确认封禁',
          type: 'warning',
          inputPattern: /\S+/,
          inputErrorMessage: '请填写封禁原因'
        }
      )
    )
    if (reason == null) return
    await disableUser(u.id, { reason })
    ElMessage.success('已封禁')
    await reloadAfterMutate()
  } catch {
    // 后端报错（拦截器已提示），这里不重复弹
  } finally {
    busyUserId.value = null
  }
}

async function unban(u) {
  if (!assertNotAdmin(u)) return
  // ⚠️ 同 ban：锁必须在任何 await 之前同步置位
  if (busyUserId.value !== null) return
  busyUserId.value = u.id
  try {
    // ⚠️ 必须走 confirmThen：点「取消」时 promise 会 reject，
    // 直接 await 会产生未捕获的 Promise 拒绝（P1-12）
    const ok = await confirmThen(() =>
      ElMessageBox.confirm(`解封「${u.nickname}」？用户可以重新登录。`, '确认解封', {
        confirmButtonText: '确认解封'
      })
    )
    if (!ok) return
    await enableUser(u.id)
    ElMessage.success('已解封')
    await reloadAfterMutate()
  } catch {
    // 后端报错（拦截器已提示），这里不重复弹
  } finally {
    busyUserId.value = null
  }
}

/* ---------------- 重置密码对话框 ---------------- */

/**
 * 重置密码走独立对话框，而不是 ElMessageBox.prompt。
 *
 * <p>两个理由：</p>
 * <ol>
 *   <li>一次 prompt 只能收一个输入框，而这里需要「新口令 + 重置原因」两项；</li>
 *   <li><b>新口令必须由管理员亲手输入并转交用户</b>：平台没有短信通道，
 *       站内信正文也不含口令（见后端 {@code AdminServiceImpl#resetPassword}）。
 *       若仍走「重置为部署默认口令 + 不回显」，用户会收到通知却永远不知道
 *       自己该用哪个密码登录 —— 账号就此永久锁死，管理员重置多少次都一样。</li>
 * </ol>
 */
const resetDialogVisible = ref(false)
const resetFormRef = ref(null)
const resetSubmitting = ref(false)
/** 结果态：true 时对话框展示「已重置 + 复制口令」 */
const resetDone = ref(false)
/** 重置成功后展示给管理员转交用户的新口令（一次性，不做任何持久化） */
const issuedPassword = ref('')
/** 重置目标用户 */
const resetTarget = ref(null)

const resetForm = reactive({ newPassword: '', remark: '' })

/** 复杂度口径与后端 RegisterDTO#password / ResetPasswordDTO#newPassword 一致 */
const resetRules = {
  newPassword: [
    { required: true, message: '请设置新密码', trigger: 'blur' },
    {
      pattern: PASSWORD_RE,
      message: PASSWORD_MSG,
      trigger: 'blur'
    }
  ],
  remark: [
    { required: true, message: '请填写重置原因', trigger: 'blur' },
    { max: 200, message: '重置原因不能超过 200 个字符', trigger: 'blur' }
  ]
}

function openResetDialog(u) {
  if (!assertNotAdmin(u)) return
  resetTarget.value = u
  resetForm.newPassword = ''
  resetForm.remark = ''
  issuedPassword.value = ''
  resetDone.value = false
  resetDialogVisible.value = true
}

async function submitReset() {
  // ⚠️ in-flight 锁必须在 await validate() 之前同步置位（P1-5）
  if (resetSubmitting.value) return
  resetSubmitting.value = true
  try {
    const valid = await resetFormRef.value?.validate().catch(() => false)
    if (!valid) return
    await resetUserPassword(resetTarget.value.id, {
      remark: resetForm.remark.trim(),
      newPassword: resetForm.newPassword
    })
    // ⚠️ 展示给管理员转交用户的口令，来自**他自己刚输入的值**（本地表单状态），
    //    不是后端回传 —— 后端响应体**不含任何口令字段**
    //    （安全验收判据：docs/security/安全评估报告-2026-10-18.md §8.2 P0-3）。
    //    这样既解开了「用户拿不到口令 → 永久锁死」，又没有把明文塞进接口响应。
    issuedPassword.value = resetForm.newPassword
    resetDone.value = true
    await reloadAfterMutate()
  } catch {
    // 拦截器已提示
  } finally {
    resetSubmitting.value = false
  }
}

/** 复制新口令（供管理员转交用户）。口令不写入任何持久化存储。 */
async function copyIssuedPassword() {
  const text = issuedPassword.value
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      // 降级路径：非安全上下文（http）下 clipboard API 不可用
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('口令已复制，请通过电话或线下告知用户')
  } catch {
    ElMessage.warning('复制失败，请手动选中口令转交用户')
  }
}

/** 列定义（desktop 8 列全展示；mobile 4 字段关键）
 *  固定 width 只给短内容列（账号 / 角色 / 手机号 / 状态）；
 *  昵称 / 姓名 / 注册时间用 minWidth，宽屏下按比例分摊剩余宽度，避免右侧留白。
 *  ⚠️ 「姓名」列是**脱敏**的（后端 MaskUtil.name，李四 → 李*），
 *     与手机号同口径（AGENTS.md §0.1：/api/admin/** 一律脱敏）。
 */
const columns = [
  { key: 'username',   label: '账号',     width: 130 },
  { key: 'nickname',   label: '昵称',     minWidth: 120 },
  { key: 'role',       label: '角色',     width: 110 },
  { key: 'phone',      label: '手机号',   width: 150 },
  { key: 'realName',   label: '姓名',     minWidth: 120 },
  { key: 'createTime', label: '注册时间', minWidth: 170, formatter: (v) => formatDateTime(v) },
  { key: 'status',     label: '状态',     width: 80 }
]
const mobileFields = ['username', 'role', 'status']

onMounted(loadList)
</script>

<template>
  <NlCard title="用户管理" plain class="user-card">
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
        class="filter-keyword"
        placeholder="搜索账号 / 昵称 / 手机号"
        clearable
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
      <el-button type="primary" plain @click="onFilterChange">查询</el-button>
    </section>

    <NlSkeleton v-if="loading" :count="5" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="用户列表加载失败"
      action-text="重试"
      @action="loadList"
    />

    <NlEmpty
      v-else-if="pageDrained"
      type="empty"
      title="本页已空，上一页还有"
      description="刚操作的用户已不在本页，可返回上一页继续处理"
      action-text="返回上一页"
      @action="onPrevPage"
    />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无用户"
      description="当前筛选条件下没有用户"
    />

    <NlAdminTable
      v-else
      fill
      action-width="180"
      :data="list"
      :columns="columns"
      :mobile-fields="mobileFields"
      empty-text="暂无用户"
    >
      <template #cell-role="{ row }">
        <el-tag size="small" type="info">{{ row.roleLabel || row.role }}</el-tag>
      </template>
      <template #cell-status="{ row }">
        <NlStatusChip :status="row.status" scope="user" :text="row.statusLabel" />
      </template>
      <template #actions="{ row }">
        <div class="user-actions">
          <el-button
            class="user-actions__btn"
            text
            type="primary"
            size="small"
            @click="openResetDialog(row)"
          >
            重置密码
          </el-button>
          <el-button
            class="user-actions__btn"
            text
            :type="row.status === 'NORMAL' ? 'danger' : 'primary'"
            size="small"
            :loading="busyUserId === row.id"
            @click="row.status === 'NORMAL' ? ban(row) : unban(row)"
          >
            {{ row.status === 'NORMAL' ? '封禁' : '解封' }}
          </el-button>
        </div>
      </template>
    </NlAdminTable>

    <el-pagination
      v-if="total > size"
      class="user-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPageChange"
    />
  </NlCard>

  <!--
    重置密码对话框（P0-2）
    ⚠️ 平台没有短信通道，站内信正文也不含口令 ——
       若沿用「重置为部署默认口令 + 不回显」，用户会收到通知却永远不知道自己该用
       哪个密码登录，账号就此永久锁死。因此新口令由管理员**亲手设置**。
    ⚠️ 回显给管理员看的那份口令来自**他自己刚输入的值**（本地表单状态），
       不是后端回传 —— 后端响应体不含任何口令字段
       （安全验收判据：docs/security/安全评估报告-2026-10-18.md §8.2 P0-3）。
  -->
  <el-dialog
    v-model="resetDialogVisible"
    title="重置用户密码"
    width="480px"
    :close-on-click-modal="false"
    :close-on-press-escape="!resetSubmitting"
  >
    <!-- 表单态 -->
    <el-form
      v-if="!resetDone"
      ref="resetFormRef"
      :model="resetForm"
      :rules="resetRules"
      label-position="top"
      @submit.prevent="submitReset"
    >
      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="resetForm.newPassword"
          type="password"
          show-password
          placeholder="6-32 位，需同时包含字母与数字"
          autocomplete="new-password"
        />
      </el-form-item>
      <el-form-item label="重置原因" prop="remark">
        <el-input
          v-model="resetForm.remark"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          placeholder="如：用户来电请求重置，已核验身份"
        />
      </el-form-item>
      <p class="reset-tip">
        系统没有短信通道，不会把口令自动发给用户。请在重置后把新口令通过电话或线下告知用户，用户登录后需立即修改。
      </p>
    </el-form>

    <!-- 结果态 -->
    <div v-else class="reset-result">
      <p class="reset-result__title">已重置，并已通过站内信通知用户。</p>
      <p class="reset-result__hint">
        请把下面的新口令通过电话或线下告知用户，用户登录后需立即修改。
      </p>
      <div class="reset-result__pwd">
        <code>{{ issuedPassword }}</code>
        <el-button size="small" @click="copyIssuedPassword">复制口令</el-button>
      </div>
    </div>

    <template #footer>
      <el-button @click="resetDialogVisible = false">
        {{ resetDone ? '完成' : '取消' }}
      </el-button>
      <el-button
        v-if="!resetDone"
        type="primary"
        :loading="resetSubmitting"
        @click="submitReset"
      >
        确认重置
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

/* 卡片占满视口剩余高度：100vh - 顶栏 64px - el-main 上下 padding 各 24px */
.user-card {
  height: calc(100vh - #{$header-height} - #{$nl-space-6} * 2);
}

.filters {
  display: flex;
  gap: var(--nl-space-3);
  margin-bottom: var(--nl-space-4);
  flex-wrap: wrap;
  flex-shrink: 0;
}

/* 搜索框吃掉筛选行剩余宽度，消除右上方留白 */
.filter-keyword {
  flex: 1;
  min-width: 200px;
}

/* 表格纵向撑满卡片剩余空间（el-table height="100%"，表头固定、表体内部滚动） */
.user-card :deep(.nl-admin-table) {
  flex: 1;
  min-height: 0;
}

.user-card :deep(.nl-admin-table__desktop) {
  height: 100%;
}

/* 操作按钮组：两个按钮等宽、水平平铺（desktop 表格单元格与 mobile 卡片底部共用） */
.user-actions {
  display: flex;
  gap: var(--nl-space-2);
  width: 100%;

  &__btn {
    flex: 1;
    min-width: 0;
    margin-left: 0;
  }
}

.user-pager {
  flex-shrink: 0;
  /* margin-top 不再叠加：卡片自身的 flex gap(12px) 已提供间距 */
  margin-top: 0;
  justify-content: flex-end;
}

/* ---------------- 重置密码对话框 ---------------- */

.reset-tip {
  margin: 0;
  padding: var(--nl-space-3);
  color: var(--nl-text-2);
  font-size: var(--nl-font-caption);
  line-height: 1.6;
  background: var(--nl-warning-bg);
  border-radius: var(--nl-radius-input);
}

.reset-result {
  &__title {
    margin: 0 0 var(--nl-space-2);
    color: var(--nl-text-1);
    font-size: var(--nl-font-h3);
  }

  &__hint {
    margin: 0 0 var(--nl-space-3);
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
    line-height: 1.6;
  }

  &__pwd {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
    padding: var(--nl-space-3);
    background: var(--nl-bg-sunken);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-input);

    code {
      flex: 1;
      overflow-wrap: anywhere;
      color: var(--nl-text-1);
      font-size: var(--nl-font-h3);
      font-weight: 600;
      letter-spacing: 1px;
      user-select: all;
    }
  }
}
</style>
