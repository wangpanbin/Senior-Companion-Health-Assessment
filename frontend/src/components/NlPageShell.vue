<script setup>
/**
 * NlPageShell · 形态自适应的页面壳（Mobile ⇄ Desktop 二选一）
 *
 * 依据：`docs/adr/0007-desktop-form-factor.md` · `docs/plan/desktop-adapt-2026-09.md` Q3 = b
 *
 * ## 为什么需要它（以及它和 NlPhoneShell / NlDesktopShell 的分工）
 *
 * 计划的 Stage 2 伪代码是"每个 view 写两遍模板：`<NlPhoneShell v-if="isMobile">`
 * 一份、`<NlDesktopShell v-else>` 再一份"。这要求把整段业务模板**复制两遍**
 * （`family/medication.vue` 有 959 行），复制后还要各自维护 —— 明显不可持续。
 *
 * 本组件把「选哪个壳」这件事收进一层：view 只写**一份**正文，
 * 由 NlPageShell 决定外面套 Mobile 壳还是 Desktop 壳。
 * 于是每个 view 的迁移成本降到 3 行（改 import + 改首尾标签）。
 *
 * ```
 *   NlPageShell
 *     ├─ isMobile  → NlPhoneShell   (状态栏 + NavBar + 正文 + 固定 CTA)
 *     └─ 否则      → NlDesktopShell (64px 顶栏 + 正文居中限宽 720 + 正文内联 CTA)
 * ```
 *
 * ## 用法（view 侧只改最外层）
 *
 * ```vue
 * <NlPageShell title="我的订单">
 *   <NlCard>…</NlCard>
 * </NlPageShell>
 * ```
 *
 * 带底部主行动按钮的流程页：
 *
 * ```vue
 * <NlPageShell title="预约陪诊" has-cta>
 *   <NlCard>…</NlCard>
 *   <template #cta><el-button type="primary" size="large" round>下一步</el-button></template>
 * </NlPageShell>
 * ```
 *
 * ## 三个 props 的语义
 *
 * - `title`  —— 对应原来的 `:nav="{ title }"`；桌面端直接显示在顶栏
 * - `back`   —— 对应原来的 `:nav="{ back }"`，**默认 true**（与原 NlPhoneShell 一致：
 *               `:back="nav.back !== false"`）。桌面端默认也显示返回箭头，
 *               否则 `/family/medication` 这类非 `elderlyHidden` 的二级页在桌面端会没有回退入口。
 * - `hasCta` —— 对应原来的 `:has-cta`。手机端是底部固定条；桌面端改为**正文末尾内联**，
 *               避免在 720px 居中列上再叠一条通栏固定条。
 *
 * ## 注意
 *
 * - `#actions` 会同时投到手机端的 NavBar 右侧和桌面端的顶栏右侧。
 * - 本组件**不含** TabBar 能力：目前只有 3 个首页用底部 TabBar，它们直接组合
 *   `NlPhoneShell` / `NlDesktopShell` 并各自设计了桌面端导航。
 *   若将来有页面需要"移动端底部 Tab + 桌面端顶部导航"，在本组件上补 `tabs` 支持即可。
 */
import { computed, useSlots } from 'vue'
import { useRoute } from 'vue-router'
import { useDevice } from '@/composables/useDevice'
import NlPhoneShell from './NlPhoneShell.vue'
import NlDesktopShell from './NlDesktopShell.vue'
import NlUserMenu from './NlUserMenu.vue'

const props = defineProps({
  /** 页面标题（原 nav.title） */
  title: { type: String, default: '' },
  /** 是否显示返回（原 nav.back；默认 true） */
  back: { type: Boolean, default: true },
  /** 是否有底部主行动按钮（原 has-cta） */
  hasCta: { type: Boolean, default: false },
  /** 状态栏时间（仅手机形态，原样透传） */
  statusTime: { type: String, default: '' },
  /** 状态栏反色（仅手机形态，原样透传） */
  statusInverse: { type: Boolean, default: false },
  /** 内容区背景（仅手机形态，原样透传） */
  bodyBg: { type: String, default: '' }
})

const slots = useSlots()
const route = useRoute()
const { isMobile } = useDevice()

/**
 * 移动端 NavBar 是否露出用户菜单。
 * 公开路由（登录 / 注册 / 找回密码）走的是同一个 NlPageShell，但此时 userStore.isLogin === false，
 * 露出「退出登录」反而误导：用户还没登录，退出什么？故此处根据 route.meta.public 屏蔽。
 */
const showUserMenu = computed(() => route.meta?.public !== true)

/**
 * ⚠️ 这里必须是 `hasCta && 有 cta 插槽`：
 * 本组件**无条件**转发 `#cta`（写成条件插槽模板 `<template v-if #cta>` 有编译不确定性），
 * 于是 NlPhoneShell 内部的 `$slots.cta` 恒为真。若不叠加 `hasCta` 一起判断，
 * 没有 CTA 的页面也会渲染出一条固定在最底部的空白条。
 */
const phoneCta = computed(() => props.hasCta && !!slots.cta)
</script>

<template>
  <!-- ==================== Mobile 形态：与原 NlPhoneShell 用法等价 ==================== -->
  <NlPhoneShell
    v-if="isMobile"
    :nav="{ title, back }"
    :has-cta="phoneCta"
    :status-time="statusTime"
    :status-inverse="statusInverse"
    :body-bg="bodyBg"
  >
    <template #nav-right>
      <slot name="actions" />
      <!--
        移动形态下的用户菜单（仅当非公开路由）。
        桌面形态下的用户菜单由 NlDesktopShell 自身提供（避免重复渲染）。
      -->
      <NlUserMenu v-if="showUserMenu" size="sm" :show-name="false" />
    </template>

    <slot />

    <template #cta>
      <slot name="cta" />
    </template>
  </NlPhoneShell>

  <!-- ==================== Desktop 形态：顶栏 + 720 居中列 ==================== -->
  <NlDesktopShell v-else :back="back">
    <template #actions>
      <slot name="actions" />
    </template>

    <slot />

    <!-- CTA 在桌面端内联在正文末尾（不 floating），并做右对齐观感 -->
    <div v-if="$slots.cta" class="nl-page-shell__cta">
      <slot name="cta" />
    </div>
  </NlDesktopShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-page-shell {
  &__cta {
    display: flex;
    justify-content: flex-end;
    padding-top: $nl-space-4;
    border-top: 1px solid var(--nl-divider);

    /* 流程页的按钮自己写了 width: 100%（手机端要通栏），
       桌面端收成自适应宽度更像桌面表单；选择器权重高于 view 里的单类名规则 */
    :deep(.el-button) {
      width: auto;
      min-width: 160px;
    }
  }
}
</style>
