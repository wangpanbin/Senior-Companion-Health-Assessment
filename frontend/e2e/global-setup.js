/**
 * 全局前置：
 *   1) 探活后端 8080 与前端 5141（前端由 playwright.config 的 webServer 拉起）
 *   2) 调 tools/e2e/fixture.py snapshot —— 记录 17 张被写表的 id 水位 + 白名单行影子表
 *
 * ⚠️ 前置：`MYSQL_PASSWORD`（夹具不内置默认口令）+ `tools/e2e/fixture.py`（已随仓库入库）。
 * 用 E2E_FIXTURE=0 可跳过夹具（只读巡检时，不做数据回滚）。
 */
import { spawnSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { REPO_ROOT } from './helpers/paths'

const APP = process.env.NIANGLIN_APP || 'http://127.0.0.1:5141'
const API = process.env.NIANGLIN_API || 'http://127.0.0.1:8080/api'
const FIXTURE = path.join(REPO_ROOT, 'tools', 'e2e', 'fixture.py')

async function waitFor(url, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  let lastErr = ''
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url)
      if (res.status < 500) return
      lastErr = `status=${res.status}`
    } catch (e) {
      lastErr = e.message
    }
    await new Promise((r) => setTimeout(r, 1000))
  }
  throw new Error(`[e2e] ${label} 在 ${timeoutMs}ms 内不可达：${url} (${lastErr})`)
}

function runFixture(sub) {
  if (!process.env.MYSQL_PASSWORD) {
    throw new Error('[e2e] 未设置 MYSQL_PASSWORD，无法执行夹具。只读巡检请用 E2E_FIXTURE=0')
  }
  if (!existsSync(FIXTURE)) {
    throw new Error(
      `[e2e] 找不到夹具 ${FIXTURE}\n` +
        '      tools/e2e/ 已随仓库入库，缺失说明工作区不完整（被 git clean 清掉 / 稀疏检出等）。\n' +
        '      → 用 git checkout -- tools/e2e/ 补齐后重试；\n' +
        '      → 只跑只读巡检（不做 snapshot / restore / verify）：设 E2E_FIXTURE=0。\n' +
        '      详见 docs/agents/FRONTEND_CONTRACT.md §13.1。'
    )
  }
  const r = spawnSync('python', [FIXTURE, sub], {
    cwd: REPO_ROOT,
    stdio: 'inherit',
    env: process.env,
    windowsHide: true
  })
  if (r.error) throw new Error(`[e2e] 调用 fixture.py ${sub} 失败：${r.error.message}`)
  if (r.status !== 0) throw new Error(`[e2e] fixture.py ${sub} 退出码 ${r.status}`)
}

export default async function globalSetup() {
  await waitFor(`${API}/health`, 90000, '后端')
  await waitFor(APP, 30000, '前端')

  if (process.env.E2E_FIXTURE === '0') {
    console.log('[e2e] E2E_FIXTURE=0 —— 跳过 snapshot（不做数据回滚）')
    return
  }
  console.log('[e2e] snapshot：记录数据基线…')
  runFixture('snapshot')
}
