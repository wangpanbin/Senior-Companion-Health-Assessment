<script setup>
/**
 * 接单大厅（M-10 · design.md §4 · PRD §4.1 陪诊员主旅程）
 *
 * 数据来源：
 *   - GET /order/hall        → listOrderHall（待接单订单，sortBy 决定排序维度）
 *   - POST /order/{id}/accept → acceptOrder（接单，乐观锁）
 *   - GET /user/companion/application → getMyCompanionApplication（资质状态）
 *
 * 排序口径（V3.4）：可选维度由后端白名单决定（RECOMMEND / DISTANCE / TIME_MATCH），
 *   切换维度 = 重新向后端要数，前端不对已有数据自行排序。
 *
 * 资质门槛（重点）：
 *   - 仅 COMPANION 且资质 APPROVED 才能进大厅 / 接单；否则后端返回 2003。
 *   - 前端先拉资质状态，未通过直接整页拦截，不让用户点了才吃 403/2003。
 *   - 接单为乐观锁：并发下仅 1 次成功；失败由拦截器弹错，本页只刷新列表，不再重复弹。
 *
 * 列表口径：大厅会返回医院地址（address），但不返回距离/医院等级，故页面不展示这两项。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  NlPhoneShell, NlDesktopShell, NlAppTabBar, NlCard, NlStatusChip, NlEmpty, NlSkeleton,
  NlUserMenu
} from '@/components'
import { useDevice } from '@/composables/useDevice'
import { listOrderHall, acceptOrder } from '@/api/order'
import { getMyCompanionApplication, getCompanionProfile, updateCompanionWorkStatus } from '@/api/user'
import { useUserStore } from '@/store/modules/user'
import { formatVisitTime, formatMoney } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()
const myId = computed(() => userStore.userInfo?.id)

/** 形态判定（ADR-0007）：窄屏走手机壳，宽屏走桌面壳 */
const { isMobile } = useDevice()

/** 资质状态：null=从未申请 / PENDING / APPROVED / REJECTED */
const app = ref(null)
const certStatus = ref(null)
const ready = ref(false)

const orders = ref([])
const total = ref(0)
const loading = ref(false)
/** R4：大厅列表加载失败（与「附近暂无需求」空态区分） */
const hallError = ref(false)

const filter = ref('all') // all / high

/* ================================================================== *
 * 大厅排序（V3.4 · T9）
 *
 * 可选维度由**后端白名单**决定：RECOMMEND / DISTANCE / TIME_MATCH。
 * INCOME 本版仍不做 —— 后端对没有真实结算金额口径的维度返回 400，
 * 所以前端干脆不提供该选项（给一个必然报错的选项比不给更糟）。
 *
 * 切换排序 = 重新向后端要数（把 sortBy 传给 listOrderHall），
 * 前端绝不对已有数据自行排序 —— 那会把后端的五维加权口径直接作废。
 * ================================================================== */
const SORT_OPTIONS = [
  { value: 'RECOMMEND', label: '推荐' },
  { value: 'DISTANCE', label: '距离最近' },
  { value: 'TIME_MATCH', label: '时间匹配' }
]

const sortBy = ref('RECOMMEND')
/** 大厅当前一次性取一页（size 50）；排序切换时显式回到第 1 页 */
const page = ref(1)
const PAGE_SIZE = 50

/* ================================================================== *
 * 接单状态自助开关（V3.1 · T0）
 *
 * 语义边界：切换只影响**新订单的匹配**，不影响已接订单。
 * 已接单 / 服务中的订单继续按订单状态机推进，不会因为陪诊员切到
 * 「休息中」而被中断或取消 —— 页面文案也据此措辞，不写「停止服务」。
 *
 * 前端禁用按钮不构成安全边界：服务端仍会二次校验资质与状态值。
 * ================================================================== */

/** null=加载中 / AVAILABLE / REST / OFFLINE */
const workStatus = ref(null)
const workStatusSaving = ref(false)

const WORK_STATUS_OPTIONS = [
  { value: 'AVAILABLE', label: '接单中' },
  { value: 'REST', label: '休息中' },
  { value: 'OFFLINE', label: '离线' }
]

/** 当前是否接受新订单；与后端 WorkStatus.isAccepting 同源 */
const isAccepting = computed(() => workStatus.value === 'AVAILABLE')

