/** M13 角标只采纳服务端总数；SSE 是刷新信号，不在本地累加。 */
import { onMounted, onBeforeUnmount, ref, toValue } from 'vue'
import { getUnreadTotal } from '@/api/chat'
import { startUnreadPolling } from '@/api/message'

export function useChatUnread({ enabled = true, subscribe = true, onMessage } = {}) {
  const total = ref(0)
  const error = ref(false)
  let stopped = false
  let version = 0
  let timer
  let subscription

  async function refresh() {
    if (stopped || !toValue(enabled)) return
    const request = ++version
    try {
      const data = await getUnreadTotal()
      if (stopped || request !== version) return
      total.value = Number(data?.total || 0)
      error.value = false
    } catch {
      if (!stopped && request === version) error.value = true
    }
  }
  function handleMessage(payload) {
    if (stopped || !toValue(enabled)) return
    refresh()
    onMessage?.(payload)
  }
  onMounted(() => {
    if (!toValue(enabled)) return
    refresh()
    timer = setInterval(refresh, 60000)
    if (subscribe) subscription = startUnreadPolling(60000, undefined, handleMessage)
  })
  onBeforeUnmount(() => {
    stopped = true
    version++
    clearInterval(timer)
    subscription?.stop()
  })
  return { total, error, refresh, handleMessage }
}
