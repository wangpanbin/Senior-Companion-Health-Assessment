<script setup>
/**
 * 我的（M-22 · docs/design/design.md §1.2 P1 · 4 角色共用）
 *
 * - 顶部：头像 / 昵称 / 角色徽标（真实，来自 getProfile）
 * - 老人模式开关（本地偏好，非接口）
 * - 角色相关快捷入口（按当前角色渲染）
 * - 修改密码（真实接口 changePassword；ELDER 经 @AllowElderWrite 允许本人修改）
 * - 退出登录（useUserStore().logout()，内部已 clearAuth）
 *
 * ⚠️ 后端 NON_NULL：空字段会消失，模板已用可选链兜底。
 * ⚠️ 隐私最小化：昵称 / 头像均为后端脱敏值；前端不写完整手机号 / 身份证 / 密码到 localStorage。
 */
import { ref, computed, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  NlPhoneShell,
  NlAppTabBar,
  NlMobileOnlyPage,
  NlCard,
  NlAvatar,
  NlListRow
} from '@/components'
import { useResponsive } from '@/composables/useResponsive'
import { useAppStore } from '@/store/modules/app'
import { useUserStore } from '@/store/modules/user'
import { getProfile, deactivateAccount, exportMyData } from '@/api/user'
import { changePassword } from '@/api/auth'
import { confirmThen } from '@/utils/confirm'
import { PASSWORD_RE, PASSWORD_MSG } from '@/constants/validation'

const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

/** 真实资料（getProfile 返回 UserInfoVO：昵称 / 角色 / 脱敏手机 / 头像） */
const me = ref(userStore.userInfo || {})
const avatarFallback = computed(() => (me.value?.nickname || '我').slice(0, 1))

async function loadProfile() {
  try {
    const data = await getProfile()
    if (data) me.value = data
  } catch {
    // 拦截器已弹错；userStore.userInfo 兜底仍可用
  }
}

onMounted(loadProfile)

function toggleElderly() {
  appStore.toggleElderlyMode()
  ElMessage.success(appStore.elderlyMode ? '已开启老人模式' : '已退出老人模式')
}

/* ===== 退出登录 ===== */
async function logout() {
  // confirmThen 把「用户是否点了确认」变成布尔：取消时静默返回 false，不执行登出
  const ok = await confirmThen(() =>
    ElMessageBox.confirm('退出后将无法接收订单通知，是否继续？', '退出登录', {
      confirmButtonText: '退出',
      type: 'warning'
    })
  )
  if (!ok) return
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

/* ===== 修改密码 ===== */
const pwdVisible = ref(false)
const pwdLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdFormRef = ref()

/**
 * 后端「指明字段」的错误码 → 表单字段（P2-2）。
 *
 * request.js 对业务错误统一弹一个**顶部** toast，而改密弹窗在屏幕下方，
 * 用户视线在输入框上 —— 错误弹在顶上基本看不到。这里把能定位到字段的码
 * 落到对应输入框下方（setFieldError），定位不到字段的仍走全局兜底 toast。
 *
 * ⚠️ 码值与后端 ResultCode 一致（backend/.../common/ResultCode.java）：
 *   1006 OLD_PASSWORD_ERROR 原密码不正确
 *   1007 PHONE_ALREADY_EXISTS 该手机号已被注册 —— **本页没有手机号字段**，
 *        不会命中，因此**不在**下面这张映射表里（不是漏写）。
 */
const PWD_ERROR_FIELD = {
  1006: 'oldPassword'
}

/** 两次新密码是否一致（确认密码是纯前端字段，后端没有它） */
function validateConfirmPassword(rule, value, callback) {
  if (value !== pwdForm.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
    return
  }
  callback()
}

/**
 * 表单规则 —— 错误直接落在对应输入框下方。
 *
 * ⚠️ 此前这些校验是「一次性 toast」：填完一屏弹一句、不知道该改哪个字段，
 * toast 3 秒后消失。现在全部下沉到字段上。
 */
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: ['blur', 'change'] }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: ['blur', 'change'] },
    { pattern: PASSWORD_RE, message: PASSWORD_MSG, trigger: ['blur', 'change'] }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: ['blur', 'change'] },
    { validator: validateConfirmPassword, trigger: ['blur', 'change'] }
  ]
}

