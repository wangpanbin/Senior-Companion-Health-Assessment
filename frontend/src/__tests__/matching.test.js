/**
 * V3.4 智能调度 · 前端单测
 *
 * 锁两件事：
 *
 * 1) **大厅排序选择器** —— 三个选项（推荐 / 距离最近 / 时间匹配）、
 *    不含 INCOME（本版后端仍对它返回 400）、切换后**重新向后端要数并带上 sortBy**、
 *    切换后回到第 1 页（前端不对已有数据自行排序）。
 *
 * 2) **家属端候选展示** —— 正常路径渲染姓名 / 总分 / 理由 / 降级说明、
 *    空候选走空态（不是报错）、接口失败走错误态且可重试、
 *    notes 为空数组时不渲染说明行。
 *
 * 外加一条合规红线：页面文本**不得**出现「已指派 / 已分配 / 自动派单」这类
 * 暗示系统替用户拍板的表述 —— 推荐 ≠ 派单，接单权仍在陪诊员。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'

/* ---------------- mock：后端接口 ---------------- */
const listOrderHallMock = vi.fn()
const acceptOrderMock = vi.fn()
const getOrderMock = vi.fn()
const recommendCompanionsMock = vi.fn()

vi.mock('@/api/order', () => ({
  listOrderHall: (...a) => listOrderHallMock(...a),
  acceptOrder: (...a) => acceptOrderMock(...a),
  getOrder: (...a) => getOrderMock(...a),
  getOrderTimeline: () => Promise.resolve([]),
  cancelOrder: () => Promise.resolve({}),
  urgeOrder: () => Promise.resolve({}),
  listFeeItems: () => Promise.resolve(null),
  recommendCompanions: (...a) => recommendCompanionsMock(...a)
}))

vi.mock('@/api/user', () => ({
  getMyCompanionApplication: () => Promise.resolve({ auditStatus: 'APPROVED' }),
  getCompanionProfile: () => Promise.resolve({ workStatus: 'AVAILABLE' }),
  updateCompanionWorkStatus: () => Promise.resolve({ workStatus: 'AVAILABLE' })
}))

vi.mock('@/api/execution', () => ({
  listCheckins: () => Promise.resolve([]),
  connectProgressSocket: () => ({ close: () => {} })
}))

vi.mock('@/api/review', () => ({
  getReviewByOrder: () => Promise.resolve(null)
}))

/* 登录态：大厅页要拿陪诊员自己的 id（拉接单状态） */
vi.mock('@/store/modules/user', () => ({
  useUserStore: () => ({ userInfo: { id: 301 } })
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ params: { id: '1001' }, meta: {} })
}))

/* 形态可控：默认手机形态，个别用例切到桌面形态 */
const deviceState = vi.hoisted(() => ({ mobile: true }))
vi.mock('@/composables/useDevice', async () => {
  const { ref } = await import('vue')
  return {
    useDevice: () => ({
      isMobile: ref(deviceState.mobile),
      isDesktop: ref(!deviceState.mobile)
    })
  }
})

/* 页面壳：只验页面自身内容，壳内部（导航 / TabBar / 用户菜单）与本页无关 */
vi.mock('@/components', async (importOriginal) => {
  const actual = await importOriginal()
  const passThrough = (name) => ({
    name,
    template:
      '<div class="shell-stub"><slot /><slot name="nav-right" /><slot name="tabbar" />' +
      '<slot name="actions" /><slot name="extra" /><slot name="title" /></div>'
  })
  return {
    ...actual,
    NlPhoneShell: passThrough('NlPhoneShell'),
    NlDesktopShell: passThrough('NlDesktopShell'),
    NlPageShell: passThrough('NlPageShell'),
    NlAppTabBar: { name: 'NlAppTabBar', template: '<div />' },
    NlUserMenu: { name: 'NlUserMenu', template: '<div />' }
  }
})

/* 费用明细是独立子组件，会自己打接口 —— 这里换成空壳 */
vi.mock('@/components/OrderFeeItems.vue', () => ({
  default: { name: 'OrderFeeItems', template: '<div class="fee-items-stub" />' }
}))

// 动态 import：确保 mock 注册后再加载组件
const { default: Hall } = await import('@/views/companion/hall.vue')
const { default: OrderDetail } = await import('@/views/family/order-detail.vue')

const ElButtonStub = {
  props: ['type', 'round', 'link', 'size', 'plain', 'loading'],
  template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
}

const ElTagStub = {
  props: ['type', 'effect', 'size'],
  template: '<span class="el-tag-stub"><slot /></span>'
}

const GLOBAL_STUBS = {
  ElButton: ElButtonStub,
  'el-button': ElButtonStub,
  ElTag: ElTagStub,
  'el-tag': ElTagStub
}

/* ---------------- 夹具 ---------------- */

