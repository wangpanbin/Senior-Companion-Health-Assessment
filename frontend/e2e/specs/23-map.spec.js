/**
 * 23 · 实时位置地图（ADR-0022）
 *
 * 这组用例守的是**降级路径 + 可见性**，不是地图渲染本身：
 *
 *   1. CI / 本地默认没有 `VITE_BAIDU_MAP_AK`，所以「无 AK → 文字卡片」是
 *      **唯一在评委机器上 100% 会被执行**的分支。它必须不白屏、不弹错、
 *      且仍然给出「直线距离 + 多久前更新」这两条家属真正要的信息。
 *   2. 位置事件只推 FAMILY + COMPANION —— 老人与管理员订阅同一订单的 WS
 *      不得收到 `LOCATION`。这条泄漏在功能测试里完全看不出来。
 *
 * 有 AK 时的地图渲染断言用 `test.skip` 保护：CI 无网无 AK，硬断言会稳定红。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { apiAsAccount, API } from '../helpers/api'
import { seedAcceptedOrder } from '../helpers/seedOrder'
import { hasMapAk } from '../helpers/mapAk'

/** 同一个订单两次上报的最小间隔（后端默认 10 秒），用例里要等过它 */
const RATE_LIMIT_WAIT_MS = 11_000

/**
 * 上报一次位置（原始，不重试）。需要验证真实 `4004` 的用例必须用它 ——
 * 下面的 {@link reportLocation} 会把 4004 吞掉。
 */
async function reportLocationOnce(compCtx, orderId, data = {}) {
  return (
    await compCtx.post(`${API}/execution/${orderId}/location`, {
      data: { longitude: '110.321200', latitude: '20.031500', accuracy: 18.5, ...data }
    })
  ).json()
}

/**
 * 上报一次位置（陪诊员身份），返回后端业务码。
 *
 * 遇 `4004`（限流）自动等过一个窗口重试一次 —— e2e 回滚会把自增 id 重置，
 * 同一 `orderId` 会在相邻两次跑之间重复出现，而 Redis 的 `track:rate:{orderId}`
 * 只有 10 秒 TTL，跨跑残留会让「首次上报」变成限流。这是测试夹具的 id 复用假象，
 * 不是产品缺陷（生产主键单调递增、不会重用）。
 *
 * ⚠️ 验证「限流真的生效」时必须用 {@link reportLocationOnce}。
 */
async function reportLocation(compCtx, orderId, data = {}) {
  const first = await reportLocationOnce(compCtx, orderId, data)
  if (first.code !== 4004) return first
  await new Promise((r) => setTimeout(r, RATE_LIMIT_WAIT_MS))
  return reportLocationOnce(compCtx, orderId, data)
}

/**
 * 把位置共享状态归一到期望值（幂等）。
 *
 * 同上：`track:share:{orderId}` 在测试环境会因 id 复用跨跑残留，
 * 而它是**无 TTL** 的（生产设计如此：共享状态应与订单同寿）。
 * 所以用例开头先显式设一次，不依赖“新订单应该默认开”。
 */
async function setSharing(compCtx, orderId, enabled) {
  return (
    await compCtx.put(`${API}/execution/${orderId}/location-sharing`, { data: { enabled } })
  ).json()
}

