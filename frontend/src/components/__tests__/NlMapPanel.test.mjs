import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

/**
 * NlMapPanel 单测（ADR-0022）。
 *
 * 这里守的是**降级路径**，不是地图本身 —— CI 无网络、无 AK，
 * 而「无 AK / 加载失败时页面必须给出距离与更新时间，且不白屏不弹错」
 * 才是评委机器上 100% 会被执行到的分支。
 *
 * 地图真实渲染无法在 jsdom 里验证（没有 WebGL / 瓦片），
 * 那部分由 `e2e/specs/23-map.spec.js` 与真实 AK 手工实测覆盖。
 */

const baiduMapMock = vi.hoisted(() => ({
  hasMapAk: vi.fn(() => false),
  // ⚠️ 用 mockImplementation 而不是 mockReturnValue(Promise.reject(...))：
  //    后者在 beforeEach 里就创建一个已被拒绝的 Promise，组件没来得及挂 .catch
  //    就会被 vitest 抓成 unhandled rejection
  loadBaiduMap: vi.fn(() => Promise.reject(new Error('map-no-ak')))
}))

vi.mock('@/utils/baiduMap', () => baiduMapMock)

const NlMapPanel = (await import('@/components/NlMapPanel.vue')).default

/** 与后端 LocationVO 一致的形状（WGS84） */
const POINT = {
  lng: '110.321200',
  lat: '20.031500',
  recordTime: '2026-09-20 09:10:00',
  staleSeconds: 30
}

function mountPanel(props = {}) {
  return mount(NlMapPanel, {
    props: {
      orderLng: '110.311200',
      orderLat: '20.021500',
      point: POINT,
      distanceMeters: 1526,
      ...props
    }
  })
}

describe('NlMapPanel · 降级卡片', () => {
  beforeEach(() => {
    baiduMapMock.hasMapAk.mockReturnValue(false)
    baiduMapMock.loadBaiduMap.mockImplementation(() => Promise.reject(new Error('map-no-ak')))
  })

  it('未配置 AK → 直接降级，不发请求，并给出「未配置地图 AK」', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    expect(baiduMapMock.loadBaiduMap).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('未配置地图 AK')
    expect(wrapper.find('.nl-map__fallback').exists()).toBe(true)
  })

  it('降级卡片仍给出距离与更新时间（地图没有，信息不能没有）', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('直线约 1.5 公里')
    expect(text).toContain('前更新')
  })

  it('降级卡片文案不出现「导航 / 路线 / 预计到达」（一期红线：不做导航）', async () => {
    const wrapper = mountPanel()
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('导航')
    expect(text).not.toContain('路线')
    expect(text).not.toContain('预计到达')
  })

  it('AK 有效但加载失败 → 降级并提示「地图暂不可用」，不抛到页面上', async () => {
    baiduMapMock.hasMapAk.mockReturnValue(true)
    baiduMapMock.loadBaiduMap.mockImplementation(() => Promise.reject(new Error('boom')))

    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('地图暂不可用')
    // 只 warn 不弹 toast：降级是设计内的分支，不是异常
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })

  it('position 缺失时降级卡片给出「尚未获取到位置」，而不是 0 米', async () => {
    const wrapper = mountPanel({ point: null, distanceMeters: null })
    await flushPromises()

    expect(wrapper.text()).toContain('尚未获取到位置')
    expect(wrapper.text()).not.toContain('0 米')
  })
})

describe('NlMapPanel · 状态文案', () => {
  beforeEach(() => {
    baiduMapMock.hasMapAk.mockReturnValue(false)
    baiduMapMock.loadBaiduMap.mockImplementation(() => Promise.reject(new Error('map-no-ak')))
  })

  it('暂停共享时优先显示「已暂停位置共享」', async () => {
    const wrapper = mountPanel({ sharingEnabled: false })
    await flushPromises()

    expect(wrapper.text()).toContain('已暂停位置共享')
  })

  it('staleSeconds 超过 180 时明确标注「位置已较长时间未更新」，不显示成在线', async () => {
    const wrapper = mountPanel({
      point: { ...POINT, staleSeconds: 600 }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('较长时间未更新')
  })

  it('距离小于 1000 米用米为单位（家属对「还有 300 米」有直觉）', async () => {
    const wrapper = mountPanel({ distanceMeters: 300 })
    await flushPromises()

    expect(wrapper.text()).toContain('直线约 300 米')
  })
})
