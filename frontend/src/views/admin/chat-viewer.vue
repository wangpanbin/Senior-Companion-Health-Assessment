<script setup>
/** W-09 仲裁查看器：只检索和阅读，复用聊天室 GET / WS，不调用发送 / 已读 POST。 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NlEmpty } from '@/components'
import NlChatConversations from '@/components/chat/NlChatConversations.vue'
import NlChatPanel from '@/components/chat/NlChatPanel.vue'
const route = useRoute()
const router = useRouter()
const orderId = computed(() =>
  /^\d+$/.test(String(route.query.orderId || '')) ? String(route.query.orderId) : ''
)
function select(id) {
  router.replace({ name: 'AdminChatViewer', query: { orderId: id } })
}
</script>

<template>
  <div class="admin-chat">
    <NlChatConversations admin :selected-id="orderId" @select="select" />
    <NlChatPanel v-if="orderId" :order-id="orderId" read-only />
    <NlEmpty v-else title="选择订单查看沟通记录" description="仅用于仲裁查看，管理员不可发送消息" />
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;
.admin-chat {
  display: grid;
  gap: var(--nl-space-4);
  align-items: start;
  min-width: 0;
}
@media (min-width: 1280px) {
  .admin-chat {
    grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
  }
}
</style>
