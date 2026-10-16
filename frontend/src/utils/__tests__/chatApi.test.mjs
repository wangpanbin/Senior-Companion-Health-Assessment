import { afterEach, describe, expect, it, vi } from 'vitest'
import request from '@/utils/request'
import * as chat from '@/api/chat'
import { uploadFile } from '@/api/file'
import { startUnreadPolling } from '@/api/message'
vi.mock('@/utils/request', () => ({ default: vi.fn().mockResolvedValue({ total: 2 }) }))
vi.mock('@/utils/auth', () => ({ getToken: () => 'test-token' }))
afterEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})
describe('M13 API 契约', () => {
  it('sends exact HTTP paths and payloads without adding /api twice', async () => {
    await chat.sendMessage(1, { msgType: 'TEXT', content: '到院见' })
    expect(request).toHaveBeenLastCalledWith({
      url: '/chat/1/messages',
      method: 'post',
      data: { msgType: 'TEXT', content: '到院见' }
    })
    await chat.getHistory(1, { cursor: 0, size: 100 })
    expect(request).toHaveBeenLastCalledWith({
      url: '/chat/1/messages',
      method: 'get',
      params: { cursor: 0, size: 100 }
    })
    await chat.markRead(1, 9)
    expect(request).toHaveBeenLastCalledWith({
      url: '/chat/1/read',
      method: 'post',
      data: { lastMessageId: 9 }
    })
    await chat.listConversations({ page: 2, size: 10 })
    expect(request.mock.lastCall[0].url).toBe('/chat/conversations')
    await chat.getUnreadTotal()
    expect(request.mock.lastCall[0].url).toBe('/chat/unread-total')
    await chat.listAdminChatOrders({ keyword: '医院' })
    expect(request.mock.lastCall[0].url).toBe('/admin/chat/orders')
  })
  it('sets CHAT multipart bizType and long timeout without changing other uploads', async () => {
    const file = new File(['mp4'], 'test.mp4', { type: 'video/mp4' })
    await uploadFile(file, 'CHAT')
    expect(request.mock.lastCall[0].timeout).toBe(180000)
    expect(request.mock.lastCall[0].data.get('bizType')).toBe('CHAT')
    expect(request.mock.lastCall[0].data.get('file')).toBe(file)
    await uploadFile(file, 'AVATAR')
    expect(request.mock.lastCall[0].timeout).toBeUndefined()
  })
  it('adds named CHAT_MESSAGE events to the same SSE and stops late callbacks', async () => {
    let source
    vi.stubGlobal(
      'EventSource',
      class {
        listeners = {}
        close = vi.fn()
        constructor() {
          source = this
        }
        addEventListener(name, callback) {
          this.listeners[name] = callback
        }
      }
    )
    const onChange = vi.fn()
    const onChat = vi.fn()
    const subscription = startUnreadPolling(60000, onChange, onChat)
    await Promise.resolve()
    source.listeners.NEW_MESSAGE({ data: '{"unreadCount":3}' })
    source.listeners.CHAT_MESSAGE({ data: '{"orderId":1}' })
    expect(onChange).toHaveBeenCalledWith(3, {})
    expect(onChat).toHaveBeenCalledWith({ orderId: 1 })
    subscription.stop()
    source.listeners.CHAT_MESSAGE({ data: '{"orderId":1}' })
    expect(onChat).toHaveBeenCalledTimes(1)
    expect(source.close).toHaveBeenCalledOnce()
  })
})
