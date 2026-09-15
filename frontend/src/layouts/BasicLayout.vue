<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useAppStore } from '@/store/modules/app'
import { useUserStore } from '@/store/modules/user'
import { routes } from '@/router/routes'

/**
 * 主布局：侧边菜单 + 顶栏 + 内容区。
 *
 * 适老化要点（M11）：
 *   - 顶栏提供「老人模式」一键开关
 *   - 老人模式下自动精简菜单（隐藏 meta.elderlyHidden 的项）
 *   - 菜单项高度、字号在 elderly.scss 中整体放大
 */
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

/** 从路由表生成菜单（骨架阶段不带角色过滤，M2 接入后按 userStore.role 过滤） */
const menus = computed(() => {
  const layoutRoute = routes.find((r) => r.path === '/')
  const children = layoutRoute?.children || []
  return children
    .filter((item) => (appStore.elderlyMode ? !item.meta?.elderlyHidden : true))
    .filter((item) => {
      const allow = item.meta?.roles
      if (!allow || allow.length === 0) return true
      // 骨架阶段未登录时展示全部菜单，方便验证布局；M2 后由守卫与角色决定
      return !userStore.isLogin || allow.includes(userStore.role)
    })
    .map((item) => ({
      path: `/${item.path}`,
      title: item.meta?.title || item.name,
      icon: item.meta?.icon || 'Menu'
    }))
})

const activeMenu = computed(() => route.path)

/** 当前页面标题 */
const pageTitle = computed(() => route.meta?.title || '')

/** 当前页面归属模块（便于开发期自查） */
const pageModule = computed(() => route.meta?.module || '')

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

function handleCommand(cmd) {
  if (cmd === 'logout') handleLogout()
  if (cmd === 'profile') router.push('/profile')
}
</script>

<template>
  <el-container class="layout">
    <!-- ==================== 侧边栏 ==================== -->
    <el-aside class="layout__aside" :width="appStore.sidebarCollapsed ? '64px' : '220px'">
      <div class="layout__logo">
        <span class="layout__logo-mark">银</span>
        <span v-show="!appStore.sidebarCollapsed" class="layout__logo-text">银龄伴诊</span>
      </div>

      <el-menu
        class="layout__menu"
        :default-active="activeMenu"
        :collapse="appStore.sidebarCollapsed"
        :collapse-transition="false"
        router
      >
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <template #title>{{ m.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- ==================== 顶栏 ==================== -->
      <el-header class="layout__header">
        <div class="layout__header-left">
          <el-button text @click="appStore.toggleSidebar()">
            <el-icon size="20"><Fold /></el-icon>
          </el-button>
          <span class="layout__page-title">{{ pageTitle }}</span>
          <el-tag v-if="pageModule" size="small" type="info" effect="plain">{{ pageModule }}</el-tag>
        </div>

        <div class="layout__header-right">
          <!-- 适老化总开关：答辩现场演示用 -->
          <div class="layout__elder-switch">
            <span class="layout__elder-label">老人模式</span>
            <el-switch
              :model-value="appStore.elderlyMode"
              size="large"
              inline-prompt
              active-text="大字"
              inactive-text="常规"
              @change="appStore.setElderlyMode($event)"
            />
          </div>

          <!-- 站内信（M8 实现，当前为占位入口） -->
          <el-tooltip content="站内信（M8 待实现）" placement="bottom">
            <el-badge :value="0" :hidden="true">
              <el-button text>
                <el-icon size="20"><Bell /></el-icon>
              </el-button>
            </el-badge>
          </el-tooltip>

          <el-dropdown @command="handleCommand">
            <span class="layout__user">
              <el-icon><UserFilled /></el-icon>
              <span>{{ userStore.nickname }}</span>
              <el-tag size="small" effect="plain">{{ userStore.roleLabel }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- ==================== 内容区 ==================== -->
      <el-main class="layout__main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.layout {
  height: 100vh;

  &__aside {
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: #ffffff;
    border-right: 1px solid var(--nl-border-color);
    transition: width 0.2s;
  }

  &__logo {
    display: flex;
    gap: 10px;
    align-items: center;
    height: $header-height;
    padding: 0 $space-base;
    border-bottom: 1px solid var(--nl-border-color);
  }

  &__logo-mark {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    font-size: 18px;
    font-weight: 700;
    color: #ffffff;
    background: var(--nl-color-primary);
    border-radius: 50%;
  }

  &__logo-text {
    font-size: 17px;
    font-weight: 600;
    white-space: nowrap;
  }

  &__menu {
    flex: 1;
    border-right: none;
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: $header-height;
    padding: 0 $space-base;
    background: #ffffff;
    border-bottom: 1px solid var(--nl-border-color);
  }

  &__header-left {
    display: flex;
    gap: 10px;
    align-items: center;
  }

  &__page-title {
    font-size: 17px;
    font-weight: 600;
  }

  &__header-right {
    display: flex;
    gap: 16px;
    align-items: center;
  }

  &__elder-switch {
    display: flex;
    gap: 8px;
    align-items: center;
  }

  &__elder-label {
    font-size: 14px;
    color: var(--nl-text-secondary);
  }

  &__user {
    display: flex;
    gap: 8px;
    align-items: center;
    color: var(--nl-text-primary);
    cursor: pointer;
    outline: none;
  }

  &__main {
    padding: 0;
    overflow-y: auto;
    background: var(--nl-bg-page);
  }
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
