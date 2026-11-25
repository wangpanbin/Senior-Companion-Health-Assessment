/**
 * ReviewRulingDialog 单测（E4）
 *
 * 锁四件事：
 *
 * 1) **理由不足 10 字不发请求** —— 后端 `ReviewRulingDTO` 的 `@Size(min=10)` 会拦，
 *    但前端先拦一道是为了不浪费一次往返，也为了让用户当场看到「为什么不能提交」；
 * 2) **提交载荷固定为 `isValid: false`** —— 本期只支持「有效 → 无效」单向裁定，
 *    前端**不给**「恢复有效」这个选项，所以载荷里不可能出现 `true`；
 * 3) **in-flight 锁：连点两次只发一次请求** —— 锁必须在第一个 `await` 之前同步置位，
 *    置位晚了两次点击会一起穿过 await，用户看到「成功 + 报错」双提示；
 * 4) **已被裁定无效的评价不再给裁** —— 后端会返 409，前端提前挡住。
 *
 * ⚠️ element-plus 在 jsdom 下依赖 ResizeObserver 等浏览器 API，测试里用 stub 替换。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/* ---------------- mock：后端裁定端点 ---------------- */
const { rulingReviewMock } = vi.hoisted(() => ({ rulingReviewMock: vi.fn() }))
vi.mock('@/api/admin', () => ({
  rulingReview: (...a) => rulingReviewMock(...a)
}))

/* ---------------- mock：二次确认（用户点「确认」） ---------------- */
const { confirmThenMock } = vi.hoisted(() => ({ confirmThenMock: vi.fn() }))
vi.mock('@/utils/confirm', () => ({
  confirmThen: (...a) => confirmThenMock(...a)
}))

/* ---------------- mock：element-plus ---------------- */
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn(() => Promise.resolve()) }
}))

const { default: ReviewRulingDialog } = await import('@/components/admin/ReviewRulingDialog.vue')

/* ---------------- element-plus stubs ---------------- */
const stubs = {
  ElDialog: {
    props: ['modelValue', 'title', 'width', 'alignCenter'],
    emits: ['update:modelValue'],
    template:
      '<div v-if="modelValue" class="el-dialog-stub">' +
      '<h3 class="el-dialog-stub__title">{{ title }}</h3>' +
      '<slot /><slot name="footer" /></div>'
  },
  ElInput: {
    props: ['modelValue', 'type', 'rows', 'maxlength', 'placeholder', 'id'],
    emits: ['update:modelValue', 'input'],
    template:
      '<textarea class="el-input-stub" :value="modelValue" ' +
      '@input="$emit(\'update:modelValue\', $event.target.value); $emit(\'input\', $event.target.value)"></textarea>'
  },
  ElButton: {
    props: ['type', 'loading', 'disabled', 'size'],
    emits: ['click'],
    template:
      '<button class="el-button-stub" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>'
  },
  ElEmpty: {
    props: ['type', 'title', 'description'],
    template:
      '<div class="el-empty-stub"><span class="el-empty-stub__title">{{ title }}</span></div>'
  }
}

/** 一条正常评价（已回复、仍有效） */
const validReview = {
  id: 30001,
  orderNo: 'NL20260820000031',
  score: 5,
  content: '小李很耐心，全程陪着老人。',
  companionReply: '感谢认可，后续会继续保持。',
  replyTime: '2026-09-14 20:00:00',
  isValid: true
}

/** 挂载组件；返回 wrapper 与若干便捷操作 */
function mountDialog(review = validReview, props = {}) {
  const wrapper = mount(ReviewRulingDialog, {
    props: { modelValue: true, review, ...props },
    global: { stubs }
  })
  return wrapper
}

/** 填理由（textarea 的 input 事件） */
async function fillReason(wrapper, text) {
  const input = wrapper.find('textarea.el-input-stub')
  await input.setValue(text)
}

/** 点「确认裁定」 */
async function clickSubmit(wrapper) {
  const buttons = wrapper.findAll('button.el-button-stub')
  const target = buttons.find((b) => b.text().includes('确认裁定'))
  await target.trigger('click')
  await flushPromises()
}

describe('ReviewRulingDialog · 评价有效性裁定', () => {
  beforeEach(() => {
    rulingReviewMock.mockReset()
    confirmThenMock.mockReset()
    confirmThenMock.mockResolvedValue(true)
    rulingReviewMock.mockResolvedValue({ reviewId: 30001, isValid: false, companionId: 307 })
  })

  it('理由不足 10 字时不让提交，也不发请求', async () => {
    const wrapper = mountDialog()
    await fillReason(wrapper, '太假了')

    await clickSubmit(wrapper)

    expect(rulingReviewMock).not.toHaveBeenCalled()
    expect(wrapper.find('.ruling__error').exists()).toBe(true)
  })

  it('理由合法时调用 rulingReview，载荷固定 isValid=false', async () => {
    const wrapper = mountDialog()
    await fillReason(wrapper, '家属描述与打卡记录明显不符，证据充分')

    await clickSubmit(wrapper)

    expect(rulingReviewMock).toHaveBeenCalledTimes(1)
    const [reviewId, payload] = rulingReviewMock.mock.calls[0]
    expect(reviewId).toBe(30001)
    // 单向裁定：载荷里不可能出现 isValid=true
    expect(payload.isValid).toBe(false)
    expect(payload.reason).toBe('家属描述与打卡记录明显不符，证据充分')
  })

  it('提交成功后关闭对话框并抛 success 事件', async () => {
    const wrapper = mountDialog()
    await fillReason(wrapper, '家属描述与打卡记录明显不符，证据充分')

    await clickSubmit(wrapper)

    expect(wrapper.emitted('success')).toBeTruthy()
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([false])
  })

  it('in-flight 锁：连点两次只发一次请求', async () => {
    // 第一次请求悬而未决 —— 模拟网络慢 / 用户手快连点
    let release = null
    rulingReviewMock.mockImplementation(
      () => new Promise((resolve) => {
        release = resolve
      })
    )
    const wrapper = mountDialog()
    await fillReason(wrapper, '家属描述与打卡记录明显不符，证据充分')

    // 两次连点（不 flushPromises，让第一次仍卡在 await 里）
    await clickSubmit(wrapper)
    await wrapper.find('button.el-button-stub:last-of-type').trigger('click')

    expect(rulingReviewMock).toHaveBeenCalledTimes(1)
    release?.({ reviewId: 30001, isValid: false, companionId: 307 })
    await flushPromises()
  })

  it('已被裁定无效的评价不给「确认裁定」按钮', () => {
    const wrapper = mountDialog({ ...validReview, isValid: false })
    const buttons = wrapper.findAll('button.el-button-stub')
    expect(buttons.find((b) => b.text().includes('确认裁定'))).toBeUndefined()
    expect(wrapper.find('.ruling__badge').exists()).toBe(true)
  })

  it('取不到评价时显示空态，且不给「确认裁定」按钮', () => {
    const wrapper = mountDialog(null)
    expect(wrapper.find('.el-empty-stub').exists()).toBe(true)
    const buttons = wrapper.findAll('button.el-button-stub')
    expect(buttons.find((b) => b.text().includes('确认裁定'))).toBeUndefined()
  })

  it('用户取消二次确认时不发请求', async () => {
    confirmThenMock.mockResolvedValue(false)
    const wrapper = mountDialog()
    await fillReason(wrapper, '家属描述与打卡记录明显不符，证据充分')

    await clickSubmit(wrapper)

    expect(rulingReviewMock).not.toHaveBeenCalled()
  })
})
