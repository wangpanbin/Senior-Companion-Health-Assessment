/**
 * 23c · 两个「口径一致性」与「失效 AK 降级」的实测（ADR-0022）
 *
 * 1. **打卡距离 ↔ 地图距离同口径**：同一坐标下 `/location` 返回的 `distanceMeters`
 *    与 `/checkin` 返回的 `distance` 必须**完全相等**（同一个 `GeoUtil.distanceMeters`）。
 *    这条如果不成立，说明有人在地图链路里另写了一套距离算法 ——
 *    家属看到的「还有 1.2 公里」与打卡被拒的「超出 2000 米」就会互相矛盾。
 *
 * 2. **地图卡不展示「距老人」**：终点是医院坐标，不是老人住址
 *    （`elder_profile` 没有坐标列）。文案写成「距老人」就是界面在说谎。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { apiAsAccount, API } from '../helpers/api'
import { seedAcceptedOrder } from '../helpers/seedOrder'

const HOSPITAL = { lng: '110.311422', lat: '20.021674' }

test.describe('位置距离与打卡距离同口径', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('同一坐标：/location.distanceMeters === /checkin.distance（误差 0）', async () => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({
        famCtx,
        compCtx,
        remark: 'e2e distance parity',
        longitude: HOSPITAL.lng,
        latitude: HOSPITAL.lat
      })
      await compCtx.put(`${API}/execution/${orderId}/location-sharing`, { data: { enabled: true } })

      // 一个距医院约 560 米的点，在 2000 米阈值内 → 打卡应当成功并回填 distance
      const point = { longitude: '110.315500', latitude: '20.025000' }

      const loc = await (
        await compCtx.post(`${API}/execution/${orderId}/location`, { data: point })
      ).json()
      expect([200, 4004], `位置上报应成功或限流，实际 ${loc.code}`).toContain(loc.code)

      const checkin = await (
        await compCtx.post(`${API}/execution/${orderId}/checkin`, {
          data: { node: 'DEPART', ...point }
        })
      ).json()
      expect(checkin.code, '阈值内打卡应成功').toBe(200)

      const locationDistance = loc.data.distanceMeters
      const checkinDistance = checkin.data.distance
      expect(locationDistance, '位置距离不能为 null（订单有坐标）').not.toBeNull()
      expect(
        locationDistance,
        `位置距离 ${locationDistance} 与打卡距离 ${checkinDistance} 必须完全相等（同一个 GeoUtil）`
      ).toBe(checkinDistance)
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})

test.describe('地图卡文案口径', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('距离文案写「距医院」，不写「距老人」（终点是医院坐标）', async ({ page }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({
        famCtx,
        compCtx,
        remark: 'e2e copy',
        longitude: HOSPITAL.lng,
        latitude: HOSPITAL.lat
      })
      await compCtx.put(`${API}/execution/${orderId}/location-sharing`, { data: { enabled: true } })
      await compCtx.post(`${API}/execution/${orderId}/location`, {
        data: { longitude: '110.315500', latitude: '20.025000' }
      })

      await page.goto(ROUTES.familyOrderDetail(orderId))
      await expect(page.getByText('实时位置', { exact: true })).toBeVisible()
      await expect
        .poll(async () => /直线约\s*[\d.]+\s*(米|公里)/.test(await page.locator('body').innerText()), {
          timeout: 15_000
        })
        .toBeTruthy()

      const body = await page.locator('body').innerText()
      expect(body, '必须写明「距医院」').toContain('距医院')
      expect(body, '不得写「距老人」—— 终点是医院坐标，不是老人住址').not.toContain('距老人')
      expect(body, '必须出现「仅供参考」').toContain('仅供参考')
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})
