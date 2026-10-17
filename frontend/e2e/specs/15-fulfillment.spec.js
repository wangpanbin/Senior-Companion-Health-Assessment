/**
 * 15 · V3.1 履约保障（工作状态 / 风险读口径 / 催单 / 风险中心 / 改派 / 大厅排序）
 *
 * 覆盖 docs/spec/V3.1-tickets.md 的 DoD 后半段：「既有端到端用例全部通过」由 01–14 保证，
 * 本文件只负责**本版新增**的行为。
 *
 * 三条自陈约束（与既有套件一致）：
 *   1. **断言不变量，不断言绝对条数** —— 库相对种子已被历史写路径污染，
 *      绝对条数会把环境债伪装成代码缺陷（见 constants.js §F-04）；
 *   2. **写操作必须自清理** —— 会改状态的用例在 finally 里恢复原值；
 *      恢复不了的（如催单次数）只走「必然被拒」的负向路径；
 *   3. **越权用例必须携带合法请求体** —— 否则请求会死在参数校验层，
 *      拿到 400 而不是 403，看起来像鉴权生效、实则根本没走到鉴权。
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES, SEED } from '../helpers/constants'
import { sweep } from '../helpers/pageAudit'
import { apiAsAccount, API } from '../helpers/api'

/** 四档风险的中文文案 —— 与后端 FulfillmentRisk.getLabel() 同源，前端不自行判断等级 */
const RISK_LABELS = ['正常等待', '等待时间较长', '履约风险较高', '需要人工介入']

/* ================================================================== */
/* 1 · 陪诊员接单状态自助控制（T0）                                      */
/* ================================================================== */

