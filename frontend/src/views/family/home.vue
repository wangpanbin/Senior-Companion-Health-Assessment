<script setup>
/**
 * 家属端首页（M-06 · design.md §4）
 *
 * 聚合展示，全由真实接口驱动：
 *   1. `listElder({ page:1, size:100 })`（`@/api/user`）→ 我绑定的老人，横滑卡可切换「当前就诊人」
 *   2. `listMyOrders({ page:1, size:5 })`（`@/api/order`）→ 最近订单（状态 chip + 医院 + 科室 + 就诊时间 + 服务费）
 *   3. `getUnreadCount()`（`@/api/message`）→ 未读消息红点（每 60s 轮询）
 *   4. `getTodayTasks(elderId)`（`@/api/medication`）→ 当前老人的今日待服用药提醒
 *
 * 各区块独立加载 / 失败：一个接口挂了不整页白屏、也不整页报错。
 *
 * ⚠️ 未读数字段：后端 `/message/unread-count` 实际返回 `UnreadCountVO{ total, byType }`
 *    （见 `vo/UnreadCountVO.java`），与契约文档里写的 `{ unreadCount }` 不一致；
 *    这里按后端真值读取 `data.total` 自行轮询（`@/api/message` 的 `startUnreadPolling`
 *    读的是 `unreadCount`，对接真实后端会恒为 0，故不依赖它）。组件卸载必须清掉定时器。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Bell,
  Calendar, FirstAidKit as Med, Clock, Plus
} from '@element-plus/icons-vue'
import {
  NlPhoneShell, NlDesktopShell, NlAppTabBar, NlCard, NlAvatar, NlStatusChip,
  NlIconBox, NlSkeleton, NlEmpty, NlNoticeBar, NlUserMenu
} from '@/components'
import { useAppStore } from '@/store/modules/app'
import { useDevice } from '@/composables/useDevice'
import { listElder } from '@/api/user'
import { listMyOrders } from '@/api/order'
import { getUnreadCount } from '@/api/message'
import { getTodayTasks } from '@/api/medication'
import { formatVisitTime, formatMoney } from '@/utils/format'

const router = useRouter()
const appStore = useAppStore()

/**
 * 形态判定（ADR-0007 / 计划 Stage 1）
 * - 窄屏（<= 767px）→ NlPhoneShell，模板与改造前**完全一致**
 * - 宽屏（>= 768px）→ NlDesktopShell，内容居中限宽 720（宽度由壳控制，此处不写死）
 */
const { isMobile } = useDevice()

/* ---------------- 老人（切换就诊人上下文） ---------------- */
const elders = ref([])
const eldersLoading = ref(false)
const activeElder = ref(0)
const currentElder = computed(() => elders.value[activeElder.value] || null)

async function loadElders() {
  eldersLoading.value = true
  try {
    const data = await listElder({ page: 1, size: 100 })
    elders.value = data?.records || []
    activeElder.value = 0
    loadTodayTasks(currentElder.value?.id)
  } catch {
    elders.value = []
    tasks.value = []
  } finally {
    eldersLoading.value = false
  }
}

function pickElder(i) {
  activeElder.value = i
  loadTodayTasks(currentElder.value?.id)
}

/* ---------------- 最近订单 ---------------- */
const orders = ref([])
const ordersLoading = ref(false)

async function loadOrders() {
  ordersLoading.value = true
  try {
    const data = await listMyOrders({ page: 1, size: 5 })
    orders.value = data?.records || []
  } catch {
    orders.value = []
  } finally {
    ordersLoading.value = false
  }
}

/* ---------------- 今日用药提醒 ---------------- */
const tasks = ref([])
const tasksLoading = ref(false)

async function loadTodayTasks(elderId) {
  if (elderId == null) {
    tasks.value = []
    return
  }
  tasksLoading.value = true
  try {
    // 后端返回 List<MedicationTaskVO>（数组），拦截器直接给数组
    const data = await getTodayTasks(elderId)
    tasks.value = data || []
  } catch {
    tasks.value = []
  } finally {
    tasksLoading.value = false
  }
}

/* ---------------- 未读消息红点（轮询） ---------------- */
const unread = ref(0)
let unreadTimer = null

