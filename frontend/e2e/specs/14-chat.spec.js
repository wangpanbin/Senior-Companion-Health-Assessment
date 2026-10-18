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

/**
 * 导航层可达性回归。
 *
 * 为什么单独一组：M13 首轮交付（ad2552a）里，家属端**只有订单详情页**有
 * 「联系对方」，工作台 / 订单列表 / 消息页全无入口 —— 后端、WebSocket、组件、
 * 路由都是好的，但功能等于不可达（用户原话：「开发了却没有 UI 展示」）。
 *
 * 为什么原有用例拦不住：本文件此前的每条用例都用 `page.goto(ROUTES.chatRoom(id))`
 * **直接输 URL** 进入聊天室，跳过了导航层。入口在不在，跟它们一律无关。
 * 所以这组用例的核心纪律是：**不许 goto 聊天室，只许从界面点进去**。
 */
test.describe('订单沟通导航层可达性（入口回归）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1280, height: 800 } })

  test('工作台点「订单沟通」进入会话列表', async ({ page }) => {
    await page.goto(ROUTES.familyHome)
    const entry = page.getByText('订单沟通', { exact: true }).first()
    await expect(entry, '家属工作台应暴露订单沟通入口').toBeVisible()

    // 靠点击进入 —— 这一条正是原缺陷所在
    await entry.click()
    await page.waitForURL(`**${ROUTES.chatSessions}`)
    await expect(page.locator('.chat-conversations').first()).toBeVisible()
  })

  test('消息页顶部沟通卡片可点入会话列表', async ({ page }) => {
    await page.goto(ROUTES.familyMessage)
    const card = page.locator('.msg-chat-entry__btn')
    await expect(card, '消息页应有订单沟通入口卡片').toBeVisible()

    await card.click()
    await page.waitForURL(`**${ROUTES.chatSessions}`)
    await expect(page.locator('.chat-conversations').first()).toBeVisible()
  })

  test('订单列表点「联系对方」进聊天室，不被整卡点击截胡', async ({ page }) => {
    await page.goto(ROUTES.familyOrder)

    // ⚠️ 必须 exact: true。getByRole 的 name 默认**子串**匹配，而整张卡
    //   `<li class="order" role="button">` 的可访问名包含卡内全部文本（含
    //   「联系对方」）。不加 exact，.first() 取到的是 li 而不是里面的按钮，
    //   点下去走 li 的 @click="view(o)" 落到订单详情 —— 症状与「按钮失效」一样。
    const btn = page.getByRole('button', { name: '联系对方', exact: true }).first()
    await expect(btn, '已接单的订单应出现「联系对方」').toBeVisible()
    await btn.click()

    // ⚠️ 关键断言：必须落在 /chat/{id}。
    //   整张卡是 <li role="button" @click="view(o)">，一旦按钮的 @click.stop
    //   失效或被截胡，这里会看到 /family/order/{id}（订单详情）而不是聊天室。
    await page.waitForURL(/\/chat\/\d+$/, { timeout: 15000 })
    await expect(page.locator('.chat-panel').first()).toBeVisible()
    // 家属是写侧，应能拿到输入框
    await expect(page.getByLabel('消息内容')).toBeEnabled()
  })
})

