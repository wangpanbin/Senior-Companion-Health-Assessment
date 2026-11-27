<!--
  家属端 · 站内信 —— ⚠️ **死文件，改这里不会生效**

  路由 `/family/message` 在 routes.js 里**直连** `views/companion/message.vue`，
  本文件没有任何引用点。改文案 / 改样式 / 加功能一律去 `views/companion/message.vue`。
  保留原因与删除授权口径同 `elder/message.vue`（死文件，需单独授权才能删）。
-->
<script setup>
import CompanionMessage from '@/views/companion/message.vue'
</script>

<template>
  <CompanionMessage />
</template>
