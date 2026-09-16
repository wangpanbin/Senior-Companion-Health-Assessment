<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
function goHome() { router.push('/') }
</script>

<template>
  <div class="not-found">
    <div class="not-found__box">
      <div class="not-found__code">404</div>
      <div class="not-found__title">页面走丢了</div>
      <div class="not-found__desc">您访问的地址不存在，或者您没有权限查看。</div>
      <el-button type="primary" size="large" round @click="goHome">回到首页</el-button>
    </div>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--nl-bg);

  &__box {
    text-align: center;
    padding: $nl-space-5;
  }

  &__code {
    font-size: 96px;
    font-weight: 800;
    line-height: 1;
    color: var(--nl-primary);
    opacity: 0.25;
  }

  &__title {
    margin-top: 8px;
    font-size: 24px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__desc {
    margin: 12px 0 $nl-space-6;
    color: var(--nl-text-2);
  }
}
</style>
