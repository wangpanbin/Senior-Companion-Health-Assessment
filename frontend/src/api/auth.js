import request from '@/utils/request'

/**
 * 认证与账号接口
 * 对应文档：docs/api/01-auth-user.md
 * 负责模块：M2 认证与多角色鉴权（后端责任人 B）
 *
 * 骨架阶段仅声明签名，后端接口就绪后直接可用（接口先行约定）。
 */

/** 获取图形验证码 → { captchaKey, captchaImage } */
export function getCaptcha() {
  return request({ url: '/auth/captcha', method: 'get', skipAuth: true })
}

/** 注册 → { userId } */
export function register(data) {
  return request({ url: '/auth/register', method: 'post', data, skipAuth: true })
}

/** 登录 → { accessToken, refreshToken, expiresIn, userInfo } */
export function login(data) {
  return request({ url: '/auth/login', method: 'post', data, skipAuth: true })
}

/** 刷新 token → { accessToken, expiresIn } */
export function refreshToken(data) {
  return request({ url: '/auth/refresh', method: 'post', data, skipAuth: true })
}

/** 登出（服务端把 token 拉黑） */
export function logout() {
  return request({ url: '/auth/logout', method: 'post' })
}

/** 获取当前登录用户信息 → UserInfoVO */
export function getCurrentUser() {
  return request({ url: '/auth/me', method: 'get' })
}

/** 修改密码 */
export function changePassword(data) {
  return request({ url: '/auth/password', method: 'put', data })
}