const HALL_PAGE = {
  total: 2,
  page: 1,
  size: 50,
  pages: 1,
  records: [
    { id: 1001, status: 'PENDING', hospital: '海南省人民医院', department: '心内科', fee: 180 },
    { id: 1002, status: 'PENDING', hospital: '三亚中心医院', department: '骨科', fee: 220 }
  ]
}

const ORDER_PENDING = {
  id: 1001,
  orderNo: 'NL20261020001',
  status: 'PENDING',
  statusLabel: '待接单',
  createTime: '2026-10-20 09:00:00',
  visitTime: '2026-10-22 08:30:00',
  hospital: '海南省人民医院',
  department: '心内科',
  address: '海口市美兰区海府路 12 号',
  elderName: '张德海',
  elderAge: 72,
  fee: '180.00',
  paymentStatusLabel: '线上记账 + 线下结算'
}

const CANDIDATES = {
  orderId: 1001,
  total: 12,
  candidates: [
    {
      companionId: 301,
      companionName: '李*军',
      totalScore: 86,
      regionScore: 100,
      timeScore: 100,
      distanceScore: 0,
      skillScore: 60,
      ratingScore: 96,
      reasons: ['同服务区域', '就诊时间在可服务时段内', '服务过该科室 12 单'],
      notes: ['未填写坐标，距离维度记 0']
    }
  ]
}

async function mountHall() {
  const wrapper = mount(Hall, { global: { stubs: GLOBAL_STUBS } })
  await flushPromises()
  await nextTick()
  return wrapper
}

async function mountOrderDetail() {
  const wrapper = mount(OrderDetail, { global: { stubs: GLOBAL_STUBS } })
  await flushPromises()
  await nextTick()
  return wrapper
}

/* ================================================================== *
 * 1 · 大厅排序选择器
 * ================================================================== */

describe('大厅排序选择器 · 选项与透传', () => {
  beforeEach(() => {
    deviceState.mobile = true
    listOrderHallMock.mockReset().mockResolvedValue(HALL_PAGE)
    acceptOrderMock.mockReset()
    getOrderMock.mockReset().mockResolvedValue(ORDER_PENDING)
    recommendCompanionsMock.mockReset().mockResolvedValue(CANDIDATES)
  })

  it('渲染三个排序选项：推荐 / 距离最近 / 时间匹配', async () => {
    const wrapper = await mountHall()
    const labels = wrapper.findAll('.sort__btn').map((b) => b.text())
    expect(labels).toEqual(['推荐', '距离最近', '时间匹配'])
  })

  it('不提供 INCOME（收益优先）选项 —— 后端对它仍返回 400', async () => {
    const wrapper = await mountHall()
    const text = wrapper.text()
    expect(text).not.toContain('收益优先')
    expect(text).not.toContain('INCOME')
    expect(wrapper.findAll('.sort__btn').length).toBe(3)
  })

  it('初次加载把当前排序维度传给后端（默认 RECOMMEND）', async () => {
    await mountHall()
    expect(listOrderHallMock).toHaveBeenCalled()
    expect(listOrderHallMock.mock.calls[0][0]).toMatchObject({
      sortBy: 'RECOMMEND',
      page: 1
    })
  })

  it('切换排序会重新调用接口，且带上新的 sortBy', async () => {
    const wrapper = await mountHall()
    const before = listOrderHallMock.mock.calls.length
    const btn = wrapper.findAll('.sort__btn').find((b) => b.text() === '距离最近')
    expect(btn).toBeTruthy()
    await btn.trigger('click')
    await flushPromises()
    await nextTick()

    expect(listOrderHallMock.mock.calls.length).toBeGreaterThan(before)
    const last = listOrderHallMock.mock.calls[listOrderHallMock.mock.calls.length - 1][0]
    expect(last.sortBy).toBe('DISTANCE')
  })

  it('切换排序回到第 1 页', async () => {
    const wrapper = await mountHall()
    const btn = wrapper.findAll('.sort__btn').find((b) => b.text() === '时间匹配')
    await btn.trigger('click')
    await flushPromises()
    await nextTick()
    const last = listOrderHallMock.mock.calls[listOrderHallMock.mock.calls.length - 1][0]
    expect(last.sortBy).toBe('TIME_MATCH')
    expect(last.page).toBe(1)
  })

  it('切换排序不在前端自行重排：仍只依赖后端返回的 records 顺序', async () => {
    const wrapper = await mountHall()
    const btn = wrapper.findAll('.sort__btn').find((b) => b.text() === '距离最近')
    await btn.trigger('click')
    await flushPromises()
    await nextTick()
    // 后端仍返回 1001 → 1002 的顺序，页面就照这个顺序渲染（前端不排）
    const ids = wrapper.findAll('.order').map((el) => el.text())
    expect(ids.length).toBe(2)
    expect(ids[0]).toContain('海南省人民医院')
    expect(ids[1]).toContain('三亚中心医院')
  })

  it('桌面形态同样有排序选择器（两处模板都改了）', async () => {
    deviceState.mobile = false
    const wrapper = await mountHall()
    const labels = wrapper.findAll('.sort__btn').map((b) => b.text())
    expect(labels).toEqual(['推荐', '距离最近', '时间匹配'])
  })
})

