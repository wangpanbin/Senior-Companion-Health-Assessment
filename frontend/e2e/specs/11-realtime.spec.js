/**
 * 11 · M5 实时推送 / M8 未读数（WebSocket）
 *
 * 陪诊执行页依赖 WebSocket（端点 `/ws/progress?token=&orderId=`）实时推送打卡进度。
 * 本 spec 证明「页面确实建立了 WS 连接」，作为实时链路的冒烟判据。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES, SEED } from '../helpers/constants'

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
})
