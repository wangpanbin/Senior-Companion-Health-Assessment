/**
 * 路由表。
 *
 * 设计约定：
 *   - meta.title      菜单与页面标题
 *   - meta.icon       Element Plus 图标组件名
 *   - meta.roles      允许访问的角色数组；不写表示所有已登录角色可访问
 *   - meta.public     true 表示无需登录
 *   - meta.elderlyHidden  老人模式下从菜单中隐藏（菜单精简，M11）
 *   - meta.module     该页面归属的模块编号，便于对照 plan.md 分工
 *
 * 骨架阶段所有页面均为占位页，真实页面由对应模块负责人替换。
 */

const BasicLayout = () => import('@/layouts/BasicLayout.vue')

export const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', public: true, elderlyHidden: true }
  },

  {
    path: '/',
    component: BasicLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '首页', icon: 'HomeFilled', module: 'M0' }
      },
      {
        path: 'elder',
        name: 'ElderHome',
        component: () => import('@/views/elder/index.vue'),
        meta: {
          title: '我的就诊',
          icon: 'UserFilled',
          roles: ['ELDER'],
          module: 'M11'
        }
      },
      {
        path: 'family',
        name: 'FamilyHome',
        component: () => import('@/views/family/index.vue'),
        meta: {
          title: '家属工作台',
          icon: 'House',
          roles: ['FAMILY'],
          module: 'M4'
        }
      },
      {
        path: 'companion',
        name: 'CompanionHome',
        component: () => import('@/views/companion/index.vue'),
        meta: {
          title: '陪诊员工作台',
          icon: 'Van',
          roles: ['COMPANION'],
          module: 'M5'
        }
      },
      {
        path: 'admin',
        name: 'AdminHome',
        component: () => import('@/views/admin/index.vue'),
        meta: {
          title: '管理后台',
          icon: 'Setting',
          roles: ['ADMIN'],
          module: 'M9'
        }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { title: '个人中心', icon: 'User', elderlyHidden: true }
      }
    ]
  },

  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', public: true, elderlyHidden: true }
  }
]

export default routes
