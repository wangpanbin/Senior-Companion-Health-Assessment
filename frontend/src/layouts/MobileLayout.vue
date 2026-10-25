<script setup>
/**
 * MobileLayout · 移动形态顶层布局（docs/design/design.md §4 骨架 · 390×844）
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

/**
 * keep-alive 的缓存键。
 *
 * ⚠️ **原来这里用的是 `route.name`** —— 同一条路由记录只换 params 时
 * （`/family/order/1` → `/family/order/2`）key 不变，命中同一个缓存实例：
 * `onMounted` 不再触发，屏幕上还留着**上一单**的内容，
 * 用户在 A 的界面上点「去评价」，评价会**提交给订单 A**。
 *
 * 现在把 params 纳入 key：换单 = 换实例 = 先落 loading 态，再拉新数据。
 *
 * ⚠️ 但这**只是第一层**。Vue Router 4 在「同路由记录只换 params」时
 * **本来就会复用组件实例**（与 keep-alive 无关，是 vue-router 的语义）。
 * 所以参数页还必须自己用 `computed(() => route.params.x)` + `watch` 重新拉数据 ——
 * 两层都做，才既不会残留旧数据，也不会在去掉缓存后退回「数据不刷新」。
 *
 * 键里只放 params、不放 query：query 变化（筛选、分页）不该重建实例。
 *
 * @param {import('vue-router').RouteLocationNormalizedLoaded} route
 * @returns {string}
 */
function cacheKey(route) {
  const params = route.params || {}
  const keys = Object.keys(params)
  if (!keys.length) return String(route.name)
  return `${route.name}:${keys.map((k) => `${k}=${params[k]}`).join(',')}`
}
</script>

<template>
  <div class="mobile-layout">
    <router-view v-slot="{ Component, route }">
      <!--
        移动端一级 Tab 直接切换，不使用 mode="out-in" 路由离场动画。
        部分页面在响应式切换时会渲染 Fragment；若等待其离场动画，可能留下空白容器。
        KeepAlive 直接缓存路由组件，切换时保留已加载内容和滚动状态。
        ⚠️ 缓存键必须包含 params（见上方 cacheKey），否则换订单时会复用上一单的实例。
      -->
      <keep-alive :max="12">
        <component :is="Component" :key="cacheKey(route)" />
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
