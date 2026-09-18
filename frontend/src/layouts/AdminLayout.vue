<script setup>
/**
 * AdminLayout · 管理后台桌面端布局（design.md §4 · 1440×900）
 *
 * - 左栏 220px：Logo + 侧边菜单（选中态 3px 主色条 + 浅蓝底）
 * - 内容区：顶部 64px（面包屑 + 管理员信息）→ 内容 padding 24
 * - 表格：表头 --nl-bg-sunken、行高 48、hover --nl-primary-ghost、斑马纹关闭、分页器右对齐
 * - 适老化：菜单项高度 56 / 字号 18，老人模式下边栏只隐藏 elderlyHidden 项
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useAppStore } from '@/store/modules/app'
import { useUserStore } from '@/store/modules/user'
import { routes } from '@/router/routes'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

/** 从路由表 admin 区段生成菜单 */
const menus = computed(() => {
  const adminRoot = routes.find((r) => r.path === '/admin')
  const children = adminRoot?.children || []
  return children
    .filter((item) => (appStore.elderlyMode ? !item.meta?.elderlyHidden : true))
    .filter((item) => {
      const allow = item.meta?.roles
      if (!allow || allow.length === 0) return true
      return !userStore.isLogin || allow.includes(userStore.role)
    })
    .map((item) => ({
      path: `/admin/${item.path}`,
      title: item.meta?.title || item.name,
      icon: item.meta?.icon || 'Menu'
    }))
})

const activeMenu = computed(() => route.path)

const pageTitle = computed(() => route.meta?.title || '')
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
}
</script>

<template>
  <el-container class="admin">
    <!-- ==================== 侧边栏 ==================== -->
    <el-aside class="admin__aside" :width="appStore.sidebarCollapsed ? '64px' : '220px'">
      <div class="admin__logo">
        <span class="admin__logo-mark">银</span>
        <span v-show="!appStore.sidebarCollapsed" class="admin__logo-text">银龄伴诊 · 后台</span>
      </div>

      <el-menu
        class="admin__menu"
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
      <el-header class="admin__header">
        <div class="admin__header-left">
          <el-button text @click="appStore.toggleSidebar()">
            <el-icon size="20"><Fold /></el-icon>
          </el-button>
          <span class="admin__page-title">{{ pageTitle }}</span>
          <el-tag v-if="pageModule" size="small" type="info" effect="plain">{{ pageModule }}</el-tag>
        </div>

        <div class="admin__header-right">
          <!-- 适老化总开关：桌面后台恒为宽屏，老人模式按 ADR-0007 Q4 = II 禁用 -->
          <div class="admin__elder-switch">
            <span class="admin__elder-label">老人模式</span>
            <el-tooltip
              content="桌面端已按设计禁用老人模式（仅手机形态生效）"
              placement="bottom"
            >
              <el-switch
                :model-value="appStore.elderlyMode"
                size="large"
                disabled
                inline-prompt
                active-text="大字"
                inactive-text="常规"
              />
            </el-tooltip>
          </div>

          <el-dropdown @command="handleCommand">
            <span class="admin__user">
              <el-icon><UserFilled /></el-icon>
              <span>{{ userStore.nickname }}</span>
              <el-tag size="small" effect="plain">{{ userStore.roleLabel }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- ==================== 内容区 ==================== -->
      <el-main class="admin__main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <!--
              ⚠️ 这层带 key 的 div 与 MobileLayout 里的同款，是必需的不是装饰：
              Transition 只能作用于单根子节点，一旦某个 admin view 的根节点写成
              v-if / v-else 双分支（Fragment），mode="out-in" 会等不到离场完成，
              新页面**永远不渲染**（URL 变了但整页空白，且没有任何报错）。
              详见 MobileLayout.vue 里的同类注释与实测记录。

              这里刻意直接复用 <script setup> 里的 route（不再从 slot 解构同名变量，
              否则会触发 vue/no-template-shadow）。
            -->
            <div :key="route.path" class="admin__page">
              <component :is="Component" />
            </div>
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.admin {
  height: 100vh;

  &__aside {
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: var(--nl-bg-card);
    border-right: 1px solid var(--nl-divider);
    transition: width 0.2s;
  }

  &__logo {
    display: flex;
    gap: 10px;
    align-items: center;
    height: $header-height;
    padding: 0 $nl-space-4;
    border-bottom: 1px solid var(--nl-divider);
  }

  &__logo-mark {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-text-inverse);
    background: var(--nl-primary);
    border-radius: 50%;
  }

  &__logo-text {
    font-size: 17px;
    font-weight: 600;
    color: var(--nl-text-1);
    white-space: nowrap;
  }

  &__menu {
    flex: 1;
    border-right: none;

    :deep(.el-menu-item.is-active) {
      background: var(--nl-primary-light);
      border-left: 3px solid var(--nl-primary);
    }
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: $header-height;
    padding: 0 $nl-space-6;
    background: var(--nl-bg-card);
    border-bottom: 1px solid var(--nl-divider);
  }

  &__header-left {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  &__page-title {
    font-size: 18px;
    font-weight: 600;
    color: var(--nl-text-1);
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
    color: var(--nl-text-2);
  }

  &__user {
    display: flex;
    gap: 8px;
    align-items: center;
    color: var(--nl-text-1);
    cursor: pointer;
    outline: none;
  }

  &__main {
    padding: $nl-space-6;
    overflow-y: auto;
    background: var(--nl-bg);
  }

  /* 透明包装层：不引入盒模型副作用，只为了让 Transition 拿到单根节点 */
  &__page {
    min-height: 100%;
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
