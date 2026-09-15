<script setup>
import ModulePlaceholder from '@/components/ModulePlaceholder.vue'

/**
 * 老人端首页。
 *
 * 定位：**极简 + 只读**。老人不做任何复杂操作，所有写操作引导家属代办。
 * 验收约束（plan.md · M2 / M11）：老人账号调用写接口必须被服务端拒绝（403）。
 */
</script>

<template>
  <div class="nl-page">
    <h2 class="nl-page__title">我的就诊</h2>

    <div class="nl-elder-hint">
      这里可以看您的就诊安排和吃药记录。如果要做修改，请让家人帮您操作。
    </div>

    <div style="margin-top: 16px">
      <ModulePlaceholder
        module="M11"
        title="老人端（适老化极简界面）"
        owner="A（前端主力）+ C（UI 走查）"
        iteration="横切 W3–W16"
        depends-on="M2 认证与鉴权、M3 用户与档案、M11 适老化体系"
        api-doc="docs/api/01-auth-user.md"
        :features="[
          '大字版首页：本次就诊安排、今天该吃什么药（只读卡片）',
          '一键呼叫家属（拨号 / 站内信）',
          '就诊进度大字时间线（几张卡片，不滚动）',
          '用药日历大字版（已吃 / 待吃 / 漏吃三色）',
          '全站只读：任何写操作按钮均不出现，服务端同步拦截'
        ]"
        :acceptance="[
          '老人登录后调用任意写接口返回 403（服务端拦截，绕过前端也无效）',
          '正文计算字号 >= 18px（DevTools 实测）',
          '主要文字对比度 >= 4.5:1',
          '核心流程点击次数 <= 3 次',
          '1366×768 与 1920×1080 下无横向滚动、无元素重叠'
        ]"
      />
    </div>
  </div>
</template>
