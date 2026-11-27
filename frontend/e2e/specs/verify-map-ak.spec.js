/**
 * 手工实测脚本（ADR-0022 · S14 真实 AK 双端联动）
 *
 * 与 e2e 的区别：这里**故意不用断言**，只把每一步的界面事实打印出来 + 截图，
 * 供人工核对（截图落 reports/playwright/assets/，不在 git 里）。
 *
 * 跑法（后端 8080 + dev server 5141 已就绪，frontend/.env.development.local 已填 AK）：
 *   cd frontend
 *   pnpm exec playwright test verify-map-ak --workers=1 --reporter=list
 *
 * 覆盖：
 *   1. 家属端订单详情：地图出瓦片 + 覆盖物 + 距离文案
 *   2. 双端联动：陪诊员上报更近的位置 → 家属端距离数字下降（不刷新页面）
 *   3. 暂停共享 → 家属端显示「已暂停位置共享」
 *   4. 陪诊员打卡页：地图 + 共享开关
 */
import path from 'node:path'
import fs from 'node:fs'
import { test } from '@playwright/test'
import { authFile, ARTIFACT_DIR } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { apiAsAccount, API } from '../helpers/api'
import { seedAcceptedOrder } from '../helpers/seedOrder'
import { hasMapAk } from '../helpers/mapAk'

const SHOT_DIR = path.join(ARTIFACT_DIR, 'assets')
fs.mkdirSync(SHOT_DIR, { recursive: true })

// 没有 AK 时这套「手工实测」毫无意义（地图根本不会渲染）。
// 它在全量跑里会自动跳过，不拖累任何人的 e2e。
test.skip(!hasMapAk(), '未配置 VITE_BAIDU_MAP_AK（frontend/.env.development.local），手工实测跳过')

/** 医院坐标来自 seedAcceptedOrder 默认值（海口·琼山区） */
const HOSPITAL = { lng: '110.311422', lat: '20.021674' }
/** 远端：距医院约 1.5 公里 */
const FAR = { lng: '110.321200', lat: '20.031500' }
/** 近端：距医院约 0.6 公里 */
const NEAR = { lng: '110.315500', lat: '20.025000' }

/**
 * 上报一次位置；撞上 4004 限流时等一个窗口重试。
 *
 * e2e 回滚会让自增 id 复位，同一 orderId 在相邻两次跑之间会重复出现，
 * 而 Redis 的 `track:rate:{orderId}` 有 10 秒 TTL —— 跨跑残留会让「首次上报」变成限流。
 * 这是测试夹具的 id 复用假象，不是产品缺陷。
 */
async function report(compCtx, orderId, point, waitMs = 11_000) {
  const shot = async () =>
    (
      await compCtx.post(`${API}/execution/${orderId}/location`, {
        data: { longitude: point.lng, latitude: point.lat, accuracy: 12 }
      })
    ).json()
  const res = await shot()
  if (res.code !== 4004) return res
  await new Promise((r) => setTimeout(r, waitMs))
  return shot()
}

/** 把共享态归一（track:share 无 TTL，跨跑会残留） */
async function setSharing(compCtx, orderId, enabled) {
  return (
    await compCtx.put(`${API}/execution/${orderId}/location-sharing`, { data: { enabled } })
  ).json()
}

