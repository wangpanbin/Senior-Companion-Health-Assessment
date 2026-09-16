<script setup>
/**
 * 站内信（M-14 · design.md §4 · 三角色共用：/elder/message /family/message /companion/message）
 *
 * 数据来源（@/api/message）：
 *   - listMessages({page,size,type}) → 站内信列表
 *   - markRead(id) / markAllRead() / removeMessage(id)
 *   - startUnreadPolling(60000, onChange) → 轮询未读数（SSE 端点需 Authorization 头，浏览器
 *     EventSource 设不了头，故走轮询；详见契约 §3）
 *
 * 关键点：
 *   - 接口按当前登录 token 返回该角色的消息，天然按角色工作（读 useUserStore().role 仅用于展示）。
 *   - 一期砍掉 IM：无输入框、无对话气泡，点击消息仅标记已读（并按 linkUrl 跳转）。
 *   - onBeforeUnmount 必须 stop() 轮询。
 *   - catch 不重复弹错（拦截器已弹）。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { NlPhoneShell, NlEmpty, NlIconBox, NlCard } from '@/components'
import {
  listMessages, markRead, markAllRead, removeMessage, startUnreadPolling
} from '@/api/message'
import { formatDateTime } from '@/utils/format'
import { useUserStore } from '@/store/modules/user'

const router = useRouter()
const userStore = useUserStore()
const roleLabel = computed(() => userStore.roleLabel || '用户')

const messages = ref([])
const loading = ref(true)
const total = ref(0)
const unreadCount = computed(() => messages.value.filter((m) => !m.isRead).length)

let polling = null

function toneOf(type = '') {
  if (type.startsWith('ORDER')) return 'primary'
  if (type.startsWith('AUDIT')) return 'success'
  if (type.startsWith('MEDICATION') || type.startsWith('MISSED')) return 'danger'
  if (type.startsWith('SYSTEM')) return 'info'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const data = await listMessages({ page: 1, size: 50 })
    messages.value = data?.records || []
    total.value = data?.total || 0
  } catch {
    messages.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function openItem(item) {
  if (!item.isRead) {
    item.isRead = true // 乐观更新，避免红点闪烁
    markRead(item.id).catch(() => {})
  }
  if (item.linkUrl) {
    router.push(item.linkUrl)
  }
}

async function doMarkAll() {
  if (!unreadCount.value) return
  try {
    await markAllRead()
    messages.value.forEach((m) => (m.isRead = true))
  } catch {
    // 拦截器已弹错
  }
}

async function removeItem(item) {
  try {
    await removeMessage(item.id)
    messages.value = messages.value.filter((m) => m.id !== item.id)
  } catch {
    // 拦截器已弹错
  }
}

const hasAny = computed(() => messages.value.length > 0)

onMounted(() => {
  load()
  polling = startUnreadPolling(60000, (n) => {
    // 轮询只更新红点数；列表内的已读态由本页本地维护
    void n
  })
})

onBeforeUnmount(() => {
  polling?.stop()
})
</script>

<template>
  <NlPhoneShell :nav="{ title: `消息（${unreadCount} 条未读）` }">
    <!-- 顶部操作条 -->
    <section class="msg-bar">
      <span class="nl-caption nl-text-muted">
        共 {{ total }} 条 · {{ unreadCount }} 条未读 · {{ roleLabel }}
      </span>
      <el-button text size="small" :disabled="!unreadCount" @click="doMarkAll">
        全部已读
      </el-button>
    </section>

    <NlSkeleton v-if="loading" :count="3" class="pad" />

    <NlEmpty
      v-else-if="!hasAny"
      type="empty"
      title="暂无新消息"
      description="订单事件、资质审核、漏服提醒、系统公告会在这里出现"
    />

    <NlCard v-else title="站内信" plain>
      <ul class="msg-list">
        <li
          v-for="item in messages"
          :key="item.id"
          class="msg-item"
          @click="openItem(item)"
        >
          <NlIconBox :tone="toneOf(item.type)" :size="36">
            <svg viewBox="0 0 16 16" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
              <path d="M2 4h12v8H2z M2 4l6 4 6-4" />
            </svg>
          </NlIconBox>
          <div class="msg-item__body">
            <div class="msg-item__title">
              <span :class="['nl-h3', { 'is-unread': !item.isRead }]">{{ item.title }}</span>
              <span v-if="item.typeLabel" class="nl-caption msg-item__tag">{{ item.typeLabel }}</span>
            </div>
            <p class="nl-caption msg-item__brief">{{ item.content }}</p>
          </div>
          <div class="msg-item__right">
            <span class="nl-caption nl-text-muted is-num">{{ formatDateTime(item.createTime) }}</span>
            <span v-if="!item.isRead" class="msg-item__dot" aria-label="未读" />
            <el-button
              text size="small" type="danger"
              class="msg-item__del"
              @click.stop="removeItem(item)"
            >删除</el-button>
          </div>
        </li>
      </ul>
    </NlCard>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter);
}

.msg-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--nl-gutter);
}

.msg-list {
  padding: 0;
  margin: 0;
  list-style: none;
}

.msg-item {
  display: flex;
  gap: var(--nl-space-3);
  align-items: flex-start;
  padding: var(--nl-space-3) 0;
  cursor: pointer;

  & + .msg-item {
    border-top: 1px solid var(--nl-divider);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: var(--nl-space-2);
  }

  .is-unread {
    color: var(--nl-text-1);
    font-weight: 600;
  }

  &__tag {
    flex-shrink: 0;
    padding: 1px 6px;
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border-radius: 999px;
  }

  &__brief {
    margin: 4px 0 0;
    color: var(--nl-text-2);
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    overflow: hidden;
  }

  &__right {
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 4px;
    padding-top: 2px;
  }

  &__dot {
    width: 8px;
    height: 8px;
    background: var(--nl-primary);
    border-radius: 50%;
  }

  &__del {
    margin-top: 2px;
  }
}
</style>
