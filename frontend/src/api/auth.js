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

/** 登出（服务端把 accessToken 与 refreshToken 一起拉黑） */
export function logout(refreshToken) {
  return request({ url: '/auth/logout', method: 'post', data: { refreshToken } })
}

/** 获取当前登录用户信息 → UserInfoVO */
export function getCurrentUser() {
  return request({ url: '/auth/me', method: 'get' })
}

/** 修改密码 */
export function changePassword(data) {
  return request({ url: '/auth/password', method: 'put', data })
}

/**
 * 获取找回密码验证码（V4 · P1-1；去短信化见 ADR-0021）
 * → data 为 6 位验证码字符串（后端直接回传，不经短信通道），前端自动填充
 * 频控超限返回 1009（拦截器已弹文案）
 */
export function sendSmsCode(data) {
  return request({ url: '/auth/sms-code', method: 'post', data, skipAuth: true })
}

/**
 * 凭手机号 + 验证码自助找回密码（V4 · P1-1）
 * → data 恒为 null；手机号不存在时响应与成功完全同构（见 docs/api/01-auth-user.md §11）
 */
export function forgotPassword(data) {
  return request({ url: '/auth/forgot-password', method: 'post', data, skipAuth: true })
}
