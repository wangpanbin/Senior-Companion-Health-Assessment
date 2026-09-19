/**
 * 页面巡检工具：统一收集 console 错误 / pageerror / ≥400 的接口响应，
 * 并把「落地路径必须等于目标路由」做成硬断言。
 *
 * ⚠️ 这是本项目最重要的一条判据（FRONTEND_CONTRACT §12.1 的假绿事故）：
 * 路由守卫会把不匹配的角色重定向回自己的主页，若不断言落地路径，
 * 「整批账号都渲染了老人端首页」也会判绿。
 */
import { expect } from '@playwright/test'

/** 挂上收集器，返回 { errors, apiFails, stop() } */
export function collectPageIssues(page) {
  const errors = []
  const apiFails = []
  const wsNotes = []

  const onConsole = (m) => {
    if (m.type() === 'error') errors.push(String(m.text()).slice(0, 300))
  }
  const onPageError = (e) => errors.push('PAGEERROR: ' + String(e && e.message).slice(0, 300))
  const onResponse = (r) => {
    const u = r.url()
    if (!u.includes('/api/')) return
    if (r.status() >= 400) apiFails.push(`${r.status()} ${r.request().method()} ${u}`)
  }
  const onRequestFailed = (req) => {
    const u = req.url()
    const why = String((req.failure() && req.failure().errorText) || '').slice(0, 120)
    if (u.includes('/api/')) apiFails.push(`NETFAIL ${req.method()} ${u} :: ${why}`)
    // 切页时 WebSocket 必然被销毁，这个 teardown 事件不算错，单独记
    else if (u.includes('/ws/')) wsNotes.push(`WSFAIL ${u} :: ${why}`)
  }

  page.on('console', onConsole)
  page.on('pageerror', onPageError)
  page.on('response', onResponse)
  page.on('requestfailed', onRequestFailed)

  return {
    errors,
    apiFails,
    wsNotes,
    stop() {
      page.off('console', onConsole)
      page.off('pageerror', onPageError)
      page.off('response', onResponse)
      page.off('requestfailed', onRequestFailed)
    }
  }
}

/**
 * 巡检一个路由：导航 → 断言落地路径 → 断言有内容 → 断言无 JS 错 / 无 ≥400。
 * @returns 页面纯文本（供进一步断言）
 */
export async function sweep(page, path, options = {}) {
  const { expectText, minText = 20, timeout = 8000 } = options
  const issues = collectPageIssues(page)
  try {
    await page.goto(path, { waitUntil: 'domcontentloaded', timeout: 20000 })
    await page.waitForTimeout(900)

    // ① 落地路径 = 目标路由（防「被重定向回角色主页」的假绿）
    const landed = await page.evaluate(() => location.pathname)
    expect(landed, `${path} 落地路径应为目标路由（实际 ${landed}）`).toBe(path)

    // ② 页面必须有实质内容
    const text = ((await page.locator('body').innerText()) || '').replace(/\s+/g, ' ').trim()
    expect(text.length, `${path} 页面文本不应为空/过短`).toBeGreaterThan(minText)

    // ③ 可选：关键文案
    if (expectText) expect(text, `${path} 应包含「${expectText}」`).toContain(expectText)

    // ④ 无 JS 错误、无接口 ≥400（失败时把证据打出来，否则只看到「有错」没法定位）
    expect(
      issues.errors,
      `${path} 不应有 console/page 错误：${JSON.stringify(issues.errors.slice(0, 4))}`
    ).toEqual([])
    expect(
      issues.apiFails,
      `${path} 不应有 ≥400 的接口响应：${JSON.stringify(issues.apiFails.slice(0, 6))}`
    ).toEqual([])

    return text
  } finally {
    issues.stop()
  }
}
