import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { useChatMessages } from '../useChatMessages'
import { connectChatSocket, getHistory, markRead } from '@/api/chat'
vi.mock('@/api/chat', () => ({
  connectChatSocket: vi.fn(),
  getHistory: vi.fn(),
  markRead: vi.fn()
}))
let host, flow, id, element, handlers, close
const msg = (n) => ({ messageId: n, orderId: 1 })
async function start(writer = true) {
  id = ref('1')
  element = document.createElement('div')
  Object.defineProperties(element, {
    scrollHeight: { configurable: true, value: 1000 },
    clientHeight: { configurable: true, value: 300 }
  })
  element.scrollTop = 700
  host = mount({
    setup() {
      flow = useChatMessages(id, ref(element), ref(writer))
      return () => null
    }
  })
  await flushPromises()
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  close = vi.fn()
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  getHistory.mockResolvedValue({ records: [msg(5)], nextCursor: 5, hasMore: true })
  markRead.mockResolvedValue({ unreadCount: 0 })
  connectChatSocket.mockImplementation((_id, options) => {
    handlers = options
    return { close }
  })
})
afterEach(() => {
  host?.unmount()
  vi.useRealTimers()
})

describe('M13 消息流生命周期', () => {
  it('on every CONNECTED fetches latest and catches >100 missed messages without duplicates', async () => {
    await start()
    getHistory.mockImplementation(async (_id, { cursor }) => {
      const start = cursor ? cursor - 1 : 255
      const records = Array.from({ length: Math.min(100, start) }, (_, i) => msg(start - i))
      return { records, nextCursor: records.at(-1).messageId, hasMore: start > 100 }
    })
    handlers.onEvent({ type: 'CONNECTED', orderId: 1 })
    // WS 与回填交错，不能把它误当作已经补齐的最新水位。
    handlers.onEvent({ type: 'CHAT_MESSAGE', orderId: 1, data: msg(255) })
    await flushPromises()
    expect(getHistory.mock.calls.slice(1).map(([, params]) => params.cursor)).toEqual([0, 156, 56])
    expect(flow.messages.value).toHaveLength(255)
    handlers.onEvent({ type: 'CONNECTED' })
    await flushPromises()
    expect(getHistory.mock.lastCall[1].cursor).toBe(0)
  })
  it('does not mark hidden or scrolled-up messages read', async () => {
    await start()
    markRead.mockClear()
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    await flow.append(msg(6))
    expect(markRead).not.toHaveBeenCalled()
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    element.scrollTop = 300
    flow.onScroll()
    await flow.append(msg(7))
    expect(markRead).not.toHaveBeenCalled()
    expect(element.scrollTop).toBe(300)
    await flow.scrollToBottom()
    await flushPromises()
    expect(markRead).toHaveBeenCalledWith('1', 7)
  })
  it('ignores stale history and closes sockets/timers when changing routes or unmounting', async () => {
    await start()
    let resolve
    getHistory.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      })
    )
    flow.refresh()
    id.value = '2'
    await flushPromises()
    resolve({ records: [msg(999)], nextCursor: 999, hasMore: false })
    await flushPromises()
    expect(flow.messages.value.some((m) => m.messageId === 999)).toBe(false)
    expect(close).toHaveBeenCalled()
    host.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('preserves scroll anchor when loading an older page and merges overlap', async () => {
    await start(false)
    element.scrollTop = 20
    const item = document.createElement('div')
    item.dataset.messageId = '5'
    item.getBoundingClientRect = () => {
      const top = flow.messages.value.length > 1 ? 250 : 100
      return { top, bottom: top + 30 }
    }
    element.getBoundingClientRect = () => ({ top: 0 })
    element.append(item)
    getHistory.mockResolvedValueOnce({
      records: [msg(5), msg(4), msg(3)],
      nextCursor: 3,
      hasMore: false
    })
    await flow.loadOlder()
    expect(element.scrollTop).toBe(170)
    expect(flow.messages.value.map((m) => m.messageId)).toEqual([3, 4, 5])
    expect(markRead).not.toHaveBeenCalled()
  })
})
