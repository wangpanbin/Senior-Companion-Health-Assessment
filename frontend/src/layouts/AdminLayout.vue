<script setup>
/**
 * AdminLayout · 管理后台桌面端布局（docs/design/design.md §4 · 1440×900）
 *
 * - 左栏自适应：auto 模式按 useResponsive().isMd 翻转（<1280 收起 / ≥1280 展开）
 *   手动态 'expanded' / 'collapsed' 强制覆盖，偏好写 localStorage（reload 保持）
 * - 内容区：顶部 64px（面包屑 + 管理员信息）→ 内容 padding 24
 * - 适老化：菜单项高度 56 / 字号 18，老人模式下边栏只隐藏 elderlyHidden 项
 *
 * desktop-adapt-v2（T-03）：侧栏模式从硬编码 expanded → auto + 手动 + 持久化
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useAppStore } from '@/store/modules/app'
import { useUserStore } from '@/store/modules/user'
import { useResponsive } from '@/composables/useResponsive'
import { resolveSidebarMode, toggleSidebarMode as computeNextMode } from '@/utils/sidebar'
import { confirmThen } from '@/utils/confirm'
import { routes } from '@/router/routes'
import { listAllOrders, listAuditApplications, listComplaints } from '@/api/admin'
import { getAdminMobileHintClosed, setAdminMobileHintClosed } from '@/utils/auth'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

/**
 * 窄屏提示条是否已被用户关闭（localStorage 记住，跨会话不再打扰）
 *
 * N3 决策（2026-11-23，见 docs/reports/UI快速走查与修复记录-2026-11-23.md §7）：
 * 管理端是**桌面优先**场景 —— 手机形态只保证「不破版 + 可用」：
 *   · 不硬拦截：管理员在门诊现场用手机处理投诉 / 仲裁是真实路径，
 *     拦截会把这类操作直接堵死，代价大于信息密度不佳；
 *   · 不做移动端专用重排：13 个管理端页面在 390px 下已验证无横向溢出，
 *     兜底有效，而重排一整套响应式表格的工作量与收益不匹配。
 * 提示条只做预期管理：告诉用户当前是窄屏视图、信息密度高。
 */
const mobileHintClosed = ref(getAdminMobileHintClosed())

function closeMobileHint() {
  mobileHintClosed.value = true
  setAdminMobileHintClosed(true)
}

/** 形态判定（desktop-adapt-v2 复用 useResponsive）
 *  ⚠️ 断点用的是 `isLg`（≥1280），不是 `isMd`（1024-1279）：
 *     useResponsive 5 档中 `md` 是 1024-1279 区间，语义不是"中等屏"，
 *     命名易误用。sidebar 设计语义见 docs/spec/desktop-adapt-v2.md §2.1 / sidebar.js 注释：
 *     <1280 收起、≥1280 展开。E2E 12-admin-responsive.spec.js:38 直接覆盖这条契约。
 */
const { isLg } = useResponsive()

/**
 * 实际侧栏 width / collapsed / mode 三元组
 * 模板只读这几个值；状态推进（toggle / change mode）由 action 负责
 */
const sidebar = computed(() => resolveSidebarMode(appStore.sidebarMode, isLg.value))

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
    .map((item) => {
      const path = `/admin/${item.path}`
      const field = TODO_BADGE_FIELD[path]
      return {
        path,
        title: item.meta?.title || item.name,
        icon: item.meta?.icon || 'Menu',
        badge: field ? todoCount[field] : 0
      }
    })
})

const activeMenu = computed(() => route.path)

const pageTitle = computed(() => route.meta?.title || '')
const pageModule = computed(() => route.meta?.module || '')

/* ==================== 侧栏待办角标（P2-7） ==================== */

