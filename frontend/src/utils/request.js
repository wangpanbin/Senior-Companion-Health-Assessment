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

/** blob 响应里夹带的 JSON envelope —— 后端业务错误在 blob 模式下仍是 HTTP 200 + JSON */
function isJsonBlob(body) {
  return (
    body instanceof Blob && typeof body.type === 'string' && body.type.includes('application/json')
  )
}

/** 尝试把 JSON blob 读成对象；非 JSON / 解析失败返回 null */
async function readJsonBlob(blob) {
  try {
    return JSON.parse(await blob.text())
  } catch {
    return null
  }
}

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
// 挂起的请求：元素是 { resolve, reject }。
// ⚠️ 必须同时存 reject —— 只存 resolve 的话，刷新失败时这些 Promise 既不 resolve 也不 reject，
// 调用方的 await 永远等不到结果，页面就一直卡在 loading（用户看到的是「一直转圈」而不是「请重新登录」）。
let pendingQueue = []

/** 刷新成功：放行所有挂起的请求（用新 token 重放） */
function flushQueue(token) {
  pendingQueue.forEach((item) => item.resolve(token))
  pendingQueue = []
}

/** 刷新失败：让所有挂起的请求以「登录已失效」失败，而不是永远悬挂 */
function rejectQueue(error) {
  pendingQueue.forEach((item) => item.reject(error))
  pendingQueue = []
}

service.interceptors.response.use(
  (response) => {
    const body = response.data

    // blob 响应（如受控证件下载）：后端业务错误仍是 HTTP 200 + JSON envelope，
    // 此时 body 是 type=application/json 的 Blob，若直接透传会被当成二进制流，
    // 错误提示将被静默吞掉。这里解析后按统一结构处理。
    if (isJsonBlob(body)) {
      return readJsonBlob(body).then((parsed) => {
        if (!parsed || typeof parsed.code === 'undefined' || parsed.code === SUCCESS_CODE) {
          return body
        }
        ElMessage.closeAll()
        ElMessage.error(parsed.message || '操作失败')
        return Promise.reject(
          Object.assign(new Error(parsed.message || '操作失败'), { code: parsed.code })
        )
      })
    }

    // 非统一结构（如文件下载流）直接透传
    if (!body || typeof body.code === 'undefined') {
      return body
    }

    if (body.code === SUCCESS_CODE) {
      return body.data
    }

    // 业务错误：统一提示，交由调用方决定是否额外处理
    // ⚠️ data 必须随错误一起 reject —— 11001 等少数业务码靠 data 携带
    // 结构化信息（如 pendingOrderCount），丢掉它前端就只能拿到 code/message，
    // 模板里的数字占位会渲染成空白（V4V5 联调 DEF-2）
    ElMessage.closeAll()
    ElMessage.error(body.message || '操作失败')
    return Promise.reject(
      Object.assign(new Error(body.message || '操作失败'), { code: body.code, data: body.data ?? null })
    )
  },
  async (error) => {
    const { response, config } = error

    /* ---------- 网络层异常 ---------- */
    if (!response) {
      ElMessage.closeAll()
      ElMessage.error(error.code === 'ECONNABORTED' ? '请求超时，请检查网络' : '网络异常，请稍后重试')
      return Promise.reject(error)
    }

    const { status } = response
    // 错误响应也可能以 blob 返回（如 responseType: 'blob' 的受控下载端点），
    // 需先解析出 JSON envelope 才能拿到 message / code
    let data = response.data
    if (isJsonBlob(data)) {
      const parsed = await readJsonBlob(data)
      if (parsed) {
        data = parsed
      }
    }
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
        // reject 也要一起挂上：刷新失败时走 rejectQueue，
        // 否则这个 Promise 既不 resolve 也不 reject —— 请求永久悬挂
        return new Promise((resolve, reject) => {
          pendingQueue.push({
            resolve: (token) => {
              config.headers.Authorization = `Bearer ${token}`
              resolve(service(config))
            },
            reject
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
        // 先让挂起的请求全部失败，再登出：
        // 否则它们既不 resolve 也不 reject，调用方的 await 永远等不到结果（页面卡在 loading）
        rejectQueue(
          Object.assign(new Error('登录状态已失效，请重新登录'), { code: UNAUTHORIZED_CODE })
        )
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

/**
 * 会话失效对话框是否已弹出（V4V5 联调 DEF-3）。
 *
 * <p>不加这个标志时：第一个 401 调 handleLogout → clearAuth() 清掉
 * refreshToken → 并发的其余 401 各自走「无 refreshToken」分支再弹一个
 * 对话框 —— 现场实测堆叠了几十个。对话框关闭/跳转后复位，保证
 * 下一次真的会话失效仍能提示。</p>
 */
let logoutPromptOpen = false

function handleLogout(message) {
  clearAuth()
  if (logoutPromptOpen) return
  logoutPromptOpen = true
  ElMessageBox.alert(message || '登录状态已失效，请重新登录', '提示', {
    type: 'warning',
    confirmButtonText: '去登录',
    callback: () => {
      // 带上当前路径：重新登录后能回到原来那一页，而不是一律回角色主页（P2-14）。
      // 只取 pathname + search（不带 hash、不带任何身份信息），再 URL 编码进 query。
      const current = `${window.location.pathname}${window.location.search}`
      window.location.href = current.startsWith('/login')
        ? '/login'
        : `/login?redirect=${encodeURIComponent(current)}`
    }
  })
    .catch(() => {
      // 用户手动关闭（未跳转）：复位，否则本会话后续的会话失效再也不会提示
      logoutPromptOpen = false
    })
}

export default service
