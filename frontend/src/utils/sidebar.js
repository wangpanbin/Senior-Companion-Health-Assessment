/**
 * sidebar 模式解析（desktop-adapt-v2 T-03）
 *
 * 依据：`docs/spec/desktop-adapt-v2.md` §4.5 + ticket 03
 *
 * AdminLayout 的侧栏有 3 个状态：
 *   - 'auto'      默认；按 useResponsive().isMd 翻转（<1280 收起 / ≥1280 展开）
 *   - 'expanded'  强制展开，忽略断点
 *   - 'collapsed' 强制收起，忽略断点
 *
 * 用户手动 toggle 时在 auto 与 collapsed 之间切换：
 *   - 当前是 auto 且实际展开 → 切到 collapsed（手动收）
 *   - 当前是 auto 且实际收起 → 切到 expanded（手动展，再 toggle 回 collapsed 即可）
 *   - 当前是手动态 → 切回 auto（让断点接管）
 *
 * 设计：状态机解析独立成纯函数 `resolveSidebarMode(storedMode, isMd)`，
 * AdminLayout 模板只消费结果，不做判断逻辑。便于单测 + 给 store / 其它 view 复用。
 */

export const SIDEBAR_MODE_AUTO = 'auto'
export const SIDEBAR_MODE_EXPANDED = 'expanded'
export const SIDEBAR_MODE_COLLAPSED = 'collapsed'

/** 默认展开宽度（与 design.md / variables.scss $sidebar-width 一致） */
export const SIDEBAR_WIDTH_EXPANDED = 220
/** 默认收起宽度（与 $sidebar-width-collapsed 一致） */
export const SIDEBAR_WIDTH_COLLAPSED = 64

/**
 * 把 "storedMode × isMd" 解析成实际的展示参数。
 *
 * @param {string|null|undefined} storedMode  'auto' / 'expanded' / 'collapsed'，null/undefined 走 auto
 * @param {boolean}                isMd       useResponsive().isMd（≥1280）
 * @returns {{ width: number, collapsed: boolean, mode: 'expanded'|'collapsed' }}
 */
export function resolveSidebarMode(storedMode, isMd) {
  const mode = storedMode || SIDEBAR_MODE_AUTO
  // 手动态优先；auto 模式按断点
  let effective
  if (mode === SIDEBAR_MODE_EXPANDED) {
    effective = SIDEBAR_MODE_EXPANDED
  } else if (mode === SIDEBAR_MODE_COLLAPSED) {
    effective = SIDEBAR_MODE_COLLAPSED
  } else {
    // auto
    effective = isMd ? SIDEBAR_MODE_EXPANDED : SIDEBAR_MODE_COLLAPSED
  }
  return {
    width: effective === SIDEBAR_MODE_EXPANDED ? SIDEBAR_WIDTH_EXPANDED : SIDEBAR_WIDTH_COLLAPSED,
    collapsed: effective === SIDEBAR_MODE_COLLAPSED,
    mode: effective
  }
}

/**
 * 用户点 toggle 按钮时的状态推进。语义：
 *   - 当前 auto 实际展开 → 切到 collapsed（手动收，记住手动选择）
 *   - 当前 auto 实际收起 → 切到 expanded（手动展）
 *   - 当前手动 expanded → 切回 auto
 *   - 当前手动 collapsed → 切回 auto
 *
 * @param {string|null|undefined} storedMode  当前存储的 mode
 * @param {boolean}                isMd       当前断点
 * @returns {string}                            下一个 mode（auto / expanded / collapsed）
 */
export function toggleSidebarMode(storedMode, isMd) {
  const mode = storedMode || SIDEBAR_MODE_AUTO
  // 手动态 → 回到 auto（让断点接管）
  if (mode === SIDEBAR_MODE_EXPANDED || mode === SIDEBAR_MODE_COLLAPSED) {
    return SIDEBAR_MODE_AUTO
  }
  // auto：按当前实际状态反向手动
  return isMd ? SIDEBAR_MODE_COLLAPSED : SIDEBAR_MODE_EXPANDED
}