<script setup>
/**
 * NlPhoneShell · 移动端页面壳（design.md §4 骨架）
 *
 * 把 状态栏 + 顶部导航 + 内容区 + 底部 Tab Bar 统一包起来。
 * 用于保持页面与设计骨架 1:1 对齐，并支持后续老人模式 / 适老化统一处理。
 *
 * 两种布局：
 *  1. Tab 主页（首页）：底部胶囊 Tab Bar，scroll 内容
 *  2. 流程页（如下单）：底部主行动按钮（CTA 区），无 Tab Bar
 *
 * 用法：
 *   <NlPhoneShell :nav="{ title: '首页', back: false }" :has-tabs="true">
 *     <template #tabbar>
 *       <NlTabBar :tabs="..." v-model="active" />
 *     </template>
 *     页面内容
 *     <template #cta>
 *       <el-button type="primary" size="large" round>提交订单</el-button>
 *     </template>
 *   </NlPhoneShell>
 */
import NlStatusBar from './NlStatusBar.vue'
import NlNavBar from './NlNavBar.vue'

defineProps({
  /** 顶部导航配置；null 表示不要 NavBar */
  nav: { type: Object, default: null },
  /** 当前是否为 Tab 主页（用于底部 padding） */
  hasTabs: { type: Boolean, default: false },
  /** 是否显示底部 CTA（流程页使用） */
  hasCta: { type: Boolean, default: false },
  /** 状态栏是否反色（深色渐变背景时） */
  statusInverse: { type: Boolean, default: false },
  /** 状态栏时间，如 '09:41' */
  statusTime: { type: String, default: '' },
  /** 内容区背景（默认 --nl-bg，渐变卡片时可设为 transparent） */
  bodyBg: { type: String, default: '' }
})
</script>

<template>
  <div class="nl-shell">
    <NlStatusBar :inverse="statusInverse" :time="statusTime" />
    <NlNavBar
      v-if="nav"
      :title="nav.title || ''"
      :back="nav.back !== false"
      :transparent="nav.transparent || false"
    >
      <template v-if="nav.$slots?.right" #right>
        <slot name="nav-right" />
      </template>
      <template v-if="$slots['nav-right']" #right>
        <slot name="nav-right" />
      </template>
    </NlNavBar>

    <main
      :class="['nl-shell__main', { 'has-tabs': hasTabs, 'has-cta': hasCta }]"
      :style="bodyBg ? { background: bodyBg } : {}"
    >
      <slot />
    </main>

    <div v-if="$slots.cta && hasCta" class="nl-shell__cta">
      <slot name="cta" />
    </div>

    <div v-if="hasTabs && $slots.tabbar" class="nl-shell__tabbar">
      <slot name="tabbar" />
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--nl-bg);

  &__main {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: var(--nl-gap-section);
    padding: var(--nl-gap-section) 0 calc(var(--nl-gap-section) + 20px);

    &.has-tabs {
      padding-bottom: calc(#{$nl-tabbar-height} + #{$nl-tabbar-padding-v} * 2 + env(safe-area-inset-bottom, 0));
    }

    &.has-cta {
      padding-bottom: calc(96px + env(safe-area-inset-bottom, 0));
    }
  }

  &__cta {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    z-index: 10;
    padding: var(--nl-space-4);
    padding-bottom: calc(var(--nl-space-4) + env(safe-area-inset-bottom, 0));
    background: var(--nl-bg-card);
    box-shadow: var(--nl-shadow-float);
  }

  &__tabbar {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    z-index: 10;
  }
}
</style>
