/**
 * 本地存储键名与 Token 读写工具。
 *
 * ⚠️ 安全约定：
 *   - 骨架阶段用 localStorage 存 token，简单可调试
 *   - 若后续引入敏感数据，需评估改用 httpOnly Cookie（当前一期不做）
 *   - 任何情况下都不得把密码、身份证号写入本地存储
 */

const TOKEN_KEY = 'nianglin_access_token'
const REFRESH_TOKEN_KEY = 'nianglin_refresh_token'
const USER_INFO_KEY = 'nianglin_user_info'
const ELDERLY_MODE_KEY = 'nianglin_elderly_mode'

/* -------------------- Access Token -------------------- */

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  }
}

export function removeToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/* -------------------- Refresh Token -------------------- */

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY) || ''
}

export function setRefreshToken(token) {
  if (token) {
    localStorage.setItem(REFRESH_TOKEN_KEY, token)
  }
}

export function removeRefreshToken() {
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

/* -------------------- 用户信息 -------------------- */

/**
 * 用户信息只缓存非敏感字段：id / username / nickname / role / avatar。
 * 严禁缓存手机号明文、身份证号、密码。
 */
export function getUserInfo() {
  const raw = localStorage.getItem(USER_INFO_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

export function setUserInfo(info) {
  if (info) {
    localStorage.setItem(USER_INFO_KEY, JSON.stringify(info))
  }
}

export function removeUserInfo() {
  localStorage.removeItem(USER_INFO_KEY)
}

/* -------------------- 适老化模式偏好 -------------------- */

export function getElderlyMode() {
  return localStorage.getItem(ELDERLY_MODE_KEY) === 'true'
}

export function setElderlyMode(enabled) {
  localStorage.setItem(ELDERLY_MODE_KEY, String(!!enabled))
}

/* -------------------- 一键清空登录态 -------------------- */

export function clearAuth() {
  removeToken()
  removeRefreshToken()
  removeUserInfo()
}