async function refreshUnread() {
  try {
    const data = await getUnreadCount()
    unread.value = Number(data?.total ?? 0)
  } catch {
    // 轮询失败不弹提示，静默等下次
  }
}

/* ---------------- 导航 ---------------- */
const quickEntries = [
  { key: 'order',   icon: Calendar, label: '预约挂号', tone: 'primary', desc: '帮父母约医院陪诊',     path: '/family/order/step1' },
  { key: 'med',     icon: Med,      label: '用药计划', tone: 'success', desc: '管理药品 / 服药提醒', path: '/family/medication' },
  { key: 'track',   icon: Clock,    label: '进度查看', tone: 'warning', desc: '看正在进行的订单',     path: '/family/order' }
]

/* 底部 Tab 由 NlAppTabBar 统一管理（角色感知 + 路由感知） */

function toggleElderly() {
  appStore.toggleElderlyMode()
  ElMessage.success(appStore.elderlyMode ? '已切换至老人模式' : '已退出老人模式')
}

function addElder() {
  router.push('/family/elder/bind')
}

function quickEnter(item) {
  router.push(item.path)
}

function viewOrder(o) {
  router.push(`/family/order/${o.id}`)
}

function createOrder() {
  router.push('/family/order/step1')
}

/** 桌面形态的顶栏操作区（老人模式开关在桌面端按 Q6 隐藏，故只剩消息入口） */
function goMessage() {
  router.push('/family/message')
}

onMounted(() => {
  loadElders()
  loadOrders()
  refreshUnread()
  unreadTimer = setInterval(refreshUnread, 60000)
})
onBeforeUnmount(() => {
  if (unreadTimer) clearInterval(unreadTimer)
})
</script>

