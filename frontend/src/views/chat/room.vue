<script setup>
/**
 * M-27 订单聊天室；订单参数保持响应式，ELDER 仅浏览。
 *
 * ## 三栏布局（2026-10 聊天室 UI 重构）
 *
 * ≥1280（route.meta.wide 拿到 1440px 宽档）：
 *   会话列表 │ 聊天 │ 订单信息面板
 * 768–1279 与手机端：
 *   折叠信息卡（默认收起） → 聊天
 *
 * 折叠卡与右侧栏共用 NlOrderInfoPanel，只是外层容器不同。
 * 折叠卡用原生 <details> 而不是 el-collapse：原生元素在键盘与读屏下
 * 语义正确（summary 可聚焦、可读屏播报），且不引入 Element Plus 组件
 * 在 jsdom 下的额外行为差异。
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NlPageShell } from '@/components'
import { useUserStore } from '@/store/modules/user'
import { useResponsive } from '@/composables/useResponsive'
import { useOrderInfo } from '@/composables/useOrderInfo'
import NlChatPanel from '@/components/chat/NlChatPanel.vue'
import NlChatConversations from '@/components/chat/NlChatConversations.vue'
import NlOrderInfoPanel from '@/components/chat/NlOrderInfoPanel.vue'

const route = useRoute()
const router = useRouter()
const user = useUserStore()
const { isLg } = useResponsive()
const list = ref(null)
const orderId = computed(() => String(route.params.orderId || ''))
const writer = computed(() => user.isFamily || user.isCompanion)
const info = useOrderInfo(orderId)

/**
 * 聊天头部与气泡头像用的对方姓名。
 *
 * 家属端取订单详情里的陪诊员全名；陪诊员端订单详情不返回家属姓名，
 * 此时传空串，NlChatPanel 会回落到聊天接口的脱敏名。
 */
const peerName = computed(() => (user.isFamily ? info.order.value?.companionName || '' : ''))
const senderName = computed(() => peerName.value)
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

      <div class="chat-room__main">
        <!-- <1280：折叠信息卡，默认收起，只露一行摘要 -->
        <details v-if="!isLg" class="chat-room__fold">
          <summary class="chat-room__fold-head">
            <NlOrderInfoPanel :flow="info" collapsed />
          </summary>
          <div class="chat-room__fold-body">
            <NlOrderInfoPanel :flow="info" />
          </div>
        </details>

        <NlChatPanel
          :order-id="orderId"
          :peer-name="peerName"
          :sender-name="senderName"
          :read-only="!writer"
          @read="list?.refresh()"
          @sent="list?.refresh()"
        />
      </div>

      <!-- ≥1280：右侧信息常驻栏 -->
      <aside v-if="isLg" class="chat-room__aside">
        <NlOrderInfoPanel :flow="info" />
      </aside>
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

.chat-room__main {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-4);
  min-width: 0;
}

.chat-room__aside {
  min-width: 0;
  padding: var(--nl-space-4);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  box-shadow: $nl-shadow-card;
}

/* ---------- 折叠卡 ---------- */
.chat-room__fold {
  overflow: hidden;
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  box-shadow: $nl-shadow-card;
}

.chat-room__fold-head {
  display: flex;
  align-items: center;
  min-height: var(--nl-touch-min);
  padding: 0 var(--nl-space-3);
  cursor: pointer;
  list-style: none;

  /* 隐藏 summary 的默认三角，我们自己画一个 */
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

.chat-room__fold[open] .chat-room__fold-head::after {
  transform: rotate(45deg);
}

.chat-room__fold-body {
  padding: 0 var(--nl-space-4) var(--nl-space-4);
}

/* ---------- ≥1280 三栏 ---------- */
@media (min-width: 1280px) {
  .chat-room {
    padding: 0;
  }

  .chat-room.is-split {
    grid-template-columns: 300px minmax(0, 1fr) 360px;
  }

  /* 只读角色（ELDER / 管理员仲裁）没有会话列表，两栏即可 */
  .chat-room:not(.is-split) {
    grid-template-columns: minmax(0, 1fr) 360px;
  }
}
</style>
