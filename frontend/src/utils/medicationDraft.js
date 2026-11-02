/**
 * 订单 → 用药计划草稿的跨页传递（V4 · P1-2）。
 *
 * 为什么用 sessionStorage：
 *   草稿只在「订单详情 → 用药计划」这一次跳转里有效，关掉标签页就该消失；
 *   与 `orderDraft` 的「不落 localStorage」原则一致，避免老人/家属共用手机时
 *   上一个人的草稿出现在下一个人页面上。
 *
 * ⚠️ 合规：只存药品名 / 每日次数 / 时间点，**绝不存剂量**。
 *    剂量由家属在用药计划页按医嘱录入，系统不生成、不推断、不建议。
 */
const STORAGE_KEY = 'nianglin:medication:draft:from-order'

/**
 * 保存后端返回的草稿。
 * @param {{orderId:number, elderId:number, items:Array, ttlMinutes?:number}} draft
 */
export function saveMedicationDraft(draft) {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({
      orderId: draft?.orderId ?? null,
      elderId: draft?.elderId ?? null,
      items: Array.isArray(draft?.items) ? draft.items : [],
      ttlMinutes: draft?.ttlMinutes ?? 30
    }))
  } catch {
    // sessionStorage 不可用（隐私模式 / 配额满）时静默放弃跨页草稿，
    // 用户仍可在用药计划页手动填写，不阻断主流程
  }
}

/**
 * 读取草稿；没有或格式损坏返回 null。
 * @returns {{orderId:number, elderId:number, items:Array, ttlMinutes:number}|null}
 */
export function readMedicationDraft() {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const draft = JSON.parse(raw)
    if (!Array.isArray(draft?.items) || draft.items.length === 0) return null
    return draft
  } catch {
    return null
  }
}

/** 放弃剩余草稿 */
export function clearMedicationDraft() {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    // 同上：清理失败也不影响页面继续使用
  }
}
