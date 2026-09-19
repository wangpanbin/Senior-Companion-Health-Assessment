<script setup>
/**
 * 老人端首页（M-04 / M-05 · design.md §4）
 *
 * 聚合展示，只读。数据来源：
 *  - getProfile()            → 当前老人自己的脱敏资料（姓名 / 头像 / 角色）
 *  - listMyOrders()          → 老人作为就诊人的最近订单（只读，无写入口）
 *  - getTodayTasks(elderId)  → 今日服药提醒（需 elderId）
 *
 * ⚠️ ELDER 默认只读：本页不渲染任何写操作入口（无「确认服药 / 编辑 / 删除」）。
 * ⚠️ 后端 NON_NULL：空字段会从 JSON 消失，模板已用可选链 / != null 兜底。
 * ⚠️ elderId 由 `/user/profile`（UserInfoVO）直接下发，仅 ELDER 且已建立档案时有值；
 *    其他角色 / 未建档的 ELDER 该字段不存在（NON_NULL）。为空时页面降级为入口引导空态。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { FirstAidKit, Bell } from '@element-plus/icons-vue'
import {
  NlPhoneShell, NlDesktopShell, NlAppTabBar, NlCard, NlAvatar, NlStatusChip,
  NlSkeleton, NlEmpty, NlNoticeBar, NlUserMenu
} from '@/components'
import { useAppStore } from '@/store/modules/app'
import { useUserStore } from '@/store/modules/user'
import { useDevice } from '@/composables/useDevice'
import { getProfile } from '@/api/user'
import { listMyOrders } from '@/api/order'
import { getTodayTasks } from '@/api/medication'
import { formatVisitTime, formatMoney, formatTime } from '@/utils/format'

const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()

/** 形态判定（ADR-0007）：窄屏走手机壳，宽屏走桌面壳 */
const { isMobile } = useDevice()

