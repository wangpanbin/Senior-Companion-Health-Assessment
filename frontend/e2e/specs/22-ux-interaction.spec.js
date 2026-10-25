/**
 * 22 · 交互流程修复的回归护栏（UX 审查批次）
 *
 * <p>这一批用例有个共同点：<b>修的缺陷都是「代码已经改对了、但既有测试盖不住」</b>。
 * 不是补覆盖率，是补**盲区**——每一条的断言都对应一个真实发生过的、或随时会发生的
 * 交互缺陷。</p>
 *
 * <p>对应的缺陷与曾经的失败形态：</p>
 * <ul>
 *   <li>P1-2 订单详情页把上一单的数据留着 → 家属据此点的「去评价」作用到<b>错误的订单</b>上</li>
 *   <li>P1-3 下单第二步换选就诊人不生效 → <b>订单下给了旧老人</b>（不可逆）</li>
 *   <li>P1-4 老人端把接口失败显示成「暂无用药数据」→ 老人以为自己没药</li>
 *   <li>P1-5 写操作按钮无 in-flight 锁 → 连点发出两个请求</li>
 *   <li>P1-6 越权被静默弹回主页 → 用户以为系统坏了</li>
 *   <li>P1-7 陪诊员的「站内信」「收到的评价」两页<b>零入口</b></li>
 *   <li>P0-1 管理端用户列表返回<b>未脱敏的真实姓名</b>（违反 AGENTS.md §0.1）</li>
 *   <li>P0-2 重置密码谎称「已通过短信发送」→ 用户<b>永久锁死</b></li>
 * </ul>
 *
 * <p>写路径（建老人 / 下单 / 接单）由 globalSetup 的 snapshot + globalTeardown 的
 * restore/verify 负责回滚，跑完库回到基线。</p>
 */
import { test, expect } from '@playwright/test'
import { authFile } from '../helpers/paths'
import { ROUTES, SEED } from '../helpers/constants'
import { apiAsAccount, API } from '../helpers/api'
import { ROLE_HOME } from '../helpers/accounts'
import { READ_TOKEN } from '../helpers/storage'

/** 造一位新老人（自动绑定到调用者名下），返回 elderId */
async function createElderForFamily(ctx, name, phone, idCard) {
  const res = await (
    await ctx.post(`${API}/user/elder`, {
      data: { name, gender: 'MALE', birthDate: '1950-01-01', idCard, phone, address: '海南省海口市' }
    })
  ).json()
  expect(res.code, '建老人档案应成功').toBe(200)
  expect(res.data?.elderId, '应返回 elderId').toBeTruthy()
  return res.data.elderId
}

/* ================================================================== */
/* P1-2 · 订单详情换单后数据必须刷新                                     */
/* ================================================================== */
test.describe('订单详情换单后数据刷新（P1-2）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('列表点进 A 再点进 B：页面必须显示 B，URL 与内容一致', async ({ page }) => {
    await page.goto(ROUTES.familyOrder)
    await page.waitForTimeout(1500)

    const readOrderNo = async () => {
      await page.waitForTimeout(1500)
      const t = await page.locator('.overview .nl-h2').first().innerText()
      return t.trim()
    }

    // 点第一单 → 详情（客户端路由）
    await page.locator('.order').first().click()
    await page.waitForTimeout(1200)
    const noA = await readOrderNo()
    expect(noA, '第一单应显示订单号').toBeTruthy()

    // 返回列表（**客户端**历史导航）→ 点第二单 → 详情
    // ⚠️ 必须走客户端导航：整页刷新会重建组件，测不出「组件被复用」这个 bug。
    await page.goBack()
    await page.waitForTimeout(1200)
    await page.locator('.order').nth(1).click()
    await page.waitForTimeout(1200)
    const noB = await readOrderNo()

    expect(noB, '换单后页面必须刷新成第二单，不能还留着第一单的内容').not.toBe(noA)

    // URL 里的 id 必须与页面展示的订单号对得上
    const urlId = new URL(page.url()).pathname.split('/').pop()
    expect(urlId, '应停在第二单的详情路由上').toBeTruthy()
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const detail = await (await ctx.get(`${API}/order/${urlId}`)).json()
      expect(detail.code).toBe(200)
      expect(noB, '页面订单号必须与 URL 指向的订单一致').toContain(detail.data.orderNo)
    } finally {
      await dispose()
    }
  })

})

