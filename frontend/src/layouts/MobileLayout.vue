<script setup>
/**
 * MobileLayout · 移动形态顶层布局（design.md §4 骨架 · 390×844）
 *
 * 依据：`docs/adr/0007-desktop-form-factor.md`（形态与角色解耦）
 * 命名：由旧的手机端布局文件更名而来，语义与形态对齐；旧文件已删除。
 *
 * - 用于 4 角色（ELDER / FAMILY / COMPANION）的**窄屏**渲染
 * - 只负责"套一层路由出口 + 缓存"；页面壳由 view 自己选用 `NlPhoneShell`
 * - 宽屏时 view 内部走 `isDesktop` 分支渲染 `NlDesktopShell`（Q3 = b 组件内响应式），
 *   因此本 layout 不做重定向 —— 重定向会与"URL 不变、形态随宽度切换"的决议冲突
 * - 不再使用 el-aside 抽屉菜单（移动端不该有侧栏）
 * - 老人模式由 App.vue 在启动时统一初始化，此处不重复
 */
</script>

<template>
  <div class="mobile-layout">
    <router-view v-slot="{ Component, route }">
      <!--
        移动端一级 Tab 直接切换，不使用 mode="out-in" 路由离场动画。
        部分页面在响应式切换时会渲染 Fragment；若等待其离场动画，可能留下空白容器。
        KeepAlive 直接缓存路由组件，以 route.name 区分实例，切换时保留已加载内容和滚动状态。
      -->
      <keep-alive :max="12">
        <component :is="Component" :key="route.name" />
      </keep-alive>
    </router-view>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.mobile-layout {
  min-height: 100vh;
  background: var(--nl-bg);
}
</style>