/* ================================================================== *
 * 2 · 家属端候选展示
 * ================================================================== */

describe('候选展示 · 正常路径', () => {
  beforeEach(() => {
    deviceState.mobile = true
    listOrderHallMock.mockReset().mockResolvedValue(HALL_PAGE)
    getOrderMock.mockReset().mockResolvedValue(ORDER_PENDING)
    recommendCompanionsMock.mockReset().mockResolvedValue(CANDIDATES)
  })

  it('渲染脱敏姓名 / 加权总分 / 五个维度得分 / 理由 / 降级说明', async () => {
    const wrapper = await mountOrderDetail()
    const text = wrapper.text()
    expect(text).toContain('系统正在为你匹配')
    expect(text).toContain('李*军')
    expect(text).toContain('86')
    expect(text).toContain('区域 100')
    expect(text).toContain('时间 100')
    expect(text).toContain('距离 0')
    expect(text).toContain('服务 60')
    expect(text).toContain('评分 96')
    expect(text).toContain('同服务区域')
    expect(text).toContain('服务过该科室 12 单')
    expect(text).toContain('未填写坐标，距离维度记 0')
  })

  it('明确标注推荐不等于接单', async () => {
    const wrapper = await mountOrderDetail()
    expect(wrapper.text()).toContain('最终由陪诊员本人确认')
  })

  it('候选为空数组时走空态（不是报错）', async () => {
    recommendCompanionsMock.mockResolvedValue({ orderId: 1001, total: 0, candidates: [] })
    const wrapper = await mountOrderDetail()
    const text = wrapper.text()
    expect(text).toContain('暂无匹配候选')
    expect(text).not.toContain('匹配信息加载失败')
  })

  it('notes 为空数组时不渲染降级说明行', async () => {
    recommendCompanionsMock.mockResolvedValue({
      orderId: 1001,
      total: 1,
      candidates: [{ ...CANDIDATES.candidates[0], notes: [] }]
    })
    const wrapper = await mountOrderDetail()
    expect(wrapper.findAll('.cand__notes').length).toBe(0)
    expect(wrapper.findAll('.cand__reasons').length).toBe(1)
  })

  it('接口失败走错误态并给出重试入口', async () => {
    recommendCompanionsMock.mockRejectedValue(new Error('boom'))
    const wrapper = await mountOrderDetail()
    const text = wrapper.text()
    expect(text).toContain('匹配信息加载失败')
    expect(text).toContain('重试')
  })

  it('点重试会重新请求候选', async () => {
    recommendCompanionsMock.mockRejectedValueOnce(new Error('boom'))
    const wrapper = await mountOrderDetail()
    const before = recommendCompanionsMock.mock.calls.length
    const retry = wrapper.findAll('.el-button-stub').find((b) => b.text().includes('重试'))
    expect(retry).toBeTruthy()
    await retry.trigger('click')
    await flushPromises()
    expect(recommendCompanionsMock.mock.calls.length).toBeGreaterThan(before)
  })

  it('非「待接单」状态不展示候选区，也不请求候选接口', async () => {
    getOrderMock.mockResolvedValue({ ...ORDER_PENDING, status: 'ACCEPTED', statusLabel: '已接单' })
    const wrapper = await mountOrderDetail()
    expect(recommendCompanionsMock).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('系统正在为你匹配')
  })
})

/* ================================================================== *
 * 3 · 合规红线：不得暗示系统自动派单
 * ================================================================== */

describe('候选展示 · 不暗示自动派单', () => {
  beforeEach(() => {
    deviceState.mobile = true
    listOrderHallMock.mockReset().mockResolvedValue(HALL_PAGE)
    getOrderMock.mockReset().mockResolvedValue(ORDER_PENDING)
    recommendCompanionsMock.mockReset().mockResolvedValue(CANDIDATES)
  })

  const FORBIDDEN = ['已指派', '已分配', '自动派单', '已为您指派', '系统已分配']

  it('候选区渲染出的文本不含「已指派 / 已分配 / 自动派单」', async () => {
    const wrapper = await mountOrderDetail()
    const text = wrapper.text()
    FORBIDDEN.forEach((w) => {
      expect(text, `页面出现了「${w}」`).not.toContain(w)
    })
  })

  it('空态与错误态同样不含违禁表述', async () => {
    recommendCompanionsMock.mockResolvedValue({ orderId: 1001, total: 0, candidates: [] })
    const empty = await mountOrderDetail()
    FORBIDDEN.forEach((w) => expect(empty.text()).not.toContain(w))

    recommendCompanionsMock.mockRejectedValue(new Error('boom'))
    const failed = await mountOrderDetail()
    FORBIDDEN.forEach((w) => expect(failed.text()).not.toContain(w))
  })
})
