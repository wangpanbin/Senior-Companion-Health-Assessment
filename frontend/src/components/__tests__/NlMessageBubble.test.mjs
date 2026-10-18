import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import NlMessageBubble from '@/components/chat/NlMessageBubble.vue'

const base = {
  messageId: 1,
  msgType: 'TEXT',
  content: '已到医院',
  createTime: '2026-10-16 10:00:00'
}

function render(message, mine = false, extra = {}) {
  return mount(NlMessageBubble, {
    props: { message: { ...base, ...message }, mine, ...extra },
    global: {
      stubs: {
        ElImage: { template: '<div />' },
        NlChatVideo: true,
        NlAvatar: {
          props: ['fallback', 'size', 'tone'],
          template: '<span class="nl-avatar-stub">{{ fallback }}</span>'
        }
      }
    }
  })
}

const meta = (message, mine) => render(message, mine).find('.chat-bubble__meta').text()

describe('M13 气泡署名', () => {
  it.each([
    ['FAMILY', '家属'],
    ['COMPANION', '陪诊员']
  ])('%s 的消息署名 %s', (senderRole, label) => {
    expect(meta({ senderRole })).toContain(label)
  })

  it('未知角色回落陪诊员文案', () => {
    expect(meta({ senderRole: 'UNKNOWN' })).toContain('陪诊员')
  })

  it('本人一侧署名「我」而不是角色', () => {
    expect(meta({ senderRole: 'FAMILY' }, true)).toContain('我')
  })

  it('ELDER 署名取角色表口径（现实里 ELDER 无发送权限，仅锁单一数据源）', () => {
    expect(meta({ senderRole: 'ELDER' })).toContain('老年患者')
  })
})

describe('M13 气泡视觉分化', () => {
  it('aligns my messages to the right and drops the avatar', () => {
    const wrapper = render({ senderRole: 'FAMILY' }, true)
    expect(wrapper.find('.chat-bubble').classes()).toContain('is-mine')
    // 自己一侧不需要头像，头像只为区分对方。
    expect(wrapper.find('.chat-bubble__avatar').exists()).toBe(false)
  })

  it('shows a role avatar on the other side only', () => {
    const wrapper = render({ senderRole: 'COMPANION' })
    expect(wrapper.find('.chat-bubble').classes()).not.toContain('is-mine')
    expect(wrapper.find('.chat-bubble__avatar').exists()).toBe(true)
    expect(wrapper.find('.chat-bubble__avatar').text()).toBe('陪')
  })

  it('accepts an explicit sender name for the avatar initial', () => {
    const wrapper = render({ senderRole: 'COMPANION' }, false, { senderName: '黄建军' })
    expect(wrapper.find('.chat-bubble__avatar').text()).toBe('黄')
  })

  it('never escapes to raw html', () => {
    const wrapper = render({ senderRole: 'FAMILY', content: '<img src=x onerror=alert(1)>' })
    expect(wrapper.find('.chat-bubble img').exists()).toBe(false)
    expect(wrapper.find('.chat-bubble__text').text()).toContain('<img src=x onerror=alert(1)>')
  })
})
