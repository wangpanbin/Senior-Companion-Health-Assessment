/**
 * 陪诊进度实时通道（M5 · WebSocket）。
 *
 * 端点：`/ws/progress?token=<accessToken>&orderId=<orderId>`
 *
 * ⚠️ 三个必须做对的地方（对应后端 `JwtHandshakeInterceptor` / `OrderProgressHandler`）：
 *
 *   1. **令牌走 query，不走请求头。** 浏览器原生 `WebSocket` 构造函数不允许自定义
 *      请求头，后端因此专门在握手阶段从 query 取 token。写成 header 会得到一个
 *      永远连不上的连接，而浏览器控制台只会说「握手失败」，不给任何原因。
 *
 *   2. **令牌必须是「实时」的。** 握手会校验黑名单与密码版本（ver）。如果这里读的是
 *      某个早期快照里的 token，改过密码 / 登出过的用户会一直连不上。所以每次
 *      `connect()` 都现取一次 `getToken()`，重连时同理。
 *
 *   3. **心跳是客户端驱动的。** 后端约定客户端每 30 秒发一次 `ping`，服务端回 `pong`。
 *      不发的话，中间的代理层会在几分钟后静默掐掉空闲连接，前端却以为还连着。
 *
 * 服务端下行报文形状（`OrderProgressHub#buildPayload`）：
 *   { type: 'CONNECTED' | 'PONG' | 'ORDER_PROGRESS', orderId, data: { ... , pushTime } }
 */

import { getToken } from '@/utils/auth'

/** 心跳间隔：与后端约定一致，不要改小 —— 无意义的上行会白白占用连接 */
const HEARTBEAT_MS = 30000

/** 重连退避参数 */
const RECONNECT_BASE_MS = 1000
const RECONNECT_MAX_MS = 30000

/**
 * 拼 WebSocket 地址。
 *
 * 走同源相对路径（`/ws/progress`），由 vite dev server 代理到 8080；
 * 生产环境由 Nginx 转发。因此这里**不能**硬编码 `localhost:8080` ——
 * 一旦硬编码，局域网手机调试、Nginx 部署两种场景都会连不上。
 */
function buildUrl(orderId) {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const token = encodeURIComponent(getToken())
  return `${proto}//${window.location.host}/ws/progress?token=${token}&orderId=${encodeURIComponent(orderId)}`
}

/**
 * 建立某订单的进度订阅。
 *
 * @param {number|string} orderId 订单 ID
 * @param {object} handlers
 * @param {(payload: {type: string, orderId?: number, data?: object}) => void} [handlers.onEvent]
 *        收到服务端事件时回调（PONG 也会回调，调用方一般只关心 ORDER_PROGRESS）
 * @param {(status: 'connecting'|'open'|'closed', detail?: string) => void} [handlers.onStatus]
 *        连接状态变化，用于页面显示「实时/已断开」
 * @param {() => void} [handlers.onGiveUp] 重连次数用尽（令牌已失效等），调用方应停止等待
 * @returns {{ close: () => void, isOpen: () => boolean }}
 */
export function createProgressSocket(orderId, handlers = {}) {
  const { onEvent, onStatus, onGiveUp } = handlers

  let ws = null
  let heartbeatTimer = null
  let reconnectTimer = null
  let attempts = 0
  /** 主动关闭标记：区分「用户离开页面」与「意外断开」，前者不该触发重连 */
  let closedByUser = false

  function clearTimers() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function startHeartbeat() {
    clearInterval(heartbeatTimer)
    heartbeatTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        // 后端只认裸字符串 "ping"，不要包成 JSON
        ws.send('ping')
      }
    }, HEARTBEAT_MS)
  }

  function scheduleReconnect() {
    if (closedByUser) return

    // 指数退避封顶 30s。上限保留「继续重试」而不是彻底放弃 ——
    // 老人机在地下车库、电梯里断网是常态，网络恢复后应当自愈。
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** attempts, RECONNECT_MAX_MS)
    attempts += 1

    // 连续 5 次都连不上，基本可以判定不是网络抖动（令牌失效、订单归属变更、
    // 服务端未启动）。继续无限重试只会让控制台刷满错误，此时交回调用方决定。
    if (attempts > 5) {
      onStatus?.('closed', 'reconnect-exhausted')
      onGiveUp?.()
      return
    }

    onStatus?.('closed', `retry-in-${delay}ms`)
    reconnectTimer = setTimeout(connect, delay)
  }

  function connect() {
    if (closedByUser) return
    clearTimers()
    onStatus?.('connecting')

    try {
      ws = new WebSocket(buildUrl(orderId))
    } catch {
      // 构造阶段就抛（地址非法等），直接进入重连流程
      scheduleReconnect()
      return
    }

    ws.onopen = () => {
      attempts = 0
      onStatus?.('open')
      startHeartbeat()
    }

    ws.onmessage = (event) => {
      let payload
      try {
        payload = JSON.parse(event.data)
      } catch {
        // 报文不是 JSON（理论上不会发生），忽略而不是抛错 ——
        // 一次脏报文不该让整个订阅通道崩掉
        return
      }
      onEvent?.(payload)
    }

    ws.onerror = () => {
      // 浏览器出于安全考虑不把握手失败的原因（如 401 / 403）暴露给 JS，
      // 所以这里区分不了「令牌过期」与「网络抖动」，统一走退避重试。
      onStatus?.('closed', 'error')
    }

    ws.onclose = () => {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
      if (!closedByUser) {
        scheduleReconnect()
      }
    }
  }

  connect()

  return {
    close() {
      closedByUser = true
      clearTimers()
      if (ws) {
        // 置空回调，避免 close() 触发 onclose 里的重连分支
        ws.onclose = null
        ws.onerror = null
        ws.onmessage = null
        ws.onopen = null
        if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) {
          ws.close()
        }
        ws = null
      }
      onStatus?.('closed', 'closed-by-user')
    },
    isOpen() {
      return !!ws && ws.readyState === WebSocket.OPEN
    }
  }
}