/* ================================================================== */
/* P1-2 · 评价必须落在当前订单上                                         */
/* ================================================================== */
test.describe('换单后的评价目标（P1-2）', () => {
  // fam019 名下有两单（1049 REVIEWED / 1019 COMPLETED），可复现「换单后点评价」
  test.use({ storageState: authFile('fam019'), viewport: { width: 390, height: 844 } })

  test('换单后点「去评价」：进入当前订单的评价页，且提交也落到当前订单', async ({ page }) => {
    // fam019 名下有两单：1049(REVIEWED) 与 1019(COMPLETED，可评价)。
    // 活路径：列表 → 点 1049 详情 → 返回 → 点 1019 详情 → 点「去评价」→ 提交。
    // 修复前：详情页的 orderId 是 setup 期快照，仍停在 1049，
    //         「去评价」会跳到 /family/order/1049/review（上一单），
    //         评价也会提交给 1049。
    //
    // ⚠️ 这条用例**必须**走客户端路由（列表 → 点 → 返回 → 点）才能区分修复前后：
    //    整页 goto 会重建组件，快照本来就是对的，修复前也会通过。
    const seen = []
    await page.route('**/api/review', async (r) => {
      if (r.request().method() === 'POST') {
        seen.push(r.request().postDataJSON?.() || {})
      }
      await r.continue()
    })

    await page.goto(ROUTES.familyOrder)
    await page.waitForTimeout(1500)

    // 先点第一单（1049 REVIEWED）
    await page.locator('.order').first().click()
    await page.waitForTimeout(1200)
    expect(new URL(page.url()).pathname).toBe('/family/order/1049')

    // 客户端返回列表 → 点第二单（1019 COMPLETED）
    await page.goBack()
    await page.waitForTimeout(1200)
    await page.locator('.order').nth(1).click()
    await page.waitForTimeout(1500)
    expect(new URL(page.url()).pathname, '应停在第二单详情').toBe('/family/order/1019')

    // 详情页必须展示第二单的信息（不是第一单的残留）
    const detailText = await page.locator('body').innerText()
    expect(detailText).toContain('NL20260905000019')
    expect(detailText, '不得残留上一单的订单号').not.toContain('NL20260907000049')

    // 点「去评价」→ 必须进第二单的评价页
    await page.getByRole('button', { name: '去评价' }).click()
    await page.waitForTimeout(1500)
    expect(new URL(page.url()).pathname, '评价页必须是当前订单的').toBe('/family/order/1019/review')

    // 订单 1019 是种子里的 COMPLETED 单（尚未评价）—— 有评价才会变成 REVIEWED。
    // 这里断言「表单可提交」而不是跳过：跳过等于这条用例什么都没验证。
    const form = page.locator('.el-form').first()
    await expect(form, 'COMPLETED 订单应当可评价（若已有评价说明种子数据变了）').toBeVisible()

    await page.locator('.stars li').nth(4).click()
    await page.locator('textarea').first().fill('e2e 回归护栏：评价必须提交给当前订单')
    await page.getByRole('button', { name: /提交评价/ }).click()
    await page.waitForTimeout(2000)

    expect(seen.length, '应发出一次评价提交').toBe(1)
    // ⚠️ 后端 DTO 的 orderId 是字符串，种子常量是数字 —— 必须归一再比，
    //    否则这条断言会因为「1019 vs "1019"」而 flaky，掩盖真正的回归。
    expect(String(seen[0].orderId), '评价必须提交给当前 URL 指向的订单').toBe(
      String(SEED.orderCompleted)
    )
  })
})

