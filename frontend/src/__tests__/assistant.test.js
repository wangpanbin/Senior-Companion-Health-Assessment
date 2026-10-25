/**
 * V3.7 家属智能助手 · 前端单测
 *
 * 锁五件事：
 *
 * 1) **提问链路** —— 输入 → 调 `chatAssistant`（带上历史）→ 回答渲染到页面；
 * 2) **草稿回填** —— 命中 FILL_ORDER_DRAFT 时先展示卡片，**点按钮才**写
 *    orderDraft 并跳转；有老人跳 step2、没老人跳 step1；
 * 3) **拒答展示** —— intent=REFUSE_MEDICAL 时显示「请咨询接诊医生」与线下指引入口；
 * 4) **降级提示** —— degraded=true 时说明这是标准答案原文；
 * 5) **历史只存 {role, content}** —— 不落任何业务标识进 sessionStorage。
 *
 * 外加一条合规红线：页面文本**不得**出现「已指派 / 已分配 / 自动派单」——
 * 推荐 ≠ 派单，接单权仍在陪诊员（与 matching.test.js 同源断言）。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/* ---------------- mock：后端接口 ---------------- */
const chatAssistantMock = vi.fn()

vi.mock('@/api/assistant', () => ({
  chatAssistant: (...a) => chatAssistantMock(...a)
}))

/* ---------------- mock：下单草稿 store ---------------- */
const draftState = vi.hoisted(() => ({
  elderId: null,
  elderName: '',
  hospital: null,
  department: '',
  visitTime: '',
  remark: ''
}))
const setElderMock = vi.hoisted(() => vi.fn())
const setVisitMock = vi.hoisted(() => vi.fn())

vi.mock('@/store/modules/orderDraft', () => ({
  useOrderDraftStore: () => ({
    get elderId() {
      return draftState.elderId
    },
    get elderName() {
      return draftState.elderName
    },
    get hospital() {
      return draftState.hospital
    },
    get department() {
      return draftState.department
    },
    get visitTime() {
      return draftState.visitTime
    },
    setElder: (...a) => {
      setElderMock(...a)
      draftState.elderId = a[0]
      draftState.elderName = a[1]
    },
    setVisit: (...a) => {
      setVisitMock(...a)
      draftState.hospital = a[0]
      draftState.department = a[1]
      draftState.visitTime = a[2]
    }
  })
}))

/* ---------------- mock：路由 ---------------- */
const pushMock = vi.hoisted(() => vi.fn())
const routeState = vi.hoisted(() => ({ query: {} }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: (...a) => pushMock(...a) }),
  useRoute: () => routeState
}))

/* 形态可控：默认手机形态 */
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

const { default: Assistant } = await import('@/views/family/assistant.vue')

const ElButtonStub = {
  props: ['type', 'round', 'link', 'size', 'plain', 'loading'],
  template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
}

const ElLinkStub = {
  props: ['type', 'underline'],
  emits: ['click'],
  template: '<a class="el-link-stub" @click="$emit(\'click\')"><slot /></a>'
}

const ElInputStub = {
  props: ['modelValue', 'type', 'rows', 'resize', 'maxlength', 'showWordLimit', 'placeholder'],
  emits: ['update:modelValue'],
  template:
    '<textarea class="el-input-stub" :value="modelValue" ' +
    '@input="$emit(\'update:modelValue\', $event.target.value)"></textarea>'
}

const GLOBAL_STUBS = {
  ElButton: ElButtonStub,
  'el-button': ElButtonStub,
  ElLink: ElLinkStub,
  'el-link': ElLinkStub,
  ElInput: ElInputStub,
  'el-input': ElInputStub
}

/* ---------------- 夹具 ---------------- */

function mountAssistant() {
  return mount(Assistant, { global: { stubs: GLOBAL_STUBS } })
}

async function ask(wrapper, text) {
  const input = wrapper.find('textarea.el-input-stub')
  await input.setValue(text)
  const send = wrapper.findAll('button.el-button-stub').find((b) => b.text() === '发送')
  await send.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  chatAssistantMock.mockReset()
  pushMock.mockReset()
  setElderMock.mockReset()
  setVisitMock.mockReset()
  deviceState.mobile = true
  routeState.query = {}
  draftState.elderId = null
  draftState.elderName = ''
  draftState.hospital = null
  draftState.department = ''
  draftState.visitTime = ''
  sessionStorage.clear()
})

/* ---------------- 用例 ---------------- */

