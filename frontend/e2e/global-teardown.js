/**
 * 全局后置：还原数据基线并校验。
 *
 *   restore  → 删本跑新增行、白名单行原值回写、自增回落、Redis / 上传文件清理
 *   verify   → 逐表比对行数/水位 + 白名单行逐列一致 + 成对性断言
 *
 * verify 不一致会让 teardown 抛错 —— 这是「回滚干净了没有」的唯一证据，
 * 必须显式失败，不能静静过去（否则重演 BUG_LIST L1）。
 */
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { REPO_ROOT } from './helpers/paths'

const FIXTURE = path.join(REPO_ROOT, 'tools', 'e2e', 'fixture.py')

function runFixture(sub) {
  if (!existsSync(FIXTURE)) {
    throw new Error(
      `[e2e] 找不到夹具 ${FIXTURE}（tools/e2e/ 被 .gitignore 忽略，不随仓库分发）。\n` +
        '      数据可能**未回滚** —— 请人工核对 reports/playwright 与本轮写过的表，\n' +
        '      并按 docs/agents/FRONTEND_CONTRACT.md §13.1 补齐夹具后重跑。'
    )
  }
  const r = spawnSync('python', [FIXTURE, sub], {
    cwd: REPO_ROOT,
    stdio: 'inherit',
    env: process.env,
    windowsHide: true
  })
  if (r.error) throw new Error(`[e2e] 调用 fixture.py ${sub} 失败：${r.error.message}`)
  return r.status
}

export default async function globalTeardown() {
  if (process.env.E2E_FIXTURE === '0') {
    console.log('[e2e] E2E_FIXTURE=0 —— 跳过 restore/verify')
    return
  }
  console.log('\n[e2e] restore：还原数据基线…')
  const rcRestore = runFixture('restore')
  if (rcRestore !== 0) throw new Error(`[e2e] fixture.py restore 退出码 ${rcRestore}`)

  console.log('[e2e] verify：校验回滚是否干净…')
  const rcVerify = runFixture('verify')
  if (rcVerify !== 0) {
    throw new Error('[e2e] 数据未回到基线（详见上方差异表）—— 回滚不干净，视为失败')
  }
}