/* ================================================================== */
/* P1-3 · 下单换选就诊人必须生效                                        */
/* ================================================================== */
test.describe('下单换选就诊人（P1-3）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('换一位老人后下单，订单必须下给新选的那位', async ({ page }) => {
    const { ctx, dispose } = await apiAsAccount('fam001')
    let newElderId = null
    try {
      newElderId = await createElderForFamily(
        ctx,
        '李测试',
        '13900008888',
        '110101195001010013'
      )
    } finally {
      await dispose()
    }
    expect(newElderId).toBeTruthy()

    // 姓名在列表里是脱敏的：李测试 → 李*试；张德海 → 张*海
    const NEW_MASKED = '李*试'
    const OLD_MASKED = '张*海'

    await page.goto(ROUTES.orderStep1)
    await page.waitForTimeout(1500)
    await expect(page.locator('.elderpick__item')).toHaveCount(2)

    // 先选老人 A（张*海）→ 下一步
    await page.locator('.elderpick__item', { hasText: OLD_MASKED }).click()
    await page.getByRole('button', { name: '下一步' }).click()
    await page.waitForTimeout(1200)
    expect(new URL(page.url()).searchParams.get('elderId')).toBe('401')

    // 客户端返回 step1 → 改选老人 B（李*试）→ 下一步
    await page.goBack()
    await page.waitForTimeout(1200)
    await page.locator('.elderpick__item', { hasText: NEW_MASKED }).click()
    await page.getByRole('button', { name: '下一步' }).click()
    await page.waitForTimeout(1200)

    // 核心断言：step2 的 URL 与「就诊人条」都跟着换了
    const elderIdInStep2 = new URL(page.url()).searchParams.get('elderId')
    expect(elderIdInStep2, 'step2 必须跟着新的就诊人').toBe(String(newElderId))
    await expect(page.locator('.elder-bar'), 'step2 必须显示当前就诊人').toBeVisible()
    await expect(page.locator('.elder-bar')).toContainText(NEW_MASKED)

    // 填完医院 / 科室 / 日期 / 时段 → step3
    await page.locator('.hospital-item').first().click()
    await page.locator('.chips__item').first().click()
    await page.locator('.date-item').nth(1).click()
    await page.locator('.slot-item').first().click()
    await page.locator('.cta__btn').click()
    await page.waitForTimeout(1200)

    // step3 也必须显示新老人
    await expect(page.locator('.confirm-list')).toContainText(NEW_MASKED)

    // 提交：拦截下单请求，断言 elderId
    const submitted = []
    await page.route('**/api/order', async (r) => {
      if (r.request().method() === 'POST') {
        submitted.push(r.request().postDataJSON?.() || {})
      }
      await r.continue()
    })
    await page.getByRole('button', { name: /提交订单/ }).click()
    // 提交前的二次确认
    await page.locator('.el-message-box__btns button').last().click()
    await page.waitForTimeout(2500)

    expect(submitted.length, '应发出一次下单请求').toBe(1)
    expect(submitted[0].elderId, '订单必须下给新选的那位老人').toBe(newElderId)
  })
})

/* ================================================================== */
/* P1-4 · 老人端：接口失败 ≠ 没有数据                                   */
/* ================================================================== */
test.describe('老人端用药页的三态（P1-4）', () => {
  test.use({ storageState: authFile('elder001'), viewport: { width: 390, height: 844 } })

  test('资料接口失败 → 显示可重试的错误态，而不是「暂无用药数据」', async ({ page }) => {
    await page.route('**/api/user/profile', async (r) => {
      await r.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, message: '模拟接口失败', data: null })
      })
    })

    await page.goto(ROUTES.elderMedication)
    await expect(page.getByText('用药信息加载失败')).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible()
    // 关键：不得显示「暂无用药数据」—— 那是对老人的错误结论
    await expect(page.getByText('暂无用药数据')).toHaveCount(0)
  })
})

