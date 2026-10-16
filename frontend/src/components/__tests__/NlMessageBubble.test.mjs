import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import NlMessageBubble from '@/components/chat/NlMessageBubble.vue'

const base = {
  messageId: 1,
  msgType: 'TEXT',
  content: '已到医院',
  createTime: '2026-10-16 10:00:00'
}

function render(message, mine = false) {
  return mount(NlMessageBubble, {
    props: { message: { ...base, ...message }, mine },
    global: { stubs: { ElImage: { template: '<div />' }, NlChatVideo: true } }
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
