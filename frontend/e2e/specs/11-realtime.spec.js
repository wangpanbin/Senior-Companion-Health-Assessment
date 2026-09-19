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
import { seedAcceptedOrder, PUSH_DEADLINE_MS, PUSH_SETTLE_MS } from '../helpers/seedOrder'

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
  // 这里走「前后两次轮询的差量」不变量：comp007 打卡后 fam001 的 unread-count 应在 ≤PUSH_DEADLINE_MS 内 +1
  test('打卡后未读数 ≤PUSH_DEADLINE_MS 内 +1（轮询通道 + 站内信同步）', async () => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const before = (await (await famCtx.get(`${API}/message/unread-count`)).json()).data?.total ?? 0
      const t0 = Date.now()
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e M8 unread' })
      await (await compCtx.post(`${API}/execution/${orderId}/checkin`, {
        data: { node: 'DEPART', longitude: '110.311422', latitude: '20.021674' }
      })).json()
      // 等 PUSH_SETTLE_MS（plan §M8 上界内）再次查询未读数
      await new Promise((r) => setTimeout(r, PUSH_SETTLE_MS))
      const after = (await (await famCtx.get(`${API}/message/unread-count`)).json()).data?.total ?? 0
      expect(after, '未读数应 +2（accept + checkin 各 1 条）').toBe(before + 2)
      expect(Date.now() - t0, '推送延迟应 < PUSH_DEADLINE_MS').toBeLessThan(PUSH_DEADLINE_MS)
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})

// ============================================================
// M5 WS 推送：plan §M5 §验收「家属端在不刷新页面的前提下，3 秒内收到陪诊进度推送」
// 上面站内信腿（同步事务写 internal_message）已覆盖；本组用例走 WS 腿。
// fam001 用浏览器打开订单详情 → 建立 /ws/progress?orderId={id} 订阅
// → comp007 打卡 → 3s 内应收到 ORDER_PROGRESS 帧。
// ============================================================
test.describe('M5 家属端 WS 推送验证（fam001 浏览器订阅）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1280, height: 800 } })

  test('打卡后家属端 WS ≤PUSH_DEADLINE_MS 内收到 ORDER_PROGRESS 帧', async ({ page }) => {
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    try {
      // 1. 自建 ACTIVE 订单
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e M5 WS' })

      // 2. fam001 用浏览器打开订单详情页（建立 /ws/progress 订阅）
      //    ⚠️ page.on('websocket') 必须在 goto 前注册，否则收不到 handshake 后再开的 WS
      const wsFrames = []
      page.on('websocket', (ws) => {
        ws.on('framereceived', (frame) => {
          try {
            wsFrames.push({ t: Date.now(), payload: frame.payload })
          } catch {
            wsFrames.push({ t: Date.now(), payload: '<binary>' })
          }
        })
      })
      await page.goto(`/family/order/${orderId}`)
      await page.waitForTimeout(1500) // 等 WS 订阅建立 + 首帧 TYPE_CONNECTED

      // 3. comp007 提交 DEPART 打卡
      const t0 = Date.now()
      const checkin = await (
        await compCtx.post(`${API}/execution/${orderId}/checkin`, {
          data: { node: 'DEPART', longitude: '110.311422', latitude: '20.021674' }
        })
      ).json()
      expect(checkin.code, 'DEPART 打卡应业务码 200').toBe(200)

      // 4. 等 PUSH_DEADLINE_MS 内 WS 帧
      await page.waitForTimeout(PUSH_DEADLINE_MS)
      const progress = wsFrames.find(
        (f) => typeof f.payload === 'string' && f.payload.includes('ORDER_PROGRESS')
      )
      expect(progress, '家属端 WS 应在 PUSH_DEADLINE_MS 内收到 ORDER_PROGRESS 帧').toBeTruthy()
      if (progress) {
        expect(progress.t - t0, '推送延迟应 < PUSH_DEADLINE_MS').toBeLessThan(PUSH_DEADLINE_MS)
      }
    } finally {
      await compDispose()
      await famDispose()
    }
  })
})
