<script setup>
/**
 * 403 无权限（P1-6）
 *
 * 越权时路由守卫不再静默弹回角色主页，而是带 ?from=<原路径> 落到本页，
 * 让用户明确知道「是权限被拦住了」，而不是「点了没反应」。
 *
 * 「回首页」的目标取自 router/index.js 导出的 roleHomePath ——
 * ROLE_HOME 只有那一份真源，本页不另抄一份常量。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NlEmpty from '@/components/NlEmpty.vue'
import { roleHomePath } from '@/router/index.js'
import { useUserStore } from '@/store/modules/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 被拦截的原路径（守卫带的 ?from=），只用于说明，不做二次跳转 */
const fromPath = computed(() => {
  const from = route.query.from
  return typeof from === 'string' ? from : ''
})

const description = computed(() => {
  const base = '你没有权限访问该页面，如需开通请联系管理员。'
  return fromPath.value ? `${base}（被拦截的地址：${fromPath.value}）` : base
})

/** 已登录 → 回该角色主页；未登录 → 去登录页 */
const homeLabel = computed(() => (userStore.isLogin ? '回首页' : '去登录'))

function goHome() {
  router.replace(roleHomePath(userStore.role))
}
</script>

<template>
  <div class="error-403">
    <NlEmpty
      type="403"
      title="无权限访问"
      :description="description"
    >
      <template #action>
        <el-button type="primary" round @click="goHome">{{ homeLabel }}</el-button>
        <el-button round @click="router.back()">返回上一页</el-button>
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