const workStatusHint = computed(() => {
  if (workStatus.value === 'REST') return '暂停接单，不影响已接的订单'
  if (workStatus.value === 'OFFLINE') return '当前不接新订单，不影响已接的订单'
  return ''
})

async function loadWorkStatus() {
  if (!myId.value) return
  const data = await getCompanionProfile(myId.value)
  workStatus.value = data?.workStatus ?? null
}

async function switchWorkStatus(target) {
  if (workStatusSaving.value || target === workStatus.value) return
  workStatusSaving.value = true
  try {
    const data = await updateCompanionWorkStatus(target)
    // 就地回填，不必再发一次查询
    workStatus.value = data?.workStatus ?? target
    ElMessage.success(`已切换为「${data?.workStatusLabel ?? target}」`)
  } catch {
    // 拦截器已经弹过错误消息，这里不重复提示；workStatus 保持原值
  } finally {
    workStatusSaving.value = false
  }
}

/* 底部 Tab 由 NlAppTabBar 统一管理（角色感知 + 路由感知） */

const certText = computed(() => {
  if (certStatus.value === 'APPROVED') return '资质已通过 · 可接单'
  if (certStatus.value === 'PENDING') return '资质审核中 · 暂不可接单'
  if (certStatus.value === 'REJECTED') return '资质未通过审核'
  return '尚未提交陪诊员资质'
})

const filtered = computed(() => {
  if (filter.value === 'high') return orders.value.filter((o) => Number(o.fee) >= 150)
  return orders.value
})

const isBlocked = computed(() => ready.value && certStatus.value !== 'APPROVED')

/** 未通过拦截态：驳回展示后端 rejectReason，未申请/审核中引导去提交 */
const blockTitle = computed(() =>
  certStatus.value === 'REJECTED' ? '资质未通过审核' : '您还未通过陪诊员资质认证'
)
const blockDesc = computed(() => {
  if (certStatus.value === 'REJECTED') return app.value?.rejectReason || '请联系管理员了解原因'
  if (certStatus.value === 'PENDING') return '审核通过前不可接单，结果将通过站内信通知'
  return '通过后可查看接单大厅、抢单结算'
})

async function loadCert() {
  const data = await getMyCompanionApplication()
  app.value = data ?? null
  certStatus.value = data?.auditStatus ?? null
}

async function loadHall() {
  loading.value = true
  try {
    const data = await listOrderHall({
      page: page.value,
      size: PAGE_SIZE,
      sortBy: sortBy.value
    })
    orders.value = data?.records || []
    total.value = data?.total || 0
    hallError.value = false
  } catch {
    orders.value = []
    total.value = 0
    hallError.value = true
  } finally {
    loading.value = false
  }
}

/** 切换排序维度：回到第 1 页并重新向后端要数（不在前端重排已有数据） */
function switchSort(value) {
  if (value === sortBy.value) return
  sortBy.value = value
  page.value = 1
  loadHall()
}

async function grab(o) {
  if (certStatus.value !== 'APPROVED') {
    ElMessage.warning('资质未通过，暂不可接单')
    return
  }
  try {
    await acceptOrder(o.id)
    ElMessage.success('接单成功，前往订单执行')
    router.push(`/companion/execute/${o.id}`)
  } catch {
    // 拦截器已弹错（乐观锁 3003 / 资质 2003 等）；刷新列表即可
    loadHall()
  }
}

function goEntry() {
  router.push('/companion/entry')
}

onMounted(async () => {
  try {
    await loadCert()
    await loadWorkStatus()
  } catch {
    // 拦截器已弹错
  } finally {
    ready.value = true
  }
  if (certStatus.value === 'APPROVED') {
    loadHall()
  }
})
</script>

