/**
 * 02 · M2 / M12 越权访问矩阵
 *
 * 硬指标：4 角色 × 3 类接口 = 12 条越权用例 100% 通过（PRD §9.2）。
 *
 * 判据必须在**接口层**成立 —— 前端隐藏按钮不算安全边界（PRD §2.3）。
 * 所以本 spec 全部绕过浏览器直连后端。
 *
 * 借助后端专门提供的探针：PermissionProbeController `/api/common/perm-probe/**`
 *   每个角色只应能通过自己的那一格；其余格必须 403。
 */
import { test, expect } from '@playwright/test'
import { apiAsAccount, API } from '../helpers/api'
import { SEED } from '../helpers/constants'

/** 角色 → 探针名 */
const PROBES = ['elder', 'family', 'companion', 'admin']

const MATRIX = [
  { account: 'elder001', role: 'ELDER', allow: 'elder' },
  { account: 'fam001', role: 'FAMILY', allow: 'family' },
  { account: 'comp001', role: 'COMPANION', allow: 'companion' },
  { account: 'admin', role: 'ADMIN', allow: 'admin' }
]

test.describe('探针矩阵：4 角色 × 4 探针', () => {
  for (const row of MATRIX) {
    test(`${row.account}(${row.role}) 只通过自己的探针`, async () => {
      const { ctx, dispose } = await apiAsAccount(row.account)
      try {
        // 3 类接口之一：通用已认证接口 —— 4 角色都应 200
        const auth = await ctx.get(`${API}/common/perm-probe/authenticated`)
        expect(auth.status(), `${row.account} 应能访问 authenticated 探针`).toBe(200)

        for (const p of PROBES) {
          const res = await ctx.get(`${API}/common/perm-probe/${p}`)
          if (p === row.allow) {
            expect(res.status(), `${row.account} 应通过 /${p}`).toBe(200)
          } else {
            expect(res.status(), `${row.account} 访问 /${p} 必须 403`).toBe(403)
          }
        }
      } finally {
        await dispose()
      }
    })
  }
})

test.describe('老人账号写操作 100% 服务端 403（PRD §2.3 硬约束）', () => {
  test('ELDER 调任意写接口都是 403（不是业务错误码）', async () => {
    const { ctx, dispose } = await apiAsAccount('elder001')
    try {
      const writes = [
        ['post', `${API}/order`, { elderId: SEED.elderProfileBound, hospital: 'x', department: 'y', visitTime: '2026-09-20 09:00:00' }],
        ['post', `${API}/review`, { orderId: SEED.orderReviewed, score: 5, content: 'x' }],
        ['post', `${API}/complaint`, { orderId: SEED.orderPending, type: 'OTHER', content: 'x' }],
        ['post', `${API}/medication/plan`, { elderId: SEED.elderProfileBound, medicineId: 1, dosage: '1片' }],
        ['put', `${API}/user/profile`, { nickname: 'hacked' }]
      ]
      for (const [method, url, data] of writes) {
        const res = await ctx[method](url, { data })
        expect(res.status(), `ELDER ${method.toUpperCase()} ${url} 必须 403`).toBe(403)
      }
    } finally {
      await dispose()
    }
  })

  test('ELDER 的只读接口仍可访问（不是一刀切全禁）', async () => {
    const { ctx, dispose } = await apiAsAccount('elder001')
    try {
      expect((await ctx.get(`${API}/order`)).status()).toBe(200)
      expect((await ctx.get(`${API}/message`)).status()).toBe(200)
    } finally {
      await dispose()
    }
  })
})