/**
 * 待办量。口径全部取自后端已有的列表接口，前端不另算一套：
 *   - 待审资质：/admin/companion/audit 的 counts.PENDING（整表口径，不受分页影响）
 *   - 投诉管理：/admin/complaint?status=PENDING 的 total（待处理投诉）
 *   - 订单纠纷：/admin/order?hasComplaint=true 的 total（有纠纷待仲裁的订单）
 *
 * ⚠️ 这三个接口都是 ADMIN 专用，失败时不抛错：保持上一次的值即可，
 *    错误提示由 request.js 的统一拦截器负责（不在这里重复弹）。
 */
const todoCount = reactive({ audit: 0, complaint: 0, dispute: 0 })

/** 侧栏路径 → 角标字段 */
const TODO_BADGE_FIELD = {
  '/admin/companion-audit': 'audit',
  '/admin/complaint': 'complaint',
  '/admin/order-dispute': 'dispute'
}

let todoTimer = null

async function loadTodoCounts() {
  const [audit, complaint, dispute] = await Promise.allSettled([
    listAuditApplications({ page: 1, size: 1, auditStatus: 'PENDING' }),
    listComplaints({ page: 1, size: 1, status: 'PENDING' }),
    listAllOrders({ page: 1, size: 1, hasComplaint: true })
  ])
  todoCount.audit =
    audit.status === 'fulfilled'
      ? Number(audit.value?.counts?.PENDING ?? audit.value?.total ?? 0)
      : 0
  todoCount.complaint = complaint.status === 'fulfilled' ? Number(complaint.value?.total ?? 0) : 0
  todoCount.dispute = dispute.status === 'fulfilled' ? Number(dispute.value?.total ?? 0) : 0
}

/** 用户点 header 的 toggle 按钮：auto ↔ 手动态（语义见 utils/sidebar.js） */
function handleToggleSidebar() {
  const next = computeNextMode(appStore.sidebarMode, isLg.value)
  appStore.setSidebarMode(next)
}

