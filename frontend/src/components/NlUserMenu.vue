<script setup>
/**
 * NlUserMenu · 通用「当前用户」下拉菜单
 *
 * 设计目的：把「头像 + 昵称 + 角色 + 退出登录」这套交互从单个 view 里抽出来，
 * 统一挂在 `NlDesktopShell` 顶栏（桌面端所有视图）与 `NlPageShell` 移动端 NavBar 右侧。
 * 这样：
 *   1. view 只负责业务，不需要关心登出交互的确认 / toast / 跳转；
 *   2. 桌面形态的退出入口永远出现在右上角，与既有消息 / 主行动按钮节奏一致；
 *   3. 退出逻辑单一来源（`handleLogout` 在这里），将来要加「切换账号」「锁屏」
 *      等菜单项只需改本组件，不散落各个 view。
 *
 * 退出流程（与 AdminLayout / profile 页一致的三步）：
 *   1. `ElMessageBox.confirm` 弹确认（防误触）
 *   2. `userStore.logout()` 串起 `POST /api/auth/logout` + `clearAuth()`，
 *      即便后端接口失败 finally 也会清本地态
 *   3. `router.push('/login')` 跳登录页（路由守卫也会兜底）
 *
 * Props：
 *   - `size`      `'sm' | 'md'`，默认 'md'。
 *                  'sm' 适配移动端 NavBar 右侧（48px 高度内紧凑）；
 *                  'md' 适配桌面顶栏（48px 高度，可显示昵称）。
 *                  注意 NlDesktopShell 在 >= 768px 才渲染，移动形态走 NlPhoneShell
 *                  时也会用 NlUserMenu，所以两种尺寸都覆盖。
 *   - `showName`  是否显示昵称文字。移动形态 NavBar 较窄可关掉。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/modules/user'

const props = defineProps({
  size: { type: String, default: 'md' },
  showName: { type: Boolean, default: true }
})

const router = useRouter()
const userStore = useUserStore()

/* ---------------- 派生数据 ---------------- */
/** 头像首字母：昵称为空时回落到「我」，与 profile 页保持一致 */
const avatarInitial = computed(() => {
  const name = (userStore.nickname || '我').trim()
  return name ? name.slice(0, 1) : '我'
})

/** 触发器尺寸：sm 用在移动 NavBar，md 用在桌面顶栏 */
const isSm = computed(() => props.size === 'sm')

/* ---------------- 退出登录 ---------------- */
async function handleLogout() {
  try {
    await ElMessageBox.confirm(
      '退出后将无法接收订单通知，是否继续？',
      '退出登录',
      {
        type: 'warning',
        confirmButtonText: '退出',
        cancelButtonText: '再想想',
        closeOnClickModal: false
      }
    )
  } catch {
    // 用户点了「取消 / 关闭」→ 不执行登出
    return
  }
  await userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

function onCommand(cmd) {
  if (cmd === 'logout') handleLogout()
}
</script>

<template>
  <el-dropdown
    trigger="click"
    placement="bottom-end"
    :class="['nl-user-menu', { 'is-sm': isSm }]"
    @command="onCommand"
  >
    <button
      type="button"
      :class="['nl-user-menu__trigger', { 'is-sm': isSm }]"
      :title="userStore.nickname || '未登录'"
      aria-label="用户菜单"
    >
      <span class="nl-user-menu__avatar">{{ avatarInitial }}</span>
      <span v-if="showName" class="nl-user-menu__name">{{ userStore.nickname || '未登录' }}</span>
      <el-icon class="nl-user-menu__caret"><ArrowDown /></el-icon>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <div class="nl-user-menu__card">
          <span class="nl-user-menu__card-name">{{ userStore.nickname || '未登录' }}</span>
          <el-tag v-if="userStore.roleLabel" size="small" type="info" effect="plain">
            {{ userStore.roleLabel }}
          </el-tag>
        </div>
        <el-dropdown-item
          command="logout"
          :icon="SwitchButton"
          divided
          class="nl-user-menu__logout"
        >
          退出登录
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-user-menu {
  /* 桌面顶栏右侧的 inline-flex 节奏 */
  display: inline-flex;

  /* 让 dropdown 弹出层不被 scoped 边界裁掉 */
  :deep(.el-popper) {
    margin-top: 6px;
  }
}

.nl-user-menu__trigger {
  display: inline-flex;
  gap: var(--nl-space-2);
  align-items: center;
  height: $nl-btn-height; // 48px（与 NL 默认按钮等高）
  padding: 0 12px 0 4px;
  color: var(--nl-text-1);
  cursor: pointer;
  background: var(--nl-bg-sunken);
  border: 1px solid var(--nl-border);
  border-radius: 999px;
  transition: background 0.15s, border-color 0.15s;

  &:hover,
  &:focus-visible {
    background: var(--nl-primary-ghost);
    border-color: var(--nl-primary-light);
    outline: none;
  }

  &:active {
    transform: scale(0.98);
  }

  /* 移动端 NavBar 紧凑模式：缩小头像、隐藏昵称、压缩内边距 */
  &.is-sm {
    height: var(--nl-touch-min); // 44px，避免撑高 NavBar
    padding: 0 10px 0 3px;
  }
}

.nl-user-menu__avatar {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  font-size: 14px;
  font-weight: 600;
  color: var(--nl-text-inverse);
  background: var(--nl-primary-gradient);
  border-radius: 50%;
}

.nl-user-menu__name {
  font-size: 14px;
  font-weight: 500;
  color: var(--nl-text-1);
}

.nl-user-menu__caret {
  font-size: 12px;
  color: var(--nl-text-3);
  transition: transform 0.15s;
}

/* dropdown 展开时尖头轻旋转（Element Plus 给 trigger 挂 .is-active） */
:deep(.el-dropdown.is-active) .nl-user-menu__caret {
  transform: rotate(180deg);
}

/* dropdown 内部的用户卡片 */
.nl-user-menu__card {
  display: flex;
  gap: var(--nl-space-2);
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  background: var(--nl-bg-sunken);
  border-bottom: 1px solid var(--nl-divider);
}

.nl-user-menu__card-name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  color: var(--nl-text-1);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nl-user-menu__logout {
  color: var(--nl-danger);

  &:hover,
  &:focus-visible {
    background: var(--nl-danger-bg);
    color: var(--nl-danger);
  }
}
</style>
