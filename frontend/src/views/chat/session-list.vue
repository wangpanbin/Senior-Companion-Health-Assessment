<script setup>
/**
 * M-28 我的订单会话：桌面三栏、手机进入独立聊天室。
 *
 * 三栏与会话详情页共用同一套宽度比例与折叠卡组件，
 * 避免同一个模块在两个页面长得不一样。
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NlPageShell, NlEmpty } from '@/components'
import { useDevice } from '@/composables/useDevice'
import { useResponsive } from '@/composables/useResponsive'
import { useOrderInfo } from '@/composables/useOrderInfo'
import NlChatConversations from '@/components/chat/NlChatConversations.vue'
import NlChatPanel from '@/components/chat/NlChatPanel.vue'
import NlOrderInfoPanel from '@/components/chat/NlOrderInfoPanel.vue'

const route = useRoute()
const router = useRouter()
const { isMobile } = useDevice()
const { isLg } = useResponsive()
const list = ref(null)
const selectedId = computed(() =>
  /^\d+$/.test(String(route.query.orderId || '')) ? String(route.query.orderId) : ''
)
const info = useOrderInfo(selectedId)

function select(orderId) {
  if (isMobile.value) router.push({ name: 'ChatRoom', params: { orderId } })
  else router.replace({ name: 'ChatSessions', query: { orderId } })
}
</script>

<template>
  <NlPageShell title="订单沟通">
    <div :class="['chat-sessions', { 'has-selection': selectedId }]">
      <NlChatConversations ref="list" :selected-id="selectedId" @select="select" />

      <div v-if="selectedId" class="chat-sessions__main">
        <details v-if="!isLg" class="chat-sessions__fold">
          <summary class="chat-sessions__fold-head">
            <NlOrderInfoPanel :flow="info" collapsed />
          </summary>
          <div class="chat-sessions__fold-body">
            <NlOrderInfoPanel :flow="info" />
          </div>
        </details>

        <NlChatPanel
          :order-id="selectedId"
          :peer-name="info.order.value?.companionName || ''"
          :sender-name="info.order.value?.companionName || ''"
          @read="list?.refresh()"
          @sent="list?.refresh()"
        />
      </div>

      <NlEmpty
        v-else-if="!isMobile"
        title="选择一个订单开始沟通"
        description="每个订单独立记录，便于查看陪诊安排"
      />

      <aside v-if="isLg && selectedId" class="chat-sessions__aside">
        <NlOrderInfoPanel :flow="info" />
      </aside>
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

.chat-sessions__main {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-4);
  min-width: 0;
}

.chat-sessions__aside {
  min-width: 0;
  padding: var(--nl-space-4);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  box-shadow: $nl-shadow-card;
}

.chat-sessions__fold {
  overflow: hidden;
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  box-shadow: $nl-shadow-card;
}

.chat-sessions__fold-head {
  display: flex;
  align-items: center;
  min-height: var(--nl-touch-min);
  padding: 0 var(--nl-space-3);
  cursor: pointer;
  list-style: none;

  &::-webkit-details-marker {
    display: none;
  }

  &::after {
    width: 8px;
    height: 8px;
    margin-left: var(--nl-space-2);
    content: '';
    border-right: 2px solid var(--nl-text-3);
    border-bottom: 2px solid var(--nl-text-3);
    transform: rotate(-45deg);
    transition: transform 0.2s ease;
  }

  &:focus-visible {
    outline: 2px solid var(--nl-primary);
    outline-offset: -2px;
  }
}

.chat-sessions__fold[open] .chat-sessions__fold-head::after {
  transform: rotate(45deg);
}

.chat-sessions__fold-body {
  padding: 0 var(--nl-space-4) var(--nl-space-4);
}

@media (min-width: 1280px) {
  .chat-sessions {
    grid-template-columns: 300px minmax(0, 1fr) 360px;
    padding: 0;
  }

  /* 未选中订单时右侧无内容，退回两栏 */
  .chat-sessions:not(.has-selection) {
    grid-template-columns: 300px minmax(0, 1fr);
  }
}
</style>
