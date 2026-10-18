import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { useOrderInfo } from '../useOrderInfo'
import { getOrder } from '@/api/order'
import { connectProgressSocket, getProgress, listCheckins } from '@/api/execution'
import { getCompanionProfile } from '@/api/user'

vi.mock('@/api/order', () => ({ getOrder: vi.fn() }))
vi.mock('@/api/execution', () => ({
  getProgress: vi.fn(),
  listCheckins: vi.fn(),
  connectProgressSocket: vi.fn()
}))
vi.mock('@/api/user', () => ({ getCompanionProfile: vi.fn() }))

let host, flow, id, close, handlers
const ORDER = {
  id: 1,
  orderNo: 'NL20260820000031',
  status: 'IN_SERVICE',
  statusLabel: '服务中',
  elderName: '黄德海',
  elderAge: 78,
  companionId: 301,
  companionName: '黄建军',
  hospital: '海南省人民医院',
  department: '心血管内科',
  visitTime: '2026-08-25 09:00:00'
}
const PROGRESS = {
  orderId: 1,
  orderStatus: 'IN_SERVICE',
  orderStatusLabel: '服务中',
  currentNode: 'ARRIVED_HOSPITAL',
  currentNodeLabel: '已到院',
  nextNode: 'WAIT_QUEUE',
  nextNodeLabel: '候诊排队',
  progressPercent: 33,
  finishedNodes: ['DEPART', 'ARRIVED_HOSPITAL'],
  lastUpdateTime: '2026-08-25 09:12:00'
}
const CHECKINS = [
  {
    id: 1,
    node: 'DEPART',
    nodeLabel: '出发',
    address: '海口市美兰区海府路 1 号',
    isAbnormal: false,
    checkinTime: '2026-08-25 08:10:00'
  },
  {
    id: 2,
    node: 'ARRIVED_HOSPITAL',
    nodeLabel: '已到院',
    address: '海南省人民医院门诊楼',
    isAbnormal: true,
    checkinTime: '2026-08-25 09:12:00'
  }
]
const COMPANION = {
  id: 301,
  realName: '李建军',
  serviceArea: '海口市龙华区',
  availableTime: '工作日 08:00-18:00',
  auditStatus: 'APPROVED',
  auditStatusLabel: '资质已认证',
  score: '4.86',
  orderCount: 126,
  workStatus: 'AVAILABLE',
  workStatusLabel: '可接单'
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function start(initial = '1') {
  id = ref(initial)
  host = mount({
    setup() {
      flow = useOrderInfo(id)
      return () => null
    }
  })
  await flushPromises()
  return flow
}

beforeEach(() => {
  vi.clearAllMocks()
  close = vi.fn()
  getOrder.mockResolvedValue(ORDER)
  getProgress.mockResolvedValue(PROGRESS)
  listCheckins.mockResolvedValue(CHECKINS)
  getCompanionProfile.mockResolvedValue(COMPANION)
  connectProgressSocket.mockImplementation((_id, options) => {
    handlers = options
    return { close }
  })
})
afterEach(() => {
  host?.unmount()
})

describe('M13 订单信息面板数据装配', () => {
  it('loads order, progress and checkins concurrently, then companion profile by companionId', async () => {
    await start()
    // 第一段三并发：companionId 只可能来自 order，所以陪诊员资料必须是第二段。
    expect(getProgress).toHaveBeenCalledWith('1')
    expect(listCheckins).toHaveBeenCalledWith('1')
    expect(getCompanionProfile).toHaveBeenCalledWith(301)
    expect(flow.order.value).toMatchObject({ orderNo: 'NL20260820000031' })
    expect(flow.progress.value).toMatchObject({ currentNodeLabel: '已到院', progressPercent: 33 })
    expect(flow.checkins.value).toHaveLength(2)
    expect(flow.companion.value).toMatchObject({ score: '4.86', orderCount: 126 })
    expect(flow.loading.value).toBe(false)
    expect(flow.error.value).toBe(false)
  })

  it('skips the companion request when the order has not been accepted yet', async () => {
    getOrder.mockResolvedValue({ ...ORDER, companionId: null, companionName: null })
    await start()
    expect(getCompanionProfile).not.toHaveBeenCalled()
    expect(flow.companion.value).toBeNull()
    // 未接单不算错误：面板其余部分照常渲染。
    expect(flow.error.value).toBe(false)
  })

  it('degrades each block independently: companion failure never hides order or progress', async () => {
    // 未过审陪诊员：后端 publicProfile 抛 COMPANION_NOT_FOUND。
    getCompanionProfile.mockRejectedValue(new Error('3005'))
    await start()
    expect(flow.companionError.value).toBe(true)
    expect(flow.companion.value).toBeNull()
    expect(flow.order.value).toMatchObject({ companionName: '黄建军' })
    expect(flow.progress.value).toMatchObject({ progressPercent: 33 })
    expect(flow.checkins.value).toHaveLength(2)
    // 关键：order 是必得块，不能被可选块的成功/失败改写成整体错误。
    expect(flow.error.value).toBe(false)
  })

  it('degrades progress and checkins independently from a failed order', async () => {
    getProgress.mockRejectedValue(new Error('5000'))
    listCheckins.mockRejectedValue(new Error('5000'))
    await start()
    expect(flow.progressError.value).toBe(true)
    expect(flow.checkinsError.value).toBe(true)
    expect(flow.order.value).toMatchObject({ orderNo: 'NL20260820000031' })
  })

  it('reports a whole-panel failure when the order itself cannot be loaded', async () => {
    getOrder.mockRejectedValue(new Error('4003'))
    await start()
    expect(flow.error.value).toBe(true)
    expect(flow.order.value).toBeNull()
    // 面板整体失败时不该再去打扰陪诊员资料接口。
    expect(getCompanionProfile).not.toHaveBeenCalled()
  })

  it('drops stale responses after switching orders', async () => {
    const slow = deferred()
    getOrder.mockReturnValueOnce(slow.promise)
    await start('1')
    const pending = deferred()
    getOrder.mockReturnValueOnce(pending.promise)
    id.value = '2'
    await flushPromises()
    pending.resolve({ ...ORDER, id: 2, orderNo: 'NL2' })
    await flushPromises()
    expect(flow.order.value).toMatchObject({ orderNo: 'NL2' })
    // 旧订单的响应随后到达，不得写进当前会话。
    slow.resolve({ ...ORDER, orderNo: 'STALE' })
    await flushPromises()
    expect(flow.order.value).toMatchObject({ orderNo: 'NL2' })
  })

  it('closes the previous progress socket and stops timers on unmount', async () => {
    vi.useFakeTimers()
    try {
      await start()
      expect(connectProgressSocket).toHaveBeenCalledWith('1', expect.any(Object))
      host.unmount()
      host = null
      expect(close).toHaveBeenCalled()
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('refreshes only progress and checkins when ORDER_PROGRESS arrives', async () => {
    await start()
    getProgress.mockClear()
    listCheckins.mockClear()
    getOrder.mockClear()
    getCompanionProfile.mockClear()
    handlers.onEvent({ type: 'ORDER_PROGRESS', orderId: 1 })
    await flushPromises()
    expect(getProgress).toHaveBeenCalledWith('1')
    expect(listCheckins).toHaveBeenCalledWith('1')
    // 打卡不影响订单资料与陪诊员档案，不该重复请求。
    expect(getOrder).not.toHaveBeenCalled()
    expect(getCompanionProfile).not.toHaveBeenCalled()
  })

  it('ignores progress events belonging to another order', async () => {
    await start()
    getProgress.mockClear()
    handlers.onEvent({ type: 'ORDER_PROGRESS', orderId: 999 })
    await flushPromises()
    expect(getProgress).not.toHaveBeenCalled()
  })

  it('polls progress every 60s to cover websocket outages', async () => {
    vi.useFakeTimers()
    try {
      await start()
      getProgress.mockClear()
      listCheckins.mockClear()
      getOrder.mockClear()
      getCompanionProfile.mockClear()
      await vi.advanceTimersByTimeAsync(60000)
      expect(getProgress).toHaveBeenCalledWith('1')
      expect(listCheckins).toHaveBeenCalledWith('1')
      // 兜底轮询只刷实时情况，不重复请求订单资料与陪诊员档案。
      expect(getOrder).not.toHaveBeenCalled()
      expect(getCompanionProfile).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('retries the live blocks without refetching order or companion', async () => {
    getProgress.mockRejectedValueOnce(new Error('5000'))
    await start()
    expect(flow.progressError.value).toBe(true)
    getProgress.mockClear()
    listCheckins.mockClear()
    getOrder.mockClear()
    getCompanionProfile.mockClear()
    await flow.retryProgress()
    await flushPromises()
    expect(flow.progressError.value).toBe(false)
    expect(flow.progress.value).toMatchObject({ progressPercent: 33 })
    // 进度与打卡共用一条刷新路径（同一次 ORDER_PROGRESS 推送的产物），
    // 所以重试进度会一并刷打卡；但订单与陪诊员档案绝不能被重复请求。
    expect(getProgress).toHaveBeenCalled()
    expect(getOrder).not.toHaveBeenCalled()
    expect(getCompanionProfile).not.toHaveBeenCalled()
  })

  it('retries the companion block on its own', async () => {
    getCompanionProfile.mockRejectedValueOnce(new Error('3005'))
    await start()
    expect(flow.companionError.value).toBe(true)
    await flow.retryCompanion()
    await flushPromises()
    expect(flow.companionError.value).toBe(false)
    expect(flow.companion.value).toMatchObject({ orderCount: 126 })
  })

  it('does not leak a second polling timer when retry is clicked twice', async () => {
    vi.useFakeTimers()
    try {
      await start()
      // 连点「重试」：两个 load() 并发跑完，generation 相同。
      await Promise.all([flow.retryOrder(), flow.retryOrder()])
      await flushPromises()
      getProgress.mockClear()
      await vi.advanceTimersByTimeAsync(60000)
      // 每次 60s 窗口只应刷新一次。若第二个 setInterval 覆盖了 timer 引用，
      // 第一个定时器将永远 clearInterval 不到，60 秒内会刷新两次。
      expect(getProgress).toHaveBeenCalledTimes(1)
      host.unmount()
      host = null
      expect(vi.getTimerCount()).toBe(0)
    } finally {
      vi.useRealTimers()
    }
  })
})
