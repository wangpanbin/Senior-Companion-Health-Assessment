<script setup>
/**
 * M13 共享聊天室（业务端 / 仲裁查看器）；路由切换清理草稿、附件与异步结果。
 *
 * ## 姓名口径
 *
 * 头部优先用父组件从订单详情取到的 `peerName`（全名），无值时才回落到
 * 聊天接口的 `metadata.peerName`（脱敏，如「李*军」）。两者同屏出现
 * 「黄*涛 / 黄建军」会自相矛盾，所以只要拿得到全名就以它为准。
 * 陪诊员端拿不到家属全名（订单详情只返回陪诊员与老人），故保留回落。
 */
import { computed, onBeforeUnmount, ref, shallowRef, toRef, watch } from 'vue'
import { NlAvatar, NlEmpty, NlNoticeBar, NlSkeleton } from '@/components'
import { useUserStore } from '@/store/modules/user'
import { useChatMessages } from '@/composables/useChatMessages'
import { sendMessage } from '@/api/chat'
import { uploadFile } from '@/api/file'
import { chatTextLength, validateChatFile } from '@/utils/chat'
import NlMessageBubble from './NlMessageBubble.vue'
import NlChatVideo from './NlChatVideo.vue'

/** 两个按钮各自的 accept 白名单：用户不可能选错类型。 */
const IMAGE_ACCEPT = 'image/jpeg,image/png,image/webp'
const VIDEO_ACCEPT = 'video/mp4'

const props = defineProps({
  orderId: { type: [String, Number], required: true },
  readOnly: { type: Boolean, default: false },
  /** 订单详情口径的对方全名；不传则回落到聊天接口的脱敏名。 */
  peerName: { type: String, default: '' },
  /** 对方全名首字，用于对方气泡的头像；不传则用角色文案首字。 */
  senderName: { type: String, default: '' }
})
const emit = defineEmits(['read', 'sent'])
const user = useUserStore()
const scroller = ref(null)
const canWrite = computed(() => !props.readOnly && (user.isFamily || user.isCompanion))
const {
  messages,
  metadata,
  ready,
  loading,
  loadError,
  syncing,
  paging,
  hasMore,
  atBottom,
  status,
  append,
  loadOlder,
  onScroll,
  scrollToBottom,
  refresh
} = useChatMessages(toRef(props, 'orderId'), scroller, canWrite, () => emit('read'))
const statusText = computed(
  () =>
    ({
      connecting: '正在连接…',
      open: '实时连接正常',
      closed: '连接中断，正在重连',
      exhausted: '实时连接已断开，消息仍会保存'
    })[status.value]
)
const draft = ref('')
const file = shallowRef(null)
const preview = ref('')
const uploaded = shallowRef(null)
const imageInput = ref(null)
const videoInput = ref(null)
const uploading = ref(false)
const sending = ref(false)
const sendError = ref('')
const length = computed(() => chatTextLength(draft.value))
const busy = computed(() => uploading.value || sending.value)
const sendable = computed(
  () =>
    ready.value &&
    canWrite.value &&
    !busy.value &&
    length.value <= 1000 &&
    (!!draft.value.trim() || !!file.value)
)

/** 头部显示名：订单详情全名优先，其次聊天接口的脱敏名。 */
const displayName = computed(() => props.peerName || metadata.value.peerName || '')
const initial = computed(() => (displayName.value || '沟').slice(0, 1))
/** 气泡头像首字：只在拿得到全名时透传，否则由气泡自己用角色文案兜底。 */
const senderName = computed(() => props.senderName || '')
let generation = 0

