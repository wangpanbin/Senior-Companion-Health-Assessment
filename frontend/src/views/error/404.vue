<script setup>
/**
 * 404 页面不存在（catch-all 兜底）
 *
 * 路由表末尾的 /:pathMatch(.*)* 会把所有未知路径 redirect 到这里（routes.js），
 * 本页是独立全屏页：不进任何布局，未登录也可见（meta.public）。
 *
 * 行为约定（与 403 页同口径）：
 *   - 主按钮按登录态分流：已登录 → roleHomePath(role) 角色主页，未登录 → /login；
 *     ROLE_HOME 真源在 router/index.js，本页不另抄一份。
 *   - 「返回上一页」只在真有来路时渲染（window.history.state.back）——
 *     新标签页直接打开坏地址时没有上一页可回，按钮藏掉而不是「点了没反应」。
 *   - 装饰动效（漂浮的指路牌场景）在老人模式下的关闭规则写在全局
 *     elderly.scss（Vue scoped 里 :global() 会丢后代选择器，踩过坑），
 *     本组件只负责 prefers-reduced-motion。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { roleHomePath } from '@/router/index.js'
import { useUserStore } from '@/store/modules/user'

const router = useRouter()
const userStore = useUserStore()

/** 已登录 → 「回到首页」；未登录 → 「去登录」（文案与去向都按登录态切换） */
const homeLabel = computed(() => (userStore.isLogin ? '回到首页' : '去登录'))

/**
 * 有没有可回的上一页。
 *
 * vue-router 的 history.state 形如 { back, current, forward, position, ... }：
 * 从站内点到坏路径（经 catch-all redirect）时 back 是来路；
 * 新标签页直接打开坏地址时 back 为 null → 没有可回的页面。
 */
const canGoBack = computed(() => {
  const back = window.history.state?.back
  return typeof back === 'string' && back.length > 0
})

function goHome() {
  if (userStore.isLogin) {
    router.push(roleHomePath(userStore.role))
  } else {
    router.push('/login')
  }
}
</script>

<template>
  <div class="nl-404">
    <div class="nl-404__box">
      <!-- 装饰场景：迷路虚线圆 + 双向指路牌 + 走远的爪印（线性描边，同 NlEmpty 画法） -->
      <svg
        class="nl-404__svg"
        viewBox="0 0 120 120"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        aria-hidden="true"
      >
        <circle
          cx="60"
          cy="61"
          r="45"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-dasharray="3 7"
          stroke-linecap="round"
        />
        <path d="M60 38v46" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
        <path
          d="M58 40H35l-5.5 6.5L35 53h23z"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linejoin="round"
        />
        <path
          d="M62 63h23l5.5 6.5L85 76H62z"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linejoin="round"
        />
        <text class="nl-404__q" x="44" y="51" text-anchor="middle">?</text>
        <text class="nl-404__q" x="74" y="74" text-anchor="middle">?</text>
        <!-- 两枚爪印：来了，又走远了 -->
        <ellipse cx="31" cy="86" rx="4.5" ry="3.5" stroke="currentColor" stroke-width="1.5" />
        <circle cx="28" cy="80" r="1.6" fill="currentColor" />
        <circle cx="32" cy="78.6" r="1.6" fill="currentColor" />
        <circle cx="35.6" cy="81" r="1.6" fill="currentColor" />
        <ellipse
          cx="42"
          cy="94"
          rx="4.5"
          ry="3.5"
          transform="rotate(18 42 94)"
          stroke="currentColor"
          stroke-width="1.5"
        />
        <circle cx="38.4" cy="88.6" r="1.6" fill="currentColor" />
        <circle cx="42.6" cy="87.4" r="1.6" fill="currentColor" />
        <circle cx="46" cy="90.2" r="1.6" fill="currentColor" />
      </svg>
      <div class="nl-404__code">404</div>
    </div>
    <h1 class="nl-404__title">页面走丢了</h1>
    <p class="nl-404__desc">您访问的地址不存在或已下线，请检查地址是否输入正确。</p>
    <div class="nl-404__actions">
      <el-button type="primary" size="large" round @click="goHome">{{ homeLabel }}</el-button>
      <el-button v-if="canGoBack" size="large" round @click="router.back()">返回上一页</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-404 {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: $nl-space-5 $nl-space-4;
  background: var(--nl-bg);
  text-align: center;

  &__box {
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  &__svg {
    width: 132px;
    height: 132px;
    color: var(--nl-text-3);
    animation: nl-404-float 6s ease-in-out infinite;
  }

  /* 指路牌上的问号：唯一的品牌色点缀 */
  &__q {
    font-size: 13px;
    font-weight: 700;
    fill: var(--nl-primary);
  }

  &__code {
    margin-top: $nl-space-3;
    font-size: 56px;
    font-weight: 800;
    line-height: 1;
    letter-spacing: 4px;
    color: var(--nl-primary);
  }

  &__title {
    margin: $nl-space-4 0 0;
    font-size: var(--nl-font-display);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__desc {
    /* 30em：桌面端一行放下整句（避免「输/入」被截断），移动端由视口宽度自然换行 */
    max-width: 30em;
    margin: var(--nl-space-2) 0 $nl-space-6;
    font-size: var(--nl-font-body);
    color: var(--nl-text-2);
  }

  &__actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--nl-space-3);
    align-items: center;
    justify-content: center;
  }
}

@keyframes nl-404-float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-6px);
  }
}

/* 尊重系统「减少动态效果」设置。
   老人模式的关闭规则不在这里 —— 必须写全局 elderly.scss，原因见其 nl-404 段注释。 */
@media (prefers-reduced-motion: reduce) {
  .nl-404__svg {
    animation: none;
  }
}
</style>
