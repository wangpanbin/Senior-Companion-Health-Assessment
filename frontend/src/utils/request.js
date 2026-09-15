import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getToken, getRefreshToken, setToken, clearAuth } from '@/utils/auth'

/**
 * Axios 统一封装
 *
 * 职责：
 *   1. 统一 baseURL / 超时 / JSON 头
 *   2. 请求拦截：自动携带 Authorization: Bearer <token>
 *   3. 响应拦截：拆解后端统一响应 { code, message, data }
 *      - code === 200 → 直接返回 data
 *      - 业务错误      → 弹出 message 并 reject
 *      - 401          → 尝试刷新 token 并重放原请求（TODO M2 完成实现）
 *   4. HTTP 层错误统一转成可读提示
 *
 * 约定见 docs/api/README.md
 */

const SUCCESS_CODE = 200
const UNAUTHORIZED_CODE = 401

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json;charset=UTF-8' }
})

/* ==================== 请求拦截 ==================== */

service.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token && !config.skipAuth) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

/* ==================== 响应拦截 ==================== */

// 刷新 token 的并发控制：多个请求同时 401 时只刷新一次
let refreshing = false
let pendingQueue = []

function flushQueue(token) {
  pendingQueue.forEach((cb) => cb(token))
  pendingQueue = []
}

service.interceptors.response.use(
  (response) => {
    const body = response.data

    // 非统一结构（如文件下载流）直接透传
    if (!body || typeof body.code === 'undefined') {
      return body
    }

    if (body.code === SUCCESS_CODE) {
      return body.data
    }

    // 业务错误：统一提示，交由调用方决定是否额外处理
    ElMessage.closeAll()
    ElMessage.error(body.message || '操作失败')
    return Promise.reject(Object.assign(new Error(body.message || '操作失败'), { code: body.code }))
  },
  async (error) => {
    const { response, config } = error

    /* ---------- 网络层异常 ---------- */
    if (!response) {
      ElMessage.closeAll()
      ElMessage.error(error.code === 'ECONNABORTED' ? '请求超时，请检查网络' : '网络异常，请稍后重试')
      return Promise.reject(error)
    }

    const { status, data } = response
    const message = data?.message

    /* ---------- 401：登录失效 ---------- */
    if (status === 401 || data?.code === UNAUTHORIZED_CODE) {
      const refreshToken = getRefreshToken()

      // TODO(M2)：后端刷新接口就绪后，在此调用 /api/auth/refresh 换新 token。
      //          当前骨架阶段直接登出，避免死循环。
      if (!refreshToken || config?.url?.includes('/auth/refresh')) {
        handleLogout(message)
        return Promise.reject(error)
      }

      if (refreshing) {
        // 已有刷新请求在途，挂起等待
        return new Promise((resolve) => {
          pendingQueue.push((token) => {
            config.headers.Authorization = `Bearer ${token}`
            resolve(service(config))
          })
        })
      }

      refreshing = true
      try {
        const { default: axiosRaw } = await import('axios')
        const res = await axiosRaw.post(
          `${import.meta.env.VITE_API_BASE_URL || '/api'}/auth/refresh`,
          { refreshToken }
        )
        const newToken = res.data?.data?.accessToken
        if (!newToken) throw new Error('刷新 token 失败')
        setToken(newToken)
        flushQueue(newToken)
        config.headers.Authorization = `Bearer ${newToken}`
        return service(config)
      } catch {
        pendingQueue = []
        handleLogout(message)
        return Promise.reject(error)
      } finally {
        refreshing = false
      }
    }

    /* ---------- 其他 HTTP 错误 ---------- */
    ElMessage.closeAll()
    ElMessage.error(message || httpStatusMessage(status))
    return Promise.reject(error)
  }
)

function httpStatusMessage(status) {
  const map = {
    400: '请求参数有误',
    403: '没有操作权限',
    404: '请求的资源不存在',
    405: '请求方法不支持',
    429: '操作过于频繁，请稍后再试',
    500: '服务器开小差了，请稍后再试',
    502: '网关异常',
    503: '服务暂不可用',
    504: '网关超时'
  }
  return map[status] || `请求失败（${status}）`
}

function handleLogout(message) {
  clearAuth()
  ElMessageBox.alert(message || '登录状态已失效，请重新登录', '提示', {
    type: 'warning',
    confirmButtonText: '去登录',
    callback: () => {
      window.location.href = '/login'
    }
  }).catch(() => {})
}

export default service