test.describe('V3.1 · 陪诊员接单状态自助控制', () => {
  test('工作状态三档可切换，在手订单状态不受影响', async () => {
    const { ctx, dispose } = await apiAsAccount('comp007')
    try {
      // 记录原值，用例结束必须还原 —— 状态是共享环境的一部分
      const before = await (await ctx.get(`${API}/user/companion/307`)).json()
      expect(before.code, '取陪诊员档案失败').toBe(200)
      const original = before.data?.workStatus
      expect(original, '档案里没有 workStatus 字段').toBeTruthy()

      // 记录在手订单（ACCEPTED / IN_SERVICE）—— 切状态前后必须逐单一致
      const ordersBefore = await (await ctx.get(`${API}/order?page=1&size=100`)).json()
      expect(ordersBefore.code).toBe(200)
      const heldBefore = (ordersBefore.data?.records || [])
        .filter((o) => o.status === 'ACCEPTED' || o.status === 'IN_SERVICE')
        .map((o) => `${o.id}:${o.status}`)
        .sort()

      for (const target of ['OFFLINE', 'REST', 'AVAILABLE']) {
        const res = await ctx.put(`${API}/user/companion/work-status`, {
          data: { workStatus: target }
        })
        const body = await res.json()
        expect(body.code, `切到 ${target} 应成功`).toBe(200)

        const after = await (await ctx.get(`${API}/user/companion/307`)).json()
        expect(after.data?.workStatus, `档案应反映 ${target}`).toBe(target)
      }

      // 切到休息中之后，已接的订单仍是已接 —— 状态只影响新单匹配
      const ordersAfter = await (await ctx.get(`${API}/order?page=1&size=100`)).json()
      const heldAfter = (ordersAfter.data?.records || [])
        .filter((o) => o.status === 'ACCEPTED' || o.status === 'IN_SERVICE')
        .map((o) => `${o.id}:${o.status}`)
        .sort()
      expect(heldAfter, '切接单状态不得改变在手订单').toEqual(heldBefore)
    } finally {
      // 还原（拿不到原值时退回 AVAILABLE：可接单是种子基线）
      await ctx
        .put(`${API}/user/companion/work-status`, { data: { workStatus: 'AVAILABLE' } })
        .catch(() => {})
      await dispose()
    }
  })

  test('非法接单状态字面量返回 400，不静默落库', async () => {
    const { ctx, dispose } = await apiAsAccount('comp007')
    try {
      const res = await ctx.put(`${API}/user/companion/work-status`, {
        data: { workStatus: 'BUSY_BUT_ACCEPTING' }
      })
      const body = await res.json()
      expect(body.code, '非法字面量必须被拒').toBe(400)

      const after = await (await ctx.get(`${API}/user/companion/307`)).json()
      expect(['AVAILABLE', 'REST', 'OFFLINE']).toContain(after.data?.workStatus)
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== */
/* 2 · 履约读口径：OrderVO 五字段与履约详情（T4）                        */
/* ================================================================== */

test.describe('V3.1 · 履约读口径', () => {
  test('订单列表返回五个履约只读字段，且既有字段零破坏', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (await ctx.get(`${API}/order?page=1&size=20`)).json()
      expect(body.code).toBe(200)
      const records = body.data?.records || []
      expect(records.length, 'fam001 名下应有订单').toBeGreaterThan(0)

      for (const r of records) {
        // 五个新增字段必须存在（类型契约）
        expect(typeof r.fulfillmentRisk, 'fulfillmentRisk 必须返回').toBe('string')
        expect(typeof r.fulfillmentRiskLabel, 'fulfillmentRiskLabel 必须返回').toBe('string')
        expect(typeof r.waitMinutes, 'waitMinutes 必须返回').toBe('number')
        expect(typeof r.urgeCount, 'urgeCount 必须返回').toBe('number')
        expect(typeof r.canUrge, 'canUrge 必须返回').toBe('boolean')

        // 既有字段零破坏 —— 老前端靠这几个字段工作
        expect(r.id).toBeTruthy()
        expect(r.orderNo).toBeTruthy()
        expect(r.statusLabel).toBeTruthy()

        // 产品不变量：风险只在待接单时对外可见
        if (r.status !== 'PENDING') {
          expect(r.fulfillmentRisk, `${r.id} 非待接单不得带风险`).toBe('NORMAL')
          expect(r.waitMinutes, `${r.id} 非待接单等待时长应为 0`).toBe(0)
          expect(r.canUrge, `${r.id} 非待接单不可催`).toBe(false)
        }
      }
    } finally {
      await dispose()
    }
  })

  test('履约详情事件流按发生时间升序，且空事件流返回数组而非 null', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (await ctx.get(`${API}/order/${SEED.orderPending}/fulfillment`)).json()
      expect(body.code).toBe(200)
      expect(body.data).toBeTruthy()
      expect(Array.isArray(body.data.events), '事件流必须是数组').toBe(true)

      // 升序：逐条比对相邻两项
      const times = body.data.events.map((e) => e.operateTime).filter(Boolean)
      for (let i = 1; i < times.length; i++) {
        expect(
          times[i] >= times[i - 1],
          `事件流必须升序：第 ${i - 1} 条 ${times[i - 1]} 晚于第 ${i} 条 ${times[i]}`
        ).toBeTruthy()
      }

      // 中文文案由后端下发，前端不自行判断等级
      expect(RISK_LABELS).toContain(body.data.fulfillmentRiskLabel)
    } finally {
      await dispose()
    }
  })

  test('无关家属 / 无关陪诊员读履约详情一律 3004', async () => {
    // 1001 归 fam001，fam019 是另一个家属
    const fam = await apiAsAccount('fam019')
    try {
      const body = await (await fam.ctx.get(`${API}/order/${SEED.orderPending}/fulfillment`)).json()
      expect(body.code, '无关家属必须被拒').toBe(3004)
    } finally {
      await fam.dispose()
    }

    const comp = await apiAsAccount('comp007')
    try {
      const body = await (await comp.ctx.get(`${API}/order/${SEED.orderPending}/fulfillment`)).json()
      expect(body.code, '无关陪诊员必须被拒').toBe(3004)
    } finally {
      await comp.dispose()
    }
  })
})

/* ================================================================== */
/* 3 · 家属催单（T3）                                                   */
/* ================================================================== */

test.describe('V3.1 · 家属催单', () => {
  test('非家属角色调催单一律 403', async () => {
    for (const account of ['comp001', 'elder001']) {
      const { ctx, dispose } = await apiAsAccount(account)
      try {
        const res = await ctx.post(`${API}/order/${SEED.orderPending}/urge`, {
          data: { remark: '端到端用例：非家属角色不应能催单' }
        })
        expect(res.status(), `${account} 应被角色门槛拦下`).toBe(403)
      } finally {
        await dispose()
      }
    }
  })

  test('催单结果只能是成功或 3008/3009（不出现 500，也不绕过频率限制）', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const res = await ctx.post(`${API}/order/${SEED.orderPending}/urge`, {
        data: { remark: '端到端用例：请尽快安排陪诊员' }
      })
      const body = await res.json()
      // 允许的三种结局：成功 / 当前不可催 / 过于频繁。
      // 断言集合而不是具体值：这一单的风险等级与催单次数取决于库的当前状态，
      // 但**绝不允许**出现 500 或其它错误码
      expect([200, 3008, 3009], `催单返回了意外的 ${body.code}`).toContain(body.code)

      if (body.code === 200) {
        expect(body.data.urgeCount).toBeGreaterThan(0)
        expect(body.data).toHaveProperty('nextUrgeAvailableAt')
      }
    } finally {
      await dispose()
    }
  })

  test('催单备注命中医疗建议词表被拦（合规红线）', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const body = await (
        await ctx.post(`${API}/order/${SEED.orderPending}/urge`, {
          data: { remark: '建议服用两片降压药后再前往医院' }
        })
      ).json()
      expect(body.code, '含医疗建议的备注必须被拦下').toBe(6003)
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== */
/* 4 · 管理员风险中心与改派（T6 / T7）                                   */
/* ================================================================== */

test.describe('V3.1 · 管理员风险中心', () => {
  test('风险计数四个字段恒定齐全且为数字（为 0 时返回 0 而非省略）', async () => {
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      const body = await (await ctx.get(`${API}/admin/fulfillment/summary`)).json()
      expect(body.code).toBe(200)
      for (const key of ['manualRequired', 'urgent', 'warning', 'normal']) {
        expect(typeof body.data[key], `${key} 必须返回数字`).toBe('number')
      }
    } finally {
      await dispose()
    }
  })

  test('风险列表脱敏且只含待接单；非法风险等级返回 400', async () => {
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      const body = await (await ctx.get(`${API}/admin/fulfillment/risk-list?page=1&size=20`)).json()
      expect(body.code).toBe(200)
      for (const r of body.data?.records || []) {
        // 列表口径一律脱敏：全名豁免只对详情生效
        if (r.elderName) expect(r.elderName, '列表姓名必须脱敏').toContain('*')
        if (r.orderNo) expect(r.orderNo, '列表订单号必须脱敏').not.toBe(r.orderNo.replace(/\*/g, ''))
      }

      const bad = await (await ctx.get(`${API}/admin/fulfillment/risk-list?risk=AT_RISK`)).json()
      expect(bad.code, '非法风险等级必须 400，不静默返回全量').toBe(400)
    } finally {
      await dispose()
    }
  })

  test('改派：非待接单订单返回 3010，且订单状态不变', async () => {
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      const before = await (await ctx.get(`${API}/order/${SEED.orderAccepted}`)).json()
      expect(before.code).toBe(200)
      const statusBefore = before.data.status

      const body = await (
        await ctx.post(`${API}/admin/order/${SEED.orderAccepted}/reassign`, {
          data: {
            companionId: 301,
            reason: '端到端用例：已接单订单不该能被改派，这条理由满足 10 字下限'
          }
        })
      ).json()
      expect(body.code, '已接单订单必须被拒').toBe(3010)

      const after = await (await ctx.get(`${API}/order/${SEED.orderAccepted}`)).json()
      expect(after.data.status, '被拒的改派不得改变订单状态').toBe(statusBefore)
    } finally {
      await dispose()
    }
  })

  test('非管理员访问风险中心三个端点一律 403（携带合法请求体）', async () => {
    for (const account of ['fam001', 'comp001', 'elder001']) {
      const { ctx, dispose } = await apiAsAccount(account)
      try {
        expect((await ctx.get(`${API}/admin/fulfillment/summary`)).status(), account).toBe(403)
        expect((await ctx.get(`${API}/admin/fulfillment/risk-list`)).status(), account).toBe(403)

        // 越权用例必须带能通过参数校验的请求体，否则死在参数层拿到 400 假绿
        const res = await ctx.post(`${API}/admin/order/${SEED.orderPending}/reassign`, {
          data: {
            companionId: 301,
            reason: '端到端用例：非管理员不应能改派，理由满足 10 字下限'
          }
        })
        expect(res.status(), `${account} 改派应 403`).toBe(403)
      } finally {
        await dispose()
      }
    }
  })
})