<template>
  <!-- ==================== Mobile 形态：原手机版模板，未做任何视觉改动 ==================== -->
  <NlPhoneShell
    v-if="isMobile"
    :nav="{ title: '接单大厅', back: false }"
    :has-tabs="true"
  >
    <template #nav-right>
      <!-- 用户菜单：头像 + 退出登录下拉。桌面形态下由 NlDesktopShell 统一提供。 -->
      <NlUserMenu size="sm" :show-name="false" />
    </template>
    <!-- 初始加载骨架 -->
    <NlSkeleton v-if="!ready" :count="3" class="list-pad" />

    <!-- 资质未通过拦截态 -->
    <NlEmpty
      v-else-if="isBlocked"
      type="empty"
      :title="blockTitle"
      :description="blockDesc"
      action-text="去提交资质"
      @action="goEntry"
    />

    <!-- 资质通过：筛选 + 订单卡 -->
    <template v-else>
      <!-- 资质状态条 -->
      <section class="cert-bar">
        <div class="cert-bar__left">
          <NlStatusChip scope="audit" :status="certStatus" :text="certText" :dot="certStatus === 'APPROVED'" />
        </div>
        <div class="cert-bar__right">
          <span class="nl-caption" :class="isAccepting ? 'nl-text-muted' : 'nl-text-weak'">
            {{ isAccepting ? '接单中' : '暂停接单' }}
          </span>
        </div>
      </section>

      <!-- 接单状态开关：点击即切换，仅在资质通过时可用 -->
      <section class="work-switch">
        <div class="work-switch__head">
          <span class="nl-caption nl-text-muted">我的接单状态</span>
          <span v-if="workStatusHint" class="nl-caption nl-text-weak">{{ workStatusHint }}</span>
        </div>
        <div class="work-switch__opts" role="group" aria-label="接单状态">
          <button
            v-for="opt in WORK_STATUS_OPTIONS"
            :key="opt.value"
            type="button"
            class="work-switch__btn"
            :class="{ 'is-active': workStatus === opt.value }"
            :disabled="workStatusSaving"
            :aria-pressed="workStatus === opt.value"
            @click="switchWorkStatus(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
        <p v-if="!isAccepting" class="work-switch__note">
          新订单不会再出现在你的大厅里，已接的订单照常服务。
        </p>
      </section>

      <div class="filters">
        <button
          v-for="f in [
            { key: 'all', label: '全部' },
            { key: 'high', label: '高额' }
          ]"
          :key="f.key"
          :class="['filters__btn', { 'is-active': filter === f.key }]"
          @click="filter = f.key"
        >
          {{ f.label }}
        </button>
      </div>

      <!-- 排序（V3.4 · T9）：维度由后端白名单决定；切换即重新向后端要数 -->
      <div class="sort" role="group" aria-label="排序方式">
        <button
          v-for="opt in SORT_OPTIONS"
          :key="opt.value"
          type="button"
          :class="['sort__btn', { 'is-active': sortBy === opt.value }]"
          :aria-pressed="sortBy === opt.value"
          @click="switchSort(opt.value)"
        >
          {{ opt.label }}
        </button>
      </div>

      <NlSkeleton v-if="loading" :count="3" class="list-pad" />

      <NlEmpty
        v-else-if="hallError"
        type="network"
        title="接单大厅加载失败"
        action-text="重试"
        @action="loadHall"
      />

      <NlEmpty
        v-else-if="!filtered.length"
        type="empty"
        title="暂无订单"
        description="附近用户暂时没有陪诊需求，请稍后下拉刷新"
      />

      <ul v-else class="orders">
        <li v-for="o in filtered" :key="o.id" class="order">
          <div class="order__head">
            <NlStatusChip status="PENDING" text="待接单" :dot="true" />
            <span class="order__fee is-num">{{ formatMoney(o.fee) }}</span>
          </div>
          <div class="order__hospital">{{ o.hospital }}</div>
          <div class="nl-caption nl-text-muted">{{ o.department }}</div>
          <ul class="order__meta">
            <li v-if="o.elderName">
              <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="8" cy="6.5" r="3" />
                <path d="M2.5 13.5c1-2 3-3 5.5-3s4.5 1 5.5 3" />
              </svg>
              {{ o.elderName }}
            </li>
            <li>
              <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="8" cy="8" r="6.5" />
                <path d="M8 4v4l3 2" />
              </svg>
              <span class="is-num">{{ formatVisitTime(o.visitTime) }}</span>
            </li>
            <li v-if="o.address">
              <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <path d="M8 14s5-4.5 5-8.5a5 5 0 1 0-10 0C3 9.5 8 14 8 14z" />
                <circle cx="8" cy="5.5" r="1.5" />
              </svg>
              <span class="order__addr">{{ o.address }}</span>
            </li>
          </ul>
          <el-button type="primary" round size="large" class="order__grab" @click="grab(o)">
            抢 单
          </el-button>
        </li>
      </ul>

      <p v-if="!loading && filtered.length" class="nl-caption nl-text-weak list-foot">
        共 {{ total }} 单待接
      </p>
    </template>

    <template #tabbar>
      <NlAppTabBar />
    </template>
  </NlPhoneShell>

  <!-- ==================== Desktop 形态（ADR-0007 · Q7 = Ⅱ COMPANION 高频页之一） ====================
       内容宽度由 NlDesktopShell 统一控制（768–1279 单列 720 / ≥1280 双栏 1120）。
       ⚠️ 接了单之后的 /companion/execute/:id 是 mobile-only（要 GPS 打卡），
          桌面下会落到"NlMobileOnlyNotice"提示页 —— 这是 ADR-0008 的既定契约。
  ============================================================================================== -->
  <NlDesktopShell v-else>
    <template #actions>
      <el-button text @click="router.push('/companion/order')">我的订单</el-button>
      <el-button text @click="router.push('/companion/income')">我的收入</el-button>
      <el-button text @click="router.push('/companion/workbench')">工作台</el-button>
      <el-button v-if="certStatus !== 'APPROVED'" type="primary" round @click="goEntry">
        去提交资质
      </el-button>
    </template>

    <NlSkeleton v-if="!ready" :count="3" />

    <NlEmpty
      v-else-if="isBlocked"
      type="empty"
      :title="blockTitle"
      :description="blockDesc"
      action-text="去提交资质"
      @action="goEntry"
    />

    <template v-else>
      <section class="work-switch work-switch--desktop">
        <div class="work-switch__head">
          <span class="nl-body nl-text-muted">我的接单状态：{{ isAccepting ? '接单中' : '暂停接单' }}</span>
        </div>
        <div class="work-switch__opts" role="group" aria-label="接单状态">
          <button
            v-for="opt in WORK_STATUS_OPTIONS"
            :key="opt.value"
            type="button"
            class="work-switch__btn"
            :class="{ 'is-active': workStatus === opt.value }"
            :disabled="workStatusSaving"
            :aria-pressed="workStatus === opt.value"
            @click="switchWorkStatus(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>
        <span class="nl-caption nl-text-weak">
          {{ isAccepting ? '订单执行（GPS 打卡）请用手机端完成' : workStatusHint }}
        </span>
      </section>

      <NlCard>
        <template #title>
          <div class="block-head">
            <span class="nl-h2">待接订单</span>
            <span class="nl-caption nl-text-muted">共 {{ total }} 单</span>
          </div>
        </template>

        <div class="filters filters--desktop">
          <button
            v-for="f in [
              { key: 'all', label: '全部' },
              { key: 'high', label: '高额（≥150）' }
            ]"
            :key="f.key"
            :class="['filters__btn', { 'is-active': filter === f.key }]"
            @click="filter = f.key"
          >
            {{ f.label }}
          </button>
        </div>

        <!-- 排序（V3.4 · T9）：维度由后端白名单决定；切换即重新向后端要数 -->
        <div class="sort sort--desktop" role="group" aria-label="排序方式">
          <button
            v-for="opt in SORT_OPTIONS"
            :key="opt.value"
            type="button"
            :class="['sort__btn', { 'is-active': sortBy === opt.value }]"
            :aria-pressed="sortBy === opt.value"
            @click="switchSort(opt.value)"
          >
            {{ opt.label }}
          </button>
        </div>

        <NlSkeleton v-if="loading" :count="3" />

        <NlEmpty
          v-else-if="hallError"
          type="network"
          title="接单大厅加载失败"
          action-text="重试"
          @action="loadHall"
        />

        <NlEmpty
          v-else-if="!filtered.length"
          type="empty"
          title="暂无订单"
          description="附近用户暂时没有陪诊需求，请稍后刷新"
        />

        <ul v-else class="desktop-orders">
          <li v-for="o in filtered" :key="o.id" class="desktop-orders__row">
            <div class="desktop-orders__main">
              <div class="desktop-orders__hospital">
                {{ o.hospital }}<span class="nl-caption nl-text-muted"> · {{ o.department }}</span>
              </div>
              <div class="nl-caption nl-text-muted is-num">
                {{ o.elderName ? `${o.elderName} · ` : '' }}{{ formatVisitTime(o.visitTime) }}
              </div>
              <div v-if="o.address" class="nl-caption nl-text-weak desktop-orders__addr">
                {{ o.address }}
              </div>
            </div>
            <span class="is-num desktop-orders__fee">{{ formatMoney(o.fee) }}</span>
            <el-button type="primary" round @click="grab(o)">抢单</el-button>
          </li>
        </ul>
      </NlCard>
    </template>
  </NlDesktopShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.list-pad {
  padding: 0 var(--nl-gutter);
}