async function handleLogout() {
  // confirmThen 把「用户是否点了确认」变成布尔：取消时静默返回 false，不执行登出
  const ok = await confirmThen(() => ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' }))
  if (!ok) return
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

function handleCommand(cmd) {
  if (cmd === 'logout') handleLogout()
}

onMounted(() => {
  loadTodoCounts()
  todoTimer = setInterval(() => {
    // 后台标签页不轮询：省一次请求，也避免后端不可用时反复弹错误提示
    if (document.visibilityState === 'visible') loadTodoCounts()
  }, 60000)
})

// 处理完待办再回到后台其它页面时，角标要跟着变（不刷新整个页面）
watch(() => route.path, loadTodoCounts)

onBeforeUnmount(() => {
  if (todoTimer) clearInterval(todoTimer)
  todoTimer = null
})
</script>

<template>
  <el-container class="admin">
    <!-- ==================== 侧边栏（断点驱动 + 持久化） ==================== -->
    <el-aside class="admin__aside" :width="`${sidebar.width}px`">
      <div class="admin__logo">
        <span class="admin__logo-mark">银</span>
        <span v-show="!sidebar.collapsed" class="admin__logo-text">银龄伴诊 · 后台</span>
      </div>

      <el-menu
        class="admin__menu"
        :default-active="activeMenu"
        :collapse="sidebar.collapsed"
        :collapse-transition="false"
        router
      >
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <template #title>
            <el-badge
              :value="m.badge"
              :hidden="!m.badge"
              :max="99"
              class="admin__menu-badge"
            >
              <span class="admin__menu-title">{{ m.title }}</span>
            </el-badge>
          </template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <!-- ==================== 顶栏 ==================== -->
      <el-header class="admin__header">
        <div class="admin__header-left">
          <el-button text :title="sidebar.collapsed ? '展开侧栏' : '收起侧栏'" @click="handleToggleSidebar">
            <el-icon size="20">
              <component :is="sidebar.collapsed ? 'Expand' : 'Fold'" />
            </el-icon>
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

      <!-- ==================== 窄屏形态提示（N3 决策） ==================== -->
      <!--
        管理端是**桌面优先**场景：手机形态只保证「不破版 + 可用」，不硬拦截、
        不做移动端重排（理由见上方 mobileHintClosed 的注释）。
        这条提示只做预期管理 —— 让用户知道自己看到的是窄屏版、信息密度高。
        宽屏不显示：判定放在样式里（display），不依赖 JS 监听 resize。
      -->
      <div v-if="!mobileHintClosed" class="admin__mobile-hint">
        <el-icon class="admin__mobile-hint-icon"><InfoFilled /></el-icon>
        <span class="admin__mobile-hint-text">
          管理端为桌面端设计，当前是窄屏视图，信息密度较高，建议在电脑上访问。
        </span>
        <button
          type="button"
          class="admin__mobile-hint-close"
          aria-label="关闭窄屏提示"
          @click="closeMobileHint"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>

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
    font-size: var(--nl-font-h2);
    font-weight: 700;
    color: var(--nl-text-inverse);
    background: var(--nl-primary);
    border-radius: 50%;
    flex-shrink: 0;
  }

  &__logo-text {
    font-size: var(--nl-font-h2);
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

  /* 待办角标贴在标题右上角（el-badge 默认绝对定位），右侧留一点空隙 */
  &__menu-badge {
    margin-right: 12px;
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
    font-size: var(--nl-font-h2);
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
    font-size: var(--nl-font-body);
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

  /*
   * 窄屏形态提示条（宽屏隐藏，窄屏由媒体查询打开）
   *
   * 配色沿用 NlNoticeBar 的 primary 档：--nl-primary-text 配 --nl-primary-ghost，
   * 对比度与全站提示条一致（原始主色在自身浅底上只有 2.1~4.0:1，不达 AA）。
   */
  &__mobile-hint {
    display: none;
    gap: $nl-space-2;
    align-items: flex-start;
    padding: $nl-space-2 $nl-space-3;
    font-size: var(--nl-font-caption);
    line-height: 1.5;
    color: var(--nl-primary-text);
    background: var(--nl-primary-ghost);
    border-bottom: 1px solid var(--nl-border);
  }

  &__mobile-hint-icon {
    flex-shrink: 0;
    margin-top: 2px;
  }

  &__mobile-hint-text {
    flex: 1;
  }

  /* 关闭按钮：44×44 命中区（窄屏手指点得准），视觉上只留一个 × */
  &__mobile-hint-close {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    padding: 0;
    color: var(--nl-text-2);
    background: transparent;
    border: 0;
    border-radius: var(--nl-radius-chip);
    cursor: pointer;

    &:hover {
      color: var(--nl-text-1);
      background: var(--nl-bg-sunken);
    }
  }
}

/* --------------------------------------------------------------------------
 * 窄屏兜底（<768px）
 *
 * 管理后台的目标形态是桌面（design.md：1440×900），但在手机上打开时顶栏会撑出
 * 视口 —— 实测 390px 宽下横向溢出 10~17px，整页出现横向滚动条，右侧用户区被裁。
 * 这里只做「不破版」兜底，不把后台改造成移动端：
 *   · 两侧都允许收缩，页面标题过长省略；
 *   · 隐藏「老人模式」开关 —— 桌面端本就按 ADR-0007 Q4 禁用，窄屏更是零信息量；
 *   · 用户区只留图标，昵称与角色标签收进下拉菜单。
 * -------------------------------------------------------------------------- */
@media (max-width: 767px) {
  .admin__header {
    gap: $nl-space-3;
    padding: 0 $nl-space-4;
  }

  .admin__header-left,
  .admin__header-right {
    min-width: 0;
  }

  .admin__header-right {
    flex-shrink: 1;
    gap: 8px;
  }

  .admin__page-title {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .admin__elder-switch {
    display: none;
  }

  /* 窄屏提示条：宽屏 display:none，这里打开 */
  .admin__mobile-hint {
    display: flex;
  }

  .admin__user > span,
  .admin__user .el-tag {
    display: none;
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