<template>
  <!-- ==================== Mobile 形态：原手机版模板，未做任何视觉改动 ==================== -->
  <NlPhoneShell
    v-if="isMobile"
    :nav="{ title: '家属工作台', back: false }"
    :has-tabs="true"
  >
    <template #nav-right>
      <!-- 老人模式开关：Q6 = a+d，桌面形态下隐藏（桌面端禁用老人模式） -->
      <button class="fam-home__navbtn" title="切换老人模式" @click="toggleElderly">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="11" />
          <text x="12" y="16" text-anchor="middle" font-size="14" fill="currentColor" stroke="none" font-family="serif">大</text>
        </svg>
      </button>
      <button class="fam-home__navbtn" title="消息" @click="goMessage">
        <el-badge :value="unread" :hidden="unread === 0" :max="99">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M6 8a6 6 0 1 1 12 0v4l2 3H4l2-3V8z" />
            <path d="M10 19a2 2 0 0 0 4 0" />
          </svg>
        </el-badge>
      </button>
      <!-- 用户菜单（移动 NavBar 右侧）：头像 + 退出登录下拉。
           桌面形态下由 NlDesktopShell 统一提供，这里只补移动形态。 -->
      <NlUserMenu size="sm" :show-name="false" />
    </template>

    <!-- ==================== 老人横滑卡 ==================== -->
    <NlSkeleton v-if="eldersLoading" :count="3" />

    <div v-else-if="!elders.length" class="elder-track__guide">
      <NlEmpty
        type="empty"
        title="还没有绑定老人"
        description="绑定后可在首页一键切换就诊人，代其预约陪诊 / 管理用药"
        action-text="去绑定"
        @action="addElder"
      />
    </div>

    <section v-else class="elder-track">
      <ul class="elder-track__list">
        <li
          v-for="(e, i) in elders"
          :key="e.id"
          :class="['elder-track__item', { 'is-active': activeElder === i }]"
          @click="pickElder(i)"
        >
          <NlAvatar :fallback="e.name ? e.name.slice(0, 1) : '?'" :size="56" tone="primary" />
          <div class="elder-track__name">{{ e.name }}</div>
          <div class="elder-track__age">
            {{ e.genderLabel }}<template v-if="e.age != null"> · {{ e.age }} 岁</template>
          </div>
        </li>
        <li class="elder-track__item elder-track__add" @click="addElder">
          <div class="elder-track__addbox">
            <el-icon size="22"><Plus /></el-icon>
          </div>
          <div class="elder-track__name">添加</div>
        </li>
      </ul>
    </section>

    <!-- ==================== 三宫格 ==================== -->
    <NlCard plain>
      <ul class="quickgrid">
        <li v-for="q in quickEntries" :key="q.key" class="quickgrid__item" @click="quickEnter(q)">
          <NlIconBox :tone="q.tone" :size="48">
            <component :is="q.icon" />
          </NlIconBox>
          <div class="quickgrid__label">{{ q.label }}</div>
          <div class="quickgrid__desc">{{ q.desc }}</div>
        </li>
      </ul>
    </NlCard>

    <!-- ==================== 今日用药提醒 ==================== -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">今日用药提醒</span>
          <el-link type="primary" :underline="false" @click="router.push('/family/medication')">管理</el-link>
        </div>
      </template>

      <NlSkeleton v-if="eldersLoading" :count="2" />

      <NlEmpty
        v-else-if="!currentElder"
        type="empty"
        title="暂无就诊人"
        description="添加并绑定老人后，可在这里查看其今日服药提醒"
        action-text="去绑定"
        @action="addElder"
      />

      <template v-else>
        <NlNoticeBar tone="warning">
          仅作用药提醒与记录，系统不提供诊断或用药建议。
        </NlNoticeBar>

        <NlSkeleton v-if="tasksLoading" :count="2" />

        <ul v-else-if="tasks.length" class="task-list">
          <li v-for="t in tasks" :key="t.id" class="task">
            <div class="task__main">
              <div class="task__name">
                {{ t.medicineName }}
                <span v-if="t.dosage" class="nl-caption nl-text-muted">{{ t.dosage }}</span>
              </div>
              <div class="nl-caption nl-text-muted is-num">
                {{ formatVisitTime(t.planTime) }}<template v-if="t.mealRelationLabel"> · {{ t.mealRelationLabel }}</template>
              </div>
            </div>
            <NlStatusChip scope="task" :status="t.status" :text="t.statusLabel" />
          </li>
        </ul>

        <div v-else class="fam-home__empty">
          <span class="nl-caption nl-text-muted">今日暂无服药提醒</span>
        </div>
      </template>
    </NlCard>

    <!-- ==================== 最近订单 ==================== -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">最近订单</span>
          <el-link type="primary" :underline="false" @click="router.push('/family/order')">查看全部</el-link>
        </div>
      </template>

      <NlSkeleton v-if="ordersLoading" :count="2" />

      <ul v-else-if="orders.length" class="order-list">
        <li v-for="o in orders" :key="o.id" class="order-card" @click="viewOrder(o)">
          <div class="order-card__head">
            <NlStatusChip :status="o.status" :text="o.statusLabel" :dot="o.status === 'PENDING' || o.status === 'IN_SERVICE'" />
            <span class="is-num order-card__amt">{{ formatMoney(o.fee) }}</span>
          </div>
          <div class="order-card__body">
            <div class="order-card__hospital">{{ o.hospital }} · {{ o.department }}</div>
            <div class="nl-caption nl-text-muted is-num">{{ formatVisitTime(o.visitTime) }}</div>
            <div class="order-card__foot">
              <div v-if="o.companionName" class="order-card__comp">
                <NlAvatar :fallback="o.companionName.slice(0, 1)" :size="24" tone="warning" />
                <span class="nl-caption">{{ o.companionName }}</span>
              </div>
              <span v-else class="nl-caption nl-text-muted">尚无陪诊员接单</span>
              <span class="order-card__chev">›</span>
            </div>
          </div>
        </li>
      </ul>

      <div v-else class="fam-home__empty">
        <NlStatusChip tone="neutral" text="暂无订单" />
        <el-button type="primary" round size="small" class="mt-3" @click="createOrder">去下单</el-button>
      </div>
    </NlCard>

    <!-- ==================== 关爱之家 banner ==================== -->
    <section class="love-banner">
      <div class="love-banner__title">关爱家人，从陪诊开始</div>
      <ul class="love-banner__tags">
        <li>专业</li>
        <li>安心</li>
        <li>便捷</li>
      </ul>
      <p class="love-banner__sub">专业陪诊员 · 全程 6 节点打卡 · 家属端实时进度</p>
    </section>

    <template #tabbar>
      <NlAppTabBar />
    </template>
  </NlPhoneShell>

  <!-- ==================== Desktop 形态（ADR-0007 / 计划 Stage 1） ====================
       内容宽度由 NlDesktopShell 统一限制为 720px 居中（Q11），此处不写 width。
       loading / empty / error 仍由本 view 自管（Q10 = ③）。
  ============================================================================== -->
  <NlDesktopShell v-else>
    <template #actions>
      <el-button text @click="goMessage">
        <el-badge :value="unread" :hidden="unread === 0" :max="99">
          <el-icon size="20"><Bell /></el-icon>
        </el-badge>
        <span class="desktop-actions__label">消息</span>
      </el-button>
      <el-button type="primary" round :icon="Plus" @click="createOrder">新增订单</el-button>
      <!-- 用户菜单（桌面顶栏最右侧）由 NlDesktopShell 自动注入，无需在此声明。 -->
    </template>

    <!-- 就诊人切换 -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">当前就诊人</span>
          <el-link type="primary" :underline="false" @click="router.push('/family/elder')">
            管理老人
          </el-link>
        </div>
      </template>

      <NlSkeleton v-if="eldersLoading" :count="2" />

      <NlEmpty
        v-else-if="!elders.length"
        type="empty"
        title="还没有绑定老人"
        description="绑定后可按老人维度预约陪诊、管理用药"
        action-text="去绑定"
        @action="addElder"
      />

      <ul v-else class="desktop-elders">
        <li
          v-for="(e, i) in elders"
          :key="e.id"
          :class="['desktop-elders__item', { 'is-active': activeElder === i }]"
          @click="pickElder(i)"
        >
          <NlAvatar :fallback="e.name ? e.name.slice(0, 1) : '?'" :size="40" tone="primary" />
          <div class="desktop-elders__meta">
            <div class="desktop-elders__name">{{ e.name }}</div>
            <div class="nl-caption nl-text-muted">
              {{ e.genderLabel }}<template v-if="e.age != null"> · {{ e.age }} 岁</template>
            </div>
          </div>
        </li>
        <li class="desktop-elders__item desktop-elders__add" @click="addElder">
          <el-icon size="18"><Plus /></el-icon>
          <span class="nl-caption">添加老人</span>
        </li>
      </ul>
    </NlCard>

    <!-- 快捷入口 -->
    <NlCard>
      <template #title><span class="nl-h2">快捷入口</span></template>
      <ul class="desktop-quick">
        <li v-for="q in quickEntries" :key="q.key" class="desktop-quick__item" @click="quickEnter(q)">
          <NlIconBox :tone="q.tone" :size="40">
            <component :is="q.icon" />
          </NlIconBox>
          <div class="desktop-quick__text">
            <div class="desktop-quick__label">{{ q.label }}</div>
            <div class="nl-caption nl-text-muted">{{ q.desc }}</div>
          </div>
        </li>
      </ul>
    </NlCard>

    <!-- 今日用药提醒 -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">今日用药提醒</span>
          <el-link type="primary" :underline="false" @click="router.push('/family/medication')">管理</el-link>
        </div>
      </template>

      <NlNoticeBar tone="warning">
        仅作用药提醒与记录，系统不提供诊断或用药建议。
      </NlNoticeBar>

      <NlSkeleton v-if="eldersLoading || tasksLoading" :count="2" />

      <NlEmpty
        v-else-if="!currentElder"
        type="empty"
        title="暂无就诊人"
        description="添加并绑定老人后，可在这里查看其今日服药提醒"
        action-text="去绑定"
        @action="addElder"
      />

      <ul v-else-if="tasks.length" class="task-list">
        <li v-for="t in tasks" :key="t.id" class="task">
          <div class="task__main">
            <div class="task__name">
              {{ t.medicineName }}
              <span v-if="t.dosage" class="nl-caption nl-text-muted">{{ t.dosage }}</span>
            </div>
            <div class="nl-caption nl-text-muted is-num">
              {{ formatVisitTime(t.planTime) }}<template v-if="t.mealRelationLabel"> · {{ t.mealRelationLabel }}</template>
            </div>
          </div>
          <NlStatusChip scope="task" :status="t.status" :text="t.statusLabel" />
        </li>
      </ul>

      <div v-else class="fam-home__empty">
        <span class="nl-caption nl-text-muted">今日暂无服药提醒</span>
      </div>
    </NlCard>

    <!-- 最近订单：桌面用横向信息行，不是手机卡片的等比放大 -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">最近订单</span>
          <el-link type="primary" :underline="false" @click="router.push('/family/order')">查看全部</el-link>
        </div>
      </template>

      <NlSkeleton v-if="ordersLoading" :count="2" />

      <ul v-else-if="orders.length" class="desktop-orders">
        <li v-for="o in orders" :key="o.id" class="desktop-orders__row" @click="viewOrder(o)">
          <NlStatusChip
            :status="o.status"
            :text="o.statusLabel"
            :dot="o.status === 'PENDING' || o.status === 'IN_SERVICE'"
          />
          <div class="desktop-orders__main">
            <div class="desktop-orders__hospital">{{ o.hospital }} · {{ o.department }}</div>
            <div class="nl-caption nl-text-muted is-num">{{ formatVisitTime(o.visitTime) }}</div>
          </div>
          <div class="desktop-orders__comp">
            <template v-if="o.companionName">
              <NlAvatar :fallback="o.companionName.slice(0, 1)" :size="24" tone="warning" />
              <span class="nl-caption">{{ o.companionName }}</span>
            </template>
            <span v-else class="nl-caption nl-text-muted">待接单</span>
          </div>
          <span class="is-num desktop-orders__amt">{{ formatMoney(o.fee) }}</span>
        </li>
      </ul>

      <div v-else class="fam-home__empty">
        <NlStatusChip tone="neutral" text="暂无订单" />
        <el-button type="primary" round size="small" class="mt-3" @click="createOrder">去下单</el-button>
      </div>
    </NlCard>

    <!-- 合规与说明 -->
    <NlNoticeBar tone="info">
      银龄伴诊提供陪诊与用药协同管理，不做诊断、不开药方；一期支持线上记账、线下结算。
    </NlNoticeBar>
  </NlDesktopShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.fam-home {
  &__navbtn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: var(--nl-touch-min);
    height: var(--nl-touch-min);
    color: var(--nl-text-1);
    background: transparent;
    border: none;
    border-radius: 50%;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;

    &:active {
      background: var(--nl-primary-ghost);
    }
  }

  &__empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: var(--nl-space-3);
  }
}

