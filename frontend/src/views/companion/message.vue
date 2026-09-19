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
 *   - 一期砍掉 IM：无输入框、无对话气泡；点击消息进入只读详情页，并标记已读。
 *   - onBeforeUnmount 必须 stop() 轮询。
 *   - catch 不重复弹错（拦截器已弹）。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  NlPageShell,
  NlAppTabBar,
  NlMobileOnlyPage,
  NlEmpty,
  NlIconBox,
  NlCard,
  NlSkeleton
} from '@/components'
import {
  listMessages,
  markRead,
  markAllRead,
  removeMessage,
  startUnreadPolling
} from '@/api/message'
import { formatDateTime } from '@/utils/format'
import { useUserStore } from '@/store/modules/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roleLabel = computed(() => userStore.roleLabel || '用户')

/**
 * 本 view 被三条路由共用（ADR-0008 只把 `/elder/message` 列为 mobile-only）。
 * 判据必须落在**当前路由实例**上，否则会误伤 /family/message 与 /companion/message。
 */
const mobileOnlyHere = computed(() => route.name === 'ElderMessage')

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
  // 后端历史 linkUrl 可能仍指向已调整的旧路由；先进入统一详情页，避免用户落到 404。
  // 跳转时把已脱敏的消息体通过 query.snapshot 一并传到详情页。
  // 用 base64(JSON) 编码避免 router 4 在 query 上做 URL 转义时丢字段；
  // 即便用户刷新或通过浏览器后退返回，URL 仍带 snapshot，详情页可即时渲染，
  // 不再依赖 window.history.state（其受扩展/中间件影响，不稳定）。
  const snapshot = btoa(
    encodeURIComponent(
      JSON.stringify({
        id: item.id,
        type: item.type,
        typeLabel: item.typeLabel,
        title: item.title,
        content: item.content,
        bizType: item.bizType,
        bizId: item.bizId,
        linkUrl: item.linkUrl,
        isRead: true,
        createTime: item.createTime
      })
    )
  )
  router.push({
    name: 'MessageDetail',
    params: { id: String(item.id) },
    query: { snapshot }
  })
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
    await ElMessageBox.confirm('删除后消息无法恢复，是否继续？', '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }

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
  <!-- mobile-only 守卫只对 /elder/message 生效（见脚本里 mobileOnlyHere 的注释） -->
  <NlMobileOnlyPage :enabled="mobileOnlyHere">
    <NlPageShell :title="`消息（${unreadCount} 条未读）`" :has-tabs="true" :back="false">
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
          <li v-for="item in messages" :key="item.id" class="msg-item" @click="openItem(item)">
            <NlIconBox :tone="toneOf(item.type)" :size="36">
              <svg
                viewBox="0 0 16 16"
                width="20"
                height="20"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <path d="M2 4h12v8H2z M2 4l6 4 6-4" />
              </svg>
            </NlIconBox>
            <div class="msg-item__body">
              <div class="msg-item__title">
                <span :class="['nl-h3', { 'is-unread': !item.isRead }]">{{ item.title }}</span>
                <span v-if="item.typeLabel" class="nl-caption msg-item__tag">
                  {{ item.typeLabel }}
                </span>
              </div>
              <p class="nl-caption msg-item__brief">{{ item.content }}</p>
            </div>
            <div class="msg-item__right">
              <span class="nl-caption nl-text-muted is-num">
                {{ formatDateTime(item.createTime) }}
              </span>
              <span v-if="!item.isRead" class="msg-item__dot" aria-label="未读" />
              <el-button
                text
                size="small"
                type="danger"
                class="msg-item__del"
                @click.stop="removeItem(item)"
              >
                删除
              </el-button>
            </div>
          </li>
        </ul>
      </NlCard>

      <template #tabbar>
        <NlAppTabBar />
      </template>
    </NlPageShell>
  </NlMobileOnlyPage>
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
