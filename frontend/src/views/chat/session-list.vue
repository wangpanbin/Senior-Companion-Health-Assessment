<script setup>
/** M-28 我的订单会话：桌面分栏、手机进入独立聊天室。 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NlPageShell, NlEmpty } from '@/components'
import { useDevice } from '@/composables/useDevice'
import NlChatConversations from '@/components/chat/NlChatConversations.vue'
import NlChatPanel from '@/components/chat/NlChatPanel.vue'
const route = useRoute()
const router = useRouter()
const { isMobile } = useDevice()
const list = ref(null)
const selectedId = computed(() =>
  /^\d+$/.test(String(route.query.orderId || '')) ? String(route.query.orderId) : ''
)
function select(orderId) {
  if (isMobile.value) router.push({ name: 'ChatRoom', params: { orderId } })
  else router.replace({ name: 'ChatSessions', query: { orderId } })
}
</script>

<template>
  <NlPageShell title="订单沟通">
    <div class="chat-sessions" :class="{ 'has-selection': selectedId }">
      <NlChatConversations ref="list" :selected-id="selectedId" @select="select" />
      <NlChatPanel
        v-if="selectedId"
        :order-id="selectedId"
        @read="list?.refresh()"
        @sent="list?.refresh()"
      />
      <NlEmpty
        v-else-if="!isMobile"
        title="选择一个订单开始沟通"
        description="每个订单独立记录，便于查看陪诊安排"
      />
    </div>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;
.chat-sessions {
  display: grid;
  gap: var(--nl-space-4);
  min-width: 0;
  padding: 0 var(--nl-gutter);
  align-items: start;
}
@media (min-width: 1280px) {
  .chat-sessions {
    grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
    padding: 0;
  }
}
</style>
