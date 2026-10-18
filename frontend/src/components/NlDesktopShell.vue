<script setup>
/**
 * NlDesktopShell · 桌面形态页面壳（Q9 = β · 含简化顶栏）
 *
 * 依据：`docs/adr/0007-desktop-form-factor.md` · `docs/plan/desktop-adapt-2026-09.md` Q2/Q5/Q9/Q11
 *
 * ## 结构
 *
 *   ┌──────────────────────────────────────────────────────────┐
 *   │ 顶栏 64px：Logo + route.meta.title + [module chip] + actions │
 *   ├──────────────────────────────────────────────────────────┤
 *   │     内容区居中：768–1279 单列 720px；≥1280 双栏 1120px      │
 *   └──────────────────────────────────────────────────────────┘
 *
 * ## 两条硬约束（计划 §2 关键约束 1 / 5）
 *
 * 1. **内容宽度由本组件统一控制**（720 / 1120 两档，见样式尾部的双栏规则）——
 *    view 内部**不要**再写死 width / max-width（Q11：宽度直接写死在 NlDesktopShell）。
 * 2. **mobile-only 路由整体替换** —— `route.meta.mobileOnly` 为真时不渲染默认插槽，
 *    改渲染 `NlMobileOnlyNotice`。view 内部**不要**为此写 v-if（计划 §2 关键约束 5）。
 *
 * ## 用法
 *
 *   <NlDesktopShell>
 *     <template #actions><!-- 右上角按钮，可选 --></template>
 *     桌面版内容（NlCard / 列表等）
 *   </NlDesktopShell>
 *
 * loading / empty / error **由 view 自管**（Q10 = ③），本组件不提供这些状态。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import NlMobileOnlyNotice from './NlMobileOnlyNotice.vue'
import NlUserMenu from './NlUserMenu.vue'

const props = defineProps({
  /**
   * 是否显示顶栏返回按钮。
   * - `null`（默认）：按路由元数据推断（见 showBack）
   * - 显式传值时以调用方为准 —— `NlPageShell` 会传 `true`，
   *   因为它包的都是 NlPhoneShell 里默认带返回箭头的二级页；
   *   若这里继续按 meta 推断，`/family/medication` 这类"非 elderlyHidden 但确实是二级页"
   *   的路由在桌面端将**没有任何返回入口**，形成死路。
   */
  back: { type: Boolean, default: null }
})

const route = useRoute()

/**
 * 是否启用「宽档」内容区（M13 聊天室三栏，1440px）。
 *
 * ⚠️ 宽度唯一真源仍然是本组件的 `__content`（ADR-0007 Q11），view 里
 *    永远不要写 max-width。页面要更宽，只在这里加一档，由路由 meta 开启。
 *    默认 false —— 未标记的页面像素级不变。
 */
const wide = computed(() => route.meta?.wide === true)

/** 顶栏标题：直接取上游约定的 route.meta.title，不再另起一套命名 */
const title = computed(() => route.meta?.title || '')

/** 模块编号 chip（M-xx / W-xx），与 AdminLayout 的同类展示保持一致 */
const moduleCode = computed(() => route.meta?.code || '')

/** 当前路由是否为 mobile-only（ADR-0008） */
const mobileOnly = computed(() => route.meta?.mobileOnly === true)

/**
 * 顶栏返回按钮（未显式传 back 时的推断规则）：
 * 判据沿用移动端 `:nav="{ back: ... }"` 的既有等价物 —— `route.meta.elderlyHidden`：
 * 该标记表示"不进主菜单的二级页"（下单三步 / 订单详情 / 投诉 / 资质入驻 …）。
 */
const showBack = computed(() => {
  if (props.back !== null) return props.back
  const meta = route.meta || {}
  return meta.elderlyHidden === true || Boolean(meta.back)
})

function goBack() {
  if (window.history.length > 1) window.history.back()
  else window.location.assign('/')
}
</script>

