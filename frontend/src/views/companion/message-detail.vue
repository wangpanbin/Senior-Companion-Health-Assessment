<script setup>
/**
 * 站内信详情（M8）
 *
 * 消息列表没有单条详情接口，因此优先读取列表页通过 history state 传入的已脱敏消息；
 * 用户刷新或直接访问时，再从本人消息列表中查找该 ID。找不到时只提示消息已删除，
 * 不展示任何其他用户的信息。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NlCard, NlEmpty, NlIconBox, NlPageShell, NlSkeleton } from '@/components'
import { listMessages } from '@/api/message'
import { formatDateTime } from '@/utils/format'
import { useUserStore } from '@/store/modules/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const loading = ref(true)
const message = ref(null)

function toneOf(type = '') {
  if (type.startsWith('ORDER')) return 'primary'
  if (type.startsWith('AUDIT')) return 'success'
  if (type.startsWith('MEDICATION') || type.startsWith('MISSED')) return 'danger'
  return 'info'
}

function isCurrentMessage(item) {
  return String(item?.id) === String(route.params.id)
}

function readHistoryMessage() {
  const cached = window.history.state?.message
  return isCurrentMessage(cached) ? cached : null
}

const relatedTarget = computed(() => {
  const type = message.value?.type || ''
  const bizId = message.value?.bizId

  // 后端历史消息保存的是旧版 linkUrl。此处仅按消息类型跳转到现有路由，
  // 既避免旧路径导致 404，也避免前端盲目拼接不存在的详情页地址。
  if (type.startsWith('ORDER')) {
    if (userStore.isFamily) return bizId ? `/family/order/${bizId}` : '/family/order'
    if (userStore.isCompanion)
      return type === 'ORDER_CREATED' ? '/companion/hall' : '/companion/order'
    if (userStore.isAdmin) return '/admin/order'
    return ''
  }

  if (type === 'MEDICATION_REMIND') {
    if (userStore.isElder) return '/elder/medication'
    if (userStore.isFamily) return '/family/medication'
    return ''
  }

  if (type === 'AUDIT_RESULT') {
    if (userStore.isCompanion) return '/companion/entry'
    if (userStore.isAdmin) return '/admin/companion-audit'
    return ''
  }

  if (type.startsWith('COMPLAINT')) {
    if (userStore.isFamily) return '/family/order'
    if (userStore.isCompanion) return '/companion/order'
    if (userStore.isAdmin) return '/admin/complaint'
  }

  return ''
})

async function loadMessage() {
  const cached = readHistoryMessage()
  if (cached) {
    message.value = cached
    loading.value = false
    return
  }

  try {
    const data = await listMessages({ page: 1, size: 100 })
    message.value = (data?.records || []).find(isCurrentMessage) || null
  } catch {
    message.value = null
  } finally {
    loading.value = false
  }
}

function openRelated() {
  if (relatedTarget.value) router.push(relatedTarget.value)
}

onMounted(loadMessage)
</script>

<template>
  <NlPageShell title="消息详情">
    <NlSkeleton v-if="loading" :count="3" class="message-detail__skeleton" />

    <NlEmpty
      v-else-if="!message"
      type="empty"
      title="消息已不可查看"
      description="这条消息可能已被删除，或不属于当前账号"
    />

    <article v-else class="message-detail">
      <NlCard class="message-detail__head" :padding="20">
        <NlIconBox :tone="toneOf(message.type)" :size="44">
          <svg
            viewBox="0 0 16 16"
            width="22"
            height="22"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          >
            <path d="M2 4h12v8H2z M2 4l6 4 6-4" />
          </svg>
        </NlIconBox>
        <div>
          <span v-if="message.typeLabel" class="message-detail__tag">{{ message.typeLabel }}</span>
          <h2>{{ message.title }}</h2>
          <p>{{ formatDateTime(message.createTime) }}</p>
        </div>
      </NlCard>

      <NlCard title="消息内容" :padding="20">
        <p class="message-detail__content">{{ message.content }}</p>
      </NlCard>

      <NlCard v-if="message.bizType" title="关联信息" :padding="20">
        <p class="message-detail__hint">
          这是一条{{ message.typeLabel || '系统' }}通知，请以相关服务页面中的最新信息为准。
        </p>
        <el-button v-if="relatedTarget" type="primary" plain @click="openRelated">
          查看关联事项
        </el-button>
      </NlCard>

      <p class="message-detail__notice">站内信仅用于系统通知，不支持在线回复。</p>
    </article>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.message-detail {
  display: flex;
  flex-direction: column;
  gap: var(--nl-gap-section);
  padding: 0 var(--nl-gutter) var(--nl-space-6);

  &__skeleton {
    padding: 0 var(--nl-gutter);
  }

  &__head {
    flex-direction: row;
    align-items: flex-start;

    h2 {
      margin: 4px 0 2px;
      font-size: var(--nl-font-h2);
      line-height: var(--nl-lh-h2);
      color: var(--nl-text-1);
    }

    p {
      margin: 0;
      font-family: var(--nl-font-num);
      font-size: var(--nl-font-caption);
      color: var(--nl-text-3);
    }
  }

  &__tag {
    display: inline-flex;
    padding: 2px 7px;
    font-size: var(--nl-font-caption);
    color: var(--nl-primary);
    background: var(--nl-primary-light);
    border-radius: var(--nl-radius-pill);
  }

  &__content,
  &__hint {
    margin: 0;
    font-size: var(--nl-font-body);
    line-height: var(--nl-lh-body);
    color: var(--nl-text-2);
    white-space: pre-wrap;
  }

  &__hint {
    margin-bottom: var(--nl-space-3);
  }

  &__notice {
    margin: 0;
    font-size: var(--nl-font-caption);
    text-align: center;
    color: var(--nl-text-3);
  }
}
</style>
