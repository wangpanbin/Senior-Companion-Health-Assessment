<script setup>
/**
 * MobileLayout · 移动形态顶层布局（design.md §4 骨架 · 390×844）
 *
 * 依据：`docs/adr/0007-desktop-form-factor.md`（形态与角色解耦）
 * 命名：由旧的手机端布局文件更名而来，语义与形态对齐；旧文件已删除。
 *
 * - 用于 4 角色（ELDER / FAMILY / COMPANION）的**窄屏**渲染
 * - 只负责"套一层路由出口 + 过渡"；页面壳由 view 自己选用 `NlPhoneShell`
 * - 宽屏时 view 内部走 `isDesktop` 分支渲染 `NlDesktopShell`（Q3 = b 组件内响应式），
 *   因此本 layout 不做重定向 —— 重定向会与"URL 不变、形态随宽度切换"的决议冲突
 * - 不再使用 el-aside 抽屉菜单（移动端不该有侧栏）
 * - 老人模式由 App.vue 在启动时统一初始化，此处不重复
 */
</script>

<template>
  <div class="mobile-layout">
    <router-view v-slot="{ Component, route }">
      <transition name="fade" mode="out-in">
        <!--
          ⚠️ 这一层带 key 的真实 div 是**必需**的，不是装饰。

          `<Transition>` 只能作用于「单根」子节点。而形态自适应之后，大量 view 的根节点
          变成 `v-if="isMobile" / v-else` 双分支（渲染成 Fragment），
          `mode="out-in"` 于是等不到离场完成，**新页面永远不会被插入**：
          现象是 URL 已经变了，但 #app 里只剩 mobile-layout 容器加一个空的注释占位
          （整页空白，等多久都不恢复）。

          实测证据（.playwright-cli/diag-spa-compare.mjs）：
            - admin 内部跳转（view 都是单根）→ 正常
            - /family/home → 任何其他路由（含程序化 router.push）→ 空白
          套一层单根 div 并给它 key 之后，Transition 有明确的离场/入场元素，问题消失。
        -->
        <div :key="route.path" class="mobile-layout__page">
          <component :is="Component" />
        </div>
      </transition>
    </router-view>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.mobile-layout {
  min-height: 100vh;
  background: var(--nl-bg);

  /* 透明包装层：不引入任何盒模型副作用，只为了让 <Transition> 拿到单根节点 */
  &__page {
    min-height: 100vh;
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
