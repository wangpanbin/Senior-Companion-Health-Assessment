<script setup>
/**
 * NlMobileOnlyNotice · mobile-only 路由在桌面形态下的提示页
 *
 * 依据：`docs/adr/0008-mobile-only-pages.md` · `docs/plan/desktop-adapt-2026-09.md` §2-D
 *
 * 由 `NlDesktopShell` 在检测到 `route.meta.mobileOnly === true` 时整体渲染，
 * **view 内部不为此写 v-if**（计划 §2 关键约束 5）。
 *
 * 结构：居中卡片 420×320 = 图标 + 标题 + 描述 + 二维码占位 + "继续查看"
 *
 * "继续查看" 的行为（计划 §2-D）：
 *   写 `sessionStorage['force-mobile'] = 'true'` → 刷新 → `useDevice().isMobile` 恒真
 *   → 同一 URL 降级渲染移动形态。关闭标签页后该标记自动失效。
 *
 * ⚠️ 合规：不出现"建议服用 / 推荐剂量 / 诊断为"等表述；此处只讲形态与设备能力。
 */
import { ElMessage } from 'element-plus'
import { forceMobile } from '@/composables/useDevice'

defineProps({
  /** 顶栏标题（由 NlDesktopShell 透传 route.meta.title） */
  routeTitle: { type: String, default: '' }
})

function continueOnDesktop() {
  forceMobile()
  ElMessage.info('已切换到手机版视图')
  // 刷新才能让所有已挂载 view 重新读取形态，故不用 router.replace
  window.location.reload()
}
</script>

<template>
  <div class="nl-mobile-only">
    <div class="nl-mobile-only__card">
      <div class="nl-mobile-only__icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <rect x="7" y="2" width="10" height="20" rx="2.5" />
          <path d="M11 18.5h2" />
        </svg>
      </div>

      <h2 class="nl-mobile-only__title">请在手机上使用{{ routeTitle ? `「${routeTitle}」` : '此功能' }}</h2>

      <p class="nl-mobile-only__desc">
        该功能依赖手机的拍照上传 / 实时定位等能力，电脑上无法完成，因此未做桌面适配。
        你可以用手机扫描下方二维码继续，或点击下方按钮在电脑上以手机版视图查看。
      </p>

      <div class="nl-mobile-only__qr" aria-label="二维码占位（后续接入真实地址）">
        <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="3" width="7" height="7" rx="1" />
          <rect x="14" y="3" width="7" height="7" rx="1" />
          <rect x="3" y="14" width="7" height="7" rx="1" />
          <path d="M14 14h3v3h-3zM20 14v1M14 20h1M18 18h3v3h-3z" />
        </svg>
        <span class="nl-mobile-only__qr-text">二维码占位</span>
      </div>

      <el-button type="primary" round @click="continueOnDesktop">继续查看（手机版视图）</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-mobile-only {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;

  &__card {
    display: flex;
    flex-direction: column;
    gap: $nl-space-3;
    align-items: center;
    box-sizing: border-box;
    width: 420px;
    height: 320px;
    padding: $nl-space-5 $nl-space-6;
    text-align: center;
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    box-shadow: var(--nl-shadow-card);
  }

  &__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 64px;
    height: 64px;
    color: var(--nl-primary);
    background: var(--nl-primary-light);
    border-radius: 50%;
  }

  &__title {
    margin: 0;
    font-size: $nl-font-h2;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__desc {
    flex: 1;
    margin: 0;
    overflow: hidden;
    font-size: $nl-font-caption;
    line-height: 1.6;
    color: var(--nl-text-2);
  }

  &__qr {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    padding: 6px $nl-space-3;
    color: var(--nl-text-3);
    background: var(--nl-bg-sunken);
    border: 1px dashed var(--nl-border-strong);
    border-radius: $nl-radius-chip;
  }

  &__qr-text {
    font-size: $nl-font-micro;
  }
}
</style>