/** 当前时段问候语（客户端本地时间，非解析后端字符串） */
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const today = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getMonth() + 1}月${d.getDate()}日 · 星期${week}`
})

/* ===== 当前老人资料（真实，来自 getProfile） ===== */
const profile = ref({})
const displayName = computed(() => profile.value?.nickname || userStore.nickname || '我')
const avatarFallback = computed(() => displayName.value.slice(0, 1))
// 老人自己的档案 id：直接来自 /user/profile 下发的 elderId（仅 ELDER 有值，否则为 null）
const elderId = computed(() => profile.value?.elderId ?? null)
const hasElderId = computed(() => elderId.value != null)

/* ===== 今日服药提醒（需 elderId，无则降级为入口引导） ===== */
const medLoading = ref(false)
const todayMeds = ref([])
const medEmpty = computed(() => !medLoading.value && !hasElderId.value)
const medNoData = computed(() => !medLoading.value && hasElderId.value && todayMeds.value.length === 0)

/* ===== 最近订单（只读） ===== */
const orderLoading = ref(false)
const recentOrders = ref([])

async function loadProfile() {
  try {
    const data = await getProfile()
    if (data) profile.value = data
  } catch {
    // 拦截器已弹错，这里不再重复弹
  }
}

async function loadTodayMeds() {
  if (!hasElderId.value) return // 无法取得自身 elderId，降级为入口引导
  medLoading.value = true
  try {
    todayMeds.value = (await getTodayTasks(elderId.value)) || []
  } catch {
    todayMeds.value = []
  } finally {
    medLoading.value = false
  }
}

async function loadRecentOrders() {
  orderLoading.value = true
  try {
    const data = await listMyOrders({ page: 1, size: 5 })
    recentOrders.value = data?.records || []
  } catch {
    recentOrders.value = []
  } finally {
    orderLoading.value = false
  }
}

onMounted(async () => {
  // elderId 来自 profile，必须先拿到 profile 才能决定要不要加载今日用药；
  // 所以先 await loadProfile()，再并行 loadTodayMeds 与 loadRecentOrders
  await loadProfile()
  await Promise.all([loadTodayMeds(), loadRecentOrders()])
})

/* ===== 底部 Tab 由 NlAppTabBar 统一管理（角色感知 + 路由感知） ===== */
function toggleElderly() {
  appStore.toggleElderlyMode()
  // 不弹 ElMessage（保持原交互，避免引入额外依赖）
}

function goMessage() {
  router.push('/elder/message')
}

function goMedication() {
  router.push('/elder/medication')
}
</script>

<template>
  <!-- ==================== Mobile 形态：原手机版模板，未做任何视觉改动 ==================== -->
  <NlPhoneShell
    v-if="isMobile"
    :nav="{ title: '银龄伴诊', back: false }"
    :has-tabs="true"
    :status-time="today"
  >
    <template #nav-right>
      <button class="elder-home__navbtn" :title="appStore.elderlyMode ? '退出老人模式' : '开启老人模式'" @click="toggleElderly">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <text x="12" y="16" text-anchor="middle" font-size="14" fill="currentColor" stroke="none" font-family="serif">大</text>
          <circle cx="12" cy="12" r="11" />
        </svg>
      </button>
      <button class="elder-home__navbtn" title="消息" @click="goMessage">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <path d="M6 8a6 6 0 1 1 12 0v4l2 3H4l2-3V8z" />
          <path d="M10 19a2 2 0 0 0 4 0" />
        </svg>
      </button>
      <!-- 用户菜单：头像 + 退出登录下拉。桌面形态下由 NlDesktopShell 统一提供。 -->
      <NlUserMenu size="sm" :show-name="false" />
    </template>

    <!-- ==================== 问候卡 ==================== -->
    <NlCard class="greeting">
      <div class="greeting__inner">
        <NlAvatar :src="profile?.avatar" :fallback="avatarFallback" :size="56" tone="success" />
        <div class="greeting__body">
          <h2 class="nl-h2 greeting__title">{{ displayName }}，{{ greeting }}</h2>
          <p class="nl-caption greeting__date">{{ today }}</p>
        </div>
      </div>
    </NlCard>

    <!-- ==================== 今日服药提醒 ==================== -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">今日服药提醒</span>
          <span v-if="hasElderId && todayMeds.length" class="nl-caption">{{ todayMeds.length }} 项</span>
        </div>
      </template>

      <p class="nl-caption nl-text-muted elder-home__readonly-tip">
        您正在使用「老人模式」只读视图，服药确认请由您的家属操作。
      </p>

      <NlSkeleton v-if="medLoading" :count="3" />

      <ul v-else-if="hasElderId && todayMeds.length" class="med-list">
        <li v-for="m in todayMeds" :key="m.id" class="med-item">
          <div class="med-item__time is-num">{{ formatTime(m.planTime) }}</div>
          <div class="med-item__body">
            <div class="med-item__row">
              <span class="nl-h3">{{ m.medicineName }}</span>
              <NlStatusChip scope="task" :status="m.status" :text="m.statusLabel" :dot="m.status === 'PENDING'" />
            </div>
            <p class="nl-caption nl-text-muted">{{ m.dosage }}<template v-if="m.mealRelationLabel"> · {{ m.mealRelationLabel }}</template></p>
          </div>
        </li>
      </ul>

      <NlEmpty
        v-else-if="medEmpty"
        type="empty"
        title="暂无用药提醒"
        description="您的用药计划由家属代为维护，可前往「用药管理」查看"
        action-text="去查看"
        @action="router.push('/elder/medication')"
      />
      <NlEmpty
        v-else-if="medNoData"
        type="empty"
        title="今日无服药安排"
        description="家属暂未为您安排今天的服药计划"
      />
    </NlCard>

    <!-- ==================== 最近陪诊订单（只读） ==================== -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">最近陪诊</span>
          <span v-if="recentOrders.length" class="nl-caption">共 {{ recentOrders.length }} 单</span>
        </div>
      </template>

      <NlSkeleton v-if="orderLoading" :count="3" />

      <ul v-else-if="recentOrders.length" class="order-list">
        <li v-for="o in recentOrders" :key="o.id" class="order-item">
          <div class="order-item__head">
            <NlStatusChip :status="o.status" :text="o.statusLabel" :dot="o.status === 'PENDING' || o.status === 'IN_SERVICE'" />
            <span class="order-item__fee is-num">{{ formatMoney(o.fee) }}</span>
          </div>
          <div class="order-item__hospital">{{ o.hospital }}</div>
          <div class="nl-caption nl-text-muted order-item__meta">
            {{ o.department }}<template v-if="o.elderAge != null"> · {{ o.elderAge }}岁</template> · {{ formatVisitTime(o.visitTime) }}
          </div>
          <div v-if="o.companionName" class="nl-caption nl-text-weak">陪诊员：{{ o.companionName }}</div>
        </li>
      </ul>

      <NlEmpty
        v-else
        type="empty"
        title="暂无陪诊订单"
        description="您的家属会为您预约陪诊服务"
      />
    </NlCard>

    <template #tabbar>
      <NlAppTabBar />
    </template>
  </NlPhoneShell>

  <!-- ==================== Desktop 形态（ADR-0007 · Q7 = Ⅱ ELDER 2 个高频页之一） ====================
       ELDER 保持**只读**（AGENTS.md §4.3）：桌面版同样不出现任何写操作入口。
       内容宽度由 NlDesktopShell 统一限制为 720px 居中。
  ============================================================================================ -->
  <NlDesktopShell v-else>
    <template #actions>
      <el-button text :icon="FirstAidKit" @click="goMedication">用药管理</el-button>
      <el-button text @click="goMessage">
        <el-icon size="20"><Bell /></el-icon>
        <span class="desktop-actions__label">消息</span>
      </el-button>
    </template>

    <NlNoticeBar tone="info">
      您正在使用只读视图：本页只展示信息，服药确认与预约陪诊请由家属操作。
    </NlNoticeBar>

    <!-- 问候卡 -->
    <NlCard class="greeting">
      <div class="greeting__inner">
        <NlAvatar :src="profile?.avatar" :fallback="avatarFallback" :size="56" tone="success" />
        <div class="greeting__body">
          <h2 class="nl-h2 greeting__title">{{ displayName }}，{{ greeting }}</h2>
          <p class="nl-caption greeting__date">{{ today }}</p>
        </div>
      </div>
    </NlCard>

    <!-- 今日服药提醒 -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">今日服药提醒</span>
          <span v-if="hasElderId && todayMeds.length" class="nl-caption">{{ todayMeds.length }} 项</span>
        </div>
      </template>

      <NlSkeleton v-if="medLoading" :count="3" />

      <ul v-else-if="hasElderId && todayMeds.length" class="med-list">
        <li v-for="m in todayMeds" :key="m.id" class="med-item">
          <div class="med-item__time is-num">{{ formatTime(m.planTime) }}</div>
          <div class="med-item__body">
            <div class="med-item__row">
              <span class="nl-h3">{{ m.medicineName }}</span>
              <NlStatusChip scope="task" :status="m.status" :text="m.statusLabel" :dot="m.status === 'PENDING'" />
            </div>
            <p class="nl-caption nl-text-muted">{{ m.dosage }}<template v-if="m.mealRelationLabel"> · {{ m.mealRelationLabel }}</template></p>
          </div>
        </li>
      </ul>

      <NlEmpty
        v-else-if="medEmpty"
        type="empty"
        title="暂无用药提醒"
        description="您的用药计划由家属代为维护，可前往「用药管理」查看"
        action-text="去查看"
        @action="goMedication"
      />
      <NlEmpty
        v-else-if="medNoData"
        type="empty"
        title="今日无服药安排"
        description="家属暂未为您安排今天的服药计划"
      />
    </NlCard>

    <!-- 最近陪诊订单（只读）：桌面用横向信息行 -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">最近陪诊</span>
          <span v-if="recentOrders.length" class="nl-caption">共 {{ recentOrders.length }} 单</span>
        </div>
      </template>

      <NlSkeleton v-if="orderLoading" :count="3" />

      <ul v-else-if="recentOrders.length" class="desktop-orders">
        <li v-for="o in recentOrders" :key="o.id" class="desktop-orders__row">
          <NlStatusChip
            :status="o.status"
            :text="o.statusLabel"
            :dot="o.status === 'PENDING' || o.status === 'IN_SERVICE'"
          />
          <div class="desktop-orders__main">
            <div class="desktop-orders__hospital">{{ o.hospital }}</div>
            <div class="nl-caption nl-text-muted is-num">
              {{ o.department }}<template v-if="o.elderAge != null"> · {{ o.elderAge }}岁</template> · {{ formatVisitTime(o.visitTime) }}
            </div>
          </div>
          <span class="nl-caption nl-text-weak desktop-orders__comp">
            {{ o.companionName ? `陪诊员：${o.companionName}` : '待接单' }}
          </span>
          <span class="is-num desktop-orders__fee">{{ formatMoney(o.fee) }}</span>
        </li>
      </ul>

      <NlEmpty
        v-else
        type="empty"
        title="暂无陪诊订单"
        description="您的家属会为您预约陪诊服务"
      />
    </NlCard>
  </NlDesktopShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.elder-home {
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

    &:active {
      background: var(--nl-primary-ghost);
    }
  }

  &__readonly-tip {
    margin: 0 0 var(--nl-space-3);
    padding: var(--nl-space-2) var(--nl-space-3);
    background: var(--nl-warning-bg);
    border-radius: 8px;
  }
}

.greeting {
  background: linear-gradient(135deg, var(--nl-primary-ghost) 0%, #f7faff 100%);
  border: 1px solid var(--nl-primary-light);

  &__inner {
    display: flex;
    align-items: center;
    gap: var(--nl-space-4);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    margin: 0;
  }

  &__date {
    margin-top: 2px;
  }
}

.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--nl-space-2);
}

.med-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
}

.med-item {
  display: flex;
  align-items: center;
  gap: var(--nl-space-3);
  padding: var(--nl-space-3);
  background: var(--nl-bg-sunken);
  border-radius: 12px;

  &__time {
    flex-shrink: 0;
    width: 56px;
    padding: 6px 0;
    font-size: 16px;
    font-weight: 600;
    color: var(--nl-primary);
    text-align: center;
    background: var(--nl-primary-light);
    border-radius: 8px;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }
}

.order-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
}

.order-item {
  padding: var(--nl-space-3);
  background: var(--nl-bg-sunken);
  border-radius: 12px;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--nl-space-2);
  }

  &__fee {
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-primary);
  }

  &__hospital {
    font-size: 15px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__meta {
    margin-top: 4px;
  }
}

/* ==========================================================================
   Desktop 形态专用样式（>= 768px 才渲染，故不做媒体查询）
   宽度交给 NlDesktopShell 的 720px 容器。
   ========================================================================== */
.desktop-actions {
  &__label {
    margin-left: 4px;
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
    border-bottom: 1px solid var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }
  }

  &__main {
    flex: 1;
    min-width: 0;
  }

  &__hospital {
    overflow: hidden;
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__comp {
    flex-shrink: 0;
  }

  &__fee {
    flex-shrink: 0;
    min-width: 72px;
    font-size: 16px;
    font-weight: 700;
    color: var(--nl-primary);
    text-align: right;
  }
}
</style>
