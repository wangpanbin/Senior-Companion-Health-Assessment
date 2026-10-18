<script setup>
/**
 * M13 气泡：文本一律插值转义，姓名不从订单详情豁免口径获取。
 *
 * 视觉分化：我的消息右对齐、主色实心、无头像；对方左对齐、白卡带头像。
 * 消息体本身不携带姓名（`ChatMessageVO` 只有 senderRole），所以对方气泡的
 * 头像首字默认取角色文案的第一个字；调用方若已从订单详情拿到全名，
 * 可通过 `senderName` 传真实的姓名首字。
 */
import { computed } from 'vue'
import { formatDateTime } from '@/utils/format'
import { chatMediaUrl } from '@/utils/chat'
import { ROLE_LABELS } from '@/store/modules/user'
import { NlAvatar } from '@/components'
import NlChatVideo from './NlChatVideo.vue'

const props = defineProps({
  message: { type: Object, required: true },
  mine: { type: Boolean, default: false },
  /** 对方全名的首字；不传则用角色文案首字兜底。 */
  senderName: { type: String, default: '' }
})
const mediaUrl = computed(() => chatMediaUrl(props.message.fileUrl))
const roleLabel = computed(() => ROLE_LABELS[props.message.senderRole] ?? ROLE_LABELS.COMPANION)
const avatarInitial = computed(() => {
  const name = props.senderName || roleLabel.value
  return (name || '?').slice(0, 1)
})
const avatarTone = computed(() => (props.message.senderRole === 'COMPANION' ? 'primary' : 'info'))
</script>

<template>
  <article :class="['chat-bubble', { 'is-mine': mine }]" :data-message-id="message.messageId">
    <div v-if="!mine" class="chat-bubble__avatar">
      <NlAvatar :fallback="avatarInitial" :size="36" :tone="avatarTone" />
    </div>
    <div class="chat-bubble__body">
      <div class="chat-bubble__meta">
        {{ mine ? '我' : roleLabel }} · {{ formatDateTime(message.createTime) }}
      </div>
      <div class="chat-bubble__content">
        <template v-if="message.msgType === 'IMAGE'">
          <el-image
            v-if="mediaUrl"
            :src="mediaUrl"
            :preview-src-list="[mediaUrl]"
            preview-teleported
            fit="contain"
            alt="聊天图片"
            class="chat-bubble__image"
          >
            <template #error><span>图片暂不可用，请刷新后重试</span></template>
          </el-image>
          <p v-else>图片附件不可用</p>
        </template>
        <NlChatVideo v-else-if="message.msgType === 'VIDEO'" :src="mediaUrl" />
        <p v-if="message.content" class="chat-bubble__text">{{ message.content }}</p>
      </div>
    </div>
  </article>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.chat-bubble {
  display: flex;
  gap: var(--nl-space-2);
  align-items: flex-start;
  margin-bottom: var(--nl-space-4);

  &__avatar {
    flex-shrink: 0;
    padding-top: 18px; /* 与 meta 文字对齐，不与气泡顶对齐 */
  }
  &__body {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-1);
    min-width: 0;
    max-width: 82%;
  }
  &__meta {
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }
  &__content {
    min-width: 0;
    padding: var(--nl-space-3);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    /* 朝向对方一侧的角收圆，视觉上把气泡"指向"发送者 */
    border-top-left-radius: var(--nl-radius-chip);
    color: var(--nl-text-1);
    font-size: var(--nl-font-body);
    box-shadow: $nl-shadow-card;
    overflow-wrap: anywhere;
  }
  &__text {
    margin: 0;
    line-height: var(--nl-lh-body);
    white-space: pre-wrap;
  }
  &__image {
    display: block;
    max-width: 100%;
    min-height: var(--nl-touch-min);
    max-height: 40vh;
    cursor: zoom-in;
  }

  /* ---------- 我的消息：右对齐 + 主色实心 ---------- */
  &.is-mine {
    justify-content: flex-end;
  }
  &.is-mine &__body {
    align-items: flex-end;
  }
  &.is-mine &__meta {
    text-align: right;
  }
  &.is-mine &__content {
    background: var(--nl-primary);
    border-color: transparent;
    border-top-left-radius: var(--nl-radius-card);
    /* 朝向我这一侧的角收圆 */
    border-top-right-radius: var(--nl-radius-chip);
    color: var(--nl-text-inverse);
    box-shadow: var(--nl-shadow-hover);
  }
  &.is-mine &__image {
    /* 实心底上的图片要留出白边，否则和气泡糊在一起 */
    border-radius: var(--nl-radius-chip);
  }
}
</style>
