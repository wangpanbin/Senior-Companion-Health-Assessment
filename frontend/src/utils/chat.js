/** M13 消息合并与补洞：游标始终从最新开始，不能拿最旧游标补断线窗口。 */
export function compareMessageIds(a, b) {
  const left = BigInt(a || 0)
  const right = BigInt(b || 0)
  return left < right ? -1 : left > right ? 1 : 0
}

export function mergeMessages(...groups) {
  const byId = new Map()
  for (const message of groups.flat()) {
    if (message?.messageId != null) byId.set(String(message.messageId), message)
  }
  return [...byId.values()].sort((a, b) => compareMessageIds(a.messageId, b.messageId))
}

/** 原子补洞：全部拉完才交给调用方合并，失败不能推进最新水位。 */
export async function fetchLatestMessages(fetchPage, knownNewestId = null, isCurrent = () => true) {
  let cursor = 0
  let records = []
  let firstPage
  const visited = new Set()
  while (isCurrent()) {
    const page = await fetchPage({ cursor, size: 100 })
    if (!isCurrent()) return null
    firstPage ??= page
    const batch = page?.records || []
    records = mergeMessages(records, batch)
    const reachedKnown =
      knownNewestId != null && batch.some((m) => compareMessageIds(m.messageId, knownNewestId) <= 0)
    if (knownNewestId == null || reachedKnown || !page?.hasMore) {
      return { records, firstPage, lastPage: page }
    }
    const next = page.nextCursor
    if (
      !batch.length ||
      !next ||
      visited.has(String(next)) ||
      (cursor && compareMessageIds(next, cursor) >= 0)
    ) {
      throw new Error('历史游标未前进，请重试')
    }
    visited.add(String(next))
    cursor = next
  }
  return null
}

/** 只接收服务端约定的站内附件路径；不渲染任意协议或外部跟踪地址。 */
export function chatMediaUrl(value) {
  return typeof value === 'string' && /^\/uploads\/[\w/-]+\.(?:jpe?g|png|webp|mp4)$/i.test(value)
    ? value
    : ''
}

/** 客户端仅做快速反馈，附件魔数 / 归属仍由服务端裁定。 */
export function validateChatFile(file) {
  if (!file || !file.size) return '请选择非空图片或 MP4 视频'
  if (file.size > 50 * 1024 * 1024) return '附件不能超过 50MB'
  if (!['image/jpeg', 'image/png', 'image/webp', 'video/mp4'].includes(file.type)) {
    return '仅支持 JPG、PNG、WebP 图片或 MP4 视频'
  }
  return ''
}

export function chatTextLength(value) {
  return Array.from(value || '').length
}
