<script setup>
import NlEmpty from '@/components/NlEmpty.vue'
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="error-403">
    <NlEmpty
      type="403"
      title="无权限访问"
      description="当前账号没有查看此页面的权限"
    >
      <template #action>
        <el-button type="primary" round @click="router.back()">返回上一页</el-button>
      </template>
    </NlEmpty>
  </div>
</template>

<style scoped lang="scss">
.error-403 {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--nl-bg);
}
</style>