test.describe('家属端 · 实时位置卡（降级路径为主）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('ACCEPTED 订单出现「实时位置」卡，并给出直线距离与更新时间', async ({ page }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e map fallback' })
      // ACCEPTED 即可上报（D3：路上那段正是家属最想看的），不必先 start
      await setSharing(compCtx, orderId, true)
      const report = await reportLocation(compCtx, orderId)
      expect(report.code, '位置上报应成功').toBe(200)

      await page.goto(ROUTES.familyOrderDetail(orderId))
      await expect(page.getByText('实时位置', { exact: true })).toBeVisible()

      // 等地图加载完成或降级（百度脚本从 CDN 拉，1~3 秒）——
      // 在「地图加载中…」时读文本会拿到什么都没的状态行
      await expect
        .poll(
          async () => {
            const text = await page.locator('body').innerText()
            return (
              text.includes('未配置地图 AK') ||
              text.includes('地图暂不可用') ||
              /直线约\s*[\d.]+\s*(米|公里)/.test(text)
            )
          },
          { timeout: 15_000 }
        )
        .toBeTruthy()

      // 地图或降级卡片二者必居其一（不允许白屏）
      const hasMap = (await page.locator('.nl-map__canvas').count()) > 0
      const body = await page.locator('body').innerText()
      const hasFallback = body.includes('未配置地图 AK') || body.includes('地图暂不可用')
      expect(hasMap || hasFallback, '必须有地图或降级卡片之一').toBeTruthy()

      // 地图有没有都不影响：距离与更新时间必须给出
      expect(body).toMatch(/直线约\s*[\d.]+\s*(米|公里)/)
      expect(body).toContain('前更新')
      // 一期红线：不做导航。两种形态下都不得出现这些词
      expect(body).not.toContain('预计到达')
      expect(body).not.toContain('导航')
      expect(body).not.toContain('路线')
      // 角标不能把布尔值直接渲染成「false」（NlCard extra 插槽回落问题）
      expect(body).not.toContain('\nfalse\n')
    } finally {
      await famDispose()
      await compDispose()
    }
  })

  test('陪诊员暂停共享后，家属端显示「已暂停位置共享」而不是冻住的点', async ({ page }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e map pause ui' })
      await setSharing(compCtx, orderId, true)
      await reportLocation(compCtx, orderId)
      const paused = await setSharing(compCtx, orderId, false)
      expect(paused.code, '暂停共享应成功').toBe(200)
      expect(paused.data.sharingEnabled).toBe(false)

      await page.goto(ROUTES.familyOrderDetail(orderId))
      await expect(page.getByText('已暂停位置共享').first()).toBeVisible()
    } finally {
      await famDispose()
      await compDispose()
    }
  })

  test('PENDING 订单不渲染「实时位置」卡（没有位置可看）', async ({ page }) => {
    await page.goto(ROUTES.familyOrderDetailPending())
    await expect(page.getByText('待接单').first()).toBeVisible()
    await expect(page.getByText('实时位置', { exact: true })).toHaveCount(0)
  })
})

test.describe('陪诊员端 · 打卡页的地图与共享开关', () => {
  test.use({ storageState: authFile('comp007'), viewport: { width: 390, height: 844 } })

  test('打卡页出现地图容器（或降级卡片）与「共享实时位置」开关', async ({ page }) => {
    const { ctx: compCtx, dispose } = await apiAsAccount('comp007')
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e map execute' })
      // 先归一为「共享开」：否则跨跑残留的 track:share=off 会让开关初始就是关的
      await setSharing(compCtx, orderId, true)
      await page.goto(ROUTES.companionExecute(orderId))
      await expect(page.getByText('当前定位', { exact: true })).toBeVisible()

      const body = await page.locator('body').innerText()
      // 地图或降级卡片二者必居其一（不允许空白）
      const hasMap = (await page.locator('.nl-map__canvas').count()) > 0
      const hasFallback = body.includes('未配置地图 AK') || body.includes('地图暂不可用')
      expect(hasMap || hasFallback, '必须有地图或降级卡片之一').toBeTruthy()

      // ⚠️ el-switch 把 <input role="switch"> 隐藏起来、只显示外层轨道，
      //    所以 toBeVisible 要断言外层 .el-switch，勾选态读 .is-checked；
      //    直接断言 input 的可见性会稳定拿到 hidden（2026-11-27 实测踩过）
      const switchEl = page.getByRole('switch', { name: '共享实时位置给家属' })
      await expect(switchEl).toBeAttached()
      await expect(switchEl).toBeChecked()
      const track = page.locator('.loc__share .el-switch')
      await expect(track).toBeVisible()
      await expect(track).toHaveClass(/is-checked/)
    } finally {
      await famDispose()
      await dispose()
    }
  })

  test('暂停共享后再上报返回 4005（不静默丢弃）', async ({ page }) => {
    const { ctx: compCtx, dispose } = await apiAsAccount('comp007')
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e map 4005' })
      await setSharing(compCtx, orderId, true)
      await page.goto(ROUTES.companionExecute(orderId))
      await expect(page.getByText('当前定位', { exact: true })).toBeVisible()

      // 点开关（UI 路径）：关闭共享
      const track = page.locator('.loc__share .el-switch')
      await track.click()

      // 等过限流窗口再上报：应回 4005 而不是 200
      await page.waitForTimeout(RATE_LIMIT_WAIT_MS)
      const rejected = await reportLocation(compCtx, orderId)
      expect(rejected.code, '暂停后上报应回 4005').toBe(4005)
    } finally {
      await famDispose()
      await dispose()
    }
  })
})

