/**
 * 订单详情 · 路由离开回归（V4V5 联调缺陷 DEF-1）
 *
 * 背景（2026-11-14 ego-browser 联调发现）：
 * 在订单详情页点「生成草稿并继续」→ `router.push('/family/medication')`。
 * 离开时 `route.params.id` 变成 undefined，而
 * `watch(orderId, loadAndConnect)`（order-detail.vue:495）不区分
 * 「换了一单」和「正在离开页面」，带着 undefined 重拉四个接口，
 * 后端打出 `GET /api/execution/undefined/checkins`、
 * `GET /api/review/order/undefined` 的参数类型不匹配 WARN，
 * 前端还同时弹出红色报错 toast（与「草稿已生成」的成功提示同屏）。
 *
 * 判据：params.id 变 undefined（路由离开）时，**不得**再发任何
 * 订单相关请求；换订单（id 有值）的重拉行为不受影响。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'

/* ---------------- mock：后端接口（记录入参） ---------------- */
const getOrderMock = vi.fn()
const getOrderTimelineMock = vi.fn()
const listCheckinsMock = vi.fn()
const getLocationMock = vi.fn()
const getTrackMock = vi.fn()
const getReviewByOrderMock = vi.fn()
const recommendCompanionsMock = vi.fn()

vi.mock('@/api/order', () => ({
  getOrder: (...a) => getOrderMock(...a),
  getOrderTimeline: (...a) => getOrderTimelineMock(...a),
  // V6.0（FE-01）：履约详情已接进 loadAll，mock 必须给实现
  getOrderFulfillment: () => Promise.resolve(null),
  cancelOrder: () => Promise.resolve({}),
  urgeOrder: () => Promise.resolve({}),
  recommendCompanions: (...a) => recommendCompanionsMock(...a)
}))

vi.mock('@/api/execution', () => ({
  listCheckins: (...a) => listCheckinsMock(...a),
  // ADR-0022：页面接单后会多拉一次位置快照 + 轨迹；
  // 不在这里给出就必须 fail，否则会被当成 DEF-1 之外的「undefined 请求」假红
  getLocation: (...a) => getLocationMock(...a),
  getTrack: (...a) => getTrackMock(...a),
  connectProgressSocket: () => ({ close: () => {} })
}))

vi.mock('@/api/review', () => ({
  getReviewByOrder: (...a) => getReviewByOrderMock(...a)
}))

vi.mock('@/api/medication', () => ({
  buildDraftFromOrder: () => Promise.resolve({})
}))

/* ---------------- mock：可变路由（模拟换单 / 离开） ---------------- */
// 路由对象必须是响应式的：orderId 是 computed(route.params.id)，
// 非响应式对象不会触发 watch，测不出 DEF-1。
// ⚠️ 必须通过 reactive 代理改参数 —— 直接改原始对象会绕过 Proxy 的
// set 陷阱，watcher 不触发，断言会假绿（本测试首版就踩了这个坑）。
const routeHolder = vi.hoisted(() => ({ route: null }))

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  const route = reactive({ params: { id: '1031' }, meta: {}, query: {} })
  routeHolder.route = route
  return {
    useRoute: () => route,
    useRouter: () => ({ push: vi.fn() })
  }
})

/* 登录态：下单家属本人（转入用药计划按钮的显示条件） */
vi.mock('@/utils/auth', () => ({
  getUserInfo: () => ({ id: 101, role: 'FAMILY' })
}))

/* 形态可控（同 matching.test.js） */
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

/* 页面壳：只验页面自身内容 */
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

vi.mock('@/components/OrderFeeItems.vue', () => ({
  default: { name: 'OrderFeeItems', template: '<div class="fee-items-stub" />' }
}))

const { default: OrderDetail } = await import('@/views/family/order-detail.vue')

