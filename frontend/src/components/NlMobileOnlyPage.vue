<script setup>
/**
 * NlMobileOnlyPage · mobile-only 路由的形态守卫（ADR-0008 的落地组件）
 *
 * ## 为什么需要它
 *
 * `NlDesktopShell` 只能给"自己走桌面形态"的 view 做 mobileOnly 分流。
 * 但 mobile-only 的 view 恰恰**永远不会**渲染 `NlDesktopShell`
 * （它们只有手机版模板），于是 `meta.mobileOnly` 在这些路由上形同虚设 ——
 * 1280 宽屏打开 `/profile` 仍然是一台被拉满的"大号手机"。
 *
 * 所以判据放在更靠上的位置：路由表里 `/` 这一层挂的是 `MobileLayout`，
 * 由本组件在 view 的模板最外层补上这一步判断。
 *
 * ## 两种等价写法（都合法，按 view 结构选）
 *
 * 写法 A（本组件，推荐给"整页只有手机壳"的 view）：
 *
 *   <NlMobileOnlyPage>
 *     <NlPhoneShell :nav="{ title: '我的', back: false }"> … </NlPhoneShell>
 *   </NlMobileOnlyPage>
 *
 * 写法 B（view 内联，等价）：
 *
 *   <NlPhoneShell v-if="isMobile || !route.meta.mobileOnly"> … </NlPhoneShell>
 *   <NlDesktopShell v-else> …（内部会渲染 NlMobileOnlyNotice）… </NlDesktopShell>
 *
 * ## ⚠️ 多路由共用一个 view 时必传 `enabled`
 *
 * `views/companion/message.vue` 被三条路由共用：
 * `/elder/message`（**是** mobileOnly）、`/family/message` 与 `/companion/message`
 * （两个都**不是**）。若本组件只读 `route.meta.mobileOnly`，就会把
 * FAMILY / COMPANION 的消息页一起在桌面端打成提示页 —— 那是错的。
 * 因此多路由共用的 view **必须**把判据收敛到"当前路由实例"：
 *
 *   <NlMobileOnlyPage :enabled="route.name === 'ElderMessage'">
 *
 * ## force-mobile 闭环
 *
 * 点"继续查看" → `forceMobile()` 写 sessionStorage → 刷新 →
 * `useDevice().isMobile` 恒真 → 本组件放行手机壳。关闭标签页后标记自动失效。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useDevice } from '@/composables/useDevice'
import NlDesktopShell from './NlDesktopShell.vue'

const props = defineProps({
  /**
   * 是否对当前路由启用 mobileOnly 守卫。
   * - 默认 `true`：本 view 只服务一条 mobileOnly 路由
   * - 多路由共用的 view 必须传显式布尔（见上方 ⚠️）
   */
  enabled: { type: Boolean, default: true }
})

const route = useRoute()
const { isMobile } = useDevice()

/** 是否放行默认插槽（手机壳） */
const showView = computed(() => {
  if (!props.enabled) return true
  if (isMobile.value) return true
  return route.meta?.mobileOnly !== true
})
</script>

<template>
  <slot v-if="showView" />
  <!-- 宽屏 + mobileOnly：交给 NlDesktopShell（它内部检测 meta 后会渲染 NlMobileOnlyNotice） -->
  <NlDesktopShell v-else />
</template>
