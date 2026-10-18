<script setup>
/** M13 分页会话栏；管理员检索与业务会话列表共享呈现，不共享写权限。 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { NlAvatar, NlEmpty, NlSkeleton } from '@/components'
import { listAdminChatOrders, listConversations } from '@/api/chat'
import { useChatUnread } from '@/composables/useChatUnread'
import { formatDateTime } from '@/utils/format'
const props = defineProps({
  admin: { type: Boolean, default: false },
  selectedId: { type: [String, Number], default: '' }
})
const emit = defineEmits(['select'])
const records = ref([])
const loading = ref(false)
const error = ref(false)
const page = ref(1)
const total = ref(0)
const keyword = ref('')
let requestVersion = 0
let disposed = false
async function load() {
  if (disposed) return
  const version = ++requestVersion
  loading.value = true
  try {
    const params = { page: page.value, size: 10 }
    const data = props.admin
      ? await listAdminChatOrders({ ...params, keyword: keyword.value.trim() || undefined })
      : await listConversations(params)
    if (disposed || version !== requestVersion) return
    records.value = data?.records || []
    total.value = data?.total || 0
    error.value = false
  } catch {
    if (!disposed && version === requestVersion) error.value = true
  } finally {
    if (!disposed && version === requestVersion) loading.value = false
  }
}
const { total: unread, refresh: refreshUnread } = useChatUnread({
  enabled: () => !props.admin,
  onMessage: load
})
function search() {
  page.value = 1
  load()
}
function changePage(value) {
  page.value = value
  load()
}
function refresh() {
  load()
  refreshUnread()
}
/** 会话项首字头像：peerName 已脱敏（李*军），取首字不影响隐私口径。 */
const initialOf = (item) =>
  (props.admin ? item.companionName || item.familyName : item.peerName || '沟').slice(0, 1)
const titleOf = (item) =>
  props.admin
    ? `${item.familyName || '家属'} · ${item.companionName || '陪诊员'}`
    : item.peerName || '对方'
const timeOf = (item) =>
  formatDateTime(
    props.admin ? item.lastMessageTime : item.lastMessage?.createTime || item.visitTime
  )
defineExpose({ refresh })
onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  requestVersion++
})
</script>

<template>
  <aside class="chat-conversations" aria-label="订单会话列表">
    <header class="chat-conversations__header">
      <h2 class="nl-h2">{{ admin ? '沟通记录检索' : '订单沟通' }}</h2>
      <el-badge v-if="!admin" :value="unread" :hidden="!unread" :max="99">
        <span class="nl-caption">未读</span>
      </el-badge>
      <el-button text :loading="loading" @click="refresh">刷新</el-button>
    </header>
    <form v-if="admin" class="chat-conversations__search" @submit.prevent="search">
      <el-input
        v-model="keyword"
        placeholder="订单号 / 医院"
        aria-label="搜索聊天订单"
        clearable
        @clear="search"
      />
      <el-button native-type="submit" type="primary" plain>查询</el-button>
    </form>
    <NlSkeleton v-if="loading && !records.length" :count="3" />
    <NlEmpty
      v-else-if="error"
      type="network"
      title="会话加载失败"
      action-text="重试"
      @action="load"
    />
    <NlEmpty
      v-else-if="!records.length"
      title="暂无会话"
      :description="admin ? '没有匹配的沟通记录，请调整检索条件' : '订单接单后即可与对方沟通'"
    />
    <ul v-else class="chat-conversations__list">
      <li v-for="item in records" :key="item.orderId">
        <button
          :class="[
            'chat-conversations__item',
            { 'is-selected': String(selectedId) === String(item.orderId) }
          ]"
          :aria-current="String(selectedId) === String(item.orderId) ? 'true' : undefined"
          @click="emit('select', item.orderId)"
        >
          <NlAvatar class="chat-conversations__avatar" :fallback="initialOf(item)" :size="40" tone="primary" />
          <div class="chat-conversations__main">
            <div class="chat-conversations__row">
              <strong class="chat-conversations__name">{{ titleOf(item) }}</strong>
              <el-badge v-if="!admin && item.unreadCount" :value="item.unreadCount" :max="99" />
            </div>
            <div class="chat-conversations__row chat-conversations__sub">
              <span class="chat-conversations__order">{{ item.orderNo }}</span>
              <time class="chat-conversations__time">{{ timeOf(item) }}</time>
            </div>
            <span class="chat-conversations__place">{{ item.hospital }} · {{ item.orderStatusLabel }}</span>
            <span class="chat-conversations__preview">
              {{
                admin
                  ? `${item.messageCount || 0} 条沟通记录`
                  : item.lastMessage?.preview || '暂无消息，点击开始沟通'
              }}
            </span>
          </div>
        </button>
      </li>
    </ul>
    <el-pagination
      v-if="total > 10"
      layout="prev, pager, next"
      :pager-count="5"
      :total="total"
      :page-size="10"
      :current-page="page"
      @current-change="changePage"
    />
  </aside>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;
.chat-conversations {
  min-width: 0;
  padding: var(--nl-space-4);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  box-shadow: $nl-shadow-card;
  &__header,
  &__row,
  &__search {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: var(--nl-space-3);
  }
  &__header h2 {
    margin: 0;
  }
  &__search {
    margin: var(--nl-space-3) 0;
  }
  &__list {
    margin: var(--nl-space-3) 0;
    padding: 0;
    list-style: none;
  }
  &__item {
    display: flex;
    gap: var(--nl-space-3);
    align-items: flex-start;
    width: 100%;
    min-height: var(--nl-touch-min);
    padding: var(--nl-space-3);
    text-align: left;
    color: var(--nl-text-2);
    font: inherit;
    font-size: var(--nl-font-body);
    background: transparent;
    border: 0;
    border-bottom: 1px solid var(--nl-divider);
    border-radius: var(--nl-radius-card);
    cursor: pointer;
    overflow-wrap: anywhere;

    &.is-selected {
      background: var(--nl-primary-ghost);
      /* 选中态用左侧主色竖条标记，比单纯换底色更容易在长列表里定位 */
      box-shadow: inset 3px 0 0 var(--nl-primary);
    }
    &:hover:not(.is-selected) {
      background: var(--nl-bg-sunken);
    }
    &:focus-visible {
      outline: 2px solid var(--nl-primary);
      outline-offset: -2px;
    }
  }
  &__avatar {
    flex-shrink: 0;
  }
  &__main {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    flex: 1;
  }
  &__row {
    display: flex;
    gap: var(--nl-space-2);
    align-items: baseline;
    justify-content: space-between;
  }
  &__name {
    overflow: hidden;
    color: var(--nl-text-1);
    font-size: var(--nl-font-h3);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  /* 订单号与时间退到末行并弱化：扫列表时先看姓名与预览 */
  &__sub {
    font-size: var(--nl-font-caption);
  }
  &__order {
    overflow: hidden;
    color: var(--nl-text-3);
    font-family: var(--nl-font-num);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__time {
    flex-shrink: 0;
    color: var(--nl-text-3);
  }
  &__place {
    overflow: hidden;
    color: var(--nl-primary-text);
    font-size: var(--nl-font-caption);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__preview {
    display: -webkit-box;
    overflow: hidden;
    color: var(--nl-text-2);
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
}
</style>
