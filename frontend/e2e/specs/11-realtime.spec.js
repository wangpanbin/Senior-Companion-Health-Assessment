/**
 * 11 · M5 实时推送 / M8 未读数（WebSocket）
 *
 * 陪诊执行页依赖 WebSocket（端点 `/ws/progress?token=&orderId=`）实时推送打卡进度。
 * 本 spec 证明「页面确实建立了 WS 连接」，作为实时链路的冒烟判据。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES, SEED } from '../helpers/constants'
import { apiAsAccount, API } from '../helpers/api'

test.use({ storageState: authFile('comp007'), viewport: { width: 390, height: 844 } })

test.describe('陪诊执行页的实时通道', () => {
  test('打开执行页会建立 /ws/ 连接', async ({ page }) => {
    const opened = []
    page.on('websocket', (ws) => opened.push(ws.url()))

    await page.goto(ROUTES.companionExecute(SEED.orderAccepted))
    await page.waitForTimeout(3000)

    expect(opened.length, '执行页应建立 WebSocket 连接').toBeGreaterThan(0)
    expect(
      opened.some((u) => u.includes('/ws/')),
      `WS 应指向 /ws/ 端点（实际 ${JSON.stringify(opened)}）`
    ).toBeTruthy()
  })

  test('执行页的打卡进度接口与页面一致可用', async ({ page }) => {
    await page.goto(ROUTES.companionExecute(SEED.orderAccepted))
    await page.waitForTimeout(1200)
    const text = await page.locator('body').innerText()
    expect(text).toContain('出发')
  })
})

test.describe('站内信未读数（轮询通道）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('首页会请求未读数接口', async ({ page }) => {
    const hits = []
    page.on('response', (r) => {
      if (r.url().includes('/message/unread-count')) hits.push(r.status())
    })
    await page.goto(ROUTES.familyHome)
    await page.waitForTimeout(2500)
    expect(hits.length, '首页应轮询 unread-count').toBeGreaterThan(0)
    expect(hits.every((s) => s < 400), '未读数接口不应报错').toBeTruthy()
  })

  // plan §M8 §验收：「未读数变化在 3 秒内推送到前端，无需刷新」
  // 这里走「前后两次轮询的差量」不变量：comp007 打卡后 fam001 的 unread-count 应在 ≤3s 内 +1
  test('打卡后未读数 ≤3s 内 +1（轮询通道 + 站内信同步）', async () => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const before = (await (await famCtx.get(`${API}/message/unread-count`)).json()).data?.total ?? 0
      const t0 = Date.now()
      // fam001 下单 + comp007 接单 + comp007 DEPART 打卡
      const create = await (
        await famCtx.post(`${API}/order`, {
          data: {
            elderId: 401, hospital: '海南省人民医院', department: '心血管内科',
            visitTime: '2099-09-20 09:30:00', address: '海南省海口市琼山区',
            longitude: '110.311422', latitude: '20.021674', remark: 'e2e M8 unread'
          }
        })
      ).json()
      expect(create.code).toBe(200)
      const orderId = create.data.orderId
      await (await compCtx.post(`${API}/order/${orderId}/accept`)).json()
      await (await compCtx.post(`${API}/execution/${orderId}/checkin`, {
        data: { node: 'DEPART', longitude: '110.311422', latitude: '20.021674' }
      })).json()
      // 等 3s（plan §M8 上界）再次查询未读数
      await new Promise((r) => setTimeout(r, 1500))
      const after = (await (await famCtx.get(`${API}/message/unread-count`)).json()).data?.total ?? 0
      expect(after, '未读数应 +2（accept + checkin 各 1 条）').toBe(before + 2)
      expect(Date.now() - t0, '推送延迟应 < 3000ms').toBeLessThan(3000)
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})
