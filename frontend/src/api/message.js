import request from '@/utils/request'
import { getToken } from '@/utils/auth'

/**
 * 站内信接口
 * 对应文档：docs/api/07-message.md
 * 负责模块：M8 站内信与通知（后端 B）
 *
 * 背景：一期砍掉 IM 聊天，用站内信替代（计划书「范围控制」）。
 * 四类消息：订单事件 / 资质审核结果 / 漏服提醒 / 系统公告。
 */

/** 消息列表（分页 + 按类型筛选） */
export function listMessages(params) {
  return request({ url: '/message', method: 'get', params })
}

/** 未读数（顶栏红点）→ `{ total, byType }`（后端 UnreadCountVO，**不是** unreadCount） */
export function getUnreadCount() {
  return request({ url: '/message/unread-count', method: 'get' })
}

/** 标记单条已读 */
export function markRead(messageId) {
  return request({ url: `/message/${messageId}/read`, method: 'put' })
}

/** 全部已读 */
export function markAllRead() {
  return request({ url: '/message/read-all', method: 'put' })
}

/** 删除消息（仅自己可见的软删除） */
export function removeMessage(messageId) {
  return request({ url: `/message/${messageId}`, method: 'delete' })
}

/**
 * 站内信未读数订阅（M8）。
 *
 * 收口迭代 E4：轮询升级为 SSE 推送（后端 `/sse/message` 已支持 `?token=`，
 * 与 /ws/progress 同一套做法），本函数**不再使用 setInterval**。
 * 保留原函数名与 `onChange(count, byType)` 回调签名，调用方（companion/message.vue）零改动。
 * SSE 断开时浏览器 EventSource 会自动重连；`refresh()` 仍暴露给手动刷新场景。
 *
 * 后端推送的是**命名事件** NEW_MESSAGE（EventSource 的 onmessage 只收无名事件，
 * 必须用 addEventListener），负载 JSON 含 unreadCount（服务端实时算出的总数）。
 *
 * @param {number} [_intervalMs] 兼容旧签名的占位参数（SSE 模式下无意义）
 * @param {(count: number, byType?: Record<string, number>) => void} [onChange] 未读数变化回调
 * @returns {{ stop: () => void, refresh: () => Promise<void> }}
 */
export function startUnreadPolling(_intervalMs = 60000, onChange) {
  let source = null
  let stopped = false
  let last = -1

  function apply(count, byType) {
    if (count !== last) {
      last = count
      onChange?.(count, byType || {})
    }
  }

  async function refresh() {
    try {
      const data = await getUnreadCount()
      // ⚠️ 后端 `UnreadCountVO` 的字段是 **`total`**（外加一份按类型分组的 `byType`），
      //    不是 `unreadCount`。早期这里按 `unreadCount` 读，结果恒为 0 ——
      //    红点永远不亮，而且不报错，是最难被发现的一类缺陷。
      //    这里同时兼容两种口径，避免后端将来改动时又静默失效。
      const count = Number(data?.total ?? data?.unreadCount ?? 0)
      apply(count, data?.byType)
    } catch {
      // 与旧轮询同一取舍：静默失败，不弹提示
    }
  }

  function connect() {
    if (stopped) return
    // EventSource 不能带自定义头，令牌走 query（后端 MessageSseController 校验）
    source = new EventSource(`/sse/message?token=${encodeURIComponent(getToken())}`)
    source.addEventListener('NEW_MESSAGE', (event) => {
      try {
        const payload = JSON.parse(event.data)
        // 推送帧里的 unreadCount 是服务端实时算出的真值
        apply(Number(payload.unreadCount ?? payload.total ?? 0))
      } catch {
        // 心跳/注释帧解析失败忽略，等下一帧
      }
    })
    source.onerror = () => {
      // EventSource 自动重连；断连期间给一次主动拉取兜底，红点不至于停在旧值
      refresh()
    }
  }

  refresh()
  connect()

  return {
    stop() {
      stopped = true
      source?.close()
      source = null
    },
    refresh
  }
}