/* ================================================================== */
/* P1-5 · 写操作按钮的 in-flight 锁                                      */
/* ================================================================== */
test.describe('写操作重复提交防护（P1-5）', () => {
  test.use({ storageState: authFile('comp007'), viewport: { width: 390, height: 844 } })

  test('抢单连点只发一次接单请求', async ({ page }) => {
    // 先造一条待接单订单，保证大厅里有可抢的单（写路径会被夹具回滚）
    const { ctx, dispose } = await apiAsAccount('fam001')
    try {
      const created = await (
        await ctx.post(`${API}/order`, {
          data: {
            elderId: 401,
            hospital: '海南省人民医院',
            department: '心血管内科',
            visitTime: '2027-01-01 09:30:00',
            address: '海南省海口市秀英区秀华路19号 门诊大楼3楼'
          }
        })
      ).json()
      expect(created.code, '造待接单订单应成功').toBe(200)
    } finally {
      await dispose()
    }

    let acceptCalls = 0
    await page.route('**/api/order/*/accept', async (r) => {
      acceptCalls += 1
      await r.continue()
    })

    await page.goto(ROUTES.companionHall)
    await page.waitForTimeout(2000)

    const grabBtn = page.locator('button.order__grab').first()
    await expect(grabBtn, '大厅里应有可抢的单').toBeVisible({ timeout: 15000 })

    // ⚠️ 两个 click 必须发生在**同一个 tick**里：
    //    顺序 await 会等第一次请求结束才点第二次，测不出「连点」。
    await grabBtn.evaluate((el) => {
      el.click()
      el.click()
    })
    await page.waitForTimeout(2500)

    expect(acceptCalls, '连点只能发一次接单请求').toBe(1)
  })
})

/* ================================================================== */
/* P1-6 · 越权不再静默跳主页                                            */
/* ================================================================== */
test.describe('越权访问（P1-6）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 1440, height: 900 } })

  test('家属访问后台 → 落到 403 页，且有「回首页」按钮', async ({ page }) => {
    await page.goto(ROUTES.adminDashboard, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1500)

    expect(new URL(page.url()).pathname, '越权应落到 403 页而不是静默弹回主页').toBe(
      ROUTES.error403
    )
    const backHome = page.getByRole('button', { name: /回首页|返回首页/ }).first()
    await expect(backHome, '403 页必须给出路').toBeVisible()

    await backHome.click()
    await page.waitForTimeout(1500)
    expect(new URL(page.url()).pathname, '「回首页」应回到本人角色主页').toBe(ROLE_HOME.FAMILY)
  })
})

/* ================================================================== */
/* P1-7 · 陪诊员的站内信与评价必须有入口                                  */
/* ================================================================== */
test.describe('陪诊员入口（P1-7）', () => {
  test.use({ storageState: authFile('comp007'), viewport: { width: 390, height: 844 } })

  test('工作台可达「消息中心」与「收到的评价」', async ({ page }) => {
    await page.goto(ROUTES.companionWorkbench)
    await page.waitForTimeout(1500)

    const msg = page.getByText('消息中心', { exact: false }).first()
    const rev = page.getByText('收到的评价', { exact: false }).first()
    await expect(msg, '工作台必须有「消息中心」入口').toBeVisible()
    await expect(rev, '工作台必须有「收到的评价」入口').toBeVisible()

    await msg.click()
    await page.waitForTimeout(1500)
    expect(new URL(page.url()).pathname).toBe(ROUTES.companionMessage)

    await page.goto(ROUTES.companionWorkbench)
    await page.waitForTimeout(1500)
    await page.getByText('收到的评价', { exact: false }).first().click()
    await page.waitForTimeout(1500)
    expect(new URL(page.url()).pathname).toBe(ROUTES.companionReviews)
  })
})

/* ================================================================== */
/* P0-1 · 管理端用户列表的姓名必须脱敏                                  */
/* ================================================================== */
test.describe('管理端用户列表脱敏（P0-1）', () => {
  test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

  test('用户列表的 realName 必须脱敏（AGENTS.md §0.1：/api/admin/** 一律脱敏）', async ({ page }) => {
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      const body = await (await ctx.get(`${API}/admin/user?page=1&size=20`)).json()
      expect(body.code).toBe(200)
      const records = body.data?.records || []
      expect(records.length, '应至少有一条用户').toBeGreaterThan(0)
      for (const r of records) {
        if (r.realName) {
          expect(r.realName, `管理端列表姓名必须脱敏（实得 ${r.realName}）`).toContain('*')
        }
        if (r.phone) {
          expect(r.phone, '管理端列表手机号必须脱敏').toContain('*')
        }
      }
    } finally {
      await dispose()
    }

    // 页面上的列名也不该再叫「真实姓名」
    await page.goto(ROUTES.adminUser)
    await page.waitForTimeout(2000)
    const bodyText = await page.locator('body').innerText()
    expect(bodyText, '列名应为「姓名」（脱敏），不应是「真实姓名」').not.toContain('真实姓名')
  })
})

