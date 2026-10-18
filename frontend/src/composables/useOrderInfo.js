/**
 * M13 聊天室 · 订单信息面板数据装配
 *
 * 供 NlOrderInfoPanel 消费：订单概览 + 陪诊员资料 + 老人实时进度/打卡。
 *
 * ## 为什么要分块降级
 *
 * 面板由四块组成，只有 `order` 是必得块（拿不到它连陪诊员是谁都无从判断），
 * `progress` / `checkins` / `companion` 都可以缺：
 *
 *   - `companion` 缺失很常见 —— `GET /api/user/companion/{id}` 对**未过审**陪诊员
 *     直接抛 `COMPANION_NOT_FOUND`（CompanionServiceImpl#publicProfile）。
 *   - `progress` / `checkins` 在订单还没接单、或服务尚未开始打卡时本就为空。
 *
 * 因此每块独立 `.catch`，独立 retry：任一块失败都不该让另外三块跟着消失。
 *
 * ## 竞态防护（照抄 useChatMessages 的 context 模式）
 *
 * 切订单时必须 `generation++` 并丢弃所有在途响应 —— 否则「先点订单 A 再点 B」时，
 * A 的慢响应会写进 B 的面板。`useChatMessages.js` 已经踩过同一个坑。
 *
 * ## 实时性
 *
 * 进度走**独立的** `/ws/progress` 连接（与聊天的 `/ws/chat` 不共享实例，
 * 各自 30s ping + 1s 重连）。⚠️ 按 `ProgressVO` 的后端约定，**必须先拉一次
 * `/progress` 快照铺底**再订阅推送，只等推送的话连接建立前页面会一直空白。
 * 另起一个 60s 定时器覆盖 WS 断线窗口。
 */

import { onBeforeUnmount, ref, toValue, watch } from 'vue'
import { getOrder } from '@/api/order'
import { connectProgressSocket, getProgress, listCheckins } from '@/api/execution'
import { getCompanionProfile } from '@/api/user'

/** WS 断线时的兜底轮询间隔（与 useChatMessages 的 60s 拉齐）。 */
const POLL_INTERVAL = 60000

export function useOrderInfo(orderId) {
  const order = ref(null)
  const progress = ref(null)
  const checkins = ref([])
  const companion = ref(null)

  const loading = ref(false)
  const error = ref(false)
  const progressError = ref(false)
  const checkinsError = ref(false)
  const companionError = ref(false)

  let generation = 0
  let socket = null
  let timer = null

  const current = (run) => run === generation
  const id = () => {
    const value = toValue(orderId)
    return value == null ? '' : String(value)
  }

  function closeSocket() {
    socket?.close()
    socket = null
    clearInterval(timer)
    timer = null
  }

  /**
   * 启动兜底轮询（幂等）。
   *
   * ⚠️ 必须防重复：连点两次「重试」会让两个 `load()` 并发跑完，它们的
   * `run` 是同一个 generation，都能通过 `current(run)`。若这里无条件
   * `setInterval`，第二个定时器会覆盖 `timer` 引用，第一个再也
   * `clearInterval` 不到 —— 表现为卸载后仍在发请求。
   */
  function ensureTimer(run) {
    if (timer) return
    timer = setInterval(() => refreshLive(run), POLL_INTERVAL)
  }

  function reset() {
    generation++
    closeSocket()
    order.value = null
    progress.value = null
    checkins.value = []
    companion.value = null
    loading.value = false
    error.value = false
    progressError.value = false
    checkinsError.value = false
    companionError.value = false
  }

  /** 打卡推送只刷新「实时情况」两块，不重拉订单资料与陪诊员档案。 */
  async function refreshLive(run = generation) {
    const current_ = id()
    if (!current_ || !current(run)) return
    const [nextProgress, nextCheckins] = await Promise.allSettled([
      getProgress(current_),
      listCheckins(current_)
    ])
    if (!current(run)) return
    if (nextProgress.status === 'fulfilled') {
      progress.value = nextProgress.value
      progressError.value = false
    } else {
      progressError.value = true
    }
    if (nextCheckins.status === 'fulfilled') {
      checkins.value = nextCheckins.value || []
      checkinsError.value = false
    } else {
      checkinsError.value = true
    }
  }

  /** 第二段：陪诊员资料依赖 order.companionId，只能在订单回来之后取。 */
  async function loadCompanion(companionId, run) {
    if (!current(run)) return
    if (companionId == null) {
      companion.value = null
      companionError.value = false
      return
    }
    try {
      const profile = await getCompanionProfile(companionId)
      if (!current(run)) return
      companion.value = profile
      companionError.value = false
    } catch {
      if (!current(run)) return
      // 未过审陪诊员走这里。资料缺失不是订单故障，面板其余部分照常渲染。
      companion.value = null
      companionError.value = true
    }
  }

  async function load() {
    const current_ = id()
    const run = generation
    closeSocket()
    if (!current_) {
      loading.value = false
      return
    }
    loading.value = true
    error.value = false
    try {
      const [orderResult] = await Promise.allSettled([getOrder(current_), refreshLive(run)])
      if (!current(run)) return
      if (orderResult.status === 'rejected') {
        // 必得块失败 = 整块面板不可用；不再请求陪诊员资料。
        order.value = null
        error.value = true
        loading.value = false
        return
      }
      order.value = orderResult.value
      error.value = false
      loading.value = false
      await loadCompanion(orderResult.value?.companionId, run)
      if (!current(run)) return
      openSocket(current_, run)
      ensureTimer(run)
    } catch {
      if (!current(run)) return
      error.value = true
      loading.value = false
    }
  }

  function openSocket(target, run) {
    if (!current(run) || socket) return
    socket = connectProgressSocket(target, {
      onEvent: (event) => {
        if (!current(run)) return
        if (event?.orderId != null && String(event.orderId) !== String(target)) return
        if (event?.type === 'ORDER_PROGRESS' || event?.type === 'CONNECTED') refreshLive(run)
      }
    })
  }

  watch(
    () => toValue(orderId),
    () => {
      reset()
      load()
    },
    { immediate: true }
  )

  onBeforeUnmount(() => {
    reset()
  })

  return {
    order,
    progress,
    checkins,
    companion,
    loading,
    error,
    progressError,
    checkinsError,
    companionError,
    refresh: load,
    retryOrder: load,
    retryProgress: () => refreshLive(),
    retryCheckins: () => refreshLive(),
    retryCompanion: () => loadCompanion(order.value?.companionId, generation)
  }
}
