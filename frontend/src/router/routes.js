/**
 * 路由表
 *
 * 设计约定（AGENTS.md / design.md §1.4）：
 *   - meta.title          菜单与页面标题
 *   - meta.icon           Element Plus 图标组件名
 *   - meta.roles          允许访问的角色数组；不写表示所有已登录角色可访问
 *   - meta.public         true 表示无需登录
 *   - meta.elderlyHidden  老人模式下从菜单中隐藏（菜单精简，M11）
 *   - meta.module         该页面归属的模块编号
 *   - meta.code           页面编号，如 M-04（与 design.md §1.2/1.3 对齐）
 *
 * 路由结构：
 *   /login /register /forget                    公开（M2）
 *   /                                          PhoneLayout（移动端 4 角色）
 *     /elder /family /companion                各自的 Home + 业务页
 *   /admin                                     AdminLayout（桌面端）
 *     /dashboard /companion-audit ...          后台管理
 *   /error/403 /error/404                      错误页
 */

const PhoneLayout = () => import('@/layouts/PhoneLayout.vue')
const AdminLayout = () => import('@/layouts/AdminLayout.vue')

export const routes = [
  // ==================== 公开页 ====================
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true, elderlyHidden: true, code: 'M-01' }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/login/register.vue'),
    meta: { title: '注册', public: true, elderlyHidden: true, code: 'M-02' }
  },
  {
    path: '/forget',
    name: 'Forget',
    component: () => import('@/views/login/forget.vue'),
    meta: { title: '找回密码', public: true, elderlyHidden: true, code: 'M-03' }
  },

  // ==================== 移动端（PhoneLayout / 4 角色共用） ====================
  {
    // 刻意不写 redirect：根路径的归属要按登录态 + 角色决定，
    // 统一交给 router/index.js 的 beforeEach（未登录 → /login，已登录 → 角色主页）。
    // 若在这里写死 `redirect: '/login'`，路由重定向会先于守卫执行，
    // 导致登录成功后 `router.push('/')` 解析成「当前就在 /login」被判为重复导航，
    // 守卫里的 `to.path === '/'` 分支永远不会命中，用户会卡在登录页。
    path: '/',
    component: PhoneLayout,
    children: [
      // ---------- 老人端 ELDER ----------
      {
        path: 'elder/home',
        name: 'ElderHome',
        component: () => import('@/views/elder/home.vue'),
        meta: {
          title: '银龄伴诊',
          icon: 'HomeFilled',
          roles: ['ELDER'],
          module: 'M11',
          code: 'M-04'
        }
      },
      {
        path: 'elder/medication',
        name: 'ElderMedication',
        component: () => import('@/views/elder/medication.vue'),
        meta: { title: '用药管理', icon: 'FirstAidKit', roles: ['ELDER'], module: 'M6', code: 'M-12' }
      },
      {
        path: 'elder/message',
        name: 'ElderMessage',
        component: () => import('@/views/companion/message.vue'),
        meta: { title: '消息', icon: 'Bell', roles: ['ELDER'], module: 'M8', code: 'M-14' }
      },

      // ---------- 家属端 FAMILY ----------
      {
        path: 'family/home',
        name: 'FamilyHome',
        component: () => import('@/views/family/home.vue'),
        meta: {
          title: '家属工作台',
          icon: 'HomeFilled',
          roles: ['FAMILY'],
          module: 'M3',
          code: 'M-06'
        }
      },
      {
        path: 'family/elder',
        name: 'FamilyElderList',
        component: () => import('@/views/family/elder-list.vue'),
        meta: { title: '我的老人', icon: 'UserFilled', roles: ['FAMILY'], module: 'M3', code: 'M-19' }
      },
      {
        path: 'family/elder/bind',
        name: 'FamilyElderBind',
        component: () => import('@/views/family/elder-bind.vue'),
        meta: { title: '绑定老人', icon: 'Plus', roles: ['FAMILY'], module: 'M3', elderlyHidden: true, code: 'M-19' }
      },
      {
        path: 'family/medication',
        name: 'FamilyMedication',
        component: () => import('@/views/family/medication.vue'),
        meta: { title: '用药管理', icon: 'FirstAidKit', roles: ['FAMILY'], module: 'M6', code: 'M-12' }
      },
      {
        path: 'family/message',
        name: 'FamilyMessage',
        component: () => import('@/views/companion/message.vue'),
        meta: { title: '消息', icon: 'Bell', roles: ['FAMILY'], module: 'M8', code: 'M-14' }
      },

      // 下单三步
      {
        path: 'family/order/step1',
        name: 'OrderStep1',
        component: () => import('@/views/family/order-step1.vue'),
        meta: { title: '选择就诊人', roles: ['FAMILY'], module: 'M4', elderlyHidden: true, code: 'M-07' }
      },
      {
        path: 'family/order/step2',
        name: 'OrderStep2',
        component: () => import('@/views/family/order-step2.vue'),
        meta: { title: '选择医院与时间', roles: ['FAMILY'], module: 'M4', elderlyHidden: true, code: 'M-08' }
      },
      {
        path: 'family/order/step3',
        name: 'OrderStep3',
        component: () => import('@/views/family/order-step3.vue'),
        meta: { title: '确认订单', roles: ['FAMILY'], module: 'M4', elderlyHidden: true, code: 'M-09' }
      },
      {
        path: 'family/order',
        name: 'FamilyOrderList',
        component: () => import('@/views/family/order-list.vue'),
        meta: { title: '我的订单', icon: 'Tickets', roles: ['FAMILY'], module: 'M4', code: 'M-15' }
      },
      {
        path: 'family/order/:id',
        name: 'FamilyOrderDetail',
        component: () => import('@/views/family/order-detail.vue'),
        meta: { title: '订单详情', roles: ['FAMILY'], module: 'M4', elderlyHidden: true, code: 'M-16' }
      },
      {
        path: 'family/order/:id/review',
        name: 'OrderReview',
        component: () => import('@/views/family/order-review.vue'),
        meta: { title: '评价订单', roles: ['FAMILY'], module: 'M7', elderlyHidden: true, code: 'M-17' }
      },
      {
        path: 'family/order/:id/complaint',
        name: 'OrderComplaint',
        component: () => import('@/views/family/order-complaint.vue'),
        meta: { title: '我要投诉', roles: ['FAMILY'], module: 'M7', elderlyHidden: true, code: 'M-18' }
      },

      // ---------- 陪诊员端 COMPANION ----------
      {
        path: 'companion/hall',
        name: 'CompanionHall',
        component: () => import('@/views/companion/hall.vue'),
        meta: {
          title: '接单大厅',
          icon: 'List',
          roles: ['COMPANION'],
          module: 'M4',
          code: 'M-10'
        }
      },
      {
        path: 'companion/entry',
        name: 'CompanionEntry',
        component: () => import('@/views/companion/entry.vue'),
        meta: { title: '资质入驻', icon: 'EditPen', roles: ['COMPANION'], module: 'M3', elderlyHidden: true, code: 'M-20' }
      },
      {
        path: 'companion/execute/:id',
        name: 'CompanionExecute',
        component: () => import('@/views/companion/execute.vue'),
        meta: { title: '订单执行', roles: ['COMPANION'], module: 'M5', elderlyHidden: true, code: 'M-11' }
      },
      {
        path: 'companion/order',
        name: 'CompanionOrderList',
        component: () => import('@/views/companion/order-list.vue'),
        meta: { title: '我的订单', icon: 'Tickets', roles: ['COMPANION'], module: 'M4', code: 'M-15' }
      },
      {
        path: 'companion/income',
        name: 'CompanionIncome',
        component: () => import('@/views/companion/income.vue'),
        meta: { title: '我的收入', icon: 'Money', roles: ['COMPANION'], module: 'M4', code: 'M-21' }
      },
      {
        path: 'companion/message',
        name: 'CompanionMessage',
        component: () => import('@/views/companion/message.vue'),
        meta: { title: '消息', icon: 'Bell', roles: ['COMPANION'], module: 'M8', code: 'M-14' }
      },

      // ---------- 我的（4 角色共用） ----------
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { title: '我的', icon: 'User', module: 'M2', code: 'M-22' }
      }
    ]
  },

  // ==================== 管理后台（AdminLayout） ====================
  {
    path: '/admin',
    component: AdminLayout,
    redirect: '/admin/dashboard',
    meta: { roles: ['ADMIN'] },
    children: [
      {
        path: 'dashboard',
        name: 'AdminDashboard',
        component: () => import('@/views/admin/dashboard.vue'),
        meta: { title: '数据看板', icon: 'TrendCharts', roles: ['ADMIN'], module: 'M10', code: 'W-01' }
      },
      {
        path: 'companion-audit',
        name: 'AdminCompanionAudit',
        component: () => import('@/views/admin/companion-audit.vue'),
        meta: { title: '陪诊员资质审核', icon: 'Verified', roles: ['ADMIN'], module: 'M9', code: 'W-02' }
      },
      {
        path: 'order-dispute',
        name: 'AdminOrderDispute',
        component: () => import('@/views/admin/order-dispute.vue'),
        meta: { title: '订单纠纷仲裁', icon: 'ChatLineSquare', roles: ['ADMIN'], module: 'M9', code: 'W-03' }
      },
      {
        path: 'user',
        name: 'AdminUser',
        component: () => import('@/views/admin/user.vue'),
        meta: { title: '用户管理', icon: 'UserFilled', roles: ['ADMIN'], module: 'M9', code: 'W-04' }
      },
      {
        path: 'order',
        name: 'AdminOrder',
        component: () => import('@/views/admin/order.vue'),
        meta: { title: '订单管理', icon: 'Tickets', roles: ['ADMIN'], module: 'M9', code: 'W-05' }
      },
      {
        path: 'complaint',
        name: 'AdminComplaint',
        component: () => import('@/views/admin/complaint.vue'),
        meta: { title: '投诉管理', icon: 'WarningFilled', roles: ['ADMIN'], module: 'M9', code: 'W-06' }
      },
      {
        path: 'export',
        name: 'AdminExport',
        component: () => import('@/views/admin/export.vue'),
        meta: { title: '数据导出', icon: 'Download', roles: ['ADMIN'], module: 'M10', code: 'W-07' }
      },
      {
        path: 'oper-log',
        name: 'AdminOperLog',
        component: () => import('@/views/admin/oper-log.vue'),
        meta: { title: '操作日志', icon: 'Document', roles: ['ADMIN'], module: 'M9', code: 'W-08' }
      }
    ]
  },

  // ==================== 错误页 ====================
  {
    path: '/error/403',
    name: 'Forbidden',
    component: () => import('@/views/error/403.vue'),
    meta: { title: '无权限', public: true, elderlyHidden: true }
  },
  {
    path: '/error/404',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', public: true, elderlyHidden: true }
  },

  // ==================== 兜底 ====================
  {
    path: '/:pathMatch(.*)*',
    redirect: '/error/404'
  }
]

export default routes
