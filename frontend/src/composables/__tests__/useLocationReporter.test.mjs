import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'

/**
 * useLocationReporter 单测（ADR-0022 D3）。
 *
 * 这个 composable 是「家属端地图会动」的唯一动力源，而它做错的四种方式在
 * 功能测试里都看不出来：
 *
 *   1. **页面隐藏时还在上报** → 手机锁屏后浏览器本就拿不到定位，白发一堆失败请求；
 *   2. **恢复可见后不补报** → 用户要盯着一个旧点再等 30 秒；
 *   3. **4004/4005 被当成故障** → 连续「失败」三次就弹「定位不可用」，
 *      而那两个码是**明确的业务答复**（上一轮还没过间隔 / 用户刚关了开关）；
 *   4. **卸载时不清理** → 来回进出打卡页堆出多条上报流，每条都在跑 getCurrentPosition。
 *
 * 所以这里逐条钉住。
 */

const reportLocationMock = vi.fn()

vi.mock('@/api/execution', () => ({
  reportLocation: (...args) => reportLocationMock(...args)
}))

const { useLocationReporter, REPORT_INTERVAL_MS } = await import(
  '@/composables/useLocationReporter'
)

/** 本文件里所有已启动的 reporter，afterEach 统一 stop —— 否则上一个用例的定时器
 *  会继续在下一个用例的 fake timer 里跑到，断言「调用次数」全部失真 */
const started = []

function startWith(options) {
  const handle = useLocationReporter(options)
  started.push(handle)
  handle.start()
  return handle
}

/** 可控的 document.visibilityState */
function setVisibility(state) {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => state
  })
  document.dispatchEvent(new Event('visibilitychange'))
}

/** 可控的 geolocation；coords 传 null 表示「拒绝 / 取不到」 */
function stubGeolocation(coords) {
  const spy = vi.fn((success, failure) => {
    if (coords) success({ coords })
    else failure(new Error('denied'))
  })
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    get: () => ({ getCurrentPosition: spy })
  })
  return spy
}

const COORDS = { longitude: 110.3212, latitude: 20.0315, accuracy: 12 }

beforeEach(() => {
  vi.useFakeTimers()
  reportLocationMock.mockReset()
  reportLocationMock.mockResolvedValue({ longitude: '110.321200', latitude: '20.031500' })
  setVisibility('visible')
})

afterEach(() => {
  // 逐个 stop：把定时器与 visibilitychange 监听摘干净，避免跨用例污染
  started.splice(0).forEach((h) => h.stop())
  vi.useRealTimers()
})

describe('useLocationReporter · 上报节奏', () => {
  it('启动即上报一次，之后每 REPORT_INTERVAL_MS 一次', async () => {
    stubGeolocation(COORDS)
    startWith({ orderId: () => 1001 })
    await vi.advanceTimersByTimeAsync(0)
    expect(reportLocationMock).toHaveBeenCalledTimes(1)
    expect(reportLocationMock.mock.calls[0][0]).toBe(1001)
    expect(reportLocationMock.mock.calls[0][1]).toMatchObject({
      longitude: '110.3212',
      latitude: '20.0315'
    })

    await vi.advanceTimersByTimeAsync(REPORT_INTERVAL_MS)
    expect(reportLocationMock).toHaveBeenCalledTimes(2)

    await vi.advanceTimersByTimeAsync(REPORT_INTERVAL_MS)
    expect(reportLocationMock).toHaveBeenCalledTimes(3)
  })

  it('页面隐藏时不上报，恢复可见立刻补报一次', async () => {
    stubGeolocation(COORDS)
    startWith({ orderId: () => 1001 })
    await vi.advanceTimersByTimeAsync(0)
    expect(reportLocationMock).toHaveBeenCalledTimes(1)

    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(REPORT_INTERVAL_MS * 3)
    expect(reportLocationMock, '隐藏期间不应上报').toHaveBeenCalledTimes(1)

    setVisibility('visible')
    await vi.advanceTimersByTimeAsync(0)
    expect(reportLocationMock, '恢复可见应立刻补报').toHaveBeenCalledTimes(2)
  })

  it('stop() 之后不再上报（卸载清理）', async () => {
    stubGeolocation(COORDS)
    const { stop } = startWith({ orderId: () => 1001 })
    await vi.advanceTimersByTimeAsync(0)
    expect(reportLocationMock).toHaveBeenCalledTimes(1)

    stop()
    await vi.advanceTimersByTimeAsync(REPORT_INTERVAL_MS * 3)
    expect(reportLocationMock).toHaveBeenCalledTimes(1)
  })

  it('enabled 变 false 自动停、变 true 自动起（共享开关驱动）', async () => {
    stubGeolocation(COORDS)
    const enabled = ref(true)
    const handle = useLocationReporter({ orderId: () => 1001, enabled })
    started.push(handle)
    await vi.advanceTimersByTimeAsync(0)
    expect(reportLocationMock, 'enabled 为 true 时应自动启动').toHaveBeenCalledTimes(1)

    enabled.value = false
    await vi.advanceTimersByTimeAsync(REPORT_INTERVAL_MS * 2)
    expect(reportLocationMock, '关闭共享后应停止上报').toHaveBeenCalledTimes(1)

    enabled.value = true
    await vi.advanceTimersByTimeAsync(0)
    expect(reportLocationMock, '重新开启后应恢复上报').toHaveBeenCalledTimes(2)
  })
})

describe('useLocationReporter · 失败处理', () => {
  it('4004 / 4005 是业务答复而非故障：不累加失败次数、不触发降级', async () => {
    stubGeolocation(COORDS)
    reportLocationMock.mockRejectedValue(Object.assign(new Error('too frequent'), { code: 4004 }))
    const onDegraded = vi.fn()
    const { status } = startWith({ orderId: () => 1001, onDegraded })

    for (let i = 0; i < 5; i += 1) {
      await vi.advanceTimersByTimeAsync(REPORT_INTERVAL_MS)
    }
    expect(onDegraded).not.toHaveBeenCalled()
    expect(status.value).not.toBe('degraded')
  })

  it('真实失败连续 3 次后才降级', async () => {
    stubGeolocation(COORDS)
    reportLocationMock.mockRejectedValue(new Error('network down'))
    const onDegraded = vi.fn()
    const { status } = startWith({ orderId: () => 1001, onDegraded })

    await vi.advanceTimersByTimeAsync(0)
    expect(onDegraded, '第 1 次失败不降级').not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(REPORT_INTERVAL_MS)
    expect(onDegraded, '第 2 次失败不降级').not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(REPORT_INTERVAL_MS)
    expect(onDegraded, '第 3 次失败应降级').toHaveBeenCalled()
    expect(status.value).toBe('degraded')
  })

  it('取不到定位（权限拒绝）不发请求', async () => {
    stubGeolocation(null)
    startWith({ orderId: () => 1001 })
    await vi.advanceTimersByTimeAsync(0)
    expect(reportLocationMock, '没有坐标就不该发请求').not.toHaveBeenCalled()
  })

  it('上报成功后调 onReported 并回传 LocationVO', async () => {
    stubGeolocation(COORDS)
    const vo = { orderId: 1001, longitude: '110.321200', distanceMeters: 564 }
    reportLocationMock.mockResolvedValue(vo)
    const onReported = vi.fn()
    startWith({ orderId: () => 1001, onReported })
    await vi.advanceTimersByTimeAsync(0)
    expect(onReported).toHaveBeenCalledWith(vo)
  })
})
