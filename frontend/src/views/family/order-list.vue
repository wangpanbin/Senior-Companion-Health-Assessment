<script setup>
/**
 * 订单列表家属版（M-15）
 *
 * - 状态分段筛选：全部 / 待接单 / 已接单 / 服务中 / 已完成 / 已取消
 * - 订单卡：状态 chip + 医院 + 科室 + 时间 + 老人 + 陪诊员 + 服务费
 * - 空态：「暂无订单，去帮父母下单吧」
 * - 进入订单详情：M-16（family/order-detail）
 *
 * 数据来源：`GET /api/order`（M4 已交付）。
 *
 * ⚠️ 三处必须对齐后端的口径：
 *   1. 分页参数是 `page` / `size`（**不是 pageNum / pageSize**），
 *      且 `size` 上限 100 —— 见 `common/PageQuery`。
 *   2. `status` 支持逗号分隔多值，但**非法取值后端直接 400** 而不是忽略
 *      （`OrderServiceImpl#parseStatuses`）—— 所以 tab 的 key 必须是真实枚举名。
 *   3. 列表口径（`OrderVO.ofList`）**不返回地址与备注**，姓名与陪诊员名已脱敏。
 *      这是后端刻意的隐私设计，不是接口缺字段。
 *
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { NlPageShell, NlAppTabBar, NlStatusChip, NlEmpty, NlSkeleton } from '@/components'
import { listMyOrders } from '@/api/order'
import { formatVisitTime, formatMoney } from '@/utils/format'
import { fulfillmentBadge } from '@/utils/fulfillment'

const router = useRouter()

const tab = ref('all')
const orders = ref([])
const loading = ref(false)
/** R4：加载失败 ≠ 没有订单 */
const loadError = ref(false)
const total = ref(0)
/**
 * 分页（P1-9）。
 *
 * 早先固定 `size: 50` 且页面没有任何翻页控件 —— 第 51 条以后的订单用户永远看不到，
 * 而底部又写着「共 N 单」，两处口径自相矛盾。
 * 现在按后端分页口径（page / size，size 上限 100）取 10 条一页，配 el-pagination。
 */
const page = ref(1)
const size = ref(10)

/** tab key 直接就是后端 OrderStatus 枚举名（'all' 除外），避免再维护一张映射表 */
const tabs = [
  { key: 'all', label: '全部' },
  { key: 'PENDING', label: '待接单' },
  { key: 'ACCEPTED', label: '已接单' },
  { key: 'IN_SERVICE', label: '服务中' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'CANCELLED', label: '已取消' }
]

async function loadOrders() {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (tab.value !== 'all') {
      params.status = tab.value
    }
    const data = await listMyOrders(params)
    orders.value = data?.records || []
    total.value = data?.total || 0
    loadError.value = false
  } catch {
    orders.value = []
    total.value = 0
    loadError.value = true
  } finally {
    loading.value = false
  }
}

// 切换 tab 直接查后端，不在前端做本地过滤：
// 本地过滤只能筛出「已加载的那一页」，用户会以为只有这几条待接单
watch(tab, () => {
  // 换筛选条件必须回到第一页，否则会停在上一页的页码上看到空列表
  page.value = 1
  loadOrders()
})

/** 翻页：重新向后端要对应页的数据（不在前端切数组） */
function onPageChange(p) {
  page.value = p
  loadOrders()
}

function view(o) {
  router.push(`/family/order/${o.id}`)
}

const isEmpty = computed(() => !loading.value && !orders.value.length)

/**
 * 卡片视图模型：把风险标记预先算好挂到订单上。
 * 不在模板里对每张卡调三次 fulfillmentBadge(o)。
 * 正常等待的标记是 null（见 utils/fulfillment 的说明：扫读界面挂满正常标记等于没标记）。
 */
const cards = computed(() => orders.value.map((o) => ({ ...o, badge: fulfillmentBadge(o) })))

onMounted(loadOrders)
</script>

