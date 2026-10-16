/**
 * 14 · M13 订单聊天（家属 ↔ 陪诊员）
 *
 * 覆盖：双账号真实订单互发文字、WS 在 3 秒内送达、未读数差量、只读矩阵。
 * 媒体附件用真实 PNG 走 CHAT 上传与 IMAGE 消息，验证契约链路。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { apiAsAccount, API } from '../helpers/api'
import { seedAcceptedOrder, PUSH_DEADLINE_MS } from '../helpers/seedOrder'

const PNG_BYTES = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64'
)

async function sendText(ctx, orderId, content) {
  const body = await (
    await ctx.post(`${API}/chat/${orderId}/messages`, {
      data: { msgType: 'TEXT', content }
    })
  ).json()
  expect(body.code, JSON.stringify(body)).toBe(200)
  return body.data
}

test.describe('订单聊天双端实时互发', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1280, height: 800 } })

  test('陪诊员发消息，家属聊天室 3 秒内收到且留下未读差量', async ({ page }) => {
    const fam = await apiAsAccount('fam001')
    const comp = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx: fam.ctx, compCtx: comp.ctx, remark: 'e2e chat' })

      const frames = []
      page.on('websocket', (ws) => {
        if (!ws.url().includes('/ws/chat')) return
        expect(ws.url(), '聊天室应连接 /ws/chat').toContain('/ws/chat')
        ws.on('framereceived', (frame) => frames.push(String(frame.payload)))
      })
      await page.goto(ROUTES.chatRoom(orderId))
      await expect(page.getByLabel('消息内容')).toBeEnabled()

      const before = (await (await fam.ctx.get(`${API}/chat/unread-total`)).json()).data.total
      const sentAt = Date.now()
      const sent = await sendText(comp.ctx, orderId, '老人已经到诊室门口')
      await expect(page.getByText('老人已经到诊室门口')).toBeVisible({ timeout: PUSH_DEADLINE_MS })

      const frame = frames.find((value) => value.includes('CHAT_MESSAGE') && value.includes('老人已经到诊室门口'))
      expect(frame, `应收到 CHAT_MESSAGE 帧，实际=${JSON.stringify(frames)}`).toBeTruthy()
      expect(frame).toContain(String(sent.messageId))
      expect(Date.now() - sentAt, 'WS 送达应 <= PUSH_DEADLINE_MS').toBeLessThanOrEqual(PUSH_DEADLINE_MS)

      await sendText(comp.ctx, orderId, '稍后同步缴费单')
      await expect.poll(async () => {
        const total = (await (await fam.ctx.get(`${API}/chat/unread-total`)).json()).data.total
        return total - before
      }, { timeout: 2000 }).toBeGreaterThanOrEqual(1)
    } finally {
      await comp.dispose()
      await fam.dispose()
    }
  })

  test('图片消息走 CHAT 附件并在聊天室显示', async ({ page }) => {
    const fam = await apiAsAccount('fam001')
    const comp = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx: fam.ctx, compCtx: comp.ctx, remark: 'e2e chat image' })
      const upload = await (
        await comp.ctx.post(`${API}/file/upload`, {
          multipart: { file: { name: 'queue.png', mimeType: 'image/png', buffer: PNG_BYTES }, bizType: 'CHAT' }
        })
      ).json()
      expect(upload.code, JSON.stringify(upload)).toBe(200)
      await sendText(comp.ctx, orderId, '排队照片')
      const media = await (
        await comp.ctx.post(`${API}/chat/${orderId}/messages`, {
          data: { msgType: 'IMAGE', content: '现场照片', fileId: upload.data.fileId }
        })
      ).json()
      expect(media.code, JSON.stringify(media)).toBe(200)

      await page.goto(ROUTES.chatRoom(orderId))
      await expect(page.getByText('现场照片')).toBeVisible()
      await expect(page.locator('.chat-bubble__image img').first()).toHaveAttribute('src', /\/uploads\//)
    } finally {
      await comp.dispose()
      await fam.dispose()
    }
  })
})

test.describe('订单聊天只读界面', () => {
  test.use({ storageState: authFile('elder001') })
  test('老人进入聊天室只见只读提示，不出现输入区', async ({ page }) => {
    const fam = await apiAsAccount('fam001')
    const comp = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx: fam.ctx, compCtx: comp.ctx, remark: 'e2e chat elder ui' })
      await sendText(fam.ctx, orderId, '家属留言供老人查看')
      await page.goto(ROUTES.chatRoom(orderId))
      await expect(page.getByText('家属留言供老人查看')).toBeVisible()
      await expect(page.getByLabel('发送消息')).toHaveCount(0)
      await expect(page.getByText(/只读：/)).toBeVisible()
    } finally {
      await comp.dispose()
      await fam.dispose()
    }
  })
})

test.describe('管理员聊天查看器', () => {
  test.use({ storageState: authFile('admin') })
  test('管理员仲裁查看器只读展示订单沟通', async ({ page }) => {
    const fam = await apiAsAccount('fam001')
    const comp = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx: fam.ctx, compCtx: comp.ctx, remark: 'e2e chat admin ui' })
      await sendText(comp.ctx, orderId, '陪诊员处理记录')
      await page.goto(`${ROUTES.adminChatViewer}?orderId=${orderId}`)
      await expect(page.getByText('陪诊员处理记录')).toBeVisible()
      await expect(page.getByText(/仲裁只读/)).toBeVisible()
      await expect(page.getByLabel('发送消息')).toHaveCount(0)
    } finally {
      await comp.dispose()
      await fam.dispose()
    }
  })
})

test.describe('订单聊天只读矩阵（绕过前端直连后端）', () => {
  test('老人写入 403，管理员写入 7008，管理员仲裁可读历史', async () => {
    const fam = await apiAsAccount('fam001')
    const comp = await apiAsAccount('comp007')
    const elder = await apiAsAccount('elder001')
    const admin = await apiAsAccount('admin')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx: fam.ctx, compCtx: comp.ctx, remark: 'e2e chat readonly' })
      await sendText(fam.ctx, orderId, '请帮忙留意老人状态')

      const elderWrite = await elder.ctx.post(`${API}/chat/${orderId}/messages`, {
        data: { msgType: 'TEXT', content: '我自己发' }
      })
      expect(elderWrite.status(), await elderWrite.text()).toBe(403)

      const adminWrite = await (
        await admin.ctx.post(`${API}/chat/${orderId}/messages`, {
          data: { msgType: 'TEXT', content: '仲裁不得插话' }
        })
      ).json()
      expect(adminWrite.code).toBe(7008)

      const adminHistory = await (await admin.ctx.get(`${API}/chat/${orderId}/messages`)).json()
      expect(adminHistory.code).toBe(200)
      expect(adminHistory.data.myRole).toBe('ADMIN')
      expect(adminHistory.data.records.some((item) => item.content === '请帮忙留意老人状态')).toBeTruthy()

      const adminOrders = await (await admin.ctx.get(`${API}/admin/chat/orders?page=1&size=20`)).json()
      expect(adminOrders.code).toBe(200)
      expect(adminOrders.data.records.some((item) => item.orderId === orderId)).toBeTruthy()
    } finally {
      await admin.dispose()
      await elder.dispose()
      await comp.dispose()
      await fam.dispose()
    }
  })
})