.elder-track {
  margin: 0 0 var(--nl-gap-section);
  /* ⚠️ 修复既有缺陷：原写法 `margin: 0 calc(var(--nl-space-2) - var(--nl-gutter))`
     等于左右各 -8px 出血，在 390 宽屏上把 document.scrollWidth 撑到 398，
     产生 8px 横向滚动（已用 git stash 回改动前复测确认是本轮之前就有的问题）。
     改为「分区只负责装订线，滚动仍由 __list 承担」：宽度不再超出屏幕，
     且首项左边缘正好落在装订线（--nl-gutter = 16px）上。
     注意别把 overflow-x 挪到本分区 —— 那样 __list 就不再是滚动容器，
     横滑会失效（实测过）。 */
  padding: 0 var(--nl-gutter);

  &__list {
    display: flex;
    gap: var(--nl-space-3);
    padding: 0;
    margin: 0;
    overflow-x: auto;
    list-style: none;
    scrollbar-width: none;

    &::-webkit-scrollbar {
      display: none;
    }
  }

  &__item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
    flex-shrink: 0;
    padding: var(--nl-space-3) var(--nl-space-2);
    width: 96px;
    background: var(--nl-bg-card);
    border: 1.5px solid transparent;
    border-radius: var(--nl-radius-card);
    cursor: pointer;
    box-shadow: var(--nl-shadow-card);
    transition: border-color 0.18s;

    &.is-active {
      border-color: var(--nl-primary);
      background: var(--nl-primary-ghost);
    }
  }

  &__name {
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__age {
    font-size: 12px;
    color: var(--nl-text-3);
  }

  &__guide {
    /* 装订线内边距已上移到 .elder-track，此处不再重复加，否则会变双倍缩进 */
    padding: 0;
  }

  &__add {
    background: var(--nl-bg-sunken);
    border-style: dashed;

    &:active {
      background: var(--nl-primary-ghost);
    }
  }

  &__addbox {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 56px;
    height: 56px;
    color: var(--nl-primary);
    background: var(--nl-bg-card);
    border-radius: 50%;
    box-shadow: var(--nl-shadow-card);
  }
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--nl-space-2);
}

