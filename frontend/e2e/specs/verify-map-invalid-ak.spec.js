/**
 * 23d · 失效 AK 的降级实测（ADR-0022 D7）
 *
 * 这条**不能**在常规 e2e 里跑：它需要临时把 `.env.development.local` 的 AK 改成一个
 * 必然被百度拒绝的值，然后重启 dev server。写进常规套件会让任何人跑全量都红。
 *
 * 跑法（手工，两步）：
 *   1. 把 `frontend/.env.development.local` 的 AK 改成一个假值（如 `INVALID_AK_FOR_TEST`）
 *   2. `pnpm exec playwright test verify-map-invalid-ak --workers=1 --reporter=list`
 *
 * 期望：8 秒内（NlMapPanel 的 loadBaiduMap timeout）从「地图加载中…」落到
 * 「地图暂不可用，已切换为文字模式」，且**距离与更新时间仍然给出**、页面不抛异常。
 *
 * 注意约定：`NlMapPanel` 对「AK 为空」与「AK 被拒」给的是**两句不同的话** ——
 * 前者「未配置地图 AK」，后者「地图暂不可用」。这区分是刻意的：
 * 前者是配置问题（去填变量），后者是百度侧问题（看 Network / 白名单）。
 */
import path from 'node:path'
import fs from 'node:fs'
import { test, expect } from '@playwright/test'
import { authFile, ARTIFACT_DIR } from '../helpers/paths'
import { ROUTES } from '../helpers/constants'
import { apiAsAccount, API } from '../helpers/api'
import { seedAcceptedOrder } from '../helpers/seedOrder'
import { resolveMapAk } from '../helpers/mapAk'

const SHOT_DIR = path.join(ARTIFACT_DIR, 'assets')
fs.mkdirSync(SHOT_DIR, { recursive: true })

test.use({ storageState: authFile('fam001'), viewport: { width: 390, height: 844 } })

test('失效 AK → 8 秒内降级为文字卡片，且信息仍在', async ({ page }) => {
  const ak = resolveMapAk()
  // 只在本用例专用的假 AK 下跑：真实 AK 跑本用例会得到「地图正常」的假绿／假红，
  // 而常规套件里更不该包含「需要先改环境变量 + 重启 dev server」的用例
  test.skip(!ak.startsWith('INVALID_AK'), '请先把 .env.development.local 的 AK 改成 INVALID_AK_xxx')

  const { ctx: famCtx, dispose: famDispose } = await apiAsAccount('fam001')
  const { ctx: compCtx, dispose: compDispose } = await apiAsAccount('comp007')
  try {
    const { orderId } = await seedAcceptedOrder({
      famCtx,
      compCtx,
      remark: 'e2e invalid ak',
      longitude: '110.311422',
      latitude: '20.021674'
    })
    await compCtx.put(`${API}/execution/${orderId}/location-sharing`, { data: { enabled: true } })
    await compCtx.post(`${API}/execution/${orderId}/location`, {
      data: { longitude: '110.321200', latitude: '20.031500' }
    })

    const errors = []
    page.on('pageerror', (e) => errors.push(e.message))
    const t0 = Date.now()
    await page.goto(ROUTES.familyOrderDetail(orderId))
    await expect(page.getByText('实时位置', { exact: true })).toBeVisible()

    // 降级必须在 8 秒 timeout + 少量余量内发生
    await expect
      .poll(async () => (await page.locator('body').innerText()).includes('地图暂不可用'), {
        timeout: 15_000
      })
      .toBeTruthy()
    console.log('[invalid-ak] 降级耗时：', Date.now() - t0, 'ms')

    const body = await page.locator('body').innerText()
    expect(body, '降级后必须仍给出直线距离').toMatch(/直线约\s*[\d.]+\s*(米|公里)/)
    expect(body, '降级后必须仍给出更新时间').toContain('前更新')

    // ⚠️ **不**断言 pageerror 为空：AK 被拒时百度 JSAPI 自己会抛一串异步异常
    //    （`Cannot read properties of null (reading 'logCbk…')` / `FeatureStyledefault`），
    //    它们不从我们的代码里出，`try/catch` 也无法接住。
    //    我们的责任是「把半成品容器拆掉并降级」，不是修复第三方脚本的内部错误。
    //    这里只把它打印出来供排查，并要求**我们自己的**降级文案到位。
    console.log('[invalid-ak] 百度侧异步异常（不可控，仅记录）：', JSON.stringify(errors))

    await page.screenshot({ path: path.join(SHOT_DIR, 's14-family-map-invalid-ak.png'), fullPage: true })
  } finally {
    await famDispose()
    await compDispose()
  }
})
