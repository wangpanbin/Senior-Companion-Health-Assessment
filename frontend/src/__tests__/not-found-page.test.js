/**
 * 404 兜底页（catch-all → /error/404）
 *
 * 2026-11-28 重设计的回归防线，判据三条：
 *   1. 路由表：未知路径必须 redirect 到 /error/404，且该页是公开页（未登录可见）；
 *   2. 登录态分流：主按钮文案与去向随登录态切换
 *      （已登录 → roleHomePath 角色主页；未登录 → /login）—— 与 403 页同口径；
 *   3. 「返回上一页」只在真有来路（window.history.state.back）时渲染，
 *      直接打开坏地址不给一个「点了没反应」的按钮。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { routes } from '@/router/routes'

/* ---------------- 桩：路由行为（记录 push / back 调用） ---------------- */
const pushMock = vi.fn()
const backMock = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock, back: backMock })
}))

/* 登录态可控：与 user store 暴露给本页的字段同形状（isLogin / role） */
const userState = vi.hoisted(() => ({ isLogin: false, role: '' }))

vi.mock('@/store/modules/user', () => ({
  useUserStore: () => userState
}))

/* ROLE_HOME 真源打桩：真模块在 import 期就会创建 router 实例并挂全局守卫，测试里没必要 */
vi.mock('@/router/index.js', () => ({
  roleHomePath: (role) =>
    (
      {
        ELDER: '/elder/home',
        FAMILY: '/family/home',
        COMPANION: '/companion/hall',
        ADMIN: '/admin/dashboard'
      }
    )[role] || '/login'
}))

/* mock 落位后再取组件 */
const { default: NotFound } = await import('@/views/error/404.vue')

/* ---------------- Element Plus 桩（同 order-detail-route-leave.test.js） ---------------- */
const ElButtonStub = {
  name: 'ElButton',
  props: ['type', 'round', 'link', 'size', 'plain', 'loading'],
  /* ⚠️ 必须声明 emits: 不声明时父组件的 @click 会同时走「emit 事件」和
     「attrs 透传到根元素的原生 click」两条路，处理器被调用两次。 */
  emits: ['click'],
  template: '<button class="el-button-stub" @click="$emit(\'click\')"><slot /></button>'
}

function mountNotFound() {
  return mount(NotFound, {
    global: { stubs: { ElButton: ElButtonStub, 'el-button': ElButtonStub } }
  })
}

/** jsdom 里模拟 vue-router 写入的 history.state（back = 来路） */
function setHistoryBack(back) {
  window.history.replaceState(back ? { back } : null, '')
}

beforeEach(() => {
  vi.clearAllMocks()
  userState.isLogin = false
  userState.role = ''
  setHistoryBack(null)
})

describe('404 兜底页 · 路由表', () => {
  it('未知路径的路由必须 redirect 到 /error/404', () => {
    const fallback = routes.find((r) => String(r.path).includes(':pathMatch'))
    expect(fallback, '路由表末尾必须有 catch-all 兜底').toBeTruthy()
    expect(fallback.redirect).toBe('/error/404')
  })

  it('/error/404 是公开页（未登录可见），标题「页面不存在」', () => {
    const notFound = routes.find((r) => r.path === '/error/404')
    expect(notFound).toBeTruthy()
    expect(notFound.meta.public).toBe(true)
    expect(notFound.meta.title).toBe('页面不存在')
  })
})

describe('404 兜底页 · 渲染与按钮行为', () => {
  it('渲染「页面走丢了」标题、404 数字与装饰插画', () => {
    const wrapper = mountNotFound()
    expect(wrapper.find('.nl-404__title').text()).toBe('页面走丢了')
    expect(wrapper.find('.nl-404__code').text()).toBe('404')
    expect(wrapper.find('.nl-404__svg').exists(), '装饰场景 SVG 必须渲染').toBe(true)
  })

  it('未登录：主按钮文案「去登录」，点击去 /login', async () => {
    const wrapper = mountNotFound()
    const buttons = wrapper.findAll('button')
    expect(buttons.map((b) => b.text()), '无来路时只应有主按钮一个').toEqual(['去登录'])
    await buttons[0].trigger('click')
    expect(pushMock).toHaveBeenCalledTimes(1)
    expect(pushMock).toHaveBeenCalledWith('/login')
    expect(backMock).not.toHaveBeenCalled()
  })

  it('已登录：主按钮文案「回到首页」，点击回角色主页（ROLE_HOME 真源）', async () => {
    userState.isLogin = true
    userState.role = 'FAMILY'
    const wrapper = mountNotFound()
    const homeButton = wrapper.findAll('button').find((b) => b.text() === '回到首页')
    expect(homeButton, '已登录时主按钮文案必须是「回到首页」').toBeTruthy()
    await homeButton.trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/family/home')
  })

  it('未知角色兜底到 /login（roleHomePath 的兜底语义不被绕过）', async () => {
    userState.isLogin = true
    userState.role = 'SOMETHING_ELSE'
    const wrapper = mountNotFound()
    const homeButton = wrapper.findAll('button').find((b) => b.text() === '回到首页')
    await homeButton.trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/login')
  })

  it('有来路（history.state.back）时渲染「返回上一页」，点击调 router.back', async () => {
    setHistoryBack('/family/home')
    const wrapper = mountNotFound()
    const backButton = wrapper.findAll('button').find((b) => b.text() === '返回上一页')
    expect(backButton, '有来路时必须出现「返回上一页」').toBeTruthy()
    await backButton.trigger('click')
    expect(backMock).toHaveBeenCalledTimes(1)
  })

  it('直接打开坏地址（无来路）时不渲染「返回上一页」', () => {
    const wrapper = mountNotFound()
    const backButton = wrapper.findAll('button').find((b) => b.text() === '返回上一页')
    expect(backButton, '没有来路就不能给一个点了没反应的返回按钮').toBeFalsy()
  })
})
