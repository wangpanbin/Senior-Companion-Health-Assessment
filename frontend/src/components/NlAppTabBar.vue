<script setup>
/**
 * NlAppTabBar · 角色感知 + 路由感知的通用底部 Tab Bar
 *
 * 为「一级 tab 页面」提供统一底部导航：每个角色的一级页常驻同一排 tab，
 * 点击直接切换到对应一级页（无需左上角返回）。
 *
 * 各角色真实 tab（以对应首页/大厅内联配置为唯一真源，param: 参考各 view）：
 *   - ELDER     首页→/elder/home  用药→/elder/medication  消息→/elder/message   我的→/profile
 *   - FAMILY    首页→/family/home 订单→/family/order  用药→/family/medication  消息→/family/message   我的→/profile
 *   - COMPANION 大厅→/companion/hall 订单→/companion/order 收入→/companion/income  我的→/profile
 *   - ADMIN     无移动端一级页（后台是桌面端 AdminLayout）→ 不给 tab，只给「请使用桌面端」提示
 *
 * 切换用 router.replace：目标 === 当前 path 则忽略；否则替换跳转（不污染 back 历史，
 * 一级页之间不再逐条累积返回栈，配合移动端 keep-alive 减少重建白屏）。
 *
 * 用法：
 *   <template #tabbar><NlAppTabBar /></template>
 *
 * 只负责渲染 TabBar 与切换跳转；不强制 has-tabs / back（由各自页面壳决定）。
 */
import { computed, defineComponent, h, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElBadge } from 'element-plus'
import { HomeFilled, FirstAidKit, Bell, User, List, Tickets, Money } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/modules/user'
import { startUnreadPolling } from '@/api/message'
import NlTabBar from './NlTabBar.vue'
import NlNoticeBar from './NlNoticeBar.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const pendingKey = ref('')

/** 每个角色的一级 tab 定义（with `to`；激活态按 path 精确匹配） */
const TABS_BY_ROLE = {
  ELDER: [
    { key: 'home', label: '首页', icon: HomeFilled, to: '/elder/home' },
    { key: 'medication', label: '用药', icon: FirstAidKit, to: '/elder/medication' },
    { key: 'message', label: '消息', icon: Bell, to: '/elder/message' },
    { key: 'profile', label: '我的', icon: User, to: '/profile' }
  ],
  FAMILY: [
    { key: 'home', label: '首页', icon: HomeFilled, to: '/family/home' },
    { key: 'order', label: '订单', icon: Tickets, to: '/family/order' },
    { key: 'medication', label: '用药', icon: FirstAidKit, to: '/family/medication' },
    { key: 'message', label: '消息', icon: Bell, to: '/family/message' },
    { key: 'profile', label: '我的', icon: User, to: '/profile' }
  ],
  COMPANION: [
    { key: 'hall', label: '大厅', icon: List, to: '/companion/hall' },
    { key: 'order', label: '订单', icon: Tickets, to: '/companion/order' },
    { key: 'income', label: '收入', icon: Money, to: '/companion/income' },
    { key: 'mine', label: '我的', icon: User, to: '/profile' }
  ],
  /**
   * 管理员没有移动端一级页（管理后台是桌面端 AdminLayout）。
   *
   * ⚠️ 这里必须显式给空数组：早先的写法是 `TABS_BY_ROLE[role] || TABS_BY_ROLE.ELDER`，
   *    没有 ADMIN 键就会回退到**老人端**的 tab —— 管理员一点底部 tab 就跳到
   *    /elder/home，再被守卫的角色校验静默弹回，看起来像「点了没反应」（P2-15）。
   */
  ADMIN: []
}

/* ==================== 消息 tab 的未读角标 ==================== */

/**
 * 站内信未读数（只有 ELDER / FAMILY 有「消息」tab）。
 *
 * 数据源与家属首页顶栏铃铛同源（GET /message/unread-count → { total }），
 * 角标数与首页红点一致，用户不会看到「首页显示 5 条、切过来变 0」（P2-7）。
 *
 * 刷新机制：
 *   - 进页面时 startUnreadPolling 内部先拉一次（初始值正确）
 *   - 新消息由 SSE（/sse/message）推来，onChange 立刻更新
 *   - 「标记已读」服务端不推事件，故另设 60s 轮询刷新一次，让角标最终收敛
 */
const unread = ref(0)
let polling = null
let refreshTimer = null

/**
 * 「消息」tab 的图标（带未读角标）。
 *
 * 为什么把角标做进图标里：NlTabBar 是通用组件，tab 模型里没有 badge 字段，
 * 而本轮不改组件库；把 el-badge 包在图标外层，既不用改 NlTabBar 的模型，
 * 又能只在「消息」这一个 tab 上显示角标。
 */
const MessageIcon = defineComponent({
  name: 'NlAppTabBarMessageIcon',
  setup() {
    return () =>
      h(
        ElBadge,
        { value: unread.value, hidden: unread.value === 0, max: 99 },
        { default: () => h(Bell) }
      )
  }
})

const tabs = computed(() =>
  (TABS_BY_ROLE[userStore.role] || []).map((t) =>
    t.key === 'message' ? { ...t, icon: MessageIcon } : t
  )
)

/** 当前角色有没有「消息」tab（只有 ELDER / FAMILY 有） */
const hasMessageTab = computed(() => tabs.value.some((t) => t.key === 'message'))

/** 管理员：移动端没有可用的一级导航，给一句明确提示，而不是让 tab 点不动 */
const isAdmin = computed(() => userStore.isAdmin)

/** 从当前路由精确匹配激活的 tab key；匹配不到不高亮 */
const active = computed(() => {
  if (pendingKey.value) return pendingKey.value
  const target = tabs.value.find((t) => route.path === t.to)
  return target ? target.key : ''
})

async function onTabChange(key) {
  const target = tabs.value.find((t) => t.key === key)
  if (!target) return
  if (route.path === target.to) return // 已在目标页，不重复跳转

  // 路由组件尚未完成切换时，先反馈用户刚刚点中的 Tab，避免视觉滞后。
  pendingKey.value = key
  try {
    await router.replace(target.to)
  } finally {
    pendingKey.value = ''
  }
}

onMounted(() => {
  if (!hasMessageTab.value) return
  polling = startUnreadPolling(60000, (count) => {
    unread.value = Number(count || 0)
  })
  refreshTimer = setInterval(() => polling?.refresh(), 60000)
})

onBeforeUnmount(() => {
  polling?.stop()
  polling = null
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = null
})
</script>

<template>
  <NlTabBar v-if="tabs.length" :tabs="tabs" :model-value="active" @change="onTabChange" />
  <!-- 管理员：后台是桌面端形态，手机端不提供一级导航（P2-15） -->
  <NlNoticeBar v-else-if="isAdmin" tone="info">
    管理后台请在电脑端使用，手机端暂未提供管理功能。
  </NlNoticeBar>
</template>