/* ---------------- Element Plus 桩（同 matching.test.js） ---------------- */
const ElButtonStub = {
  props: ['type', 'round', 'link', 'size', 'plain', 'loading'],
  template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
}
const ElTagStub = {
  props: ['type', 'effect', 'size'],
  template: '<span class="el-tag-stub"><slot /></span>'
}
const ElSelectStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<select class="el-select-stub" :value="modelValue" ' +
    '@change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>'
}
const ElOptionStub = {
  props: ['label', 'value'],
  template: '<option class="el-option-stub">{{ label }}</option>'
}
const ElDialogStub = {
  props: ['modelValue', 'title', 'width'],
  template: '<div v-if="modelValue" class="el-dialog-stub"><slot /><slot name="footer" /></div>'
}
const ElInputStub = {
  props: ['modelValue', 'placeholder', 'maxlength'],
  emits: ['update:modelValue'],
  template:
    '<input class="el-input-stub" :value="modelValue" :placeholder="placeholder" ' +
    '@input="$emit(\'update:modelValue\', $event.target.value)" />'
}
const ElTimePickerStub = {
  props: ['modelValue', 'format', 'valueFormat'],
  emits: ['update:modelValue'],
  template: '<input class="el-time-picker-stub" :value="modelValue" />'
}

const GLOBAL_STUBS = {
  ElButton: ElButtonStub,
  'el-button': ElButtonStub,
  ElTag: ElTagStub,
  'el-tag': ElTagStub,
  ElSelect: ElSelectStub,
  'el-select': ElSelectStub,
  ElOption: ElOptionStub,
  'el-option': ElOptionStub,
  ElDialog: ElDialogStub,
  'el-dialog': ElDialogStub,
  ElInput: ElInputStub,
  'el-input': ElInputStub,
  ElTimePicker: ElTimePickerStub,
  'el-time-picker': ElTimePickerStub
}

const ORDER_REVIEWED = {
  id: 1031,
  orderNo: 'NL20260820000031',
  status: 'REVIEWED',
  statusLabel: '已评价',
  familyId: 101,
  elderId: 401,
  companionId: 307,
  serviceSummary: '已陪同老人完成挂号、就诊、缴费、取药。',
  hospital: '海南省人民医院',
  department: '心血管内科',
  visitTime: '2026-08-25 14:30:00',
  elderName: '张德海',
  elderAge: 88,
  fee: '98.00'
}

async function mountOrderDetail() {
  const wrapper = mount(OrderDetail, { global: { stubs: GLOBAL_STUBS } })
  await flushPromises()
  await nextTick()
  return wrapper
}

/** 收集所有以 undefined / 'undefined' 为 orderId 的调用 */
function undefinedCalls(...mocks) {
  return mocks.flatMap((m) =>
    m.mock.calls.filter((args) => args[0] === undefined || args[0] === 'undefined')
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  routeHolder.route.params.id = '1031'
  deviceState.mobile = true
  getOrderMock.mockResolvedValue(ORDER_REVIEWED)
  getOrderTimelineMock.mockResolvedValue([])
  listCheckinsMock.mockResolvedValue([])
  getLocationMock.mockResolvedValue(null)
  getTrackMock.mockResolvedValue([])
  getReviewByOrderMock.mockResolvedValue(null)
  recommendCompanionsMock.mockResolvedValue({ orderId: 1031, total: 0, candidates: [] })
})

describe('DEF-1 · 路由离开不得带 undefined 重拉', () => {
  it('params.id 变 undefined（路由离开）时不发任何订单请求', async () => {
    await mountOrderDetail()
    // 首屏加载是合法的（带真实 id）
    expect(getOrderMock).toHaveBeenCalledWith('1031')

    // 模拟 router.push('/family/medication')：路由离开时 params.id 变 undefined
    routeHolder.route.params.id = undefined
    await flushPromises()
    await nextTick()
    await flushPromises()

    expect(
      undefinedCalls(
        getOrderMock,
        getOrderTimelineMock,
        listCheckinsMock,
        getReviewByOrderMock,
        recommendCompanionsMock,
        getLocationMock,
        getTrackMock
      )
    ).toEqual([])
  })

  it('换订单（id 仍有值）仍正常重拉 —— 修复不得破坏路由复用语义', async () => {
    await mountOrderDetail()
    routeHolder.route.params.id = '2002'
    await flushPromises()
    await nextTick()
    await flushPromises()
    expect(getOrderMock).toHaveBeenCalledWith('2002')
  })
})
