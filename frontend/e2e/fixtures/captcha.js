/**
 * 验证码旁路工具
 *
 * 后端登录强制校验图形验证码：`GET /api/auth/captcha` 返回 captchaKey，
 * 验证码**明文**存在 Redis 键 `captcha:<captchaKey>`（dev 配置，大写，TTL 300s，
 * 校验时一次性消费）。因此自动化不需要 OCR —— 直接从 Redis 取明文即可。
 *
 * 出处：docs/agents/FRONTEND_CONTRACT.md §10.7
 */
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'

const execFileAsync = promisify(execFile)

const REDIS_CLI = process.env.REDIS_CLI || 'D:\\develop\\Redis-8.8.0\\redis-cli.exe'

/** 读取某个 captchaKey 对应的明文验证码 */
export async function readCaptcha(captchaKey) {
  if (!captchaKey) throw new Error('readCaptcha: captchaKey 为空')
  let stdout
  try {
    ;({ stdout } = await execFileAsync(REDIS_CLI, ['GET', `captcha:${captchaKey}`], {
      timeout: 10000,
      windowsHide: true
    }))
  } catch (e) {
    throw new Error(
      `调用 redis-cli 失败（${REDIS_CLI}）：${e.message}。` +
        '请确认 redis-cli 可执行，或用 REDIS_CLI 环境变量指定绝对路径。'
    )
  }
  const value = String(stdout || '').trim().replace(/^"|"$/g, '')
  if (!value || value === '(nil)') {
    throw new Error(`Redis 没有 captcha:${captchaKey}（可能已过期或被消费）`)
  }
  return value
}
