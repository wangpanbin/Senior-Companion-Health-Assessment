/**
 * 23b · 位置事件的**线路级**角色过滤（ADR-0022 D10）
 *
 * `OrderProgressHubRoleFilterTest` 已在单测层证明 `publishTo` 会跳过非白名单会话，
 * 但那只证明了「我们的代码逻辑对」。这里的用例进一步在**真实 WebSocket 线上**验证：
 * 用 elder001 / admin 的令牌去订阅同一订单，**一帧 `LOCATION` 都不能收到**；
 * 同一时刻 fam001 的订阅必须收到。
 *
 * 为什么值得单独一条：这类泄漏在功能测试里完全看不出来（家属端一切正常），
 * 只有抓包才会发现管理员那台电脑也在收「陪诊员此刻在哪」——
 * 而那是一次真实的隐私事故，不是「多推了一条无关消息」。
 *
 * 实现方式：不开新页面（ELDER/ADMIN 本来就没有这张页面 —— 这正是设计），
 * 而是在一个空白页里用 `WebSocket` 构造函数直连 `/ws/progress`，
 * 复刻 `utils/realtime.js` 的 query 令牌做法。
 */
import { test, expect } from '@playwright/test'
import { apiAsAccount, apiLogin, API } from '../helpers/api'

/** 医院坐标（seedAcceptedOrder 默认） */
const HOSPITAL = { lng: '110.311422', lat: '20.021674' }
const FAR = { lng: '110.321200', lat: '20.031500' }

/** 在页面里开一条 /ws/progress 订阅，把收到的原始帧堆到 window.__frames */
async function subscribeInPage(page, token, orderId) {
  await page.goto('/login') // 任意同源页面，只为拿到 window 环境
  await page.evaluate(
    ({ token, orderId }) => {
      window.__frames = []
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
      const url = `${proto}//${location.host}/ws/progress?token=${encodeURIComponent(
        token
      )}&orderId=${orderId}`
      const ws = new WebSocket(url)
      window.__ws = ws
      window.__wsClosed = false
      ws.onmessage = (e) => window.__frames.push(e.data)
      ws.onclose = () => {
        window.__wsClosed = true
      }
    },
    { token, orderId }
  )
}

/** 等握手完成（收到 CONNECTED 或 PONG）或连接被关闭 */
async function waitConnected(page) {
  await page.waitForFunction(
    () => (window.__frames || []).length > 0 || window.__wsClosed === true,
    null,
    { timeout: 10_000 }
  )
  return page.evaluate(() => ({ frames: window.__frames.length, closed: window.__wsClosed }))
}

test.describe('位置事件线路级角色过滤（ADR-0022）', () => {
  test.use({ viewport: { width: 1280, height: 800 } })

  test('ELDER / ADMIN 订阅同一订单收不到 LOCATION，FAMILY 收得到', async ({ page }) => {
    const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
    const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
    try {
      // ① 自建一条 ACCEPTED 订单
      const create = await (
        await famCtx.post(`${API}/order`, {
          data: {
            elderId: 401,
            hospital: '海南省人民医院',
            department: '心血管内科',
            visitTime: '2099-09-20 09:30:00',
            address: '海南省海口市琼山区',
            longitude: HOSPITAL.lng,
            latitude: HOSPITAL.lat,
            remark: 'e2e ws role filter'
          }
        })
      ).json()
      expect(create.code, '下单应成功').toBe(200)
      const orderId = create.data.orderId
      const accept = await (await compCtx.post(`${API}/order/${orderId}/accept`)).json()
      expect(accept.code, '接单应成功').toBe(200)

      // ② 归一共享态（track:share 无 TTL，跨跑会残留）
      await compCtx.put(`${API}/execution/${orderId}/location-sharing`, { data: { enabled: true } })

      // ③ 三个身份各自订阅同一订单
      const famToken = (await apiLogin('fam001')).token
      const elderToken = (await apiLogin('elder001')).token
      const adminToken = (await apiLogin('admin')).token

      await subscribeInPage(page, famToken, orderId)
      // 同一个页面只能持有一条 window.__ws（上面 evaluate 会覆盖），
      // 所以另两条用独立的 evaluate 句柄并存到 window.__wsExtra
      await page.evaluate(
        ({ tokens, orderId }) => {
          window.__framesExtra = { elder: [], admin: [] }
          const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
          const open = (name, token) => {
            const ws = new WebSocket(
              `${proto}//${location.host}/ws/progress?token=${encodeURIComponent(
                token
              )}&orderId=${orderId}`
            )
            ws.onmessage = (e) => window.__framesExtra[name].push(e.data)
            ws.onclose = () => window.__framesExtra[name].push('__CLOSED__')
          }
          open('elder', tokens.elder)
          open('admin', tokens.admin)
        },
        { tokens: { elder: elderToken, admin: adminToken }, orderId }
      )
      await page.waitForTimeout(2500) // 等三条握手完成

      // ④ 陪诊员上报一次位置
      const report = await (
        await compCtx.post(`${API}/execution/${orderId}/location`, {
          data: { longitude: FAR.lng, latitude: FAR.lat }
        })
      ).json()
      expect([200, 4004], `上报应成功（4004 视为限流，不影响本用例）实际 ${report.code}`).toContain(
        report.code
      )

      await page.waitForTimeout(2500) // 等帧到达

      const result = await page.evaluate(() => ({
        family: (window.__frames || []).filter((f) => typeof f === 'string'),
        elder: window.__framesExtra?.elder || [],
        admin: window.__framesExtra?.admin || []
      }))

      const hasLocation = (frames) => frames.some((f) => f.includes('"type":"LOCATION"'))
      const hasConnected = (frames) => frames.some((f) => f.includes('CONNECTED'))

      // 先确认三条订阅**真的都握手成功了**（否则「没收到」可能只是没连上，假绿）
      expect(hasConnected(result.family), '家属订阅应握手成功').toBeTruthy()
      expect(hasConnected(result.elder), '老人订阅应握手成功（能看打卡进度）').toBeTruthy()
      expect(hasConnected(result.admin), '管理员订阅应握手成功').toBeTruthy()

      // 核心断言：家属收得到，老人与管理员收不到
      expect(hasLocation(result.family), '家属必须收到 LOCATION').toBeTruthy()
      expect(hasLocation(result.elder), '老人不得收到 LOCATION（行踪信息）').toBeFalsy()
      expect(hasLocation(result.admin), '管理员不得收到 LOCATION（取证走 /track）').toBeFalsy()
    } finally {
      await famDispose()
      await compDispose()
    }
  })
})