/* ================================================================== */
/* P0-2 · 重置密码不得谎称「已通过短信发送」                            */
/* ================================================================== */
test.describe('重置密码文案（P0-2）', () => {
  test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

  test('重置密码：响应不含任何口令字段；UI 用管理员刚输入的值供其转交', async ({ page }) => {
    // ⚠️ 靶子必须是**夹具白名单内、且没有 e2e 用例在用**的账号。
    //    白名单：tools/e2e/fixture.py 的 sys_user 白名单 = 1,2,101,201-230,301；
    //    其中 1(admin)/101(fam001)/201(elder001)/301(comp001) 被 e2e 当登录态用，
    //    重置它们会 bump pwd:version → 作废别处已保存的 storageState。
    //    elder002(id=202) 两头都满足：能被夹具回滚，又不影响任何其它 spec。
    const TARGET_USER_ID = 202
    const TARGET_USERNAME = 'elder002'

    await page.goto(ROUTES.adminUser)
    await page.waitForTimeout(2000)
    // 用关键词把列表筛到只剩靶子那一行，避免点到「列表第一条」（那是随机账号）
    await page.getByPlaceholder('搜索账号 / 昵称 / 手机号').fill(TARGET_USERNAME)
    await page.getByRole('button', { name: '查询' }).click()
    await page.waitForTimeout(1500)

    await page.getByRole('button', { name: '重置密码' }).first().click()
    await page.waitForTimeout(1000)

    const dialog = page.locator('.el-dialog').first()
    await expect(dialog).toBeVisible()
    const dialogText = await dialog.innerText()
    // ⚠️ 断言的是「**谎称已送达**」，不是「出现短信二字」——
    //    澄清句「系统没有短信通道，不会把口令自动发给用户」里出现「短信」是对的。
    expect(dialogText, '不得声称口令已通过短信送达').not.toContain('短信发送')
    expect(dialogText, '站内信正文不含口令，不得承诺「默认密码」').not.toContain('默认密码')

    // 管理员亲手设置新口令（这是解开「用户拿不到口令 → 永久锁死」的关键动作）
    await dialog.locator('input[type="password"]').first().fill('E2ePass123')
    await dialog.locator('textarea').first().fill('e2e 回归护栏')
    await dialog.getByRole('button', { name: '确认重置' }).click()
    await page.waitForTimeout(2500)

    // UI 必须把管理员**刚输入的**口令显示出来，供其转交用户
    await expect(page.locator('.reset-result__pwd code'), 'UI 必须给出可转交的口令').toHaveText(
      'E2ePass123'
    )

    // 但接口响应体**不含任何口令字段** —— F-04 / P0-3 的安全验收判据
    const { ctx, dispose } = await apiAsAccount('admin')
    try {
      const res = await (
        await ctx.post(`${API}/admin/user/${TARGET_USER_ID}/reset-password`, {
          data: { remark: 'e2e 回归护栏', newPassword: 'E2ePass123' }
        })
      ).json()
      expect(res.code, '重置密码应成功').toBe(200)
      expect(res.data?.temporaryPassword, '响应体不得含任何口令字段').toBeUndefined()
      expect(JSON.stringify(res.data)).not.toContain('E2ePass123')
      expect(JSON.stringify(res)).not.toContain('短信发送')
    } finally {
      await dispose()
    }
  })
})