.quickgrid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-2);
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
    justify-content: center;
    padding: var(--nl-space-3) 0;
    text-align: center;
    cursor: pointer;

    &:active {
      opacity: 0.7;
    }
  }

  &__label {
    margin-top: 6px;
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__desc {
    font-size: 11px;
    color: var(--nl-text-3);
  }
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-2);
  padding: 0;
  margin: var(--nl-space-2) 0 0;
  list-style: none;
}

.task {
  display: flex;
  gap: var(--nl-space-2);
  align-items: center;
  justify-content: space-between;
  padding: var(--nl-space-3);
  background: var(--nl-bg-sunken);
  border-radius: var(--nl-radius-card);

  &__name {
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__main {
    min-width: 0;
  }
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.order-card {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  cursor: pointer;
  transition: box-shadow 0.18s, transform 0.18s;

  &:active {
    transform: scale(0.99);
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--nl-space-2);
  }

  &__amt {
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-primary);
  }

  &__body {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__hospital {
    font-size: 15px;
    font-weight: 500;
    color: var(--nl-text-1);
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--nl-space-2);
  }

  &__comp {
    display: flex;
    gap: 6px;
    align-items: center;
    color: var(--nl-text-2);
  }

  &__chev {
    font-size: 20px;
    color: var(--nl-text-3);
  }
}

