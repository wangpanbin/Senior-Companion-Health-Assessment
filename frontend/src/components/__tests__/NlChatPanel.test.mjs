import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import NlChatPanel from '@/components/chat/NlChatPanel.vue'
import { connectChatSocket, getHistory, markRead, sendMessage } from '@/api/chat'
import { uploadFile } from '@/api/file'

const user = vi.hoisted(() => {
  const store = { role: 'FAMILY', userInfo: { id: 101 } }
  return Object.defineProperties(store, {
    isFamily: { get: () => store.role === 'FAMILY' },
    isCompanion: { get: () => store.role === 'COMPANION' },
    isAdmin: { get: () => store.role === 'ADMIN' },
    isElder: { get: () => store.role === 'ELDER' }
  })
})
// 只替掉 useUserStore，保留 ROLES / ROLE_LABELS 等真实导出：气泡子组件要读 ROLE_LABELS。
vi.mock('@/store/modules/user', async (importOriginal) => ({
  ...(await importOriginal()),
  useUserStore: () => user
}))
vi.mock('@/api/chat', () => ({
  getHistory: vi.fn(),
  markRead: vi.fn(),
  sendMessage: vi.fn(),
  connectChatSocket: vi.fn()
}))
vi.mock('@/api/file', () => ({ uploadFile: vi.fn() }))
vi.mock('@/components', () => ({
  NlEmpty: { props: ['title'], template: '<div>{{ title }}</div>' },
  NlSkeleton: { template: '<div>加载中</div>' },
  NlNoticeBar: { template: '<div><slot /></div>' }
}))
const stubs = {
  ElButton: {
    props: ['disabled', 'loading', 'nativeType'],
    template:
      '<button :type="nativeType || \'button\'" :disabled="disabled || loading"><slot /></button>'
  },
  ElInput: {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template:
      '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  },
  ElImage: { template: '<div class="image-preview" />' }
}
let wrapper
const close = vi.fn()
async function render(props = {}) {
  wrapper = mount(NlChatPanel, { props: { orderId: '1', ...props }, global: { stubs } })
  await flushPromises()
  return wrapper
}
function deferred() {
  let resolve
  const promise = new Promise((r) => {
    resolve = r
  })
  return { promise, resolve }
}
beforeEach(() => {
  vi.clearAllMocks()
  user.role = 'FAMILY'
  Object.defineProperty(document, 'hidden', { configurable: true, value: false })
  getHistory.mockResolvedValue({ records: [], hasMore: false, myRole: 'FAMILY' })
  connectChatSocket.mockReturnValue({ close })
  markRead.mockResolvedValue({ unreadCount: 0 })
  vi.stubGlobal(
    'URL',
    class extends URL {
      static createObjectURL = vi.fn(() => 'blob:test')
      static revokeObjectURL = vi.fn()
    }
  )
})
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})

describe('M13 发送与只读', () => {
  it('retains rejected text and retries with escaped rendering after REST success', async () => {
    sendMessage.mockRejectedValueOnce(new Error('7004')).mockResolvedValueOnce({
      messageId: 1,
      orderId: 1,
      senderId: 101,
      senderRole: 'FAMILY',
      msgType: 'TEXT',
      content: '<img src=x onerror=alert(1)>'
    })
    await render()
    await wrapper.get('textarea').setValue('<img src=x onerror=alert(1)>')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toContain('<img')
    expect(wrapper.text()).toContain('文字和附件已保留')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sendMessage).toHaveBeenCalledTimes(2)
    expect(wrapper.get('textarea').element.value).toBe('')
    expect(wrapper.findAll('.chat-bubble')).toHaveLength(1)
    expect(wrapper.find('.chat-bubble img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
  })
  it('uses code points, not UTF-16 units, for the 1000 character limit', async () => {
    sendMessage.mockResolvedValue({ messageId: 1, msgType: 'TEXT', content: '成功' })
    await render()
    await wrapper.get('textarea').setValue('\u{20000}'.repeat(1001))
    await wrapper.get('form').trigger('submit')
    expect(sendMessage).not.toHaveBeenCalled()
    await wrapper.get('textarea').setValue('\u{20000}'.repeat(1000))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sendMessage).toHaveBeenCalledWith('1', { msgType: 'TEXT', content: '\u{20000}'.repeat(1000) })
  })
  it.each(['ELDER', 'ADMIN'])('never renders composer or posts reads for %s', async (role) => {
    user.role = role
    getHistory.mockResolvedValue({
      records: [{ messageId: 1, msgType: 'TEXT', content: '安排已确认' }]
    })
    await render()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('只读')
    expect(markRead).not.toHaveBeenCalled()
    expect(sendMessage).not.toHaveBeenCalled()
  })
  it('uploads CHAT before VIDEO and keeps file/text when upload fails', async () => {
    uploadFile
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ fileId: 'chat-file' })
    sendMessage.mockResolvedValue({
      messageId: 1,
      msgType: 'VIDEO',
      senderId: 101,
      fileUrl: '/uploads/test.mp4'
    })
    await render()
    const file = new File(['mp4'], 'test.mp4', { type: 'video/mp4' })
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await wrapper.get('textarea').setValue('现场记录')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sendMessage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('test.mp4')
    expect(wrapper.get('textarea').element.value).toBe('现场记录')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(uploadFile).toHaveBeenLastCalledWith(file, 'CHAT')
    expect(sendMessage).toHaveBeenCalledWith('1', {
      msgType: 'VIDEO',
      fileId: 'chat-file',
      content: '现场记录'
    })
    expect(wrapper.find('video').attributes('preload')).toBe('metadata')
    expect(wrapper.find('video').attributes('controls')).toBeDefined()
    expect(wrapper.find('.chat-panel__attachment').exists()).toBe(false)
  })
  it('ignores late send success after changing orders', async () => {
    const pending = deferred()
    sendMessage.mockReturnValue(pending.promise)
    await render()
    await wrapper.get('textarea').setValue('旧会话草稿')
    await wrapper.get('form').trigger('submit')
    await wrapper.setProps({ orderId: '2' })
    await flushPromises()
    await wrapper.get('textarea').setValue('新会话草稿')
    pending.resolve({ messageId: 100, orderId: 1, msgType: 'TEXT', content: '旧会话草稿' })
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('新会话草稿')
    expect(wrapper.findAll('.chat-bubble')).toHaveLength(0)
    expect(close).toHaveBeenCalled()
  })
  it('does not submit media if upload response lacks fileId', async () => {
    uploadFile.mockResolvedValue({})
    await render()
    const input = wrapper.get('input[type=file]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['png'], 'test.png', { type: 'image/png' })]
    })
    await input.trigger('change')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(sendMessage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('test.png')
  })
})
