<script setup>
/**
 * NlAppTabBar · 角色感知 + 路由感知的通用底部 Tab Bar
 *
 * 为「一级 tab 页面」提供统一底部导航：每个角色的一级页常驻同一排 tab，
 * 点击直接切换到对应一级页（无需左上角返回）。
 *
 * 各角色真实 tab（以对应首页/大厅内联配置为唯一真源，param: 参考各 view）：
 *   - ELDER     首页→/elder/home  用药→/elder/medication  消息→/elder/message   我的→/profile
 *   - FAMILY    首页→/family/home 订单→/family/order  用药→/family/medication  消息→/family/message   我的→/profile
 *   - COMPANION 大厅→/companion/hall  订单→/companion/order  收入→/companion/income  我的→/profile
 *
 * 切换用 router.replace：目标 === 当前 path 则忽略；否则替换跳转（不污染 back 历史，
 * 一级页之间不再逐条累积返回栈，配合移动端 keep-alive 减少重建白屏）。
 *
 * 用法：
 *   <template #tabbar><NlAppTabBar /></template>
 *
 * 只负责渲染 TabBar 与切换跳转；不强制 has-tabs / back（由各自页面壳决定）。
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HomeFilled, FirstAidKit, Bell, User, List, Tickets, Money } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/modules/user'
import NlTabBar from './NlTabBar.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const pendingKey = ref('')

/** 每个角色的一级 tab 定义（with `to`；激活态按 path 精确匹配） */
const TABS_BY_ROLE = {
  ELDER: [
    { key: 'home', label: '首页', icon: HomeFilled, to: '/elder/home' },
    { key: 'medication', label: '用药', icon: FirstAidKit, to: '/elder/medication' },
    { key: 'message', label: '消息', icon: Bell, to: '/elder/message' },
    { key: 'profile', label: '我的', icon: User, to: '/profile' }
  ],
  FAMILY: [
    { key: 'home', label: '首页', icon: HomeFilled, to: '/family/home' },
    { key: 'order', label: '订单', icon: Tickets, to: '/family/order' },
    { key: 'medication', label: '用药', icon: FirstAidKit, to: '/family/medication' },
    { key: 'message', label: '消息', icon: Bell, to: '/family/message' },
    { key: 'profile', label: '我的', icon: User, to: '/profile' }
  ],
  COMPANION: [
    { key: 'hall', label: '大厅', icon: List, to: '/companion/hall' },
    { key: 'order', label: '订单', icon: Tickets, to: '/companion/order' },
    { key: 'income', label: '收入', icon: Money, to: '/companion/income' },
    { key: 'mine', label: '我的', icon: User, to: '/profile' }
  ]
}

const tabs = computed(() => TABS_BY_ROLE[userStore.role] || TABS_BY_ROLE.ELDER || [])

/** 从当前路由精确匹配激活的 tab key；匹配不到不高亮 */
const active = computed(() => {
  if (pendingKey.value) return pendingKey.value
  const target = tabs.value.find((t) => route.path === t.to)
  return target ? target.key : ''
})

async function onTabChange(key) {
  const target = tabs.value.find((t) => t.key === key)
  if (!target) return
  if (route.path === target.to) return // 已在目标页，不重复跳转

  // 路由组件尚未完成切换时，先反馈用户刚刚点中的 Tab，避免视觉滞后。
  pendingKey.value = key
  try {
    await router.replace(target.to)
  } finally {
    pendingKey.value = ''
  }
}
</script>

<template>
  <NlTabBar :tabs="tabs" :model-value="active" @change="onTabChange" />
</template>