.love-banner {
  padding: var(--nl-space-4);
  background: linear-gradient(135deg, var(--nl-primary-light) 0%, var(--nl-primary-ghost) 100%);
  border-radius: var(--nl-radius-card);

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: var(--nl-primary-dark, var(--nl-primary));
  }

  &__tags {
    display: flex;
    gap: 6px;
    padding: 0;
    margin: var(--nl-space-2) 0 0;
    list-style: none;

    li {
      padding: 2px 10px;
      font-size: 11px;
      font-weight: 500;
      color: var(--nl-primary);
      background: rgba(255, 255, 255, 0.8);
      border-radius: 999px;
    }
  }

  &__sub {
    margin: var(--nl-space-2) 0 0;
    font-size: 12px;
    color: var(--nl-text-2);
  }
}

.mt-3 {
  margin-top: 12px;
}

/* ==========================================================================
   Desktop 形态专用样式（>= 768px 才渲染，故不做媒体查询）
   宽度一律交给 NlDesktopShell 的 720px 容器，这里只排版。
   ========================================================================== */
.desktop-actions {
  &__label {
    margin-left: 4px;
  }
}

.desktop-elders {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
    padding: var(--nl-space-2) var(--nl-space-3);
    cursor: pointer;
    background: var(--nl-bg-sunken);
    border: 1.5px solid transparent;
    border-radius: var(--nl-radius-card);
    transition: border-color 0.18s;

    &.is-active {
      background: var(--nl-primary-ghost);
      border-color: var(--nl-primary);
    }
  }

  &__meta {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  &__name {
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__add {
    color: var(--nl-primary);
    border-style: dashed;
    border-color: var(--nl-border-strong);
  }
}

.desktop-quick {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
    padding: var(--nl-space-3);
    cursor: pointer;
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    transition: box-shadow 0.18s;

    &:hover {
      box-shadow: var(--nl-shadow-hover);
    }
  }

  &__text {
    min-width: 0;
  }

  &__label {
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
  }
}

.desktop-orders {
  display: flex;
  flex-direction: column;
  padding: 0;
  margin: 0;
  list-style: none;

  &__row {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
    padding: var(--nl-space-3) 0;
    cursor: pointer;
    border-bottom: 1px solid var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }

    &:hover {
      background: var(--nl-primary-ghost);
    }
  }

  &__main {
    flex: 1;
    min-width: 0;
  }

  &__hospital {
    overflow: hidden;
    font-size: 14px;
    font-weight: 500;
    color: var(--nl-text-1);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__comp {
    display: flex;
    flex-shrink: 0;
    gap: 6px;
    align-items: center;
    color: var(--nl-text-2);
  }

  &__amt {
    flex-shrink: 0;
    min-width: 72px;
    font-size: 16px;
    font-weight: 700;
    color: var(--nl-primary);
    text-align: right;
  }
}
</style>
