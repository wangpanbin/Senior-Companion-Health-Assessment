/** M13 消息流：按房间隔离异步任务，历史/重连补洞共用同一串行队列。 */
import { computed, nextTick, onBeforeUnmount, ref, toValue, watch } from 'vue'
import { connectChatSocket, getHistory, markRead } from '@/api/chat'
import { compareMessageIds, fetchLatestMessages, mergeMessages } from '@/utils/chat'

export function useChatMessages(orderId, scroller, canWrite, onRead) {
  const messages = ref([])
  const metadata = ref({})
  const ready = ref(false)
  const loading = ref(true)
  const loadError = ref(false)
  const syncing = ref(false)
  const paging = ref(false)
  const hasMore = ref(false)
  const atBottom = ref(true)
  const status = ref('connecting')
  let context

  const newestId = computed(() => messages.value.at(-1)?.messageId || 0)
  const current = (ctx) => ctx === context && ctx.active
  const nearBottom = () => {
    const el = scroller.value
    return !!el && el.scrollHeight - el.scrollTop - el.clientHeight <= 24
  }
  function anchor() {
    const el = scroller.value
    if (!el) return null
    const top = el.getBoundingClientRect().top
    const item = [...el.querySelectorAll('[data-message-id]')].find(
      (node) => node.getBoundingClientRect().bottom > top
    )
    return item
      ? { id: item.dataset.messageId, offset: item.getBoundingClientRect().top - top }
      : null
  }
  function restoreAnchor(saved) {
    const el = scroller.value
    if (!el || !saved) return
    const item = [...el.querySelectorAll('[data-message-id]')].find(
      (node) => node.dataset.messageId === saved.id
    )
    if (item)
      el.scrollTop +=
        item.getBoundingClientRect().top - el.getBoundingClientRect().top - saved.offset
  }
  async function reportRead(ctx = context) {
    if (
      !ctx ||
      !current(ctx) ||
      ctx.reading ||
      !ready.value ||
      loadError.value ||
      syncing.value ||
      paging.value ||
      !toValue(canWrite) ||
      document.hidden ||
      !atBottom.value ||
      !nearBottom() ||
      compareMessageIds(newestId.value, ctx.lastRead) <= 0
    )
      return
    const id = newestId.value
    ctx.reading = true
    let success = false
    try {
      await markRead(ctx.orderId, id)
      if (!current(ctx)) return
      ctx.lastRead = id
      success = true
      onRead?.()
    } catch {
      // 网络/业务错误由拦截器展示；下次滚动或刷新时可重试。
    } finally {
      ctx.reading = false
      if (success && current(ctx)) reportRead(ctx)
    }
  }
  async function scrollToBottom() {
    const ctx = context
    await nextTick()
    if (!ctx || !current(ctx) || document.hidden) return
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
    atBottom.value = true
    reportRead(ctx)
  }
  async function append(message) {
    const ctx = context
    if (!ctx || !current(ctx)) return
    if (message.orderId != null && String(message.orderId) !== String(ctx.orderId)) return
    const follow = atBottom.value && !document.hidden
    messages.value = mergeMessages(messages.value, [message])
    await nextTick()
    if (!current(ctx)) return
    if (follow && atBottom.value && !document.hidden) await scrollToBottom()
  }
  function close(ctx) {
    if (!ctx) return
    ctx.active = false
    ctx.socket?.close()
    clearInterval(ctx.timer)
  }
  function openSocket(ctx) {
    if (!current(ctx) || ctx.socket) return
    ctx.socket = connectChatSocket(ctx.orderId, {
      onStatus: (value) => {
        if (current(ctx)) status.value = value
      },
      onGiveUp: () => {
        if (current(ctx)) status.value = 'exhausted'
      },
      onEvent: (event) => {
        if (!current(ctx)) return
        if (event.orderId != null && String(event.orderId) !== String(ctx.orderId)) return
        if (event.type === 'CONNECTED') refreshLatest(ctx)
        if (event.type === 'CHAT_MESSAGE' && event.data?.messageId != null) append(event.data)
      }
    })
  }
  async function refreshLatest(ctx = context) {
    if (!ctx || !current(ctx)) return
    if (ctx.busy) {
      ctx.queued = true
      return
    }
    ctx.busy = true
    syncing.value = true
    loading.value = !ready.value
    const follow = atBottom.value && !document.hidden
    try {
      // 水位只在完整补洞成功后更新，不拿补洞期间到达的 WS 消息作锚点。
      // null = 首次分页；0 = 之前为空，重连时需要补到最早一条。
      const result = await fetchLatestMessages(
        (params) => getHistory(ctx.orderId, params),
        ready.value ? ctx.watermark : null,
        () => current(ctx)
      )
      if (!result || !current(ctx)) return
      const { records, firstPage, lastPage } = result
      const saved = anchor()
      messages.value = mergeMessages(messages.value, records)
      metadata.value = firstPage || {}
      if (
        !ready.value ||
        !ctx.cursor ||
        (lastPage?.nextCursor && compareMessageIds(lastPage.nextCursor, ctx.cursor) < 0)
      ) {
        ctx.cursor = lastPage?.nextCursor || 0
        hasMore.value = !!lastPage?.hasMore
      }
      ctx.watermark = records.at(-1)?.messageId || ctx.watermark
      ready.value = true
      loadError.value = false
      loading.value = false
      await nextTick()
      if (!current(ctx)) return
      if (follow && atBottom.value && !document.hidden) await scrollToBottom()
      else restoreAnchor(saved)
      openSocket(ctx)
    } catch {
      if (current(ctx)) loadError.value = true
    } finally {
      ctx.busy = false
      if (current(ctx)) {
        syncing.value = false
        loading.value = false
        reportRead(ctx)
        if (ctx.queued) {
          ctx.queued = false
          refreshLatest(ctx)
        }
      }
    }
  }
  async function loadOlder() {
    const ctx = context
    if (!ctx || !current(ctx) || ctx.busy || !hasMore.value || !ctx.cursor) return
    ctx.busy = true
    paging.value = true
    try {
      const page = await getHistory(ctx.orderId, { cursor: ctx.cursor, size: 50 })
      if (!current(ctx)) return
      if (
        page?.hasMore &&
        (!page.nextCursor || compareMessageIds(page.nextCursor, ctx.cursor) >= 0)
      ) {
        throw new Error('历史游标未前进')
      }
      const saved = anchor()
      messages.value = mergeMessages(messages.value, page?.records || [])
      ctx.cursor = page?.nextCursor || ctx.cursor
      hasMore.value = !!page?.hasMore
      loadError.value = false
      await nextTick()
      if (current(ctx)) restoreAnchor(saved)
    } catch {
      if (current(ctx)) loadError.value = true
    } finally {
      ctx.busy = false
      if (current(ctx)) {
        paging.value = false
        if (ctx.queued) {
          ctx.queued = false
          refreshLatest(ctx)
        }
      }
    }
  }
  function onScroll() {
    atBottom.value = nearBottom()
    if (scroller.value?.scrollTop <= 8 && !loadError.value) loadOlder()
    reportRead()
  }
  function onVisibility() {
    if (!document.hidden) refreshLatest()
  }
  document.addEventListener('visibilitychange', onVisibility)
  watch(
    () => toValue(orderId),
    (id) => {
      close(context)
      const ctx = { active: true, orderId: id, cursor: 0, watermark: 0, lastRead: 0, busy: false }
      context = ctx
      messages.value = []
      metadata.value = {}
      ready.value = false
      loading.value = true
      loadError.value = false
      syncing.value = false
      paging.value = false
      hasMore.value = false
      atBottom.value = true
      status.value = 'connecting'
      if (!id) {
        loading.value = false
        return
      }
      refreshLatest(ctx)
      ctx.timer = setInterval(() => refreshLatest(ctx), 60000)
    },
    { immediate: true }
  )
  onBeforeUnmount(() => {
    close(context)
    document.removeEventListener('visibilitychange', onVisibility)
  })
  return {
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
    reportRead,
    refresh: () => refreshLatest()
  }
}
