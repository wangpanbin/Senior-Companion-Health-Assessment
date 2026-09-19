/**
 * 银龄伴诊 · Playwright E2E 配置
 *
 * 关键取舍：
 *   - **串行**（fullyParallel:false, workers:1）：种子库共享，写路径会改同一批种子行，
 *     并行 worker 必然互相踩踏。
 *   - 用**本机 Chrome**（channel: 'chrome'），不下载 Chromium 浏览器包。
 *     可用 CHROME_PATH 指定可执行文件覆盖。
 *   - 所有产物落 <repo>/reports/playwright/ —— `/reports/` 已被 .gitignore 忽略，
 *     storageState 含真实 JWT，绝不能进版本库。
 *   - 前端 dev server 由 webServer 拉起（reuseExistingServer，已起则复用）。
 *
 * 运行：
 *   $env:MYSQL_PASSWORD="123456"
 *   pnpm -C frontend exec playwright test
 */
import path from 'node:path'
import fs from 'node:fs'
import { defineConfig, devices } from '@playwright/test'
import { ARTIFACT_DIR, AUTH_DIR } from './e2e/helpers/paths'

const BASE_URL = process.env.NIANGLIN_APP || 'http://127.0.0.1:5141'
const CHROME_PATH = process.env.CHROME_PATH

fs.mkdirSync(AUTH_DIR, { recursive: true })

/** 本机 Chrome：优先用 CHROME_PATH，否则走 Playwright 的 chrome 通道探测 */
const browserUse = CHROME_PATH
  ? { launchOptions: { executablePath: CHROME_PATH } }
  : { channel: 'chrome' }

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 2 : 1,
  timeout: 30_000,
  expect: { timeout: 8_000 },

  globalSetup: './e2e/global-setup.js',
  globalTeardown: './e2e/global-teardown.js',

  outputDir: path.join(ARTIFACT_DIR, 'test-results'),
  reporter: [
    ['list'],
    ['html', { outputFolder: path.join(ARTIFACT_DIR, 'html'), open: 'never' }],
    ['json', { outputFile: path.join(ARTIFACT_DIR, 'results.json') }]
  ],

  use: {
    baseURL: BASE_URL,
    ...browserUse,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    // ⚠️ video **刻意关闭**（不是漏配）：Playwright 只要配置了 video（哪怕只是
    //    'retain-on-failure'），就会在 newContext 阶段要求 ms-playwright 缓存里的
    //    ffmpeg 二进制存在；缺失时**所有用例在 newPage 直接失败**，报错还是
    //    「Video rendering requires ffmpeg binary」—— 与用例内容毫无关系，极具迷惑性。
    //    失败取证改用 trace（on-first-retry）+ screenshot（only-on-failure），两者都不需要 ffmpeg。
    //    确需录像时：先 `pnpm exec playwright install ffmpeg`，再把这里改回
    //    'retain-on-failure'，并同步 FRONTEND_CONTRACT §13.1 的前置说明。
    video: 'off',
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  },

  webServer: {
    command: 'node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5141',
    url: BASE_URL,
    reuseExistingServer: true,
    timeout: 60_000,
    stdout: 'ignore',
    stderr: 'pipe'
  },

  projects: [
    {
      name: 'setup',
      testMatch: /auth\.setup\.js$/
    },
    {
      name: 'chromium',
      testMatch: /specs[/\\].*\.spec\.js$/,
      dependencies: ['setup'],
      // 每个 spec 自己用 test.use({ viewport }) 声明形态（避免同一 spec 被多项目重复执行）
      use: { ...devices['Desktop Chrome'], ...browserUse }
    }
  ]
})
