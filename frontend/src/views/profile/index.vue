<script setup>
/**
 * 我的（M-22 · design.md §1.2 P1 · 4 角色共用）
 *
 * - 顶部：头像 / 昵称 / 角色徽标
 * - 老人模式开关（核心 M11）
 * - 角色相关快捷入口
 * - 设置（修改密码 / 退出登录）
 */
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  NlPhoneShell, NlCard, NlAvatar, NlListRow
} from '@/components'
import { useAppStore } from '@/store/modules/app'
import { useUserStore, ROLE_LABELS, ROLES } from '@/store/modules/user'

const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

function toggleElderly() {
  appStore.toggleElderlyMode()
  ElMessage.success(appStore.elderlyMode ? '已开启老人模式' : '已退出老人模式')
}

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

function changePassword() {
  ElMessage.info('M2 修改密码入口保留（M-22 P1 后续迭代）')
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '我的', back: false }">
    <!-- 用户卡片 -->
    <NlCard>
      <div class="me">
        <NlAvatar :fallback="userStore.nickname?.slice(0, 1) || '我'" :size="64" tone="primary" />
        <div class="me__body">
          <div class="me__name">{{ userStore.nickname || '未登录' }}</div>
          <div class="me__role">{{ userStore.roleLabel }}</div>
        </div>
      </div>
    </NlCard>

    <!-- 老人模式开关 -->
    <NlCard plain>
      <NlListRow :title="appStore.elderlyMode ? '已开启老人模式' : '老人模式'" subtitle="切换字号 / 简化菜单 / 提高对比度" :chevron="false">
        <template #icon>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <text x="12" y="16" text-anchor="middle" font-size="14" fill="currentColor" stroke="none" font-family="serif">大</text>
            <circle cx="12" cy="12" r="11" />
          </svg>
        </template>
        <template #extra>
          <el-switch :model-value="appStore.elderlyMode" @change="toggleElderly" />
        </template>
      </NlListRow>
    </NlCard>

    <!-- 家属角色入口 -->
    <NlCard v-if="userStore.isFamily" plain>
      <NlListRow title="我的老人" subtitle="查看已绑定的老人，可继续新增" chevron @click="router.push('/family/elder')">
        <template #icon>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="9" cy="8" r="3.5" />
            <path d="M3 20c0-3 3-5 6-5s6 2 6 5" />
            <circle cx="17" cy="6" r="2.5" />
            <path d="M15 14c2 0 6 1 6 4" />
          </svg>
        </template>
      </NlListRow>
      <NlListRow title="我的订单" subtitle="历史订单与进行中订单" chevron @click="router.push('/family/order')">
        <template #icon>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="4" y="4" width="16" height="16" rx="2" />
            <path d="M8 9h8M8 13h8M8 17h5" />
          </svg>
        </template>
      </NlListRow>
    </NlCard>

    <!-- 陪诊员角色入口 -->
    <NlCard v-if="userStore.isCompanion" plain>
      <NlListRow v-if="userStore.role !== 'COMPANION' || true" title="我的接单" subtitle="查看已接订单 / 完成情况" chevron @click="router.push('/companion/order')">
        <template #icon>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="4" y="4" width="16" height="16" rx="2" />
            <path d="M8 9h8M8 13h8M8 17h5" />
          </svg>
        </template>
      </NlListRow>
      <NlListRow title="资质审核状态" subtitle="已通过 / 审核中 / 已驳回" chevron @click="router.push('/companion/entry')">
        <template #icon>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9" />
            <path d="M9 12l2 2 4-4" />
          </svg>
        </template>
      </NlListRow>
    </NlCard>

    <!-- 通用设置 -->
    <NlCard plain>
      <NlListRow title="修改密码" chevron @click="changePassword">
        <template #icon>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect x="5" y="11" width="14" height="10" rx="2" />
            <path d="M8 11V7a4 4 0 1 1 8 0v4" />
          </svg>
        </template>
      </NlListRow>
      <NlListRow title="服务协议" subtitle="v2.0 已生效" chevron>
        <template #icon>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M6 3h9l4 4v14H6z" />
            <path d="M14 3v5h5" />
          </svg>
        </template>
      </NlListRow>
      <NlListRow title="隐私政策" chevron>
        <template #icon>
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 3l8 4v5c0 5-3 9-8 10-5-1-8-5-8-10V7l8-4z" />
          </svg>
        </template>
      </NlListRow>
    </NlCard>

    <div class="logout-bar">
      <el-button type="danger" plain round size="large" class="logout-bar__btn" @click="logout">
        退出登录
      </el-button>
    </div>
  </NlPhoneShell>
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
    font-size: 18px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__role {
    margin-top: 4px;
    font-size: var(--nl-font-caption);
    color: var(--nl-primary);
  }
}

.logout-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}
</style>