function clearFile() {
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  file.value = null
  uploaded.value = null
  // ⚠️ 两个 input 都要清：否则「先传图片、再传视频」时未清的那个 input
  //    保留了旧值，同名文件第二次选择不会触发 change 事件。
  if (imageInput.value) imageInput.value.value = ''
  if (videoInput.value) videoInput.value.value = ''
}
function chooseFile(event) {
  if (busy.value) return
  const chosen = event.target.files?.[0]
  if (!chosen) return
  const error = validateChatFile(chosen)
  if (error) {
    sendError.value = error
    event.target.value = ''
    return
  }
  clearFile()
  file.value = chosen
  preview.value = URL.createObjectURL(chosen)
  sendError.value = ''
}
function chooseImage() {
  if (busy.value || !ready.value) return
  imageInput.value?.click()
}
function chooseVideo() {
  if (busy.value || !ready.value) return
  videoInput.value?.click()
}
async function send() {
  if (!sendable.value) return
  const run = generation
  const orderId = props.orderId
  const content = draft.value.trim()
  const attachment = file.value
  sendError.value = ''
  try {
    if (attachment && !uploaded.value) {
      uploading.value = true
      const result = await uploadFile(attachment, 'CHAT')
      if (run !== generation) return
      if (!result?.fileId) throw new Error('上传未返回附件编号，请重试')
      uploaded.value = result
    }
    uploading.value = false
    sending.value = true
    const data = attachment
      ? {
          msgType: attachment.type === 'video/mp4' ? 'VIDEO' : 'IMAGE',
          fileId: uploaded.value.fileId,
          ...(content ? { content } : {})
        }
      : { msgType: 'TEXT', content }
    const result = await sendMessage(orderId, data)
    if (run !== generation) return
    if (!result?.messageId) throw new Error('发送结果未确认')
    await append(result)
    if (run !== generation) return
    draft.value = ''
    clearFile()
    emit('sent')
  } catch {
    if (run !== generation) return
    // 保留文件本体和文字；重试重新上传，避免附件已失效后一直复用旧 fileId。
    uploaded.value = null
    sendError.value =
      '发送未完成，文字和附件已保留。请检查后重试；若结果未确认，请先刷新记录避免重复发送。'
  } finally {
    if (run === generation) {
      uploading.value = false
      sending.value = false
    }
  }
}
watch(
  () => props.orderId,
  () => {
    generation++
    draft.value = ''
    clearFile()
    sendError.value = ''
    uploading.value = false
    sending.value = false
  }
)
onBeforeUnmount(() => {
  generation++
  clearFile()
})
</script>

<template>
  <section class="chat-panel" aria-label="订单沟通">
    <header class="chat-panel__header">
      <div class="chat-panel__identity">
        <NlAvatar :fallback="initial" :size="44" tone="inverse" />
        <div class="chat-panel__identity-text">
          <h2 class="chat-panel__name">{{ displayName || '订单沟通' }}</h2>
          <p class="chat-panel__meta">
            <span class="chat-panel__dot" :class="`is-${status}`" aria-hidden="true" />
            订单 #{{ orderId }} · {{ statusText }}
          </p>
        </div>
      </div>
      <el-button class="chat-panel__refresh" text :loading="syncing" @click="refresh">刷新记录</el-button>
    </header>
    <NlNoticeBar tone="warning">不做诊断、不开药方，紧急情况请拨打 120。</NlNoticeBar>
    <NlNoticeBar v-if="!canWrite" class="chat-panel__readonly">
      {{
        user.isAdmin
          ? '仲裁只读：仅查看沟通记录，不可发送消息。'
          : '只读：您可查看本单沟通记录，发送消息请由家属操作。'
      }}
    </NlNoticeBar>
    <NlSkeleton v-if="loading" :count="3" />
    <NlEmpty
      v-else-if="!ready && loadError"
      type="network"
      title="沟通记录加载失败"
      description="请确认订单已接单且您有查看权限"
      action-text="重试"
      @action="refresh"
    />
    <template v-else-if="ready">
      <div v-if="loadError" class="chat-panel__error" role="status">
        记录更新失败，已加载的消息仍可查看。
        <el-button text @click="refresh">重试</el-button>
      </div>
      <div
        ref="scroller"
        class="chat-panel__stream"
        role="log"
        aria-label="聊天记录"
        aria-live="polite"
        tabindex="0"
        @scroll="onScroll"
      >
        <div class="chat-panel__history">
          <el-button v-if="hasMore" text :loading="paging" :disabled="syncing" @click="loadOlder">
            加载更早消息
          </el-button>
          <span v-else-if="messages.length">已显示全部记录</span>
        </div>
        <NlEmpty
          v-if="!messages.length"
          title="暂无沟通记录"
          :description="
            canWrite ? '可以围绕本次陪诊安排发送消息' : '家属与陪诊员的沟通将显示在这里'
          "
        />
        <NlMessageBubble
          v-for="message in messages"
          :key="message.messageId"
          :message="message"
          :mine="String(message.senderId) === String(user.userInfo?.id)"
          :sender-name="senderName"
        />
      </div>
      <el-button v-if="!atBottom" class="chat-panel__latest" plain @click="scrollToBottom">
        回到最新消息
      </el-button>
    </template>
    <form v-if="canWrite" class="chat-panel__composer" aria-label="发送消息" @submit.prevent="send">
      <div v-if="file" class="chat-panel__attachment">
        <NlChatVideo v-if="file.type === 'video/mp4'" :src="preview" />
        <el-image
          v-else
          :src="preview"
          :preview-src-list="[preview]"
          preview-teleported
          fit="contain"
          alt="待发送图片"
        />
        <span>{{ file.name }}</span>
        <el-button text :disabled="busy" @click="clearFile">移除附件</el-button>
      </div>
      <el-input
        v-model="draft"
        type="textarea"
        :rows="3"
        :disabled="!ready || busy"
        aria-label="消息内容"
        placeholder="输入本次陪诊安排（最多 1000 字）"
      />
      <div class="chat-panel__tools">
        <input
          ref="imageInput"
          type="file"
          :accept="IMAGE_ACCEPT"
          aria-label="选择聊天图片"
          hidden
          :disabled="!ready || busy"
          @change="chooseFile"
        />
        <input
          ref="videoInput"
          type="file"
          :accept="VIDEO_ACCEPT"
          aria-label="选择聊天视频"
          hidden
          :disabled="!ready || busy"
          @change="chooseFile"
        />
        <span :class="['nl-caption', 'chat-panel__hint', { 'chat-panel__error': length > 1000 }]">
          {{ length }}/1000 · 附件 ≤50MB
        </span>
        <div class="chat-panel__actions">
          <el-button :disabled="!ready || busy" @click="chooseImage">图片</el-button>
          <el-button :disabled="!ready || busy" @click="chooseVideo">视频</el-button>
          <el-button native-type="submit" type="primary" :loading="busy" :disabled="!sendable">
            {{ uploading ? '上传中' : sending ? '发送中' : sendError ? '重试发送' : '发送' }}
          </el-button>
        </div>
      </div>
      <p v-if="sendError" class="chat-panel__error" role="alert">{{ sendError }}</p>
    </form>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;