<template>
  <NlPageShell title="我的订单" :has-tabs="true" :back="false">
    <section class="filter-bar">
      <button
        v-for="t in tabs"
        :key="t.key"
        :class="['filter-bar__btn', { 'is-active': tab === t.key }]"
        @click="tab = t.key"
      >
        {{ t.label }}
      </button>
    </section>

    <NlSkeleton v-if="loading" :count="3" class="list-pad" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="订单列表加载失败"
      action-text="重试"
      @action="loadOrders"
    />

    <NlEmpty
      v-else-if="isEmpty"
      type="empty"
      title="暂无订单"
      description="去帮父母下单吧，3 步搞定"
      action-text="去下单"
      @action="router.push('/family/order/step1')"
    />

    <ul v-else role="list" class="orders">
      <li v-for="o in cards" :key="o.id" role="button" tabindex="0" class="order" @keydown.enter.prevent="view(o)" @keydown.space.prevent="view(o)" @click="view(o)">
        <div class="order__head">
          <div class="order__head-main">
            <!-- 优先用后端下发的 statusLabel；chip 的 scope 只做兜底 -->
            <NlStatusChip :status="o.status" :text="o.statusLabel" :dot="o.status === 'PENDING' || o.status === 'IN_SERVICE'" />
            <!-- 风险轻量标记：只占卡内一行的位置，不新增表格列 -->
            <span v-if="o.badge" class="order__risk" :class="`is-${o.badge.key}`">
              <span class="order__risk-dot" aria-hidden="true" />{{ o.badge.label }}
            </span>
          </div>
          <span class="order__fee is-num">{{ formatMoney(o.fee) }}</span>
        </div>
        <div class="order__hospital">{{ o.hospital }}</div>
        <div class="nl-caption nl-text-muted order__meta">
          {{ o.department }} · {{ o.elderName }}<template v-if="o.elderAge != null"> {{ o.elderAge }}岁</template>
          · {{ formatVisitTime(o.visitTime) }}
        </div>
        <div class="order__foot">
          <span v-if="o.companionName" class="nl-caption nl-text-muted">陪诊员：{{ o.companionName }}</span>
          <!-- 空态统一走 NlEmpty（P2-8）；卡片内嵌，故压缩成单行 -->
          <div v-else class="order__foot-empty">
            <NlEmpty type="empty" title="尚无陪诊员" />
          </div>
          <span class="order__id is-num">{{ o.orderNo }}</span>
        </div>
        <!--
          M13：有陪诊员才可聊。
          ⚠️ 判据用 companionName 而不是 companionId —— 列表 VO（OrderVO.ofList）
          **不返回 companionId**（实测 JSON 里没有该字段），用 id 判会恒为假、
          按钮永远不渲染。companionName 有值 ⇔ 已接单。
        -->
        <el-button
          v-if="o.companionName"
          class="order__chat"
          type="primary"
          plain
          @keydown.stop
          @click.stop="router.push(`/chat/${o.id}`)"
        >
          联系对方
        </el-button>
      </li>
    </ul>

    <!-- 翻页控件：没有它，第 51 条以后的订单不可达（P1-9） -->
    <el-pagination
      v-if="!loading && total > size"
      class="list-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="size"
      :current-page="page"
      @current-change="onPageChange"
    />

    <p v-if="!loading && orders.length" class="nl-caption nl-text-weak list-foot">
      共 {{ total }} 单
    </p>

    <template #tabbar>
      <NlAppTabBar />
    </template>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.filter-bar {
  display: flex;
  gap: var(--nl-space-2);
  padding: 0 var(--nl-gutter);
  overflow-x: auto;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

  &__btn {
    padding: 6px 14px;
    font-size: var(--nl-font-caption);
    font-weight: 500;
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;
    white-space: nowrap;

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

.list-pad {
  padding: 0 var(--nl-gutter);
}

.list-foot {
  margin: var(--nl-space-4) 0 0;
  text-align: center;
}

.list-pager {
  justify-content: center;
  margin-top: var(--nl-space-4);
}

.order {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  cursor: pointer;
  transition: box-shadow 0.18s;

  &:active {
    transform: scale(0.99);
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
    margin-bottom: var(--nl-space-2);
  }

  &__head-main {
    display: flex;
    align-items: center;
    gap: var(--nl-space-2);
    min-width: 0;
  }

  /* 文案色与底色成对取自 *-text / *-bg 令牌（与 NlNoticeBar 同一套口径，对比度已过 AA） */
  &__risk {
    --tone: var(--nl-text-2);
    --tone-bg: var(--nl-bg-sunken);
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 2px 10px;
    font-size: var(--nl-font-micro);
    color: var(--tone);
    background: var(--tone-bg);
    border-radius: var(--nl-radius-pill);
    white-space: nowrap;

    &-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--tone);
    }

    &.is-warning {
      --tone: var(--nl-warning-text);
      --tone-bg: var(--nl-warning-bg);
    }

    &.is-urgent {
      --tone: var(--nl-danger-text);
      --tone-bg: var(--nl-danger-bg);
    }

    &.is-manual {
      --tone: var(--nl-purple-text);
      --tone-bg: var(--nl-purple-bg);
    }
  }

  &__fee {
    font-size: var(--nl-font-h2);
    font-weight: 700;
    color: var(--nl-primary-text);
  }

  &__hospital {
    font-size: var(--nl-font-h3);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__meta {
    margin-top: 4px;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--nl-space-3);
    padding-top: var(--nl-space-2);
    border-top: 1px dashed var(--nl-divider);
  }

  /* 卡片内的空态：NlEmpty 默认是 80px 插画的大块，卡片里放不下，
     这里压成「小图标 + 一句话」的单行形态（文案与图标仍由 NlEmpty 统一出） */
  &__foot-empty {
    :deep(.nl-empty) {
      flex-direction: row;
      gap: 6px;
      padding: 0;
    }

    :deep(.nl-empty__svg) {
      width: 18px;
      height: 18px;
    }

    :deep(.nl-empty__desc) {
      display: none;
    }

    :deep(.nl-empty__title) {
      font-size: var(--nl-font-caption);
      font-weight: 400;
      color: var(--nl-text-2);
    }
  }

  &__id {
    // 订单号是用户报障时要念的凭据，属有意义内容，不能用 2.62:1 的弱化灰
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }

  // 整卡可点进详情，按钮单独跳聊天：必须 @click.stop，否则被父级 li 的
  // view(o) 截胡（companion/order-list.vue 同一处也是这个写法）。
  &__chat {
    width: 100%;
    min-height: var(--nl-touch-min);
    margin-top: var(--nl-space-3);
  }
}
</style>