/* ================================================================== */
/* P1-9 · 家属订单列表必须能翻页（第 51 条以后原本不可达）             */
/* ================================================================== */
test.describe('家属订单列表分页（P1-9）', () => {
  test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

  test('列表有分页控件，且点第 2 页会按 page=2 重新要数、渲染第 2 页的数据', async ({ page }) => {
    // ⚠️ 不用「真有多少单」做断言：分页控件的 v-if 是 total > size，
    //    fam001 名下订单不足一页时控件根本不渲染 —— 那是**数据**问题，不是代码问题。
    //    这里用路由拦截把列表接口换成分页数据（**只读**，不碰库、不写库）。
    const asked = []
    await page.route(/\/api\/order(\?|$)/, async (route) => {
      const url = new URL(route.request().url())
      const p = Number(url.searchParams.get('page') || '1')
      asked.push(p)
      const records = Array.from({ length: 3 }, (_, i) => ({
        id: 9000 + p * 10 + i,
        orderNo: `NL-E2E-P${p}-${i + 1}`,
        status: 'PENDING',
        statusLabel: '待接单',
        hospital: '海南省人民医院',
        department: '心血管内科',
        elderName: '张*海',
        fee: 120,
        visitTime: '2026-10-20 09:30:00'
      }))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'ok',
          data: { total: 25, page: p, size: 10, pages: 3, records }
        })
      })
    })

    await page.goto(ROUTES.familyOrder)
    await page.waitForTimeout(1500)

    const pager = page.locator('.el-pagination')
    await expect(pager, '列表页必须有分页控件，否则第 51 条以后的订单永远看不到').toBeVisible()
    expect(asked, '首屏应按第 1 页请求').toContain(1)
    await expect(page.locator('.order').first(), '第 1 页应渲染第 1 页的订单').toContainText(
      'NL-E2E-P1-1'
    )

    // 点页码「2」（Element Plus 的页码项是 ul.el-pager > li.number）
    await page.locator('.el-pager li.number').filter({ hasText: /^\s*2\s*$/ }).click()
    await page.waitForTimeout(1200)

    expect(asked, '点第 2 页必须重新按 page=2 向后端要数').toContain(2)
    await expect(page.locator('.order').first(), '第 2 页必须渲染第 2 页的数据').toContainText(
      'NL-E2E-P2-1'
    )
  })
})

/* ================================================================== */
/* P1-10 · 大厅空态文案不得把用户引去不存在的「下拉刷新」              */
/* ================================================================== */
test.describe('陪诊员大厅空态文案（P1-10）', () => {
  test.use({ storageState: authFile('comp007'), viewport: { width: 390, height: 844 } })

  test('有单时与空态时都不得出现「下拉刷新」（全站没有实现下拉刷新）', async ({ page }) => {
    await page.goto(ROUTES.companionHall)
    // ⚠️ 必须先等大厅真的渲染出来再断言「不含下拉刷新」——
    //    否则页面还没加载（body 是空的），not.toContain 就是一句空话。
    await expect(page.getByText('单待接').first(), '大厅应加载出来').toBeVisible({ timeout: 15000 })

    let text = await page.locator('body').innerText()
    expect(text, '大厅有单时也不得提「下拉刷新」').not.toContain('下拉刷新')

    // 再逼出空态：把大厅列表接口换成空（**只读拦截**，不碰库）
    await page.route(/\/api\/order\/hall/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'ok',
          data: { total: 0, page: 1, size: 50, pages: 0, records: [] }
        })
      })
    })
    await page.reload()
    await page.waitForTimeout(2000)

    text = await page.locator('body').innerText()
    expect(text, '空态应真的渲染出来（否则这条断言是空转）').toContain('附近用户暂时没有陪诊需求')
    expect(text, '空态不得把用户引去不存在的「下拉刷新」').not.toContain('下拉刷新')
  })
})

/* ================================================================== */
/* P1-11 · 实时推送断开必须显式提示（原来完全静默）                   */
/* ================================================================== */
test.describe('实时推送断线提示（P1-11）', () => {
  test.use({ storageState: authFile('comp007'), viewport: { width: 390, height: 844 } })

  test('推送连不上时必须给出「实时推送未连接」，而不是静默显示过期进度', async ({ page }) => {
    // 制造断线：拦掉 /ws/progress 的握手，握手即关 —— 页面拿不到可用的推送连接。
    // （realtime.js 有 1s 起步的退避重连，重连仍会被同样拦掉。）
    await page.routeWebSocket(/\/ws\/progress/, (ws) => {
      ws.close()
    })

    await page.goto(ROUTES.companionExecute(SEED.orderAccepted))

    await expect(
      page.getByText('实时推送未连接').first(),
      '推送断开时必须给出提示，否则用户不知道自己看的是过期进度'
    ).toBeVisible({ timeout: 15000 })
  })
})

