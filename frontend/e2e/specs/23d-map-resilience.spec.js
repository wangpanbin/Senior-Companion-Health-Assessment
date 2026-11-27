/**
 * 23d · 断线恢复 / 老人端无地图入口 / 事件负载不含隐私字段（ADR-0022）
 *
 * 三条都是「功能看着正常、但承诺被悄悄破坏」的类型：
 *
 *   1. **断网 12 秒再恢复**：位置流必须自己接上（`utils/realtime.js` 的指数退避），
 *      且**不重复渲染**（同一条事件只更新一次距离）；
 *   2. **老人端没有地图入口**：地图只给 FAMILY / COMPANION。老人端一旦出现地图，
 *      既违反 ADR-0022 的范围，也与「老人端 ≤3 次点击 / 48px 触控」的适老化约束冲突；
 *   3. **`LOCATION` 事件负载不含姓名 / 手机号 / 身份证**：位置本身已经是敏感行踪信息，
 *      再附带身份字段只会让它更敏感，而前端一个字段都用不上。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { apiAsAccount, API } from '../helpers/api'
import { seedAcceptedOrder } from '../helpers/seedOrder'

const HOSPITAL = { lng: '110.311422', lat: '20.021674' }
const FAR = { lng: '110.321200', lat: '20.031500' }
const NEAR = { lng: '110.315500', lat: '20.025000' }

/** 与 `NlMapPanel` 里的 `distanceText` 保持同一套格式化（< 1km 用米，否则公里一位小数） */
function expectedDistanceText(meters) {
  if (meters == null) return '尚未获取到位置'
  return meters < 1000 ? `${meters} 米` : `${(meters / 1000).toFixed(1)} 公里`
}

/** 上报；撞 4004 时等一个窗口重试（e2e 回滚会复用自增 id） */
async function report(compCtx, orderId, point) {
  const shot = async () =>
    (
      await compCtx.post(`${API}/execution/${orderId}/location`, {
        data: { longitude: point.lng, latitude: point.lat }
      })
    ).json()
  const first = await shot()
  if (first.code !== 4004) return first
  await new Promise((r) => setTimeout(r, 11_000))
  return shot()
}

test.describe('断线与恢复', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('断网 12 秒再恢复：位置流自己接上，不刷新页面也能更新', async ({ page, context }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({
        famCtx,
        compCtx,
        remark: 'e2e offline',
        longitude: HOSPITAL.lng,
        latitude: HOSPITAL.lat
      })
      await compCtx.put(`${API}/execution/${orderId}/location-sharing`, { data: { enabled: true } })
      const far = await report(compCtx, orderId, FAR)

      await page.goto(ROUTES.familyOrderDetail(orderId))
      await expect(page.getByText('实时位置', { exact: true })).toBeVisible()
      await expect
        .poll(
          async () =>
            (await page.locator('.nl-map__status').innerText()).includes(
              expectedDistanceText(far.data?.distanceMeters)
            ),
          { timeout: 15_000 }
        )
        .toBeTruthy()

      // 断网（模拟电梯 / 地下车库）
      await context.setOffline(true)
      await page.waitForTimeout(12_000)
      // 断网期间界面仍应保留最后一次已知位置（不能因为断线就清空成空态）。
      // 读整页文本而不是 `.nl-map__status`：断网时百度瓦片拉不到，看门狗会把地图
      // 降级成文字卡片，那行距离文案会从 `.nl-map__status` 挪到 `.nl-map__fallback` ——
      // **两种形态都必须保留最后已知位置**，所以断言落在「页面上还有这条距离」上。
      const duringOffline = await page.locator('body').innerText()
      expect(duringOffline, '断网期间应保留最后已知位置').toMatch(/直线约\s*[\d.]+\s*(米|公里)/)

      await context.setOffline(false)
      // 等页面重新连上（WS 退避重连最大 30s；这里只等距离文案恢复更新）
      const near = await report(compCtx, orderId, NEAR)
      await expect
        .poll(
          async () => {
            const text = await page.locator('body').innerText().catch(() => '')
            return text.includes(expectedDistanceText(near.data?.distanceMeters))
          },
          { timeout: 40_000 }
        )
        .toBeTruthy()

      // 同一条事件只应更新一次：距离文案里只有一个「直线约」，不出现重复段落
      const status = await page.locator('body').innerText()
      expect(status.match(/直线约/g)?.length, '距离文案不应重复渲染').toBe(1)
    } finally {
      await context.setOffline(false)
      await famDispose()
      await compDispose()
    }
  })
})

test.describe('老人端没有地图入口', () => {
  test.use({ storageState: authFile('elder001'), viewport: { width: 390, height: 844 } })

  test('老人端首页 / 用药页均无地图容器', async ({ page }) => {
    for (const route of [ROUTES.elderHome, ROUTES.elderMedication]) {
      await page.goto(route)
      await page.waitForTimeout(1200)
      expect(await page.locator('.nl-map').count(), `${route} 不应出现地图`).toBe(0)
      const body = await page.locator('body').innerText()
      expect(body).not.toContain('实时位置')
    }
  })
})

test.describe('LOCATION 事件负载不含隐私字段', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1280, height: 800 } })

  test('LOCATION 帧只含坐标 / 距离 / 时间 / 开关', async ({ page }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({
        famCtx,
        compCtx,
        remark: 'e2e payload',
        longitude: HOSPITAL.lng,
        latitude: HOSPITAL.lat
      })
      await compCtx.put(`${API}/execution/${orderId}/location-sharing`, { data: { enabled: true } })

      const locationFrames = []
      page.on('websocket', (ws) => {
        ws.on('framereceived', (frame) => {
          if (typeof frame.payload !== 'string') return
          if (!frame.payload.includes('"type":"LOCATION"')) return
          locationFrames.push(JSON.parse(frame.payload))
        })
      })

      await page.goto(ROUTES.familyOrderDetail(orderId))
      await expect(page.getByText('实时位置', { exact: true })).toBeVisible()
      await page.waitForTimeout(1500)

      await report(compCtx, orderId, FAR)
      await expect.poll(() => locationFrames.length, { timeout: 10_000 }).toBeGreaterThan(0)

      const data = locationFrames[0].data
      // 允许的字段白名单
      const allowed = new Set([
        'longitude',
        'latitude',
        'distanceMeters',
        'recordTime',
        'sharingEnabled',
        'staleSeconds',
        'pushTime'
      ])
      const extra = Object.keys(data).filter((k) => !allowed.has(k))
      expect(extra, `LOCATION 负载不应包含额外字段：${extra.join(',')}`).toEqual([])

      // 逐条点名禁字段（比白名单更好读，出错时直接指出是哪一类泄漏）
      const raw = JSON.stringify(data)
      for (const forbidden of ['phone', 'idCard', 'realName', 'elderName', 'companionName']) {
        expect(raw, `LOCATION 负载不应出现 ${forbidden}`).not.toContain(forbidden)
      }
      // 手机号 / 身份证形态的字符串也不该出现
      expect(raw).not.toMatch(/\b1[3-9]\d{9}\b/)
      expect(raw).not.toMatch(/\b[1-9]\d{5}(19|20)\d{2}\d{4}\d{3}[\dXx]\b/)
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})