test.describe('实时位置 · 角色过滤与可见性', () => {
  test('ELDER / ADMIN 的 GET /location 回 3004（能看进度，看不了实时位置）', async () => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    const { ctx: elderCtx, dispose: elderDispose } = await apiAsAccount('elder001')
    const { ctx: adminCtx, dispose: adminDispose } = await apiAsAccount('admin')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e map visible' })

      const asFamily = await (await famCtx.get(`${API}/execution/${orderId}/location`)).json()
      expect(asFamily.code, '家属可查').toBe(200)

      const asCompanion = await (await compCtx.get(`${API}/execution/${orderId}/location`)).json()
      expect(asCompanion.code, '陪诊员可查').toBe(200)

      const asElder = await (await elderCtx.get(`${API}/execution/${orderId}/location`)).json()
      expect(asElder.code, '老人不可查实时位置').toBe(3004)

      const asAdmin = await (await adminCtx.get(`${API}/execution/${orderId}/location`)).json()
      expect(asAdmin.code, '管理员不可查实时位置（取证走 /track）').toBe(3004)
    } finally {
      await famDispose()
      await compDispose()
      await elderDispose()
      await adminDispose()
    }
  })
})

test.describe('家属端 · LOCATION 推送与限流', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1280, height: 800 } })

  test('家属端 WS 收到 LOCATION 帧；间隔内的重复上报回 4004', async ({ page }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e map ws' })
      await setSharing(compCtx, orderId, true)

      const frames = []
      page.on('websocket', (ws) => {
        ws.on('framereceived', (frame) => {
          if (typeof frame.payload === 'string') frames.push(frame.payload)
        })
      })
      await page.goto(ROUTES.familyOrderDetail(orderId))
      // 等 WS 握手 + TYPE_CONNECTED
      await expect(page.getByText('实时位置', { exact: true })).toBeVisible()
      await page.waitForTimeout(1500)

      const first = await reportLocationOnce(compCtx, orderId)
      expect(first.code, '首次上报成功').toBe(200)

      // 立即重复上报：应被限流（4004），不静默丢弃。
      // 用不重试的版本 —— 否则会自动等 11 秒重试，把 4004 变成 200
      const second = await reportLocationOnce(compCtx, orderId, {
        longitude: '110.321300',
        latitude: '20.031600'
      })
      expect(second.code, '间隔内二次上报应回 4004').toBe(4004)

      // 等 WS 帧到达（同步发送，通常几十毫秒内）
      await expect
        .poll(() => frames.some((p) => p.includes('"type":"LOCATION"')), { timeout: 5000 })
        .toBeTruthy()
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})

test.describe('实时位置 · 有 AK 时的地图渲染', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1280, height: 800 } })

  test('配置了 AK 时地图容器真的挂上了百度地图', async ({ page }) => {
    test.skip(!hasMapAk(), '未配置 VITE_BAIDU_MAP_AK（.env*.local），跳过真实地图断言')
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      const { orderId } = await seedAcceptedOrder({ famCtx, compCtx, remark: 'e2e map real ak' })
      await setSharing(compCtx, orderId, true)
      const errors = []
      page.on('pageerror', (e) => errors.push(e.message))
      await reportLocation(compCtx, orderId)
      await page.goto(ROUTES.familyOrderDetail(orderId))
      await expect(page.getByText('实时位置', { exact: true })).toBeVisible()
      // 百度脚本 + 瓦片
      await expect
        .poll(
          async () =>
            (await page.locator('.nl-map__canvas canvas').count()) > 0 ||
            (await page.locator('.BMap_cpyCtrl').count()) > 0,
          { timeout: 15_000 }
        )
        .toBeTruthy()
      // 百度地图渲染不得抛页面级异常（Symbol 当 Marker 图标曾导致 createMarkerData NPE）
      expect(errors, `地图不应产生 pageerror：${JSON.stringify(errors)}`).toEqual([])
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})
