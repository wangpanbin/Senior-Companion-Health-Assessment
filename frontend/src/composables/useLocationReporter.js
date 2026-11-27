/**
 * 位置上报调度（ADR-0022 D3）。
 *
 * 陪诊员在打卡页停留期间，每 30 秒把自己的位置上报一次，家属端就能在地图上
 * 看到「人现在到哪了」。
 *
 * ## 三条必须做对的事
 *
 * 1. **只在页面可见时上报**。`document.visibilitychange` 隐藏即暂停 ——
 *    手机锁屏后浏览器本就拿不到定位，继续跑定时器只会积累一堆失败请求。
 *    恢复可见时**立刻补报一次**再继续，否则用户要盯着一个旧点再等 30 秒。
 * 2. **失败静默重试**。定位失败（权限拒绝 / 超时 / 室内 GPS 漂移）是常态，
 *    一次失败不该弹 toast 打断打卡流程。连续 3 次失败才通过 `onDegraded` 让页面
 *    显示一条常驻提示（而不是每次失败弹一次）。
 * 3. **离开页面必须清干净**。定时器 + 事件监听都要在 `stop()` 里摘除，
 *    否则来回进出打卡页会堆出多条上报流（每条都在跑 `getCurrentPosition`）。
 *
 * ## 一个必须承认的硬边界
 *
 * 手机锁屏 / 浏览器切到后台时，Web 无法持续取定位。这是平台限制而非实现缺陷。
 * 因此家属端在 `staleSeconds > 180` 时必须显示「位置已 x 分钟未更新」，
 * **不允许**把陈旧点渲染成「在线」。
 *
 * @see docs/api/04-companion-execution.md §7
 */
import { onBeforeUnmount, ref, watch } from 'vue'
import { reportLocation } from '@/api/execution'

/** 上报间隔：与后端文档约定的 30 秒一致，不要随意调小（会触发 4004 且费电） */
export const REPORT_INTERVAL_MS = 30000

/** 连续失败多少次后向页面报告「定位暂时不可用」 */
const DEGRADE_AFTER_FAILURES = 3

/** 单次定位超时（毫秒）：室内 / 地下车库常常拿不到，宁可快速失败等下一轮 */
const GEOLOCATION_TIMEOUT_MS = 15000

/** 可接受的最大缓存年龄：10 秒内的位置足够新，用缓存省一次 GPS 唤醒 */
const GEOLOCATION_MAX_AGE_MS = 10000

/**
 * 取一次当前位置（Promise 包装）。
 *
 * @returns {Promise<{longitude: string, latitude: string, accuracy: number|null}|null>}
 *          取不到时 resolve `null`，**不 reject** —— 调用方只需要知道「这次没取到」
 */
function getCurrentPosition() {
  return new Promise((resolve) => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      resolve(null)
      return
    }
    navigator.geolocation.getCurrentPosition(
      (pos) =>
        resolve({
          longitude: String(pos.coords.longitude),
          latitude: String(pos.coords.latitude),
          accuracy: Number.isFinite(pos.coords.accuracy) ? pos.coords.accuracy : null
        }),
      () => resolve(null),
      {
        enableHighAccuracy: true,
        timeout: GEOLOCATION_TIMEOUT_MS,
        maximumAge: GEOLOCATION_MAX_AGE_MS
      }
    )
  })
}

/**
 * 启动/停止位置上报。
 *
 * @param {object} options
 * @param {import('vue').Ref<number|string|null>|(() => number|string|null)} options.orderId 订单 ID（响应式）
 * @param {import('vue').Ref<boolean>|(() => boolean)} [options.enabled]
 *        是否应当上报（通常绑定「订单状态为 ACCEPTED/IN_SERVICE」且「共享开关为开」）
 * @param {(vo: object) => void} [options.onReported] 上报成功回调，参数是后端返回的 LocationVO
 * @param {(info: {degraded: boolean}) => void} [options.onDegraded] 连续失败后的降级通知
 * @returns {{ start: () => void, stop: () => void, reportNow: () => Promise<void>, status: import('vue').Ref<string> }}
 */
export function useLocationReporter(options = {}) {
  const { orderId, enabled, onReported, onDegraded } = options

  /** idle 未启动 / reporting 正常上报 / degraded 连续失败 / stopped 已停止 */
  const status = ref('idle')
  let timer = null
  let failures = 0
  let started = false

  const readOrderId = () => (typeof orderId === 'function' ? orderId() : orderId?.value)
  const isEnabled = () => {
    if (enabled == null) return true
    return typeof enabled === 'function' ? Boolean(enabled()) : Boolean(enabled.value)
  }

  /** 上报一次；无论成功失败都不抛，由调用方按 status 决定 UI */
  async function reportNow() {
    const id = readOrderId()
    if (!id) return
    const loc = await getCurrentPosition()
    if (!loc) {
      // 取不到定位：算一次失败，但不发请求（后端也做不了什么）
      handleFailure()
      return
    }
    try {
      const vo = await reportLocation(id, {
        longitude: loc.longitude,
        latitude: loc.latitude,
        accuracy: loc.accuracy ?? undefined
      })
      failures = 0
      status.value = 'reporting'
      onReported?.(vo)
    } catch (err) {
      // 4004（上报过频）与 4005（已暂停共享）都是**明确的业务答复**，不是故障：
      // 前者说明上一轮还没到间隔，后者说明用户刚关了开关 —— 都不该计入失败次数
      const code = err?.code
      if (code !== 4004 && code !== 4005) {
        handleFailure()
      }
    }
  }

  function handleFailure() {
    failures += 1
    if (failures >= DEGRADE_AFTER_FAILURES) {
      status.value = 'degraded'
      onDegraded?.({ degraded: true })
    }
  }

  function tick() {
    // 页面隐藏时不发：浏览器在后台本就会限制定位，发了也是白发
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
    if (!isEnabled()) return
    reportNow()
  }

  function handleVisibility() {
    if (typeof document === 'undefined') return
    if (document.visibilityState === 'visible' && started && isEnabled()) {
      // 恢复可见立刻补报一次，而不是让用户盯着一个旧点再等 30 秒
      reportNow()
    }
  }

  function start() {
    if (started) return
    started = true
    status.value = 'reporting'
    tick()
    timer = setInterval(tick, REPORT_INTERVAL_MS)
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', handleVisibility)
    }
  }

  function stop() {
    started = false
    status.value = 'stopped'
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', handleVisibility)
    }
  }

  // 开关一变（后端状态同步 / 用户拨动）就启停，不需要页面各自写 watch
  if (enabled != null) {
    watch(
      () => isEnabled(),
      (on) => {
        if (on) start()
        else stop()
      },
      { immediate: true }
    )
  }

  onBeforeUnmount(stop)

  return { start, stop, reportNow, status }
}
