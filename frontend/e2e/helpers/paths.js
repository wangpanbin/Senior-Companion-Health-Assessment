/**
 * 路径常量（唯一真源）
 *
 * 目录关系：
 *   <repo>/frontend/e2e/helpers/paths.js
 *   → FRONTEND_DIR = <repo>/frontend
 *   → REPO_ROOT    = <repo>
 *
 * 所有运行期产物（storageState / 报告 / trace）都落 <repo>/reports/playwright/，
 * 因为 `reports/` 已在根 .gitignore 中 —— storageState 含**真实 JWT**，绝不能进版本库。
 */
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))

export const FRONTEND_DIR = path.resolve(HERE, '..', '..')
export const REPO_ROOT = path.resolve(FRONTEND_DIR, '..')
export const ARTIFACT_DIR = path.join(REPO_ROOT, 'reports', 'playwright')
export const AUTH_DIR = path.join(ARTIFACT_DIR, '.auth')

/** 某个账号的 storageState 文件路径 */
export const authFile = (account) => path.join(AUTH_DIR, `${account}.json`)