.cert-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--nl-space-3) var(--nl-gutter);
  margin: 0 var(--nl-space-3);
  background: linear-gradient(90deg, var(--nl-success-bg) 0%, var(--nl-bg-card) 100%);
  border-radius: var(--nl-radius-card);

  &__left {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
  }

  &__right {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
  }
}

.work-switch {
  margin: var(--nl-space-3);
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &__head {
    display: flex;
    flex-wrap: wrap;
    gap: var(--nl-space-2);
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: var(--nl-space-2);
  }

  &__opts {
    display: flex;
    gap: var(--nl-space-2);
  }

  &__btn {
    flex: 1;
    /* 触控区不低于适老化基线，老人模式下会由 elderly.scss 再放大 */
    min-height: var(--nl-touch-min);
    padding: 0 var(--nl-space-3);
    font-size: var(--nl-font-body);
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    cursor: pointer;

    &:disabled {
      cursor: not-allowed;
      opacity: 0.6;
    }

    &.is-active {
      color: var(--nl-text-inverse);
      background: var(--nl-primary);
      border-color: var(--nl-primary);
    }

    /* 键盘可达：切换状态不是纯装饰操作 */
    &:focus-visible {
      outline: 2px solid var(--nl-primary);
      outline-offset: 2px;
    }
  }

  &__note {
    margin: var(--nl-space-2) 0 0;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
  }

  &--desktop {
    margin: 0 0 var(--nl-space-4);
  }
}

