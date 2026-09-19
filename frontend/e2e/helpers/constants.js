/**
 * 测试用种子事实常量（页面「该显示几条」的依据）
 *
 * 出处：docs/agents/FRONTEND_CONTRACT.md §10.8（已连库核对）
 * ⚠️ 这是 2026-09-16 的快照。若断言与实测不符，先回库核对，不要随手放宽断言。
 */

/** 订单状态机 */
export const ORDER_STATUS = {
  PENDING: 'PENDING',
  ACCEPTED: 'ACCEPTED',
  IN_SERVICE: 'IN_SERVICE',
  COMPLETED: 'COMPLETED',
  REVIEWED: 'REVIEWED',
  CANCELLED: 'CANCELLED'
}

/** 打卡节点顺序（M5）；节点不可回退，可跳过 */
export const CHECKIN_NODES = ['DEPART', 'ARRIVE', 'IN_CONSULT', 'TAKE_MEDICINE', 'LEAVE', 'FINISH']

/** 关键种子 id */
export const SEED = {
  // 老人档案
  elderProfileBound: 401, // 张德海，绑定 fam001（user_id 201 = elder001）
  elderProfileUnbound: 431, // UNBOUND 边界样本
  // 订单
  orderPending: 1001, // fam001 名下，PENDING
  orderReviewed: 1031, // fam001 名下，REVIEWED
  orderCompleted: 1019, // fam019 名下，COMPLETED（评价 happy path）
  orderAccepted: 1007, // comp007 名下，ACCEPTED（执行页）
  hallPendingPool: [1001, 1002, 1003, 1004, 1005, 1006], // 待接单样本池（6 条）
  // 用药
  medicationTask: 20002,
  medicationPlan: 10001,
  // 陪诊员资料（业务键是 user_id）
  companionProfileUser: 301,
  companionProfilePendingUser: 325
}

/** 期望可见条数（种子事实） */
export const EXPECT = {
  hallPendingCount: 6, // 陪诊员大厅「待接单」6 条
  fam001OrderCount: 2, // fam001 名下 2 条：1031 / 1001
  companionAuditPending: 4 // 资质待审 4 条
}

/** 前端路由（与 frontend/src/router/routes.js 一一对应） */
export const ROUTES = {
  login: '/login',
  register: '/register',
  forget: '/forget',
  profile: '/profile',
  elderHome: '/elder/home',
  elderMedication: '/elder/medication',
  elderMessage: '/elder/message',
  familyHome: '/family/home',
  familyElder: '/family/elder',
  familyElderBind: '/family/elder/bind',
  familyMedication: '/family/medication',
  familyMessage: '/family/message',
  familyOrder: '/family/order',
  orderStep1: '/family/order/step1',
  orderStep2: '/family/order/step2',
  orderStep3: '/family/order/step3',
  companionHall: '/companion/hall',
  companionEntry: '/companion/entry',
  companionOrder: '/companion/order',
  companionIncome: '/companion/income',
  companionMessage: '/companion/message',
  companionExecute: (id) => `/companion/execute/${id}`,
  familyOrderDetail: (id) => `/family/order/${id}`,
  orderReview: (id) => `/family/order/${id}/review`,
  orderComplaint: (id) => `/family/order/${id}/complaint`,
  adminDashboard: '/admin/dashboard',
  adminCompanionAudit: '/admin/companion-audit',
  adminOrderDispute: '/admin/order-dispute',
  adminUser: '/admin/user',
  adminOrder: '/admin/order',
  adminComplaint: '/admin/complaint',
  adminExport: '/admin/export',
  adminOperLog: '/admin/oper-log',
  error403: '/error/403',
  error404: '/error/404'
}

/** 仅手机形态的路由（≥768px 由 NlDesktopShell 渲染 NlMobileOnlyNotice） */
export const MOBILE_ONLY_ROUTES = ['/profile', '/companion/entry']

/** 合规红线文案 —— 药品字典 / 全站都不允许出现 */
export const FORBIDDEN_MEDICAL_PHRASES = ['建议服用', '推荐剂量', '诊断为', '可能是']
