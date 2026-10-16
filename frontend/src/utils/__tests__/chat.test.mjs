import { describe, expect, it, vi } from 'vitest'
import {
  chatMediaUrl,
  chatTextLength,
  fetchLatestMessages,
  mergeMessages,
  validateChatFile
} from '../chat'

const message = (id) => ({ messageId: id })
describe('M13 历史与附件', () => {
  it('sorts descending pages ascending and deduplicates REST/WS by id', () => {
    expect(
      mergeMessages([message(3), message(2)], [message('3'), message(1)]).map((m) => m.messageId)
    ).toEqual([1, 2, '3'])
  })
  it('starts with latest and walks more than 100 missed messages to known newest', async () => {
    const fetchPage = vi.fn(async ({ cursor }) => {
      const start = cursor ? cursor - 1 : 255
      const records = Array.from({ length: Math.min(100, start) }, (_, i) => message(start - i))
      return { records, nextCursor: records.at(-1).messageId, hasMore: start > 100 }
    })
    const result = await fetchLatestMessages(fetchPage, 5)
    expect(fetchPage.mock.calls.map(([p]) => p.cursor)).toEqual([0, 156, 56])
    expect(result.records).toHaveLength(255)
    expect(result.records.at(-1).messageId).toBe(255)
  })
  it('loads only latest page initially', async () => {
    const fetchPage = vi
      .fn()
      .mockResolvedValue({ records: [message(20)], hasMore: true, nextCursor: 20 })
    expect((await fetchLatestMessages(fetchPage)).records).toEqual([message(20)])
    expect(fetchPage).toHaveBeenCalledTimes(1)
  })
  it('walks to the beginning when the previously synchronized room was empty', async () => {
    const fetchPage = vi
      .fn()
      .mockResolvedValueOnce({ records: [message(101)], nextCursor: 101, hasMore: true })
      .mockResolvedValueOnce({ records: [message(100), message(1)], nextCursor: 1, hasMore: false })
    const result = await fetchLatestMessages(fetchPage, 0)
    expect(fetchPage.mock.calls.map(([p]) => p.cursor)).toEqual([0, 101])
    expect(result.records.map((m) => m.messageId)).toEqual([1, 100, 101])
  })
  it('discards late responses after a route change', async () => {
    let active = true
    const result = await fetchLatestMessages(
      async () => {
        active = false
        return { records: [message(1)] }
      },
      0,
      () => active
    )
    expect(result).toBeNull()
  })
  it('does not loop forever on a broken cursor', async () => {
    const fetchPage = vi
      .fn()
      .mockResolvedValue({ records: [message(20)], nextCursor: 20, hasMore: true })
    await expect(fetchLatestMessages(fetchPage, 1)).rejects.toThrow('游标')
    expect(fetchPage).toHaveBeenCalledTimes(2)
  })
  it('counts code points and validates CHAT 50MB/types without trusting URLs', () => {
    expect(chatTextLength('\u{20000}'.repeat(1000))).toBe(1000)
    expect(validateChatFile({ size: 50 * 1024 * 1024, type: 'video/mp4' })).toBe('')
    expect(validateChatFile({ size: 50 * 1024 * 1024 + 1, type: 'video/mp4' })).toContain('50MB')
    expect(validateChatFile({ size: 12, type: 'application/pdf' })).not.toBe('')
    expect(validateChatFile(null)).not.toBe('')
    expect(chatMediaUrl('/uploads/202610/test.mp4')).toBe('/uploads/202610/test.mp4')
    expect(chatMediaUrl('javascript:alert(1)')).toBe('')
    expect(chatMediaUrl('https://example.com/tracker.png')).toBe('')
  })
})
