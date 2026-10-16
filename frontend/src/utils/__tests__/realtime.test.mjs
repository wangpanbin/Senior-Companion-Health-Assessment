import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createProgressSocket } from '../realtime'

vi.mock('@/utils/auth', () => ({ getToken: () => 'test-token' }))

let sockets
beforeEach(() => {
  vi.useFakeTimers()
  sockets = []
  vi.stubGlobal(
    'WebSocket',
    class {
      static OPEN = 1
      static CONNECTING = 0
      readyState = 0
      send = vi.fn()
      close = vi.fn()
      constructor(url) {
        this.url = url
        sockets.push(this)
      }
    }
  )
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('订单实时通道', () => {
  it.each([
    [undefined, '/ws/progress'],
    ['/ws/chat', '/ws/chat']
  ])('retains default progress and supports %s', (path, expected) => {
    const connection = createProgressSocket(42, {}, path)
    const url = new URL(sockets[0].url)
    expect(url.pathname).toBe(expected)
    expect(url.searchParams.get('orderId')).toBe('42')
    expect(url.searchParams.get('token')).toBe('test-token')
    connection.close()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('heartbeats, reconnects to same path and stops on close', () => {
    const connection = createProgressSocket(42, {}, '/ws/chat')
    sockets[0].readyState = 1
    sockets[0].onopen()
    vi.advanceTimersByTime(30000)
    expect(sockets[0].send).toHaveBeenCalledWith('ping')
    sockets[0].onclose()
    vi.advanceTimersByTime(1000)
    expect(new URL(sockets[1].url).pathname).toBe('/ws/chat')
    connection.close()
    vi.advanceTimersByTime(60000)
    expect(sockets).toHaveLength(2)
    expect(vi.getTimerCount()).toBe(0)
  })
})