test.describe('S14 · 家属端地图 + 双端联动', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('家属端地图 + 距离随上报下降 + 暂停共享', async ({ page }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({
        famCtx,
        compCtx,
        remark: 'S14 真实 AK 实测',
        longitude: HOSPITAL.lng,
        latitude: HOSPITAL.lat
      })
      await setSharing(compCtx, orderId, true)

      // ① 先把陪诊员放在远端
      const far = await report(compCtx, orderId, FAR)
      console.log(
        '[S14] 远端上报 →',
        JSON.stringify({ code: far.code, distance: far.data?.distanceMeters })
      )

      // ② 家属端打开订单详情
      await page.goto(ROUTES.familyOrderDetail(orderId))
      await page.getByText('实时位置', { exact: true }).waitFor()
      // 等百度瓦片画完
      await page.waitForFunction(() => !!document.querySelector('.nl-map__canvas canvas'), null, {
        timeout: 20_000
      })
      await page.waitForTimeout(1500)

      const canvas = await page.locator('.nl-map__canvas canvas').count()
      const baiduCtrl = await page.locator('.BMap_cpyCtrl').count()
      const markers = await page.locator('.nl-map__canvas .BMap_Marker').count()
      const circles = await page.locator('.nl-map__canvas').evaluate((el) => {
        // Circle 是 canvas 里画出来的，DOM 上不可数；这里读状态行即可
        return el.querySelectorAll('canvas').length
      })
      const textFar = await page.locator('.nl-map__status').innerText()
      console.log(
        '[S14] canvas =',
        canvas,
        '| baidu 版权控件 =',
        baiduCtrl,
        '| 地图图标 Marker 数 =',
        markers,
        '| canvas 元素 =',
        circles
      )
      console.log('[S14] 家属端距离（远端）：', textFar.replace(/\s+/g, ' '))
      await page.screenshot({ path: path.join(SHOT_DIR, 's14-family-map-far.png'), fullPage: true })

      // ③ 陪诊员移动到近端 → 家属端应实时更新距离数字（不刷新页面）
      const near = await report(compCtx, orderId, NEAR)
      console.log(
        '[S14] 近端上报 →',
        JSON.stringify({ code: near.code, distance: near.data?.distanceMeters })
      )
      // 计时从「上报成功返回」开始：report() 可能已因 4004 等过一个 11s 限流窗口，
      // 那段时间不是推送延迟
      const t0 = Date.now()

      await page.waitForFunction(
        (distance) => {
          const el = document.querySelector('.nl-map__status-text')
          if (!el) return false
          // 距得近时文案是「直线约 564 米」，远时是「直线约 1.5 公里」—— 两种都算命中，
          // 所以直接把后端返回的米数写进断言，不要在前端重算格式化（重算一次就多一个能错的字）
          return el.textContent.includes(String(distance))
        },
        near.data?.distanceMeters,
        { timeout: 15_000 }
      )
      const textNear = await page.locator('.nl-map__status').innerText()
      console.log('[S14] 家属端距离（近端，未刷新页面）：', textNear.replace(/\s+/g, ' '))
      console.log('[S14] 端到端延迟（上报→界面更新）：', Date.now() - t0, 'ms')
      await page.screenshot({ path: path.join(SHOT_DIR, 's14-family-map-near.png'), fullPage: true })

      // ④ 暂停共享 → 家属端必须显示「已暂停」，而不是冻住的点
      await setSharing(compCtx, orderId, false)
      await page.waitForFunction(
        () => document.body.innerText.includes('已暂停位置共享'),
        null,
        { timeout: 10_000 }
      )
      console.log('[S14] 暂停共享后家属端已显示「已暂停位置共享」')
      await page.screenshot({ path: path.join(SHOT_DIR, 's14-family-map-paused.png'), fullPage: true })
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})

test.describe('S14 · 陪诊员打卡页地图', () => {
  test.use({ storageState: authFile('comp007'), viewport: { width: 390, height: 844 } })

  test('打卡页地图 + 共享开关', async ({ page }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({
        famCtx,
        compCtx,
        remark: 'S14 陪诊员端实测',
        longitude: HOSPITAL.lng,
        latitude: HOSPITAL.lat
      })
      await setSharing(compCtx, orderId, true)
      await report(compCtx, orderId, FAR)

      await page.goto(ROUTES.companionExecute(orderId))
      await page.getByText('当前定位', { exact: true }).waitFor()
      await page.waitForTimeout(3000)

      const canvas = await page.locator('.nl-map__canvas canvas').count()
      const switchChecked = await page.locator('.loc__share .el-switch.is-checked').count()
      const status = await page.locator('.nl-map__status').innerText().catch(() => '(无状态行)')
      console.log('[S14] 打卡页 canvas =', canvas, '| 共享开关开启 =', switchChecked > 0)
      console.log('[S14] 打卡页状态行：', status.replace(/\s+/g, ' '))
      await page.screenshot({ path: path.join(SHOT_DIR, 's14-companion-map.png'), fullPage: true })
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})
