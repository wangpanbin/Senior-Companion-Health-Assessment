<script setup>
import { useAppStore } from '@/store/modules/app'
import { useUserStore } from '@/store/modules/user'

/**
 * 个人中心（骨架版）。
 *
 * 这里顺带把「适老化偏好」做成可视化开关，方便 M11 走查与答辩演示。
 */
const appStore = useAppStore()
const userStore = useUserStore()
</script>

<template>
  <div class="nl-page">
    <h2 class="nl-page__title">个人中心</h2>

    <el-card shadow="never" class="mb-16">
      <template #header>账号信息</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="登录状态">
          <el-tag :type="userStore.isLogin ? 'success' : 'info'">
            {{ userStore.isLogin ? '已登录' : '未登录（M2 未交付）' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="角色">{{ userStore.roleLabel }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ userStore.nickname }}</el-descriptions-item>
        <el-descriptions-item label="用户 ID">{{ userStore.userInfo?.id || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-alert
        class="mt-16"
        type="info"
        :closable="false"
        show-icon
        title="账号资料编辑属于 M3（用户与档案管理），待该模块交付后接入。"
      />
    </el-card>

    <el-card shadow="never">
      <template #header>无障碍与适老化偏好（M11）</template>
      <el-form label-width="140px">
        <el-form-item label="老人模式">
          <el-switch
            :model-value="appStore.elderlyMode"
            size="large"
            active-text="大字模式"
            inactive-text="常规模式"
            @change="appStore.setElderlyMode($event)"
          />
          <div class="nl-muted mt-8">
            开启后：全站字号放大到 18px 起、对比度增强、按钮最小高度 48px、菜单自动精简。
            偏好会保存到本地，刷新页面依然生效。
          </div>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.mb-16 {
  margin-bottom: $space-base;
}

.mt-16 {
  margin-top: $space-base;
}

.mt-8 {
  margin-top: 8px;
  font-size: 13px;
  line-height: 1.7;
}
</style>
