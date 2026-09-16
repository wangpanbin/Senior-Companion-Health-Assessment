import request from '@/utils/request'

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
 * 站内信实时推送（M8）。
 *
 * ⚠️ 真实情况与最初的骨架注释不同，这里必须说清楚：
 *
 *   后端通道是 **SSE**（`GET /sse/message`），不是 WebSocket —— 见
 *   `MessageSseController` / `MessageSseHub`。而该端点的鉴权读的是
 *   `Authorization: Bearer` 请求头，**浏览器原生 `EventSource` 无法自定义请求头**，
 *   因此当前**无法从浏览器直接订阅**。
 *
 *   后端在设计上已经预留了兜底路径：`MessageSseController` 的类注释明确写了
 *   「保留双通道 —— SSE 负责 3 秒内看到红点变化，`/api/message/unread-count`
 *   每 60 秒的轮询负责在代理层掐长连接等环境问题下仍然可用」，
 *   并强调两条通道**读的是同一份数据库状态**，不会出现两个数字打架。
 *
 *   所以前端当前走轮询（见下方 `startUnreadPolling`），功能完整。
 *   若要拿到「秒级红点」，需后端把 `/sse/message` 的令牌改为支持 query 参数
 *   （与 `/ws/progress` 同一套做法），前端再把下面这个函数换成 EventSource 即可。
 *
 * @param {number} [intervalMs] 轮询间隔，默认 60 秒（与后端注释约定一致）
 * @param {(count: number) => void} [onChange] 未读数变化时回调
 * @returns {{ stop: () => void, refresh: () => Promise<void> }}
 */
export function startUnreadPolling(intervalMs = 60000, onChange) {
  let timer = null
  let stopped = false
  let last = -1

  async function refresh() {
    try {
      const data = await getUnreadCount()
      // ⚠️ 后端 `UnreadCountVO` 的字段是 **`total`**（外加一份按类型分组的 `byType`），
      //    不是 `unreadCount`。早期这里按 `unreadCount` 读，结果恒为 0 ——
      //    红点永远不亮，而且不报错，是最难被发现的一类缺陷。
      //    这里同时兼容两种口径，避免后端将来改动时又静默失效。
      const count = Number(data?.total ?? data?.unreadCount ?? 0)
      if (count !== last) {
        last = count
        onChange?.(count, data?.byType || {})
      }
    } catch {
      // 轮询失败不弹提示：网络抖动时每 60 秒弹一次「网络异常」会把用户逼疯，
      // 真正的失败由用户主动操作时的请求暴露出来
    }
  }

  refresh()
  timer = setInterval(() => {
    if (!stopped && !document.hidden) {
      // 页面在后台时不轮询。老人机前台挂着一整天是常态，
      // 后台空转的轮询既费电也白白压服务端
      refresh()
    }
  }, intervalMs)

  return {
    stop() {
      stopped = true
      clearInterval(timer)
      timer = null
    },
    refresh
  }
}