test.describe('订单聊天室信息面板（2026-10 UI 重构）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1280, height: 900 } })

  /**
   * 导航层纪律：本 describe 里的用例**一律不许 goto 聊天室**，
   * 只能从界面点进去 —— 否则「面板根本没挂载」这类缺陷对它们全盲。
   */
  test('从订单列表点进去能看到陪诊员与订单信息面板', async ({ page }) => {
    await page.goto(ROUTES.familyOrder)
    const btn = page.getByRole('button', { name: '联系对方', exact: true }).first()
    await expect(btn, '已接单的订单应出现「联系对方」').toBeVisible()
    await btn.click()
    await page.waitForURL(/\/chat\/\d+$/, { timeout: 15000 })

    const panel = page.locator('.order-info').first()
    await expect(panel, '≥1280 应常驻右侧订单信息面板').toBeVisible()

    // 就诊概览：医院 / 科室来自订单详情
    await expect(panel.locator('.order-info__section').first()).toContainText('医院科室')
    // 服务进度块即使无打卡也要有标题（要么进度条，要么「尚未开始服务」）
    await expect(panel).toContainText('服务进度')
    // 陪诊员块只在已接单订单出现
    await expect(panel.locator('.order-info__companion')).toBeVisible()
    await expect(panel).toContainText('资质')
  })

  test('陪诊员姓名在头部与信息面板一致，不出现脱敏名并存', async ({ page }) => {
    await page.goto(ROUTES.familyOrder)
    await page.getByRole('button', { name: '联系对方', exact: true }).first().click()
    await page.waitForURL(/\/chat\/\d+$/, { timeout: 15000 })

    // ⚠️ 先等陪诊员块渲染出来再读文本。textContent() 是立即读取、不等渲染的，
    //   订单详情还没回来时头部显示的是占位「订单沟通」，直接读会拿到占位值并
    //   误报「姓名不一致」。
    const companionName = page.locator('.order-info__companion .order-info__person-name').first()
    await expect(companionName, '陪诊员块应先渲染出来').toBeVisible({ timeout: 15000 })
    const panelName = (await companionName.textContent())?.trim()

    const headerName = (await page.locator('.chat-panel__name').first().textContent())?.trim()

    expect(panelName, '面板陪诊员姓名不应为空').toBeTruthy()
    expect(headerName, '头部姓名不应为空').toBeTruthy()
    // 聊天接口的 peerName 是脱敏的（李*军），头部若仍在用它就会与面板不一致。
    expect(headerName, '头部与信息面板的陪诊员姓名应一致').toBe(panelName)
    expect(headerName, '不应显示聊天接口的脱敏名').not.toMatch(/\*/)
  })

  test('免责声明在任何布局下都可见', async ({ page }) => {
    await page.goto(ROUTES.familyOrder)
    await page.getByRole('button', { name: '联系对方', exact: true }).first().click()
    await page.waitForURL(/\/chat\/\d+$/, { timeout: 15000 })
    await expect(page.getByText('不做诊断').first()).toBeVisible()
  })

  test('图片与视频是两个独立按钮，accept 互不重叠', async ({ page }) => {
    await page.goto(ROUTES.familyOrder)
    await page.getByRole('button', { name: '联系对方', exact: true }).first().click()
    await page.waitForURL(/\/chat\/\d+$/, { timeout: 15000 })

    const image = page.locator('input[type=file][aria-label="选择聊天图片"]')
    const video = page.locator('input[type=file][aria-label="选择聊天视频"]')
    await expect(image, '图片按钮应有独立的隐藏 input').toHaveCount(1)
    await expect(video, '视频按钮应有独立的隐藏 input').toHaveCount(1)

    const imageAccept = await image.getAttribute('accept')
    const videoAccept = await video.getAttribute('accept')
    expect(imageAccept, '图片按钮不应接受视频').not.toContain('video')
    expect(videoAccept, '视频按钮应只接受 MP4').toBe('video/mp4')

    // 两个可见按钮文案各自独立，不再是合并的「图片 / 视频」
    await expect(page.getByRole('button', { name: '图片', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '视频', exact: true })).toBeVisible()
  })

  test('窄屏降级为折叠卡：默认收起且可展开', async ({ page }) => {
    await page.setViewportSize({ width: 420, height: 900 })
    await page.goto(ROUTES.familyOrder)
    await page.getByRole('button', { name: '联系对方', exact: true }).first().click()
    await page.waitForURL(/\/chat\/\d+$/, { timeout: 15000 })

    const fold = page.locator('.chat-room__fold')
    await expect(fold, '<1280 应出现折叠信息卡').toBeVisible()
    await expect(fold, '折叠卡应默认收起').not.toHaveAttribute('open', '')

    // 收起态也要能一眼看到「到哪一步了」。摘要头要等订单详情回来才有值。
    await expect(
      fold.locator('.order-info__summary-percent'),
      '收起态应显示进度百分比'
    ).toHaveText(/^\d+%$/, { timeout: 15000 })

    await fold.locator('summary').click()
    await expect(fold, '点击标题后应展开').toHaveAttribute('open', '')
    await expect(fold.locator('.order-info').nth(1)).toContainText('服务进度')
  })
})
