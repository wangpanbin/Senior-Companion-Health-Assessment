<script setup>
/**
 * 接单大厅（M-10 · design.md §4 · PRD §4.1 陪诊员主旅程）
 *
 * 数据来源：
 *   - GET /order/hall        → listOrderHall（待接单订单）
 *   - POST /order/{id}/accept → acceptOrder（接单，乐观锁）
 *   - GET /user/companion/application → getMyCompanionApplication（资质状态）
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
  NlPhoneShell, NlDesktopShell, NlTabBar, NlCard, NlStatusChip, NlEmpty, NlSkeleton,
  NlNoticeBar, NlUserMenu
} from '@/components'
import { useDevice } from '@/composables/useDevice'
import { listOrderHall, acceptOrder } from '@/api/order'
import { getMyCompanionApplication } from '@/api/user'
import { formatVisitTime, formatMoney } from '@/utils/format'

const router = useRouter()

/** 形态判定（ADR-0007）：窄屏走手机壳，宽屏走桌面壳 */
const { isMobile } = useDevice()

/** 资质状态：null=从未申请 / PENDING / APPROVED / REJECTED */
const app = ref(null)
const certStatus = ref(null)
const ready = ref(false)

const orders = ref([])
const total = ref(0)
const loading = ref(false)

const filter = ref('all') // all / high

const tabs = [
  { key: 'hall', label: '大厅', icon: 'List' },
  { key: 'order', label: '订单', icon: 'Tickets' },
  { key: 'income', label: '收入', icon: 'Money' },
  { key: 'mine', label: '我的', icon: 'User' }
]
const activeTab = ref('hall')

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
    const data = await listOrderHall({ page: 1, size: 50 })
    orders.value = data?.records || []
    total.value = data?.total || 0
  } catch {
    orders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
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

function onTabChange(key) {
  if (key === 'hall') return
  if (key === 'order') router.push('/companion/order')
  if (key === 'income') router.push('/companion/income')
  if (key === 'mine') router.push('/profile')
}

onMounted(async () => {
  try {
    await loadCert()
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
          <span class="nl-caption" :class="certStatus === 'APPROVED' ? 'nl-text-muted' : 'nl-text-weak'">
            {{ certStatus === 'APPROVED' ? '接单中' : '不可接单' }}
          </span>
        </div>
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

      <NlSkeleton v-if="loading" :count="3" class="list-pad" />

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
      <NlTabBar v-model="activeTab" :tabs="tabs" @change="onTabChange" />
    </template>
  </NlPhoneShell>

  <!-- ==================== Desktop 形态（ADR-0007 · Q7 = Ⅱ COMPANION 高频页之一） ====================
       内容宽度由 NlDesktopShell 统一限制为 720px 居中。
       ⚠️ 接了单之后的 /companion/execute/:id 是 mobile-only（要 GPS 打卡），
          桌面下会落到"NlMobileOnlyNotice"提示页 —— 这是 ADR-0008 的既定契约。
  ============================================================================================== -->
  <NlDesktopShell v-else>
    <template #actions>
      <el-button text @click="router.push('/companion/order')">我的订单</el-button>
      <el-button text @click="router.push('/companion/income')">我的收入</el-button>
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
      <NlNoticeBar tone="success">
        资质已通过，可在大厅抢单。订单执行（GPS 打卡）请用手机端完成。
      </NlNoticeBar>

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

        <NlSkeleton v-if="loading" :count="3" />

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

.filters {
  display: flex;
  gap: var(--nl-space-2);
  padding: 0 var(--nl-gutter);
  margin-bottom: var(--nl-space-3);

  &__btn {
    padding: 6px 14px;
    font-size: 13px;
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
  border-radius: var(--nl-radius-card);
  box-shadow: var(--nl-shadow-card);

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--nl-space-2);
  }

  &__fee {
    font-family: var(--nl-font-num);
    font-size: 22px;
    font-weight: 700;
    color: var(--nl-primary);
  }

  &__hospital {
    font-size: 16px;
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
    font-size: 16px;
    font-weight: 500;
  }
}

.list-foot {
  margin: var(--nl-space-4) 0 0;
  text-align: center;
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
    font-size: 15px;
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
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-primary);
    text-align: right;
  }
}
</style>
