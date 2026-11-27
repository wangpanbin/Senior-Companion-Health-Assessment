<!--
  老人端 · 站内信 —— ⚠️ **死文件，改这里不会生效**

  路由 `/elder/message` 在 routes.js 里**直连** `views/companion/message.vue`
  （见 docs/agents/FRONTEND_CONTRACT.md 的页面表），本文件没有任何引用点。
  改文案 / 改样式 / 加功能一律去 `views/companion/message.vue`（3 个角色共用那一份）。

  保留原因：只为 IDE 跳转不断链；删文件属红线操作，需单独授权
  （见 docs/plan/ui-rework.md「范围外 · 死代码清理」）。
  最终复盘 FE-06 记录：新人在这里改完看不到变化，会误判成「缓存没清」。
-->
<script setup>
import CompanionMessage from '@/views/companion/message.vue'
</script>

<template>
  <CompanionMessage />
</template>
