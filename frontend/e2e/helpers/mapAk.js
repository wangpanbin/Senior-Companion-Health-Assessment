/**
 * 判断本次 Playwright 运行时是否配置了百度地图 AK（ADR-0022）。
 *
 * 为什么要这个 helper：
 *   `VITE_BAIDU_MAP_AK` 是 **Vite 构建期**变量，只有 dev server / 产物里有，
 *   Playwright 的 node 进程里读不到 `process.env`。所以这里直接从 `.env*` 文件读 ——
 *   与 `frontend/src/utils/baiduMap.js` 读的是同一份真源。
 *
 * 用途：真实地图渲染断言在**没有 AK** 的环境必须 `test.skip` 而不是失败。
 * 否则任何没配 AK 的机器（CI、新同学、评委）跑全量 e2e 都会红，
 * 而那与代码对不对毫无关系。
 *
 * 读取顺序与 Vite 一致：`.local` 覆盖基础文件。
 */
import fs from 'node:fs'
import path from 'node:path'
import { FRONTEND_DIR } from './paths'

/** 从一组 .env 文件里解析出 AK（后读的优先） */
export function resolveMapAk() {
  const files = ['.env', '.env.local', '.env.development', '.env.development.local']
  let ak = ''
  for (const name of files) {
    const full = path.join(FRONTEND_DIR, name)
    if (!fs.existsSync(full)) continue
    for (const line of fs.readFileSync(full, 'utf8').split(/\r?\n/)) {
      const m = /^\s*VITE_BAIDU_MAP_AK\s*=\s*(.*)$/.exec(line)
      if (m) {
        ak = m[1].replace(/^["']|["']$/g, '').trim()
      }
    }
  }
  return ak
}

/** 是否配置了 AK（空值 / 只有占位注释都算「没配」） */
export function hasMapAk() {
  return resolveMapAk().length > 0
}