.filters {
  display: flex;
  gap: var(--nl-space-2);
  padding: 0 var(--nl-gutter);
  margin-bottom: var(--nl-space-3);

  &__btn {
    /* 触控区不低于适老化基线（AGENTS.md §3.5） */
    min-height: var(--nl-touch-min);
    padding: 6px 14px;
    font-size: var(--nl-font-caption);
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-text-inverse);
      background: var(--nl-primary);
      border-color: var(--nl-primary);
    }
  }
}

.orders {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0 var(--nl-gutter);
  margin: 0;
  list-style: none;
}

.order {
  padding: var(--nl-space-4);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--nl-space-2);
  }

  &__fee {
    font-family: var(--nl-font-num);
    font-size: var(--nl-font-h1);
    font-weight: 700;
    color: var(--nl-primary-text);
  }

  &__hospital {
    font-size: var(--nl-font-h3);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__meta {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: var(--nl-space-3) 0;
    margin: 0;
    list-style: none;

    li {
      display: flex;
      gap: 8px;
      align-items: center;
      font-size: var(--nl-font-caption);
      color: var(--nl-text-2);

      svg {
        color: var(--nl-text-3);
        flex-shrink: 0;
      }
    }
  }

  &__addr {
    word-break: break-all;
  }

  &__grab {
    width: 100%;
    font-size: var(--nl-font-h3);
    font-weight: 500;
  }
}

.list-foot {
  margin: var(--nl-space-4) 0 0;
  text-align: center;
}

/* 排序选择器（V3.4）：与「全部 / 高额」筛选条同一套药丸样式，
   375px 窄屏下三个药丸并排也不横向溢出（文案短 + flex 换行兜底） */
.sort {
  display: flex;
  flex-wrap: wrap;
  gap: var(--nl-space-2);
  padding: 0 var(--nl-gutter);
  margin-bottom: var(--nl-space-3);

  &__btn {
    /* 触控区不低于适老化基线（AGENTS.md §3.5） */
    min-height: var(--nl-touch-min);
    padding: 6px 14px;
    font-size: var(--nl-font-caption);
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-text-inverse);
      background: var(--nl-primary);
      border-color: var(--nl-primary);
    }
  }

  &--desktop {
    padding: 0;
  }
}

/* ==========================================================================
   Desktop 形态专用样式（>= 768px 才渲染，故不做媒体查询）
   ========================================================================== */
.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--nl-space-2);
}

.filters--desktop {
  padding: 0;
  margin-bottom: var(--nl-space-3);
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
    font-size: var(--nl-font-h3);
    font-weight: 600;
    color: var(--nl-text-1);
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__addr {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__fee {
    flex-shrink: 0;
    min-width: 80px;
    font-size: var(--nl-font-h2);
    font-weight: 700;
    color: var(--nl-primary-text);
    text-align: right;
  }
}
</style>
