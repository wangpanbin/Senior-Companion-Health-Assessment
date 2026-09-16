<script setup>
/**
 * PhoneLayout · 移动端顶层布局（design.md §4 骨架 · 390×844）
 *
 * - 仅用于 4 角色（ELDER / FAMILY / COMPANION）的页面
 * - 内容区按页面需要自行选用 NlPhoneShell（自带状态栏 + Nav + Tab + CTA）
 * - 不再使用 el-aside 抽屉菜单（移动端不该有侧栏）
 * - 老人模式由 App.vue 在启动时统一初始化，此处不重复
 */
</script>

<template>
  <div class="phone-layout">
    <router-view v-slot="{ Component }">
      <transition name="fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.phone-layout {
  min-height: 100vh;
  background: var(--nl-bg);
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
