import { describe, it, expect } from 'vitest'
import {
  FULFILLMENT_TONES,
  fulfillmentTone,
  isFulfillmentPending,
  showFulfillmentBar,
  fulfillmentBadge,
  waitMinutesText,
  canUrgeOrder
} from '../fulfillment'

/**
 * 履约展示口径单测（V3.1 · T5）。
 *
 * 保护两条产品不变量：
 *   1. 风险只在「待接单」时对外可见（订单被接走后提示必须消失）；
 *   2. 催单按钮的可见性完全由后端 canUrge 驱动，前端不追加判断。
 * 另外钉住「列表里正常等待不出现标记」—— 扫读界面挂满正常标记等于没标记。
 */
describe('fulfillment 展示口径', () => {
  const pendingOrder = (overrides = {}) => ({
    status: 'PENDING',
    fulfillmentRisk: 'NORMAL',
    fulfillmentRiskLabel: '正常等待',
    waitMinutes: 0,
    urgeCount: 0,
    canUrge: false,
    ...overrides
  })

  describe('fulfillmentTone', () => {
    it('四个等级都有各自的视觉配置', () => {
      expect(Object.keys(FULFILLMENT_TONES)).toEqual([
        'NORMAL',
        'WARNING',
        'URGENT',
        'MANUAL_REQUIRED'
      ])
    })

    it('等级越高 rank 越大（模板据此决定要不要出警示符）', () => {
      expect(fulfillmentTone('NORMAL').rank).toBe(0)
      expect(fulfillmentTone('WARNING').rank).toBe(1)
      expect(fulfillmentTone('URGENT').rank).toBe(2)
      expect(fulfillmentTone('MANUAL_REQUIRED').rank).toBe(3)
    })

    it('未知等级与缺省等级回落到 NORMAL，不抛异常', () => {
      expect(fulfillmentTone('NOT_A_RISK').key).toBe('normal')
      expect(fulfillmentTone(undefined).key).toBe('normal')
      expect(fulfillmentTone(null).key).toBe('normal')
    })

    it('四个等级用四种不同的色条，避免只靠文案区分', () => {
      const keys = Object.values(FULFILLMENT_TONES).map((t) => t.key)
      expect(new Set(keys).size).toBe(keys.length)
    })
  })

  describe('isFulfillmentPending / showFulfillmentBar', () => {
    it.each(['ACCEPTED', 'IN_SERVICE', 'COMPLETED', 'REVIEWED', 'CANCELLED'])(
      '状态 %s 时不算待接单',
      (status) => {
        expect(isFulfillmentPending({ status })).toBe(false)
      }
    )

    it('待接单且带风险时展示状态条', () => {
      expect(showFulfillmentBar(pendingOrder({ fulfillmentRisk: 'URGENT' }))).toBe(true)
    })

    it('非待接单一律不展示状态条（哪怕库里残留着高风险值）', () => {
      expect(
        showFulfillmentBar(pendingOrder({ status: 'ACCEPTED', fulfillmentRisk: 'URGENT' }))
      ).toBe(false)
    })

    it('后端没给风险字段时保守不渲染', () => {
      expect(showFulfillmentBar({ status: 'PENDING' })).toBe(false)
      expect(showFulfillmentBar(null)).toBe(false)
    })
  })

  describe('fulfillmentBadge', () => {
    it('正常等待不出标记', () => {
      expect(fulfillmentBadge(pendingOrder())).toBeNull()
    })

    it('非待接单不出标记', () => {
      expect(fulfillmentBadge(pendingOrder({ status: 'COMPLETED', fulfillmentRisk: 'URGENT' }))).toBeNull()
    })

    it('有风险时用后端下发的文案，不自己拼', () => {
      const badge = fulfillmentBadge(
        pendingOrder({ fulfillmentRisk: 'MANUAL_REQUIRED', fulfillmentRiskLabel: '需要人工介入' })
      )
      expect(badge.risk).toBe('MANUAL_REQUIRED')
      expect(badge.label).toBe('需要人工介入')
    })

    it('后端漏发文案时留空字符串而不是 undefined', () => {
      const badge = fulfillmentBadge(
        pendingOrder({ fulfillmentRisk: 'URGENT', fulfillmentRiskLabel: undefined })
      )
      expect(badge.label).toBe('')
    })
  })

  describe('waitMinutesText', () => {
    it('正常返回「已等待 N 分钟」', () => {
      expect(waitMinutesText(45)).toBe('已等待 45 分钟')
    })

    it('0 或缺省时返回空串（不显示「已等待 0 分钟」）', () => {
      expect(waitMinutesText(0)).toBe('')
      expect(waitMinutesText(undefined)).toBe('')
      expect(waitMinutesText(null)).toBe('')
    })

    it('负数返回空串（后端已钳到 0，这里是双保险）', () => {
      expect(waitMinutesText(-5)).toBe('')
    })
  })

  describe('canUrgeOrder', () => {
    it('后端说可以催单就允许', () => {
      expect(canUrgeOrder(pendingOrder({ canUrge: true }))).toBe(true)
    })

    it('后端说不能催单就不允许', () => {
      expect(canUrgeOrder(pendingOrder({ canUrge: false }))).toBe(false)
      expect(canUrgeOrder(pendingOrder({ canUrge: undefined }))).toBe(false)
    })

    it('非待接单一律不允许（前端不补这条判断，但也不制造矛盾状态）', () => {
      expect(canUrgeOrder(pendingOrder({ status: 'CANCELLED', canUrge: true }))).toBe(false)
    })
  })
})
