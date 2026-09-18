<script setup>
/**
 * DesktopLayout · 桌面形态顶层布局（可选入口）
 *
 * 依据：`docs/adr/0007-desktop-form-factor.md` · `docs/plan/desktop-adapt-2026-09.md` Stage 1
 *
 * ## 与 MobileLayout 的分工（重要）
 *
 * 路由表里 `/` 这一层挂的是 `MobileLayout`，**不是** `DesktopLayout` —— 这是 Q3 = b
 * （组件内响应式）的直接后果：URL 不分叉，由 view 内的 `isMobile` 决定形态。
 * 因此本 layout 不参与主链路，落在这里的作用是：
 *
 *   1. 给"整体走桌面壳、但 view 内部没写 v-if"的场景提供一个可挂载入口
 *      （例如临时把某个路由的 component 换成 DesktopLayout 做对照排查）；
 *   2. 把 `NlDesktopShell` 的 64px 顶栏 + 居中限宽 720 逻辑暴露成一个 layout 语义，
 *      避免后来者以为"桌面形态必须自己包 shell"。
 *
 * ⚠️ 不要让 view 同时被本 layout 和 view 内的 `NlDesktopShell` 包裹，
 *    那会渲染出**两层顶栏**。二者选其一。
 */
import NlDesktopShell from '@/components/NlDesktopShell.vue'
</script>

<template>
  <NlDesktopShell>
    <router-view v-slot="{ Component }">
      <transition name="fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </NlDesktopShell>
</template>

<style scoped lang="scss">
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