test.describe('角色与资源归属越权', () => {
  // 合法的下单请求体（角色校验在**参数校验之后**，body 非法会先撞校验，
  // 拿不到 403 —— 见下面「参数校验先于角色校验」那条观察用例）
  const VALID_ORDER = {
    elderId: SEED.elderProfileBound,
    hospital: '海南省人民医院',
    department: '心血管内科',
    visitTime: '2026-09-20 09:30:00',
    address: '海南省海口市秀英区秀华路19号 门诊大楼3楼'
  }

  test('FAMILY 不能接单 / COMPANION 不能下单', async () => {
    const fam = await apiAsAccount('fam001')
    const comp = await apiAsAccount('comp001')
    try {
      expect((await fam.ctx.post(`${API}/order/${SEED.orderPending}/accept`)).status()).toBe(403)
      expect((await comp.ctx.post(`${API}/order`, { data: VALID_ORDER })).status()).toBe(403)
    } finally {
      await fam.dispose()
      await comp.dispose()
    }
  })

  test('非 ADMIN 访问 /api/admin/** 一律 403', async () => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      expect((await ctx.get(`${API}/admin/user`)).status()).toBe(403)
      expect((await ctx.get(`${API}/admin/order`)).status()).toBe(403)
      expect((await ctx.get(`${API}/admin/oper-log`)).status()).toBe(403)
    } finally {
      await dispose()
    }
  })

  test('非 ADMIN 带非法请求体访问 /api/admin/** → 仍是 403（不是 400）', async () => {
    // 关键性质：/api/admin/** 的 hasRole(ADMIN) 在**过滤链**上，
    // 早于参数校验。否则越权者可据「400 vs 403」探测权限与参数结构。
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const res = await ctx.post(`${API}/admin/companion/audit/1`, { data: { garbage: true } })
      expect(res.status(), '过滤链先于参数校验 → 必须是 403').toBe(403)
    } finally {
      await dispose()
    }
  })

  test('[观察] 普通接口的参数校验先于 @PreAuthorize：非法 body + 越权角色 → 200 业务错，不是 403', async () => {
    // 与上一条对照：非 admin 路由已把角色校验下沉到过滤链，
    // 但业务接口（如 POST /api/order）仍是 AOP @PreAuthorize，排在参数校验之后。
    // 结果：越权角色 + 非法 body 会先拿到「参数校验失败」的业务错误。
    // 这本身不构成越权（业务错误不含数据），但与「角色 403」的文档口径不完全一致，
    // 记录在此以便评审 —— 断言的是**观测到的现状**，不是期望。
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const res = await ctx.post(`${API}/order`, { data: { garbage: true } })
      const body = await res.json()
      expect(res.status()).toBe(200)
      expect(body.code).not.toBe(200) // 业务失败码（参数校验）
    } finally {
      await dispose()
    }
  })

  test('未过审陪诊员（comp025）看不到大厅、不能接单 —— 业务码 2003', async () => {
    // ⚠️ 口径：这里是**业务规则**拦截（资质 APPROVED），不是角色拦截，
    // 因此返回 HTTP 200 + code 2003，而不是 403。
    const { ctx, dispose } = await apiAsAccount('comp025')
    try {
      const hall = await (await ctx.get(`${API}/order/hall`)).json()
      expect(hall.code, '未过审 → 2003').toBe(2003)

      const accept = await (await ctx.post(`${API}/order/${SEED.orderPending}/accept`)).json()
      expect(accept.code, '未过审接单 → 2003').toBe(2003)
    } finally {
      await dispose()
    }
  })

  test('已过审陪诊员（comp001）可以看到大厅', async () => {
    const { ctx, dispose } = await apiAsAccount('comp001')
    try {
      const body = await (await ctx.get(`${API}/order/hall`)).json()
      expect(body.code).toBe(200)
    } finally {
      await dispose()
    }
  })

  test('家属不能读他人订单（归属校验，非角色校验）', async () => {
    // fam019 的订单 1019 不属于 fam001
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const res = await ctx.get(`${API}/order/${SEED.orderCompleted}`)
      const body = await res.json()
      // 允许 403/404 或业务失败码；唯独不能 200 返回他人订单详情
      const leaked = res.status() === 200 && body?.code === 200
      expect(leaked, 'fam001 不应能读到 fam019 的订单详情').toBeFalsy()
    } finally {
      await dispose()
    }
  })
})