<template>
  <div class="nl-desktop-shell">
    <header class="nl-desktop-shell__topbar">
      <div :class="['nl-desktop-shell__inner', { 'nl-desktop-shell__inner--wide': wide }]">
        <div class="nl-desktop-shell__left">
          <button
            v-if="showBack"
            class="nl-desktop-shell__back"
            aria-label="返回"
            @click="goBack"
          >
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M15 6l-6 6 6 6" />
            </svg>
          </button>

          <span class="nl-desktop-shell__logo">银</span>
          <h1 class="nl-desktop-shell__title">{{ title }}</h1>
          <span v-if="moduleCode" class="nl-desktop-shell__code">{{ moduleCode }}</span>
        </div>

        <div class="nl-desktop-shell__right">
          <slot name="actions" />
          <!--
            通用用户菜单：桌面形态下所有 view 都会自动带「头像 + 退出登录」。
            在 login/register/forget 等公开路由下，因为 NlDesktopShell 不被这些路由使用
            （它们走 NlPageShell，NlPageShell 内部已通过 route.meta.public 屏蔽），
            所以这里可以无条件渲染。
          -->
          <NlUserMenu />
        </div>
      </div>
    </header>

    <main class="nl-desktop-shell__body">
      <div :class="['nl-desktop-shell__content', { 'nl-desktop-shell__content--wide': wide }]">
        <NlMobileOnlyNotice v-if="mobileOnly" :route-title="title" />
        <slot v-else />
      </div>
    </main>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-desktop-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--nl-bg);

  /* ---------------- 顶栏（Q9：简化顶栏 64px） ---------------- */
  &__topbar {
    position: sticky;
    top: 0;
    z-index: 20;
    background: var(--nl-bg-card);
    border-bottom: 1px solid var(--nl-divider);
  }

  &__inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: $nl-space-4;
    height: $header-height; // 64px
    /* 顶栏内容同样居中限宽，避免 1920 屏上 Logo 与标题被推到两端 */
    max-width: $nl-desktop-content-width;
    padding: 0 $nl-space-6;
    margin: 0 auto;
  }

  &__left {
    display: flex;
    gap: $nl-space-3;
    align-items: center;
    min-width: 0;
  }

  &__back {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    color: var(--nl-text-1);
    cursor: pointer;
    background: transparent;
    border: none;
    border-radius: 50%;
    transition: background 0.15s;

    &:hover {
      background: var(--nl-primary-ghost);
    }
  }

  &__logo {
    display: inline-flex;
    flex-shrink: 0;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    font-size: var(--nl-font-h2);
    font-weight: 700;
    color: var(--nl-text-inverse);
    background: var(--nl-primary-gradient);
    border-radius: 50%;
  }

  &__title {
    overflow: hidden;
    font-size: var(--nl-font-h2);
    font-weight: 600;
    color: var(--nl-text-1);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__code {
    flex-shrink: 0;
    padding: 2px 8px;
    font-size: var(--nl-font-caption);
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border: 1px solid var(--nl-border);
    border-radius: $nl-radius-chip;
  }

  &__right {
    display: flex;
    flex-shrink: 0;
    gap: $nl-space-2;
    align-items: center;
  }

  /* ---------------- 内容区（Q2 居中限宽 / Q11 宽度写死在这里） ---------------- */
  &__body {
    flex: 1;
  }

  &__content {
    display: flex;
    flex-direction: column;
    gap: $nl-space-5;
    box-sizing: border-box;
    /* ⚠️ 全站桌面形态的宽度唯一真源：view 内不得再写 max-width */
    max-width: $nl-desktop-content-width; // 720px（768–1279 档；≥1280 档见文件尾部双栏规则）
    min-height: calc(100vh - #{$header-height});
    padding: $nl-space-6; // 24px
    margin: 0 auto;
  }
}
</style>

<style lang="scss">
@use '@/styles/variables.scss' as *;

/* ==========================================================================
   ≥1280 双栏（ADR-0007 「2026-10 变更」）

   ⚠️ 这段规则**刻意不加 scoped**：双栏要作用到默认插槽里的子元素（.nl-card 等），
   而插槽内容是 view 编译的，只携带 view 自己的 scope id，本组件的 scoped 选择器
   匹配不到它们（scoped + :deep 也能做，但 `:deep(> *)` 直接子元素语义依赖编译器
   实现细节，非 scoped 全局块更直白可控）。选择器全部带 .nl-desktop-shell__ 前缀，
   不会泄漏到别处。

   flex-wrap 配对规则（为什么不用 grid）：
     · 所有直接子元素 flex-basis: 100%（通栏）：筛选条 / 列表 / 骨架 / 空态 / 免责条 / CTA
     · 相邻 .nl-card 用 calc(50% - 10px) 基宽两两并排；
       同一行内的剩余空间按 flex-grow 均分 —— **孤卡自动撑满整行**，
       不会出现「半宽卡片 + 右侧空洞」；卡片不够并排宽度时自动换行回通栏。
     · 读序保持从左到右、从上到下，与单列时一致。
   ========================================================================== */
@media (min-width: 1280px) {
  /* 双类名抬高特异性：scoped 块的选择器是 `.nl-desktop-shell__inner[data-v-x]`（0,2,0），
     非 scoped 单类名（0,1,0）会输掉 max-width 覆盖；`.nl-desktop-shell .xxx` 同为（0,2,0），
     且本 style 块在 SFC 内位于 scoped 块之后，注入顺序保证同特异性时后到胜出。 */
  .nl-desktop-shell .nl-desktop-shell__inner {
    max-width: $nl-desktop-content-wide; // 顶栏与内容区同宽对齐
  }

  .nl-desktop-shell .nl-desktop-shell__content {
    flex-flow: row wrap;
    max-width: $nl-desktop-content-wide;

    > * {
      flex: 1 1 100%;
      min-width: 0;
    }

    > .nl-card {
      flex: 1 1 calc(50% - #{$nl-space-5} / 2);
      min-width: $nl-desktop-col-min;
    }
  }

  /* 宽档（M13 聊天室三栏）：与内层顶栏一起放宽并保持同宽对齐。
     `margin: 0 auto` 已在 __content 上，1280–1440 之间视口会自然留白居中，
     不会溢出。 */
  .nl-desktop-shell .nl-desktop-shell__inner--wide {
    max-width: $nl-desktop-content-chat;
  }

  .nl-desktop-shell .nl-desktop-shell__content--wide {
    max-width: $nl-desktop-content-chat;
  }
}
</style>