.chat-panel {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  min-width: 0;
  padding: var(--nl-space-4);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  box-shadow: $nl-shadow-card;

  /* ---------- 主色渐变头部：承载对方姓名 / 订单号 / 连接状态 ---------- */
  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-3);
    padding: var(--nl-space-3) var(--nl-space-4);
    margin: calc(-1 * var(--nl-space-4)) calc(-1 * var(--nl-space-4)) 0;
    background: var(--nl-primary-gradient);
    border-radius: var(--nl-radius-card) var(--nl-radius-card) 0 0;
  }
  &__identity {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
    min-width: 0;
  }
  &__identity-text {
    min-width: 0;
  }
  &__name {
    margin: 0;
    overflow: hidden;
    color: var(--nl-text-inverse);
    font-size: var(--nl-font-h2);
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__meta {
    display: flex;
    gap: var(--nl-space-1);
    align-items: center;
    margin: 0;
    color: var(--nl-text-inverse);
    font-size: var(--nl-font-caption);
    /* 渐变底上的白字要压一档透明度才不刺眼，仍满足 AA（≥4.5:1）。 */
    opacity: 0.92;
  }
  &__dot {
    width: 6px;
    height: 6px;
    background: #fff;
    border-radius: 50%;

    &.is-open {
      background: #7ef0b0;
    }
    &.is-connecting {
      background: #ffe08a;
    }
    &.is-closed,
    &.is-exhausted {
      background: #ffc2bd;
    }
  }
  /* 头部内的按钮在深底上要反色，否则 Element Plus 的默认文字色会看不见。 */
  &__refresh {
    flex-shrink: 0;
    color: var(--nl-text-inverse);

    &:hover {
      color: var(--nl-text-inverse);
      background: rgb(255 255 255 / 16%);
    }
  }
  &__readonly {
    font-size: var(--nl-font-body);
  }
  &__stream {
    height: 46vh;
    min-height: 12rem;
    overflow-y: auto;
    overflow-anchor: none;
    padding: var(--nl-space-2);
    background: var(--nl-bg-sunken);
    border-radius: var(--nl-radius-card);
  }
  &__history {
    text-align: center;
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }
  &__composer {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-2);
  }
  /* 说明文字靠左；图片 / 视频 / 发送构成右下角动作组，按钮间留固定一档间距。 */
  &__tools {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2) var(--nl-space-3);
    flex-wrap: wrap;
  }
  &__hint {
    flex: 1 1 auto;
    min-width: 0;
  }
  &__actions {
    display: flex;
    /* 固定 16px 间距替代原先 space-between 摊开的大空隙：既不成团，也不显松散。 */
    gap: var(--nl-space-4);
    align-items: center;
    flex-shrink: 0;
    flex-wrap: wrap;
    margin-left: auto;
  }
  &__tools .el-button {
    min-height: var(--nl-touch-min);
    margin-left: 0;
  }
  &__error {
    color: var(--nl-danger-text);
    font-size: var(--nl-font-body);
  }
  &__attachment {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--nl-space-2);
    overflow-wrap: anywhere;
  }
  &__attachment > .el-image,
  &__attachment > .chat-video {
    flex: 0 1 30%;
    max-height: 18vh;
  }
  &__attachment :deep(video) {
    max-height: 18vh;
  }
  &__latest {
    align-self: center;
    min-height: var(--nl-touch-min);
  }
}
</style>
