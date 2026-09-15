<script setup>
import { onMounted } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useAppStore } from '@/store/modules/app'

/**
 * 应用根组件。
 *
 * el-config-provider 是适老化的总开关：
 *   - locale  固定中文
 *   - size    老人模式下切到 large，Element Plus 组件整体变大
 * 字号与对比度由 styles/elderly.scss 覆盖 CSS 变量实现。
 */
const appStore = useAppStore()

onMounted(() => {
  appStore.initElderlyMode()
})
</script>

<template>
  <el-config-provider :locale="zhCn" :size="appStore.elementSize">
    <router-view />
  </el-config-provider>
</template>
