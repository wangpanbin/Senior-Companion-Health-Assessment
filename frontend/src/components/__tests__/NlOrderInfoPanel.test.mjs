import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import NlOrderInfoPanel from '@/components/chat/NlOrderInfoPanel.vue'

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
  orderStatusLabel: '服务中',
  currentNodeLabel: '已到院',
  nextNodeLabel: '候诊排队',
  progressPercent: 33,
  finishedNodes: ['DEPART', 'ARRIVED_HOSPITAL'],
  lastUpdateTime: '2026-08-25 09:12:00'
}
const CHECKINS = [
  {
    id: 1,
    nodeLabel: '出发',
    address: '海口市美兰区海府路 1 号',
    isAbnormal: false,
    checkinTime: '2026-08-25 08:10:00'
  },
  {
    id: 2,
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
  auditStatusLabel: '资质已认证',
  score: '4.86',
  orderCount: 126,
  workStatusLabel: '可接单'
}

let flow
function state(overrides = {}) {
  flow = {
    order: { value: ORDER },
    progress: { value: PROGRESS },
    checkins: { value: CHECKINS },
    companion: { value: COMPANION },
    loading: { value: false },
    error: { value: false },
    progressError: { value: false },
    checkinsError: { value: false },
    companionError: { value: false },
    retryOrder: vi.fn(),
    retryProgress: vi.fn(),
    retryCheckins: vi.fn(),
    retryCompanion: vi.fn(),
    ...overrides
  }
  return flow
}
function render(overrides = {}) {
  return mount(NlOrderInfoPanel, {
    props: { flow: state(overrides) },
    global: {
      stubs: {
        ElButton: {
          props: ['size', 'plain', 'text'],
          template: '<button><slot /></button>'
        }
      }
    }
  })
}
beforeEach(() => vi.clearAllMocks())

describe('M13 订单信息面板展示', () => {
  it('renders visit summary, progress, companion profile and recent checkins', async () => {
    const wrapper = render()
    await flushPromises()
    const text = wrapper.text()
    // 就诊概览
    expect(text).toContain('海南省人民医院')
    expect(text).toContain('心血管内科')
    expect(text).toContain('黄德海')
    expect(text).toContain('78')
    expect(text).toContain('08-25 09:00')
    // 服务进度
    expect(text).toContain('33%')
    expect(text).toContain('已到院')
    expect(text).toContain('候诊排队')
    expect(text).toContain('08-25 09:12')
    // 陪诊员资料
    expect(text).toContain('4.86')
    expect(text).toContain('126')
    expect(text).toContain('资质已认证')
    expect(text).toContain('海口市龙华区')
    expect(text).toContain('工作日 08:00-18:00')
    expect(text).toContain('可接单')
    // 最近打卡
    expect(text).toContain('出发')
    expect(text).toContain('海南省人民医院门诊楼')
    expect(wrapper.findAll('.order-info__checkin')).toHaveLength(2)
  })

  it('uses the companion name from the order detail (full name), never the masked peerName', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('黄建军')
    expect(wrapper.text()).not.toContain('黄*')
  })

  it('marks abnormal checkins instead of hiding them', async () => {
    const wrapper = render()
    await flushPromises()
    const abnormal = wrapper.findAll('.order-info__checkin--abnormal')
    expect(abnormal).toHaveLength(1)
    expect(abnormal[0].text()).toContain('异常')
  })

  it('renders a retry affordance per failed block and leaves the rest intact', async () => {
    const wrapper = render({ companionError: { value: true }, companion: { value: null } })
    await flushPromises()
    expect(wrapper.text()).toContain('暂未开放陪诊员资料')
    // 陪诊员块失败不影响其它三块
    expect(wrapper.text()).toContain('33%')
    expect(wrapper.text()).toContain('海南省人民医院')
    expect(wrapper.findAll('.order-info__checkin')).toHaveLength(2)
  })

  it('shows progress retry without hiding order data', async () => {
    const wrapper = render({ progressError: { value: true }, progress: { value: null } })
    await flushPromises()
    expect(wrapper.text()).toContain('服务进度暂时无法加载')
    expect(wrapper.text()).toContain('海南省人民医院')
  })

  it('shows checkin retry without hiding progress', async () => {
    const wrapper = render({ checkinsError: { value: true }, checkins: { value: null } })
    await flushPromises()
    expect(wrapper.text()).toContain('打卡记录暂时无法加载')
    expect(wrapper.text()).toContain('33%')
  })

  it('falls back to the whole-panel error state when the order fails', async () => {
    const wrapper = render({ error: { value: true }, order: { value: null } })
    await flushPromises()
    expect(wrapper.find('.order-info__fatal').exists()).toBe(true)
    expect(wrapper.find('.order-info__section').exists()).toBe(false)
  })

  it('skeletonises while loading', async () => {
    const wrapper = render({ loading: { value: true } })
    await flushPromises()
    expect(wrapper.find('.order-info__skeleton').exists()).toBe(true)
  })

  it('renders a not-yet-accepted order: no companion, no progress, but still the order', async () => {
    const wrapper = render({
      order: {
        value: {
          ...ORDER,
          status: 'PENDING',
          statusLabel: '待接单',
          companionId: null,
          companionName: null
        }
      },
      progress: { value: null },
      checkins: { value: [] },
      companion: { value: null }
    })
    await flushPromises()
    // 待接单时 /progress 与 /checkins 本就返回空，不是错误。
    expect(wrapper.text()).toContain('待接单')
    expect(wrapper.text()).toContain('尚未开始服务')
    expect(wrapper.find('.order-info__companion').exists()).toBe(false)
    expect(wrapper.find('.order-info__fatal').exists()).toBe(false)
    // 就诊概览仍要显示：家属下单后就能看到医院与就诊时间。
    expect(wrapper.text()).toContain('海南省人民医院')
  })

  it('never renders raw html for user supplied fields', async () => {
    const wrapper = render({
      order: { value: { ...ORDER, remark: '<img src=x onerror=alert(1)>' } },
      companion: { value: { ...COMPANION, serviceArea: '<b>危险</b>' } }
    })
    await flushPromises()
    expect(wrapper.find('img[onerror]').exists()).toBe(false)
    expect(wrapper.text()).toContain('<b>危险</b>')
  })

  it('tolerates a companion score of null without rendering NaN', async () => {
    const wrapper = render({ companion: { value: { ...COMPANION, score: null } } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('null')
    expect(wrapper.text()).not.toContain('NaN')
  })
})
