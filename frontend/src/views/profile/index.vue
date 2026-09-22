<script setup>
/**
 * 我的（M-22 · design.md §1.2 P1 · 4 角色共用）
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
import { getProfile } from '@/api/user'
import { changePassword } from '@/api/auth'

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
  try {
    await ElMessageBox.confirm('退出后将无法接收订单通知，是否继续？', '退出登录', {
      confirmButtonText: '退出',
      type: 'warning'
    })
  } catch {
    return
  }
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

/* ===== 修改密码 ===== */
const pwdVisible = ref(false)
const pwdLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

/** < 768 让修改密码弹窗 fullscreen，避免小屏字段被压扁（T-08） */
const { isMd } = useResponsive()

const pwdValid = computed(() => /^(?=.*[A-Za-z])(?=.*\d)\S{6,32}$/.test(pwdForm.newPassword))

function openChangePwd() {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdVisible.value = true
}

async function submitChangePwd() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword || !pwdForm.confirmPassword) {
    ElMessage.warning('请填写完整的密码信息')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  if (!pwdValid.value) {
    ElMessage.warning('新密码为 6-32 位，且需同时包含字母与数字')
    return
  }
  pwdLoading.value = true
  try {
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
  } catch {
    // 拦截器已弹错
  } finally {
    pwdLoading.value = false
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
      <el-dialog v-model="pwdVisible" title="修改密码" width="90%" :fullscreen="!isMd" align-center>
        <el-form label-position="top">
          <el-form-item label="原密码">
            <el-input
              v-model="pwdForm.oldPassword"
              type="password"
              show-password
              placeholder="请输入当前密码"
              maxlength="32"
            />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input
              v-model="pwdForm.newPassword"
              type="password"
              show-password
              placeholder="6-32 位，需同时包含字母与数字"
              maxlength="32"
            />
          </el-form-item>
          <el-form-item label="确认新密码">
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
    font-size: 22px;
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
</style>
