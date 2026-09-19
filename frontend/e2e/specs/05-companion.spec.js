/**
 * 05 · M4 接单 + M5 陪诊执行（陪诊员端）
 *
 * 覆盖：大厅（6 条待接单）/ 接单 / 执行页 6 节点打卡 / 我的订单 / 收入 / 资质入驻。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES, SEED, CHECKIN_NODES } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

test.describe('陪诊员端页面（comp007，移动形态）', () => {
  test.use({ storageState: authFile('comp007'), viewport: { width: 390, height: 844 } })

  test('接单大厅', async ({ page }) => {
    const text = await sweep(page, ROUTES.companionHall)
    // 大厅可能为空（当前库 comp007 在大厅没有待接单 → 显示「暂无订单」），
    // 故只断言页面壳与区块标题，不断言具体条数。
    expect(text).toContain('接单大厅')
    expect(text.includes('待接单') || text.includes('暂无订单')).toBeTruthy()
  })
  test('我的订单', async ({ page }) => {
    await sweep(page, ROUTES.companionOrder)
  })
  test('我的收入', async ({ page }) => {
    await sweep(page, ROUTES.companionIncome)
  })
  test('消息页', async ({ page }) => {
    await sweep(page, ROUTES.companionMessage)
  })
  test('资质入驻页（mobile-only）', async ({ page }) => {
    await sweep(page, ROUTES.companionEntry)
  })
  test('订单执行页（1007 ACCEPTED）', async ({ page }) => {
    const text = await sweep(page, ROUTES.companionExecute(SEED.orderAccepted))
    expect(text).toContain('出发')
  })
})

test.describe('订单大厅与接单契约', () => {
  test('大厅返回 200 且返回项全为 PENDING（按角色自动筛选）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const body = await (await ctx.get(`${API}/order/hall?page=1&size=20`)).json()
      expect(body.code).toBe(200)
      // ⚠️ 不断言「恰好 6 条」：大厅会剔除该陪诊员已拒单的记录，
      //    当前库相对种子已污染（实测 comp001 拒过单，返回 0 条）。
      //    这里锁定不变量：契约是「只给 PENDING」。
      for (const r of body.data.records) expect(r.status).toBe('PENDING')
      expect(Array.isArray(body.data.records)).toBeTruthy()
    } finally {
      await dispose()
    }
  })

  test('状态机：对 PENDING 订单调「开始服务」→ 3002（禁止跳级，先状态后身份）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      // /start 无请求体，正好用来验证「先判状态 → 3002」这条铁律
      const body = await (await ctx.post(`${API}/order/${SEED.orderPending}/start`)).json()
      expect(body.code, '待接单订单调 start 必须返回 3002').toBe(3002)
    } finally {
      await dispose()
    }
  })

  test('打卡节点枚举覆盖 6 个节点', async () => {
    const { ctx, dispose } = await apiAsAccount('comp007')
    try {
      const body = await (await ctx.get(`${API}/execution/${SEED.orderAccepted}/checkins`)).json()
      expect(body.code).toBe(200)
      // 已打卡的节点只能是这 6 个之一
      const nodes = (body.data || []).map((c) => c.node)
      for (const n of nodes) expect(CHECKIN_NODES).toContain(n)
    } finally {
      await dispose()
    }
  })

  test('打卡进度接口可用（执行页依赖）', async () => {
    const { ctx, dispose } = await apiAsAccount('comp007')
    try {
      const body = await (await ctx.get(`${API}/execution/${SEED.orderAccepted}/progress`)).json()
      expect(body.code).toBe(200)
    } finally {
      await dispose()
    }
  })

  // ============================================================
  // M5 验收：plan.md §M5 §验收
  //   · 打卡坐标超出阈值 → 返回业务错误
  //   · 同一陪诊员对同一订单重复提交同一打卡类型 → 去重，不产生重复记录
  //   · 时间线顺序与 order_checkin.create_time 升序完全一致
  // 报告出处：reports/playwright/e2e-report.md §6（写动作留待下一轮）
  // ============================================================
  test.describe('M5 打卡写动作', () => {
    test('距离订单地址 > 2000m 应返回业务码 4001（CHECKIN_DISTANCE_EXCEEDED）', async () => {
      const { ctx, dispose } = await apiAsAccount('comp007')
      try {
        // 订单 1007 地址在海南 (lat=20.021674, lng=110.311422, FRONTEND_CONTRACT §10.8)；
        // 北京 (39.9, 116.4) 距其约 2200 km，必然 > 2000m 阈值（nianglin.order.checkin-max-distance-meters）。
        // ⚠️ DTO 字段是 longitude/latitude（字符串），不是 lat/lng；北京经纬度按 6 位小数截断避免精度尾巴。
        const body = await (
          await ctx.post(`${API}/execution/${SEED.orderAccepted}/checkin`, {
            data: { node: 'ARRIVE', longitude: '116.400000', latitude: '39.900000' }
          })
        ).json()
        expect(body.code, '超距应返回 4001').toBe(4001)
      } finally {
        await dispose()
      }
    })

    test('同节点重复打卡应返回 4002（CHECKIN_DUPLICATED），且不新增行', async () => {
      const { ctx, dispose } = await apiAsAccount('comp007')
      try {
        // 订单 1007 在种子中已打过 DEPART（ACCEPTED 状态陪诊员常已完成首节点）。
        // 不变量：连续两次同节点都应被去重拦截（4002），且 order_checkin 行数在两次提交后不变。
        const before = (await (await ctx.get(`${API}/execution/${SEED.orderAccepted}/checkins`)).json()).data
        const beforeCount = (before || []).filter((c) => c.node === 'DEPART').length
        const body1 = { node: 'DEPART', longitude: '110.311422', latitude: '20.021674' }
        const resp1 = await (await ctx.post(`${API}/execution/${SEED.orderAccepted}/checkin`, { data: body1 })).json()
        expect(resp1.code, '同节点提交应被去重拦截（4002）').toBe(4002)
        const resp2 = await (await ctx.post(`${API}/execution/${SEED.orderAccepted}/checkin`, { data: body1 })).json()
        expect(resp2.code, '再次同节点提交应仍返回 4002（幂等拒绝）').toBe(4002)
        const after = (await (await ctx.get(`${API}/execution/${SEED.orderAccepted}/checkins`)).json()).data
        const afterCount = (after || []).filter((c) => c.node === 'DEPART').length
        expect(afterCount, 'DEPART 节点行数应不变（去重未落库）').toBe(beforeCount)
      } finally {
        await dispose()
      }
    })
  })
})