/** < 768 让修改密码弹窗 fullscreen，避免小屏字段被压扁（T-08；用 isXs(≤767)替代 !isMd 修复大屏 PC bug） */
const { isXs } = useResponsive()

function openChangePwd() {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdVisible.value = true
  // 上一次残留的字段级错误不要带进新一轮
  pwdFormRef.value?.clearValidate()
}

async function submitChangePwd() {
  // ⚠️ in-flight 锁必须在**任何 await 之前**同步置位（含下面那次表单校验）：
  //    否则同 tick 连点会发出两次 PUT /api/auth/password，第二次必然报
  //    1006「原密码不正确」—— 用户看到的是「成功 + 报错」双提示。
  if (pwdLoading.value) return
  pwdLoading.value = true
  try {
    // 重新提交前清掉上一次的字段级错误
    pwdFormRef.value?.clearValidate()
    // ⚠️ 一次性校验全部字段：错误落在对应输入框下方，不用一轮轮试
    const valid = await pwdFormRef.value?.validate().catch(() => false)
    if (!valid) return
    await changePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
      confirmPassword: pwdForm.confirmPassword
    })
    ElMessage.success('密码修改成功，请重新登录')
    pwdVisible.value = false
    // 改密后所有已签发令牌失效，主动登出并跳登录页
    await userStore.logout()
    router.push('/login')
  } catch (err) {
    const field = PWD_ERROR_FIELD[err?.code]
    if (field) {
      // 字段级错误已经承接了这条信息：撤掉 request.js 的全局兜底 toast，
      // 否则同一条错误会在屏幕顶部和输入框下方各弹一次。
      ElMessage.closeAll()
      pwdFormRef.value?.setFieldError(field, err.message || '操作失败')
      return
    }
    // 定位不到字段的错误：保留全局兜底 toast（拦截器已弹，这里不重复）
  } finally {
    pwdLoading.value = false
  }
}

/* ===== 数据导出与账号注销（V4 · P0-1；仅家属 / 陪诊员可见） ===== */

/** 注销确认固定串：与后端 DeactivateAccountService 的校验串一致，错一个字都 400 */
const DEACTIVATE_CONFIRM_TEXT = '我已知晓注销后果'

const canManageAccount = computed(() => userStore.isFamily || userStore.isCompanion)

