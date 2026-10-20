import { describe, it, expect } from 'vitest'
import {
  TIMELINE_CATEGORIES,
  timelineCategoryTone,
  FORBIDDEN_JUDGMENT_WORDS,
  containsJudgment,
  completionRateText,
  todayMedicationText,
  pendingItemText,
  eventTimeText
} from '../elderHealth'

/**
 * 老人健康协同的展示口径单测（V3.2）。
 *
 * 保护三条产品不变量：
 *   1. 分类 → 视觉的映射是全站唯一一份（前端不自己判断事件属于哪一类）；
 *   2. 页面文案不得出现结论式判断词 —— 本项目不做诊断、不开药方（合规红线-1）；
 *   3. 统计口径由后端算好，前端**原样展示**，不做任何再计算（roadmap 决策 G8）。
 */
describe('elderHealth 展示口径', () => {
  describe('timelineCategoryTone', () => {
    it('四类事件都有各自的视觉配置', () => {
      expect(Object.keys(TIMELINE_CATEGORIES)).toEqual([
        'ORDER',
        'CHECKIN',
        'MEDICATION',
        'MESSAGE'
      ])
    })

    it('每个分类都有 key / icon / label 三件套，缺一不可', () => {
      Object.entries(TIMELINE_CATEGORIES).forEach(([name, tone]) => {
        expect(tone.key, `${name} 缺 key`).toBeTruthy()
        expect(tone.icon, `${name} 缺 icon`).toBeTruthy()
        expect(tone.label, `${name} 缺 label`).toBeTruthy()
      })
    })

    it('各分类的 key 互不相同（否则色条会串）', () => {
      const keys = Object.values(TIMELINE_CATEGORIES).map((tone) => tone.key)
      expect(new Set(keys).size).toBe(keys.length)
    })

    it('未知分类与缺省分类回落到兜底，不抛异常', () => {
      expect(timelineCategoryTone('NOT_A_CATEGORY').key).toBe('default')
      expect(timelineCategoryTone(undefined).key).toBe('default')
    })

    it('已知分类原样返回', () => {
      expect(timelineCategoryTone('ORDER').key).toBe('order')
      expect(timelineCategoryTone('MEDICATION').key).toBe('medication')
    })
  })

  describe('事实型文案', () => {
    it('词表非空且包含诊断类禁词', () => {
      expect(FORBIDDEN_JUDGMENT_WORDS.length).toBeGreaterThan(0)
      expect(FORBIDDEN_JUDGMENT_WORDS).toContain('诊断')
      expect(FORBIDDEN_JUDGMENT_WORDS).toContain('建议服用')
    })

    it('事实型文案不命中任何禁词', () => {
      const facts = [
        '今日已服药',
        '还有 1 次没吃',
        '今日有服药任务未确认',
        '有订单在等待接单',
        '有已完成订单待评价',
        '有订单需要平台协调',
        '已接单',
        '开始服务',
        '服务完成'
      ]
      facts.forEach((text) => {
        expect(containsJudgment(text), `「${text}」命中了禁词`).toBe(false)
      })
    })

    it('结论式文案必须被拦下', () => {
      const conclusions = [
        '老人今天健康状况较差',
        '建议服用 2 片',
        '诊断为高血压',
        '病情可能恶化'
      ]
      conclusions.forEach((text) => {
        expect(containsJudgment(text), `「${text}」没被拦下`).toBe(true)
      })
    })

    it('空值不命中', () => {
      expect(containsJudgment('')).toBe(false)
      expect(containsJudgment(undefined)).toBe(false)
      expect(containsJudgment(null)).toBe(false)
    })
  })

  describe('completionRateText', () => {
    it('原样展示后端下发的完成率，不做再计算', () => {
      expect(completionRateText('90.00%')).toBe('90.00%')
      expect(completionRateText('0.00%')).toBe('0.00%')
    })

    it('后端没给值时兜底成 0.00%（不返回 NaN%）', () => {
      expect(completionRateText(undefined)).toBe('0.00%')
      expect(completionRateText(null)).toBe('0.00%')
      expect(completionRateText('')).toBe('0.00%')
    })
  })

  describe('todayMedicationText', () => {
    it('已服 / 总数，两个数都来自后端', () => {
      expect(todayMedicationText({ total: 3, taken: 2 })).toBe('2/3')
    })

    it('缺数据时兜底成 0/0', () => {
      expect(todayMedicationText(undefined)).toBe('0/0')
      expect(todayMedicationText({})).toBe('0/0')
    })
  })

  describe('pendingItemText', () => {
    it('有待办时显示条数', () => {
      expect(pendingItemText(2)).toBe('2 项')
    })

    it('没有待办时显示「无」而不是「0 项」', () => {
      expect(pendingItemText(0)).toBe('无')
      expect(pendingItemText(undefined)).toBe('无')
    })
  })

  describe('eventTimeText', () => {
    it('截取到分钟', () => {
      expect(eventTimeText('2026-10-16 09:30:00')).toBe('10-16 09:30')
    })

    it('空值返回空串', () => {
      expect(eventTimeText('')).toBe('')
      expect(eventTimeText(undefined)).toBe('')
    })
  })
})