/* ================================================================== */
/* 5 · 大厅排序（T8）                                                   */
/* ================================================================== */

test.describe('V3.1 · 大厅单一推荐序', () => {
  test('未开放的排序维度返回 400，不静默降级为推荐序', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      for (const sortBy of ['DISTANCE', 'TIME_MATCH', 'INCOME']) {
        const body = await (await ctx.get(`${API}/order/hall?sortBy=${sortBy}`)).json()
        expect(body.code, `sortBy=${sortBy} 必须 400`).toBe(400)
      }
    } finally {
      await dispose()
    }
  })

  test('不传 sortBy 返回待接单，且新旧参数都能拿到履约字段', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const body = await (await ctx.get(`${API}/order/hall?page=1&size=20`)).json()
      expect(body.code).toBe(200)
      for (const r of body.data?.records || []) {
        expect(r.status, '大厅只给待接单').toBe('PENDING')
        // 大厅也要有履约字段 —— 陪诊员同样需要知道这一单等了多久
        expect(typeof r.waitMinutes).toBe('number')
      }
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== */
/* 6 · 页面可达性（UI 层，仅断言壳与关键文案）                            */
/* ================================================================== */

test.describe('V3.1 · 履约页面可达性', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('家属订单详情页对待接单订单渲染风险状态条', async ({ page }) => {
    const text = await sweep(page, ROUTES.familyOrderDetail(SEED.orderPending))
    // 状态条文案来自后端，四档之一；不断言具体档位（取决于库的当前等待时长）
    expect(RISK_LABELS.some((l) => text.includes(l)), '详情页应展示四档风险文案之一').toBeTruthy()
  })
})

test.describe('V3.1 · 管理端履约风险中心页面', () => {
  test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

  test('风险中心页可达且四档计数卡渲染', async ({ page }) => {
    const text = await sweep(page, ROUTES.adminFulfillment)
    for (const label of ['需要人工介入', '履约风险较高', '等待时间较长', '正常等待']) {
      expect(text, `四档计数卡应出现「${label}」`).toContain(label)
    }
  })
})
