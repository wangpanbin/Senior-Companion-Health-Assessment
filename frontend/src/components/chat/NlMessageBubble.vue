<script setup>
/** M13 气泡：文本一律插值转义，姓名不从订单详情豁免口径获取。 */
import { computed } from 'vue'
import { formatDateTime } from '@/utils/format'
import { chatMediaUrl } from '@/utils/chat'
import NlChatVideo from './NlChatVideo.vue'
const props = defineProps({
  message: { type: Object, required: true },
  mine: { type: Boolean, default: false }
})
const mediaUrl = computed(() => chatMediaUrl(props.message.fileUrl))
const roleLabel = computed(() => (props.message.senderRole === 'FAMILY' ? '家属' : '陪诊员'))
</script>

<template>
  <article :class="['chat-bubble', { 'is-mine': mine }]" :data-message-id="message.messageId">
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
  </article>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;
.chat-bubble {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--nl-space-1);
  margin-bottom: var(--nl-space-4);
  &__meta {
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }
  &__content {
    max-width: 85%;
    min-width: 0;
    padding: var(--nl-space-3);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    background: var(--nl-bg-card);
    color: var(--nl-text-1);
    font-size: var(--nl-font-body);
    overflow-wrap: anywhere;
  }
  &__text {
    margin: 0;
    white-space: pre-wrap;
    line-height: var(--nl-lh-body);
  }
  &__image {
    display: block;
    max-width: 100%;
    min-height: var(--nl-touch-min);
    max-height: 40vh;
    cursor: zoom-in;
  }
  &.is-mine {
    align-items: flex-end;
  }
  &.is-mine &__content {
    background: var(--nl-primary-ghost);
    border-color: var(--nl-primary-light);
  }
}
</style>