/* ================================================================== */
/* P1-12 · 二次确认点「取消」不得发请求                              */
/* ================================================================== */
test.describe('二次确认取消不发请求（P1-12）', () => {
  test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

  test('封禁确认框点「取消」→ 一个封禁请求都不能发出去', async ({ page }) => {
    let disableCalls = 0
    await page.route('**/api/admin/user/*/disable', async (route) => {
      disableCalls += 1
      await route.continue()
    })

    await page.goto(ROUTES.adminUser)
    await page.waitForTimeout(2000)

    // 用 helpers/storage.js 读登录态（令牌可能在 sessionStorage，不能只读 localStorage）
    // ⚠️ 必须在 goto 之后读：goto 之前页面是 about:blank（不透明源），
    //    访问 localStorage 会抛 SecurityError。
    const token = await page.evaluate(READ_TOKEN)
    expect(token, '用例必须带着登录态跑，否则断言的是登录页').toBeTruthy()

    // ⚠️ 靶子必须在夹具白名单内（tools/e2e/fixture.py 的 sys_user 白名单含 201-230），
    //    这样即便这条用例红了（回归导致请求真的发出去），
    //    globalTeardown 的 restore 也会把它的 status 改回去，不污染开发库。
    //    elder002(id=202)：白名单内、不是任何 spec 的登录态账号、状态为 NORMAL。
    await page.getByPlaceholder('搜索账号 / 昵称 / 手机号').fill('elder002')
    await page.getByRole('button', { name: '查询' }).click()
    await page.waitForTimeout(1500)

    await page.getByRole('button', { name: '封禁', exact: true }).first().click()
    const box = page.locator('.el-message-box').first()
    await expect(box, '点「封禁」必须弹二次确认框').toBeVisible()

    await box.getByRole('button', { name: '取消', exact: true }).click()
    await page.waitForTimeout(1500)

    expect(disableCalls, '点「取消」不得发出任何封禁请求').toBe(0)
  })
})

/* ================================================================== */
/* P1-13 · 履约详情失败态必须真的有「重试」按钮                      */
/* ================================================================== */
test.describe('履约详情失败态的重试（P1-13）', () => {
  test.use({ storageState: authFile('admin'), viewport: { width: 1440, height: 900 } })

  test('详情接口失败 → 空态有「重试」按钮，且点了会重新拉取', async ({ page }) => {
    let detailCalls = 0

    // 列表给一条固定数据（**只读拦截**，不碰库）；详情一律失败
    await page.route(/\/api\/admin\/fulfillment\/risk-list/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 200,
          message: 'ok',
          data: {
            total: 1,
            page: 1,
            size: 10,
            pages: 1,
            records: [
              {
                orderId: 1001,
                orderNo: 'NL-E2E-FULFIL-1',
                elderName: '张*海',
                hospital: '海南省人民医院',
                department: '心血管内科',
                visitTime: '2026-10-20 09:30:00',
                fulfillmentRisk: 'URGENT',
                fulfillmentRiskLabel: '履约风险较高',
                waitMinutes: 120,
                urgeCount: 1,
                adminCoordinated: false,
                createTime: '2026-10-20 08:30:00'
              }
            ]
          }
        })
      })
    })
    await page.route(/\/api\/order\/\d+/, async (route) => {
      detailCalls += 1
      await route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, message: '模拟接口失败', data: null })
      })
    })

    await page.goto(ROUTES.adminFulfillment)
    await page.waitForTimeout(2000)

    await page.getByRole('button', { name: '查看详情' }).first().click()
    const dialog = page.locator('.el-dialog').filter({ hasText: '订单详情' }).first()
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('订单详情加载失败'), '详情失败必须有明确文案').toBeVisible()

    const retry = dialog.getByRole('button', { name: '重试', exact: true })
    await expect(retry, '失败态必须真的有「重试」按钮（文案承诺了就要有）').toBeVisible()

    const before = detailCalls
    await retry.click()
    await page.waitForTimeout(1500)
    expect(detailCalls, '点「重试」必须重新拉一次详情').toBeGreaterThan(before)
  })
})
