/**
 * 本地存储键名与 Token 读写工具。
 *
 * ⚠️ 安全约定：
 *   - 令牌用 localStorage / sessionStorage 存，简单可调试
 *   - 若后续引入敏感数据，需评估改用 httpOnly Cookie（当前一期不做）
 *   - 任何情况下都不得把密码、身份证号写入本地存储
 *
 * ⚠️ 存储位置（localStorage vs sessionStorage）：
 *   登录页的「7 天内自动登录」复选框需要两种真实语义：
 *     · 勾选   → localStorage：关掉浏览器再打开仍在（配合 7 天有效的 refreshToken）
 *     · 不勾选 → sessionStorage：关掉标签页即失效
 *   此前这个复选框是**死控件**（form.remember 从未被读取），勾不勾毫无区别。
 */

const TOKEN_KEY = 'nianglin_access_token'
const REFRESH_TOKEN_KEY = 'nianglin_refresh_token'
const USER_INFO_KEY = 'nianglin_user_info'
const ELDERLY_MODE_KEY = 'nianglin_elderly_mode'
const APP_SIDEBAR_MODE_KEY = 'nianglin_admin_sidebar_mode'

/* -------------------- 存储位置 -------------------- */

/**
 * 选一个存储位置。
 *
 * @param {boolean|undefined} remember
 *   - `true`  → localStorage（记住我）
 *   - `false` → sessionStorage（本次会话）
 *   - `undefined` → **沿用当前位置**（刷新令牌等场景用，不能把一次会话登录悄悄变成长效登录）
 * @param {string} key 用于判定「当前位置」的键
 */
function targetStore(remember, key) {
  if (remember === true) return localStorage
  if (remember === false) return sessionStorage
  return sessionStorage.getItem(key) !== null ? sessionStorage : localStorage
}

/**
 * 写入一个键，并清掉另一个位置上的同键旧值。
 *
 * <p>名字里带「Exclusive」是因为它同时用于令牌与用户信息 —— 它不是「写令牌」。</p>
 *
 * ⚠️ 清旧值是必须的：两个位置同时留着令牌时，读出来的是哪一个取决于顺序，
 * 用户刚勾掉的「记住我」可能因为旧令牌还在而"看起来没生效"。
 */
function writeExclusive(store, key, value) {
  store.setItem(key, value)
  const other = store === localStorage ? sessionStorage : localStorage
  other.removeItem(key)
}

/* -------------------- Access Token -------------------- */

export function getToken() {
  // sessionStorage 优先：它是更受限的那个，两边都有时以它为准
  return sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY) || ''
}

/**
 * @param {string} token
 * @param {boolean} [remember] 见 {@link targetStore}
 */
export function setToken(token, remember) {
  if (!token) return
  if (remember === undefined) {
    targetStore(undefined, TOKEN_KEY).setItem(TOKEN_KEY, token)
    return
  }
  writeExclusive(targetStore(remember, TOKEN_KEY), TOKEN_KEY, token)
}

export function removeToken() {
  localStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(TOKEN_KEY)
}

/* -------------------- Refresh Token -------------------- */

export function getRefreshToken() {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY) || localStorage.getItem(REFRESH_TOKEN_KEY) || ''
}

/**
 * @param {string} token
 * @param {boolean} [remember] 见 {@link targetStore}
 */
export function setRefreshToken(token, remember) {
  if (!token) return
  if (remember === undefined) {
    targetStore(undefined, REFRESH_TOKEN_KEY).setItem(REFRESH_TOKEN_KEY, token)
    return
  }
  writeExclusive(targetStore(remember, REFRESH_TOKEN_KEY), REFRESH_TOKEN_KEY, token)
}

export function removeRefreshToken() {
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  sessionStorage.removeItem(REFRESH_TOKEN_KEY)
}

/* -------------------- 用户信息 -------------------- */

/**
 * 用户信息只缓存非敏感字段：id / username / nickname / role / avatar。
 * 严禁缓存手机号明文、身份证号、密码。
 */
export function getUserInfo() {
  const raw = sessionStorage.getItem(USER_INFO_KEY) || localStorage.getItem(USER_INFO_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/**
 * @param {object} info
 * @param {boolean} [remember] 见 {@link targetStore}
 */
export function setUserInfo(info, remember) {
  if (!info) return
  const raw = JSON.stringify(info)
  if (remember === undefined) {
    targetStore(undefined, USER_INFO_KEY).setItem(USER_INFO_KEY, raw)
    return
  }
  writeExclusive(targetStore(remember, USER_INFO_KEY), USER_INFO_KEY, raw)
}

export function removeUserInfo() {
  localStorage.removeItem(USER_INFO_KEY)
  sessionStorage.removeItem(USER_INFO_KEY)
}

/* -------------------- 适老化模式偏好 -------------------- */

export function getElderlyMode() {
  return localStorage.getItem(ELDERLY_MODE_KEY) === 'true'
}

export function setElderlyMode(enabled) {
  localStorage.setItem(ELDERLY_MODE_KEY, String(!!enabled))
}

/* -------------------- admin 侧栏模式（desktop-adapt-v2 T-03） -------------------- */

/**
 * 取 admin 侧栏用户偏好: 'auto' | 'expanded' | 'collapsed'
 * null/undefined/无效值均视为 'auto'
 */
export function getSidebarMode() {
  const raw = localStorage.getItem(APP_SIDEBAR_MODE_KEY)
  if (raw === 'auto' || raw === 'expanded' || raw === 'collapsed') return raw
  return 'auto'
}

export function setSidebarMode(mode) {
  if (mode === 'auto' || mode === 'expanded' || mode === 'collapsed') {
    localStorage.setItem(APP_SIDEBAR_MODE_KEY, mode)
  }
}

/* -------------------- 一键清空登录态 -------------------- */

export function clearAuth() {
  removeToken()
  removeRefreshToken()
  removeUserInfo()
}
