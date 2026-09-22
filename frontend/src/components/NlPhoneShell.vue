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
    <NlNavBar
      v-if="nav"
      :title="nav.title || ''"
      :back="nav.back !== false"
      :transparent="nav.transparent || false"
    >
      <template #right>
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

  // ==================== desktop-adapt-v2 T-07 ====================
  // 小屏 Android（≤360px）：padding 收紧、底部留白压缩，避免一屏只能看到一行
  @media (max-width: $bp-phone-xs) {
    &__main {
      gap: $nl-space-3;
      padding: $nl-space-3 0 calc(#{$nl-space-3} + 16px);

      &.has-tabs {
        // 让 has-tabs 走窄屏专属底部 padding（缩小 tabbar 安全留白）
        padding-bottom: calc(#{$nl-tabbar-height} + #{$nl-tabbar-padding-v} + env(safe-area-inset-bottom, 0));
      }
    }

    &__cta {
      padding: $nl-space-3;
      padding-bottom: calc(#{$nl-space-3} + env(safe-area-inset-bottom, 0));
    }
  }

  // 横屏紧凑模式（高度 ≤ 480px）：nav / tabbar 压缩，让主区拿到更多高度
  // 同时启用 NlNavBar 高度的 CSS 自定义属性 --nl-navbar-height，让 NlNavBar 同步收紧
  @media (max-height: $bp-phone-landscape) and (orientation: landscape) {
    --nl-navbar-height: 40px;
    --nl-tabbar-height: 48px;
    --nl-tabbar-padding-v: 6px;

    &__main.has-tabs {
      padding-bottom: calc(48px + 6px * 2 + env(safe-area-inset-bottom, 0));
    }
  }
}
</style>