describe('智能助手 · 提问链路', () => {
  it('提问后调用后端，并把回答渲染到页面', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '工作日白天 128.00 元，夜间或周末 158.00 元。',
      intent: 'FAQ_PLATFORM',
      actions: [],
      sources: ['docs/api/03-order.md §1'],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '陪诊费多少钱')

    expect(chatAssistantMock).toHaveBeenCalledTimes(1)
    const payload = chatAssistantMock.mock.calls[0][0]
    expect(payload.question).toBe('陪诊费多少钱')
    expect(Array.isArray(payload.history)).toBe(true)

    expect(wrapper.text()).toContain('128.00')
    expect(wrapper.text()).toContain('docs/api/03-order.md §1')
  })

  it('第二次提问时把上一轮带进 history', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '好的',
      intent: 'FAQ_PLATFORM',
      actions: [],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '第一问')
    await ask(wrapper, '第二问')

    const payload = chatAssistantMock.mock.calls[1][0]
    expect(payload.history.length).toBeGreaterThan(0)
    expect(payload.history[0].role).toBe('user')
    expect(payload.history[0].content).toBe('第一问')
  })

  it('从订单详情页进来时带上 orderId', async () => {
    routeState.query = { orderId: '1001' }
    chatAssistantMock.mockResolvedValue({
      answer: '好的',
      intent: 'RECOMMEND_COMPANION',
      actions: [],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '推荐个陪诊员')
    expect(chatAssistantMock.mock.calls[0][0].orderId).toBe(1001)
  })

  it('降级时说明这条是标准答案原文', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '标准答案',
      intent: 'FAQ_PLATFORM',
      actions: [],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '陪诊费多少钱')
    // ⚠️ 文案只描述**事实**（这条是标准答案原文），不描述**原因** ——
    // degraded=true 有两种成因（没配模型 / Python 服务挂掉走 Java 兜底），
    // 断言「未接入大模型」会把「服务故障」说成「平台没接模型」。
    expect(wrapper.text()).toContain('以下为平台标准答案原文')
  })

  it('移动端同样渲染引导问题按钮，点击即作为问题发送', async () => {
    // 默认夹具就是手机形态。此前 presets 只在 Desktop 形态有 ——
    // 老人家属最常用的手机端反而没有可点的入口。
    chatAssistantMock.mockResolvedValue({
      answer: '好的',
      intent: 'FAQ_PLATFORM',
      actions: [],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    const presets = ['陪诊费怎么算？', '下了单还能取消吗？', '我的订单到哪了？', '帮我找个合适的陪诊员']
    for (const p of presets) {
      expect(wrapper.text()).toContain(p)
    }

    const presetBtn = wrapper
      .findAll('button.el-button-stub')
      .find((b) => b.text() === presets[0])
    await presetBtn.trigger('click')
    await flushPromises()

    expect(chatAssistantMock).toHaveBeenCalledTimes(1)
    expect(chatAssistantMock.mock.calls[0][0].question).toBe(presets[0])
  })
})

describe('智能助手 · 失败提示', () => {
  it('业务错误（限流）显示后端给的真实原因，不说「没响应」', async () => {
    // 拦截器对业务错误是 reject(new Error(message) + { code })，
    // code 是 1000 以上的业务码（限流 10001 / 违规 10003）。
    chatAssistantMock.mockRejectedValue(
      Object.assign(new Error('提问过于频繁，请稍后再试'), { code: 10001 })
    )
    const wrapper = mountAssistant()
    await ask(wrapper, '陪诊费多少钱')

    expect(wrapper.text()).toContain('提问过于频繁')
    // 「助手暂时没有响应」会让人以为服务挂了，而真实原因就在旁边提示条上
    expect(wrapper.text()).not.toContain('助手暂时没有响应')
  })

  it('网络异常没有业务码，仍显示中文兜底文案', async () => {
    chatAssistantMock.mockRejectedValue(new Error('Network Error'))
    const wrapper = mountAssistant()
    await ask(wrapper, '陪诊费多少钱')

    expect(wrapper.text()).toContain('助手暂时没有响应')
    expect(wrapper.text()).not.toContain('Network Error')
  })

  it('HTTP 层错误的 code 是字符串（axios 的 ERR_BAD_REQUEST），仍走中文兜底', async () => {
    // axios 的 HTTP 层错误带的是**字符串** code，不是业务码数字。
    // 判据要是「数字」而不是「某个阈值」，才能同时放过 400 这类小业务码、
    // 又不把英文技术文案漏给用户。
    chatAssistantMock.mockRejectedValue(
      Object.assign(new Error('Request failed with status code 403'), {
        code: 'ERR_BAD_REQUEST'
      })
    )
    const wrapper = mountAssistant()
    await ask(wrapper, '陪诊费多少钱')

    expect(wrapper.text()).toContain('助手暂时没有响应')
    expect(wrapper.text()).not.toContain('Request failed with status code 403')
  })

  it('小于 1000 的业务码（400 参数错误）同样显示后端给的真实原因', async () => {
    // 通用业务码 400 也在 1000 以下 —— 曾经被 `code >= 1000` 的判据漏掉，
    // 后端给的真实原因被「助手暂时没有响应」吞了。
    chatAssistantMock.mockRejectedValue(
      Object.assign(new Error('问题长度不能超过 500 字'), { code: 400 })
    )
    const wrapper = mountAssistant()
    await ask(wrapper, '陪诊费多少钱')

    expect(wrapper.text()).toContain('问题长度不能超过 500 字')
    expect(wrapper.text()).not.toContain('助手暂时没有响应')
  })
})

