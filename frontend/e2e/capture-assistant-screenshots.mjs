/**
 * 抓助手页面的真实渲染图（桌面 + 移动两种形态）。
 *
 * 为什么要单独抓：内置浏览器（Electron FilePanel）的**视觉通道在回放缓存帧**，
 * 点击后截图仍是点击前的样子（实测：点预设问题 → 后端日志确认请求真的发出去了、
 * DeepSeek 也真的被调用了，但截图里的页面没变），所以拿它做「界面长什么样」的
 * 证据不可靠。Playwright 走本机 Chrome，截的是真帧。
 *
 * 产物：reports/playwright/assistant-screenshots/
 */
import { chromium } from '@playwright/test'
import { authFile } from './helpers/paths.js'
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.NIANGLIN_APP || 'http://127.0.0.1:5141'
const OUT = path.resolve('..', 'reports', 'playwright', 'assistant-screenshots')
fs.mkdirSync(OUT, { recursive: true })

const QUESTIONS = [
  '陪诊费怎么算？',
  '我爸的血压药一天该吃几次',
  '下周三去海南省人民医院心内科帮我约陪诊',
  '推荐个陪诊员'
]

async function shoot(browser, { name, width, height }) {
  const context = await browser.newContext({
    storageState: authFile('fam001'),
    viewport: { width, height },
    locale: 'zh-CN'
  })
  const page = await context.newPage()
  await page.goto(`${BASE}/family/assistant`)
  await page.locator('.asst__input textarea').waitFor()

  for (const q of QUESTIONS) {
    const before = await page.locator('.asst__row.is-assistant .asst__text').count()
    await page.locator('.asst__input textarea').fill(q)
    await page.locator('.asst__input button', { hasText: '发送' }).click()
    // ⚠️ 必须等**条数增加**，不能等 .last() —— .last() 在新回答落地前
    //    就已经匹配到上一条了，会把「还没答完」当成「答完了」。
    await page.waitForFunction(
      (n) => document.querySelectorAll('.asst__row.is-assistant .asst__text').length > n,
      before,
      { timeout: 25_000 }
    )
    await page.waitForTimeout(400)
    // ⚠️ 助手限流是「每用户每分钟 10 次」，连着拍会把自己限流。
    //    两次提问之间留出余量，保证截图里是真实回答而不是限流提示。
    await page.waitForTimeout(2000)
  }

  const file = path.join(OUT, `${name}.png`)
  await page.screenshot({ path: file, fullPage: false })
  console.log(`[ok] ${file}`)
  await context.close()
}

const browser = await chromium.launch({ channel: 'chrome' })
try {
  await shoot(browser, { name: 'assistant-desktop', width: 1280, height: 900 })
  await shoot(browser, { name: 'assistant-mobile', width: 390, height: 844 })
} finally {
  await browser.close()
}