/** 用 Blob 触发浏览器下载（与 admin/export.vue 同一手法） */
function triggerDownload(blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

const exportLoading = ref(false)

async function exportData() {
  if (exportLoading.value) return
  exportLoading.value = true
  try {
    const blob = await exportMyData()
    // 甄别「业务错误」：后端未提交响应时按 GlobalExceptionHandler 返回 json，
    // 直接下载会让用户拿到一个内容是报错信息的 .xlsx（同 admin/export.vue 的坑）
    if (blob && blob.type && blob.type.includes('json')) {
      const text = await blob.text()
      let msg = '导出失败，请稍后重试'
      try {
        const err = JSON.parse(text)
        if (err && err.message) msg = err.message
      } catch {
        // 保留默认提示
      }
      ElMessage.closeAll()
      ElMessage.error(msg)
      return
    }
    triggerDownload(blob, `我的数据_${new Date().toISOString().slice(0, 10)}.xlsx`)
    ElMessage.success('已开始下载')
  } catch {
    // 网络 / 权限错误由拦截器统一弹错
  } finally {
    exportLoading.value = false
  }
}

/* ===== 注销账号弹窗 ===== */
const deactVisible = ref(false)
const deactLoading = ref(false)
const deactForm = reactive({ password: '', confirmText: '' })
const deactFormRef = ref()

const deactRules = {
  password: [{ required: true, message: '请输入当前密码', trigger: ['blur', 'change'] }],
  confirmText: [
    { required: true, message: `请输入「${DEACTIVATE_CONFIRM_TEXT}」`, trigger: ['blur', 'change'] }
  ]
}

/** 后端「指明字段」的错误码 → 表单字段（与修改密码同一取舍：能定位的落字段，否则走全局 toast） */
const DEACT_ERROR_FIELD = {
  1006: 'password' // OLD_PASSWORD_ERROR 原密码不正确
}

function openDeactivate() {
  deactForm.password = ''
  deactForm.confirmText = ''
  deactVisible.value = true
  deactFormRef.value?.clearValidate()
}

async function submitDeactivate() {
  // in-flight 锁先于一切 await（同 submitChangePwd 的坑：连点会发两次请求）
  if (deactLoading.value) return
  deactLoading.value = true
  try {
    deactFormRef.value?.clearValidate()
    const valid = await deactFormRef.value?.validate().catch(() => false)
    if (!valid) return
    await deactivateAccount({
      password: deactForm.password,
      confirmText: deactForm.confirmText
    })
    deactVisible.value = false
    ElMessage.success('账号已注销')
    // 注销后所有已签发令牌失效（密码版本 +1），主动登出并跳登录页
    await userStore.logout()
    router.push('/login')
  } catch (err) {
    if (err?.code === 11001) {
      // 存在进行中订单：如实告诉用户还差几笔，关掉弹窗去处理订单
      const count = err?.data?.pendingOrderCount
      deactVisible.value = false
      ElMessage.error(
        `您还有 ${count ?? ''} 笔进行中的订单，请先完成或取消后再注销`.replace('  笔', ' 笔')
      )
      return
    }
    const field = DEACT_ERROR_FIELD[err?.code]
    if (field) {
      // 字段级错误已承接：撤掉全局兜底 toast，避免同一错误弹两次
      ElMessage.closeAll()
      deactFormRef.value?.setFieldError(field, err.message || '操作失败')
      return
    }
    // 其余错误（如 400 确认文本不符）保留拦截器的全局 toast
  } finally {
    deactLoading.value = false
  }
}
</script>

<template>
  <!-- mobile-only 路由（ADR-0008）：宽屏下由 NlMobileOnlyPage 换成 NlMobileOnlyNotice。
       刻意不重排内部缩进，保持与原文件的 diff 最小。 -->
  <NlMobileOnlyPage>
    <NlPhoneShell class="profile-page" :nav="{ title: '我的', back: false }" :has-tabs="true">
      <!-- 用户卡片 -->
      <NlCard class="profile-hero" :padding="20">
        <div class="me">
          <NlAvatar :src="me?.avatar" :fallback="avatarFallback" :size="72" tone="primary" />
          <div class="me__body">
            <div class="me__name">{{ me?.nickname || '未登录' }}</div>
            <div class="me__role">{{ me?.roleLabel || userStore.roleLabel }}</div>
          </div>
        </div>
      </NlCard>

      <!-- 老人模式开关 -->
      <section class="profile-section">
        <p class="profile-section__label">显示与使用</p>
        <NlCard class="profile-group" :padding="0">
          <NlListRow
            :title="appStore.elderlyMode ? '已开启老人模式' : '老人模式'"
            subtitle="切换字号 / 简化菜单 / 提高对比度"
            :chevron="false"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <text
                  x="12"
                  y="16"
                  text-anchor="middle"
                  font-size="14"
                  fill="currentColor"
                  stroke="none"
                  font-family="serif"
                >
                  大
                </text>
                <circle cx="12" cy="12" r="11" />
              </svg>
            </template>
            <template #extra>
              <el-switch :model-value="appStore.elderlyMode" @change="toggleElderly" />
            </template>
          </NlListRow>
        </NlCard>
      </section>

      <!-- 家属角色入口 -->
      <section v-if="userStore.isFamily" class="profile-section">
        <p class="profile-section__label">服务管理</p>
        <NlCard class="profile-group" :padding="0">
          <NlListRow
            title="我的老人"
            subtitle="查看已绑定的老人，可继续新增"
            chevron
            @click="router.push('/family/elder')"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <circle cx="9" cy="8" r="3.5" />
                <path d="M3 20c0-3 3-5 6-5s6 2 6 5" />
                <circle cx="17" cy="6" r="2.5" />
                <path d="M15 14c2 0 6 1 6 4" />
              </svg>
            </template>
          </NlListRow>
          <NlListRow
            title="我的订单"
            subtitle="历史订单与进行中订单"
            chevron
            divider
            @click="router.push('/family/order')"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <rect x="4" y="4" width="16" height="16" rx="2" />
                <path d="M8 9h8M8 13h8M8 17h5" />
              </svg>
            </template>
          </NlListRow>
        </NlCard>
      </section>

      <!-- 陪诊员角色入口 -->
      <section v-if="userStore.isCompanion" class="profile-section">
        <p class="profile-section__label">陪诊服务</p>
        <NlCard class="profile-group" :padding="0">
          <NlListRow
            title="资质入驻"
            subtitle="提交资质申请 / 查看审核状态"
            chevron
            @click="router.push('/companion/entry')"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <circle cx="12" cy="12" r="9" />
                <path d="M9 12l2 2 4-4" />
              </svg>
            </template>
          </NlListRow>
          <NlListRow
            title="我的收入"
            subtitle="查看陪诊服务收入明细"
            chevron
            divider
            @click="router.push('/companion/income')"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M12 3v18M7 8h7a3 3 0 0 1 0 6H7m0 0h8" />
              </svg>
            </template>
          </NlListRow>
          <NlListRow
            title="工作台"
            subtitle="本月完成单数 / 收入 / 趋势 / 服务画像"
            chevron
            divider
            @click="router.push('/companion/workbench')"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M4 19V5M4 19h16" />
                <path d="M8 15l3-4 3 2 4-6" />
              </svg>
            </template>
          </NlListRow>
        </NlCard>
      </section>

      <!-- 通用设置 -->
      <section class="profile-section">
        <p class="profile-section__label">账户与安全</p>
        <NlCard class="profile-group" :padding="0">
          <NlListRow title="修改密码" subtitle="定期更换密码更安全" chevron @click="openChangePwd">
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <rect x="5" y="11" width="14" height="10" rx="2" />
                <path d="M8 11V7a4 4 0 1 1 8 0v4" />
              </svg>
            </template>
          </NlListRow>
          <NlListRow
            v-if="!userStore.isElder"
            title="服务协议"
            subtitle="v2.0 已生效"
            chevron
            divider
            @click="router.push('/legal/service')"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M6 3h9l4 4v14H6z" />
                <path d="M14 3v5h5" />
              </svg>
            </template>
          </NlListRow>
          <NlListRow
            v-if="canManageAccount"
            title="导出我的数据"
            subtitle="个人资料 / 订单 / 评价 / 消息（已脱敏）"
            chevron
            divider
            @click="exportData"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M12 3v12m0 0l-4-4m4 4l4-4" />
                <path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
              </svg>
            </template>
          </NlListRow>
          <NlListRow
            v-if="canManageAccount"
            title="注销账号"
            subtitle="注销后无法恢复，订单与日志保留但去标识化"
            chevron
            divider
            @click="openDeactivate"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <circle cx="12" cy="12" r="9" />
                <path d="M9 9l6 6m0-6l-6 6" />
              </svg>
            </template>
          </NlListRow>
          <NlListRow
            v-if="!userStore.isElder"
            title="隐私政策"
            chevron
            divider
            @click="router.push('/legal/privacy')"
          >
            <template #icon>
              <svg
                viewBox="0 0 24 24"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M12 3l8 4v5c0 5-3 9-8 10-5-1-8-5-8-10V7l8-4z" />
              </svg>
            </template>
          </NlListRow>
        </NlCard>
      </section>

      <div class="logout-bar">
        <el-button type="danger" plain round size="large" class="logout-bar__btn" @click="logout">
          退出登录
        </el-button>
      </div>

      <!-- 修改密码弹窗 -->
      <el-dialog v-model="pwdVisible" title="修改密码" width="90%" :fullscreen="isXs" align-center>
        <el-form
          ref="pwdFormRef"
          :model="pwdForm"
          :rules="pwdRules"
          label-position="top"
          @submit.prevent
        >
          <el-form-item label="原密码" prop="oldPassword">
            <el-input
              v-model="pwdForm.oldPassword"
              type="password"
              show-password
              placeholder="请输入当前密码"
              maxlength="32"
            />
          </el-form-item>
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="pwdForm.newPassword"
              type="password"
              show-password
              placeholder="6-32 位，需同时包含字母与数字"
              maxlength="32"
            />
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="pwdForm.confirmPassword"
              type="password"
              show-password
              placeholder="请再次输入新密码"
              maxlength="32"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="pwdVisible = false">取消</el-button>
          <el-button type="primary" :loading="pwdLoading" @click="submitChangePwd">确定</el-button>
        </template>
      </el-dialog>

      <!-- 注销账号弹窗（V4 · P0-1） -->
      <el-dialog
        v-model="deactVisible"
        title="注销账号"
        width="90%"
        :fullscreen="isXs"
        align-center
      >
        <el-alert type="warning" :closable="false" show-icon class="deact-warning">
          <p>注销后账号立即失效且无法恢复：</p>
          <p>· 您的姓名与手机号将被去标识化处理；</p>
          <p>· 历史订单、评价与平台日志将依法保留，但不再关联您的身份；</p>
          <p>· 存在进行中的订单时无法注销，请先完成或取消。</p>
        </el-alert>
        <el-form
          ref="deactFormRef"
          :model="deactForm"
          :rules="deactRules"
          label-position="top"
          @submit.prevent
        >
          <el-form-item label="当前密码" prop="password">
            <el-input
              v-model="deactForm.password"
              type="password"
              show-password
              placeholder="请输入当前密码"
              maxlength="32"
            />
          </el-form-item>
          <el-form-item :label="`请输入「${DEACTIVATE_CONFIRM_TEXT}」以确认`" prop="confirmText">
            <el-input
              v-model="deactForm.confirmText"
              :placeholder="DEACTIVATE_CONFIRM_TEXT"
              maxlength="20"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="deactVisible = false">取消</el-button>
          <el-button type="danger" :loading="deactLoading" @click="submitDeactivate">
            确认注销
          </el-button>
        </template>
      </el-dialog>

      <template #tabbar>
        <NlAppTabBar />
      </template>
    </NlPhoneShell>
  </NlMobileOnlyPage>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.me {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;

  &__body {
    flex: 1;
  }

  &__name {
    font-size: var(--nl-font-h1);
    font-weight: 700;
    color: var(--nl-text-inverse);
  }

  &__role {
    margin-top: 4px;
    font-size: var(--nl-font-caption);
    color: rgba(255, 255, 255, 0.82);
  }
}

.profile-hero {
  margin: 0 var(--nl-gutter);
  overflow: hidden;
  background: var(--nl-primary-gradient);
  border: none;
  box-shadow: 0 8px 18px rgba(29, 111, 242, 0.2);

  :deep(.nl-avatar) {
    box-sizing: border-box;
    border: 3px solid rgba(255, 255, 255, 0.72);
  }
}

.profile-section {
  margin: 0 var(--nl-gutter);

  &__label {
    margin: 0 4px 8px;
    font-size: var(--nl-font-caption);
    font-weight: 600;
    color: var(--nl-text-2);
  }
}

.profile-group {
  overflow: hidden;

  :deep(.nl-listrow) {
    padding-right: var(--nl-space-4);
    padding-left: var(--nl-space-4);
  }
}

.logout-bar {
  margin: 0 var(--nl-gutter);
  padding: var(--nl-space-2) 0 var(--nl-space-4);

  &__btn {
    width: 100%;
  }
}

.deact-warning {
  margin-bottom: var(--nl-space-4);

  p {
    margin: 0;
    line-height: 1.7;
  }
}
</style>