describe('智能助手 · 合规红线', () => {
  it('医疗拒答时显示「请咨询接诊医生」与线下指引入口', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '这个问题属于医疗健康咨询，平台不提供医疗建议。请直接咨询接诊医生，或前往医院就诊。',
      intent: 'REFUSE_MEDICAL',
      actions: [],
      sources: [],
      degraded: false
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '这个药一天吃几次')

    expect(wrapper.text()).toContain('请咨询接诊医生')
    expect(wrapper.text()).toContain('查看线下就医指引')
    // 医疗拒答不是降级 —— 不该显示「标准答案原文」提示
    expect(wrapper.text()).not.toContain('以下为平台标准答案原文')
  })

  it('页面文本不得出现「已指派 / 已分配 / 自动派单」', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '推荐李*军为您服务，匹配度 86 分。',
      intent: 'RECOMMEND_COMPANION',
      actions: [],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '推荐个陪诊员')

    const text = wrapper.text()
    expect(text).not.toContain('已指派')
    expect(text).not.toContain('已分配')
    expect(text).not.toContain('自动派单')
  })

  it('页面顶部常驻免责声明', async () => {
    const wrapper = mountAssistant()
    expect(wrapper.text()).toContain('不提供医疗诊断与用药建议')
  })
})

describe('智能助手 · 草稿回填', () => {
  it('命中 FILL_ORDER_DRAFT 时展示卡片，点按钮才写草稿并跳 step2', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '已记下你的就诊信息。',
      intent: 'RECOMMEND_COMPANION',
      actions: [
        {
          type: 'FILL_ORDER_DRAFT',
          payload: {
            elderId: 401,
            elderName: '张*',
            hospital: '海南省人民医院',
            address: '海口市美兰区海府路 12 号',
            department: '心内科',
            visitTime: '2026-10-28 09:30:00'
          }
        },
        { type: 'NAVIGATE', payload: { path: '/family/order/step2' } }
      ],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '帮我去海南省人民医院心内科约个陪诊员')

    expect(wrapper.text()).toContain('已为你填好下单信息')
    expect(wrapper.text()).toContain('海南省人民医院')

    // 还没点按钮 → 不该写草稿、也不该跳转
    expect(setElderMock).not.toHaveBeenCalled()
    expect(pushMock).not.toHaveBeenCalled()

    const go = wrapper.findAll('button.el-button-stub').find((b) => b.text() === '去确认下单')
    await go.trigger('click')
    await flushPromises()

    expect(setElderMock).toHaveBeenCalledWith(401, '张*')
    expect(setVisitMock).toHaveBeenCalled()
    expect(pushMock).toHaveBeenCalledWith('/family/order/step2')
  })

  it('没有老人时跳 step1（让人先选老人）', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '已记下你的就诊信息。',
      intent: 'RECOMMEND_COMPANION',
      actions: [
        {
          type: 'FILL_ORDER_DRAFT',
          payload: { hospital: '海南省人民医院', department: '心内科' }
        },
        { type: 'NAVIGATE', payload: { path: '/family/order/step1' } }
      ],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '帮我去海南省人民医院心内科约个陪诊员')

    const go = wrapper.findAll('button.el-button-stub').find((b) => b.text() === '去确认下单')
    await go.trigger('click')
    await flushPromises()

    expect(setElderMock).not.toHaveBeenCalled()
    expect(pushMock).toHaveBeenCalledWith('/family/order/step1')
  })

  it('没有抽到就诊信息时不显示草稿卡片', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '陪诊费怎么算',
      intent: 'FAQ_PLATFORM',
      actions: [],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '陪诊费怎么算')
    expect(wrapper.text()).not.toContain('已为你填好下单信息')
  })
})

describe('智能助手 · 本地历史', () => {
  it('历史只存 {role, content}，不落任何业务标识', async () => {
    chatAssistantMock.mockResolvedValue({
      answer: '好的',
      intent: 'FAQ_PLATFORM',
      actions: [],
      sources: [],
      degraded: true
    })
    const wrapper = mountAssistant()
    await ask(wrapper, '陪诊费多少钱')

    const raw = sessionStorage.getItem('nl_assistant_history')
    expect(raw).toBeTruthy()
    const parsed = JSON.parse(raw)
    expect(parsed.length).toBeGreaterThan(0)
    for (const item of parsed) {
      expect(Object.keys(item).sort()).toEqual(['content', 'role'])
    }
  })
})
