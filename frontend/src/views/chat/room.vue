<script setup>
/** M-27 订单聊天室；订单参数保持响应式，ELDER 仅浏览。 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NlPageShell } from '@/components'
import { useUserStore } from '@/store/modules/user'
import { useResponsive } from '@/composables/useResponsive'
import NlChatPanel from '@/components/chat/NlChatPanel.vue'
import NlChatConversations from '@/components/chat/NlChatConversations.vue'
const route = useRoute()
const router = useRouter()
const user = useUserStore()
const { isLg } = useResponsive()
const list = ref(null)
const orderId = computed(() => String(route.params.orderId || ''))
const writer = computed(() => ['FAMILY', 'COMPANION'].includes(user.role))
</script>

<template>
  <NlPageShell title="订单沟通">
    <template #actions>
      <el-button v-if="writer" text @click="router.push('/chat')">会话列表</el-button>
    </template>
    <div :class="['chat-room', { 'is-split': writer && isLg }]">
      <NlChatConversations
        v-if="writer && isLg"
        ref="list"
        :selected-id="orderId"
        @select="(id) => router.push({ name: 'ChatRoom', params: { orderId: id } })"
      />
      <NlChatPanel
        :order-id="orderId"
        :read-only="!writer"
        @read="list?.refresh()"
        @sent="list?.refresh()"
      />
    </div>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;
.chat-room {
  display: grid;
  gap: var(--nl-space-4);
  min-width: 0;
  padding: 0 var(--nl-gutter);
  align-items: start;
}
@media (min-width: 1280px) {
  .chat-room {
    padding: 0;
  }
  .chat-room.is-split {
    grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
  }
}
</style>
