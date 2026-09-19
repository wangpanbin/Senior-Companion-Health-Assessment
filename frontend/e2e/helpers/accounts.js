/**
 * E2E 账号与路由常量
 *
 * 账号与口令出处：docs/agents/FRONTEND_CONTRACT.md §10.7 / §10.8
 * 页面 → 账号矩阵出处：frontend/e2e/specs/*.spec.js 与 helpers/seedOrder.js（见 §10.7）
 *
 * 登录必须带图形验证码；验证码明文存 Redis `captcha:<key>`（dev 特性），
 * 由 fixtures/auth.setup.js 自动旁路，无需人工识别。
 */

/** 所有种子账号口令统一为此值 */
export const PASSWORD = 'Nl@123456'

/** 4 角色主页（与 frontend/src/router/index.js 的 ROLE_HOME 保持一致） */
export const ROLE_HOME = {
  ELDER: '/elder/home',
  FAMILY: '/family/home',
  COMPANION: '/companion/hall',
  ADMIN: '/admin/dashboard'
}

/**
 * 覆盖账号清单。
 * 之所以每类多取一个：UI 巡检要覆盖「有数据」的分支 ——
 *   fam019 名下有 COMPLETED 订单 1019（评价 happy path），
 *   comp007 名下有 ACCEPTED 订单 1007（执行页打卡），
 *   comp025 资质 PENDING（未过审反例）。
 */
export const ACCOUNTS = [
  { account: 'admin', role: 'ADMIN', userId: 1 },
  { account: 'fam001', role: 'FAMILY', userId: 101 },
  { account: 'fam019', role: 'FAMILY', userId: 119 },
  { account: 'comp001', role: 'COMPANION', userId: 301 },
  { account: 'comp007', role: 'COMPANION', userId: 307 },
  { account: 'comp025', role: 'COMPANION', userId: 325, audit: 'PENDING' },
  { account: 'elder001', role: 'ELDER', userId: 201 }
]

export const ACCOUNT_BY_NAME = Object.fromEntries(ACCOUNTS.map((a) => [a.account, a]))
