import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { useChatUnread } from '../useChatUnread'
import { getUnreadTotal } from '@/api/chat'
import { startUnreadPolling } from '@/api/message'
vi.mock('@/api/chat', () => ({ getUnreadTotal: vi.fn() }))
vi.mock('@/api/message', () => ({ startUnreadPolling: vi.fn() }))
let host, unread, stop, signal
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  stop = vi.fn()
  getUnreadTotal.mockResolvedValue({ total: 4 })
  startUnreadPolling.mockImplementation((_ms, _change, handler) => {
    signal = handler
    return { stop }
  })
})
afterEach(() => {
  host?.unmount()
  vi.useRealTimers()
})
function start(options) {
  host = mount({
    setup() {
      unread = useChatUnread(options)
      return () => null
    }
  })
}
describe('M13 权威未读总数', () => {
  it('refreshes at mount, each SSE and every 60 seconds, then stops all work', async () => {
    start()
    await flushPromises()
    expect(unread.total.value).toBe(4)
    getUnreadTotal.mockResolvedValue({ total: 2 })
    signal({ orderId: 1 })
    await flushPromises()
    expect(unread.total.value).toBe(2)
    vi.advanceTimersByTime(60000)
    await flushPromises()
    expect(getUnreadTotal).toHaveBeenCalledTimes(3)
    host.unmount()
    signal({ orderId: 1 })
    vi.advanceTimersByTime(60000)
    expect(getUnreadTotal).toHaveBeenCalledTimes(3)
    expect(stop).toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('uses the existing message-page SSE rather than creating a second source', async () => {
    start({ subscribe: false })
    await flushPromises()
    expect(startUnreadPolling).not.toHaveBeenCalled()
    unread.handleMessage({ orderId: 1 })
    await flushPromises()
    expect(getUnreadTotal).toHaveBeenCalledTimes(2)
  })
  it('does nothing for read-only users', async () => {
    start({ enabled: false })
    await flushPromises()
    expect(startUnreadPolling).not.toHaveBeenCalled()
    expect(getUnreadTotal).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('discards pending totals after unmount', async () => {
    let resolve
    getUnreadTotal.mockReturnValue(
      new Promise((done) => {
        resolve = done
      })
    )
    start()
    host.unmount()
    resolve({ total: 99 })
    await flushPromises()
    expect(unread.total.value).toBe(0)
  })
})
