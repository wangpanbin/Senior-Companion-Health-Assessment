/**
 * 展示层格式化工具（联调层）。
 *
 * ⚠️ 后端口径备忘（`config/JacksonConfig` 全局注册，改动需同步这里）：
 *   LocalDateTime → "yyyy-MM-dd HH:mm:ss"
 *   LocalDate     → "yyyy-MM-dd"
 *   金额          → 两位小数字符串，如 "128.00"
 *
 * 所以这里**一律按字符串切分**，不用 `new Date('2026-09-16 09:30:00')`。
 * 后者在 Safari / 旧版 iOS 上会解析失败返回 Invalid Date ——
 * 而本项目的目标用户恰恰大量使用 iOS 设备，`Invalid Date` 会直接显示到老人脸上。
 */

/** 补零 */
const pad = (n) => String(n).padStart(2, '0')

/**
 * "2026-09-16 09:30:00" → "09-16 09:30"
 * @param {string} value 后端时间串
 * @param {string} fallback 空值时的占位
 */
export function formatDateTime(value, fallback = '—') {
  if (!value) return fallback
  const [date, time = ''] = String(value).split(' ')
  const [, mm, dd] = date.split('-')
  if (!mm || !dd) return value
  const hm = time.slice(0, 5)
  return hm ? `${mm}-${dd} ${hm}` : `${mm}-${dd}`
}

/**
 * "2026-09-16 09:30:00" → "2026-09-16"
 * @param {string} value
 */
export function formatDate(value, fallback = '—') {
  if (!value) return fallback
  return String(value).split(' ')[0]
}

/**
 * "2026-09-16 09:30:00" → "09:30"
 */
export function formatTime(value, fallback = '—') {
  if (!value) return fallback
  const time = String(value).split(' ')[1]
  return time ? time.slice(0, 5) : fallback
}

/**
 * 就诊时段展示："2026-09-16 09:30:00" → "09-16 09:30"
 * 与 formatDateTime 同义，语义化别名，避免调用处纠结该用哪个。
 */
export const formatVisitTime = formatDateTime

/**
 * 金额展示。后端已是两位小数字符串，这里只补 ¥ 与空值兜底。
 * 不做 `toFixed(2)` —— 对字符串做 toFixed 会先转 Number 再引入浮点误差，
 * 对账场景下「差一分钱」是要被追问的。
 */
export function formatMoney(value, withSymbol = true) {
  if (value === null || value === undefined || value === '') return withSymbol ? '¥—' : '—'
  const text = String(value)
  // 后端偶尔会下发 BigDecimal（actualFee），统一到两位小数
  const fixed = /^\d+(\.\d+)?$/.test(text) ? Number(text).toFixed(2) : text
  return withSymbol ? `¥${fixed}` : fixed
}

/* ==================== 日期运算（本地时区，避免 toISOString 的 UTC 偏移） ==================== */

/** 今天，yyyy-MM-dd */
export function today() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/**
 * 在 yyyy-MM-dd 上加减天数。
 * 刻意不走 `toISOString()` —— 它按 UTC 输出，东八区下会把 08-01 00:00 变成 07-31，
 * 这类「差一天」的缺陷在统计区间上就是「少算一天的订单」。
 */
export function addDays(dateStr, days) {
  const [y, m, d] = dateStr.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  dt.setDate(dt.getDate() + days)
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`
}

/** 最近 N 天的起止日期（含今天） */
export function lastNDays(n) {
  return { startDate: addDays(today(), -(n - 1)), endDate: today() }
}

/** 当前时间，yyyy-MM-dd HH:mm:ss（服药补记 / 打卡用） */
export function now() {
  const d = new Date()
  return `${today()} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/* ==================== 枚举中文兜底 ==================== */

/**
 * 后端 VO 一般会同时下发 `statusLabel`，优先用它；
 * 这里只做「后端没给」时的兜底，避免页面直接显示 `IN_SERVICE` 这种裸枚举名。
 */
export const ORDER_STATUS_TEXT = {
  PENDING: '待接单',
  ACCEPTED: '已接单',
  IN_SERVICE: '服务中',
  COMPLETED: '已完成',
  REVIEWED: '已评价',
  CANCELLED: '已取消'
}

export const CHECKIN_NODE_TEXT = {
  DEPART: '出发',
  ARRIVE: '到院',
  IN_CONSULT: '就诊中',
  TAKE_MEDICINE: '取药',
  LEAVE: '离院',
  FINISH: '完成'
}

export const CHECKIN_NODE_ORDER = ['DEPART', 'ARRIVE', 'IN_CONSULT', 'TAKE_MEDICINE', 'LEAVE', 'FINISH']

export const PAYMENT_STATUS_TEXT = {
  UNPAID: '未结算',
  SETTLED: '已结算'
}

export const MEAL_RELATION_TEXT = {
  BEFORE_MEAL: '饭前',
  AFTER_MEAL: '饭后',
  ANY: '不限'
}

export const AUDIT_STATUS_TEXT = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回'
}

/** ⚠️ 后端取值是 RESOLVED（已结案），不是 CLOSED */
export const COMPLAINT_STATUS_TEXT = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已结案',
  REJECTED: '已驳回'
}

export const MEDICATION_PLAN_STATUS_TEXT = {
  ACTIVE: '进行中',
  DISABLED: '已停用'
}

export const MEDICATION_TASK_STATUS_TEXT = {
  PENDING: '待服',
  TAKEN: '已服',
  MISSED: '漏服'
}

export const BIND_STATUS_TEXT = {
  BOUND: '已绑定',
  UNBOUND: '未绑定'
}

export const ACCOUNT_STATUS_TEXT = {
  NORMAL: '正常',
  DISABLED: '已封禁'
}

export const RELATION_TEXT = {
  SON: '儿子',
  DAUGHTER: '女儿',
  RELATIVE: '亲属',
  OTHER: '其他'
}

export const GENDER_TEXT = { MALE: '男', FEMALE: '女' }

export const MOBILITY_TEXT = {
  SELF: '可自理',
  ASSIST: '需搀扶',
  WHEELCHAIR: '需轮椅'
}

/** 通用取值：优先后端 label，其次本地映射，最后裸值 */
export function labelOf(map, value, fallback = '—') {
  if (!value) return fallback
  return map[value] || value
}
