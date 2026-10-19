<script setup>
/**
 * M13 订单信息面板：就诊概览 + 服务进度 + 陪诊员资料 + 最近打卡。
 *
 * 桌面 ≥1280 挂在右侧栏常驻；<1280 由 room.vue 放进折叠卡复用同一组件，
 * 因此摘要头与面板内容永远同源，不会出现「收起显示 A、展开显示 B」。
 *
 * ## 数据全部来自 useOrderInfo
 *
 * 本组件是纯展示层：只读 props.flow，不自己发请求。四个数据块（order /
 * progress / checkins / companion）各自独立降级与重试，详见 useOrderInfo 头注。
 *
 * ## 姓名口径
 *
 * 陪诊员与老人姓名一律取自 `GET /api/order/{id}` 的详情口径（全名），
 * 不用聊天接口的 `peerName`（脱敏值）。后端已在 `requireInvolved` 处
 * 判定访问者是订单相关方才返回全名，前端只是又调用了一个已授权的接口，
 * 没有新增任何脱敏豁免。
 *
 * ## 不展示什么（有意为之）
 *
 * · 陪诊员头像 / 电话 / 个人简介 —— 后端一律不返回，不在前端编造占位。
 * · 老人病史 / 行动能力 —— `ElderServiceImpl` 对 COMPANION 显式 2006，
 *   展示会导致家属与陪诊员渲染出字段残缺的不同版本。
 * · 用药任务 —— 语义属老人健康档案而非陪诊进度。
 */
import { computed } from 'vue'
import { NlAvatar } from '@/components'
import { formatDateTime } from '@/utils/format'

const props = defineProps({
  /** useOrderInfo(orderId) 的返回值 */
  flow: { type: Object, required: true },
  /**
   * 是否渲染折叠态摘要头。
   * <1280 的折叠卡传 true；桌面右侧栏常驻展开，传 false。
   */
  collapsed: { type: Boolean, default: false }
})

const order = computed(() => props.flow.order.value)
const progress = computed(() => props.flow.progress.value)
const checkins = computed(() => props.flow.checkins.value || [])
const companion = computed(() => props.flow.companion.value)

/** 进度百分比做区间保护：后端给 0–100，越界值不应把进度条撑破布局。 */
const percent = computed(() => {
  const value = Number(progress.value?.progressPercent)
  if (!Number.isFinite(value)) return 0
  return Math.min(100, Math.max(0, Math.round(value)))
})

/** 后端无评分时 score 是 null 而非 "0.00"，直接渲染会出现「null 分」。 */
const scoreText = computed(() => {
  const value = companion.value?.score
  return value == null || value === '' ? '暂无评分' : `${value}`
})

const recentCheckins = computed(() => checkins.value.slice(-5).reverse())

/**
 * 订单状态徽标：优先取进度快照（它与服务进度同源、同一时刻），
 * 但 PENDING 等「尚无进度」的订单 progress 为空，回退到订单自身状态，
 * 否则该徽标会整块消失。
 */
const statusLabel = computed(() => progress.value?.orderStatusLabel || order.value?.statusLabel || '')

/** 摘要行：折叠态一行说完「谁 + 到哪一步 + 多少」。 */
const summary = computed(() => {
  const who = order.value?.companionName || '等待接单'
  const stage = progress.value?.currentNodeLabel || order.value?.statusLabel || '待开始'
  return { who, stage, percent: percent.value }
})
defineExpose({ summary })
</script>

<template>
  <section class="order-info" aria-label="订单信息">
    <!-- 折叠态摘要头：仅 <1280 的折叠卡使用，桌面栏不渲染 -->
    <header v-if="collapsed" class="order-info__summary">
      <span class="order-info__summary-who">{{ summary.who }}</span>
      <span class="order-info__summary-stage">{{ summary.stage }}</span>
      <span class="order-info__summary-bar" aria-hidden="true">
        <span class="order-info__summary-fill" :style="{ width: `${summary.percent}%` }" />
      </span>
      <span class="order-info__summary-percent">{{ summary.percent }}%</span>
    </header>

    <!-- 必得块失败 = 整块面板不可用 -->
    <div v-if="flow.error.value" class="order-info__fatal">
      <p class="order-info__fatal-text">订单信息加载失败</p>
      <p class="nl-caption nl-text-muted">可能订单已删除，或你没有查看权限</p>
      <el-button plain size="small" @click="flow.retryOrder()">重试</el-button>
    </div>

    <div v-else-if="flow.loading.value" class="order-info__skeleton" aria-busy="true">
      <span class="order-info__skeleton-row" />
      <span class="order-info__skeleton-row" />
      <span class="order-info__skeleton-row" />
    </div>

    <template v-else-if="order">
      <!-- ============ 就诊概览 ============ -->
      <section class="order-info__section">
        <h3 class="order-info__title">就诊概览</h3>
        <dl class="order-info__facts">
          <div class="order-info__fact">
            <dt>就诊人</dt>
            <dd>
              {{ order.elderName }}<template v-if="order.elderAge != null"> · {{ order.elderAge }} 岁</template>
            </dd>
          </div>
          <div class="order-info__fact">
            <dt>医院科室</dt>
            <dd>{{ order.hospital }} · {{ order.department }}</dd>
          </div>
          <div class="order-info__fact">
            <dt>就诊时间</dt>
            <dd class="is-num">{{ formatDateTime(order.visitTime) }}</dd>
          </div>
        </dl>
      </section>

      <!-- ============ 服务进度 ============ -->
      <section class="order-info__section">
        <h3 class="order-info__title">
          服务进度
          <span v-if="statusLabel" class="order-info__badge">{{ statusLabel }}</span>
        </h3>

        <template v-if="flow.progressError.value">
          <p class="order-info__degraded">服务进度暂时无法加载</p>
          <el-button text size="small" @click="flow.retryProgress()">重试</el-button>
        </template>

        <template v-else-if="progress">          <div class="order-info__progress">
            <span class="order-info__progress-value is-num">{{ percent }}%</span>
            <span class="order-info__progress-bar" role="progressbar" :aria-valuenow="percent" aria-valuemin="0" aria-valuemax="100">
              <span class="order-info__progress-fill" :style="{ width: `${percent}%` }" />
            </span>
          </div>
          <ul class="order-info__nodes">
            <li v-if="progress.currentNodeLabel" class="is-current">
              <span class="order-info__node-dot" aria-hidden="true" />
              当前：{{ progress.currentNodeLabel }}
            </li>
            <li v-if="progress.nextNodeLabel" class="is-next">
              <span class="order-info__node-dot" aria-hidden="true" />
              下一：{{ progress.nextNodeLabel }}
            </li>
            <li v-else class="is-done">全部节点已完成</li>
          </ul>
          <p class="nl-caption nl-text-muted">
            最后更新 {{ formatDateTime(progress.lastUpdateTime) }}
          </p>
        </template>

        <p v-else class="nl-caption nl-text-muted">尚未开始服务，接单后显示实时进度</p>
      </section>

      <!-- ============ 陪诊员资料 ============ -->
      <section v-if="order.companionId" class="order-info__section order-info__companion">
        <h3 class="order-info__title">陪诊员</h3>

        <div class="order-info__person">
          <NlAvatar :fallback="(order.companionName || '陪').slice(0, 1)" :size="48" tone="warning" />
          <div class="order-info__person-body">
            <div class="order-info__person-name">{{ order.companionName || '陪诊员' }}</div>
            <div class="nl-caption nl-text-muted">{{ scoreText }} · 累计 {{ companion?.orderCount ?? 0 }} 单</div>
          </div>
        </div>

        <template v-if="flow.companionError.value">
          <p class="order-info__degraded">暂未开放陪诊员资料</p>
          <el-button text size="small" @click="flow.retryCompanion()">重试</el-button>
        </template>

        <ul v-else-if="companion" class="order-info__companion-facts">
          <li>
            <span>资质</span>
            <span class="order-info__chip">{{ companion.auditStatusLabel || '—' }}</span>
          </li>
          <li>
            <span>状态</span>
            <span class="order-info__chip">{{ companion.workStatusLabel || '—' }}</span>
          </li>
          <li>
            <span>服务区域</span>
            <span>{{ companion.serviceArea || '—' }}</span>
          </li>
          <li>
            <span>可服务时段</span>
            <span>{{ companion.availableTime || '—' }}</span>
          </li>
        </ul>
      </section>

      <!-- ============ 最近打卡 ============ -->
      <section class="order-info__section">
        <h3 class="order-info__title">最近打卡</h3>

        <template v-if="flow.checkinsError.value">
          <p class="order-info__degraded">打卡记录暂时无法加载</p>
          <el-button text size="small" @click="flow.retryCheckins()">重试</el-button>
        </template>

        <ul v-else-if="recentCheckins.length" class="order-info__checkins">
          <li
            v-for="item in recentCheckins"
            :key="item.id"
            :class="['order-info__checkin', { 'order-info__checkin--abnormal': item.isAbnormal }]"
          >
            <div class="order-info__checkin-head">
              <span class="order-info__checkin-node">{{ item.nodeLabel }}</span>
              <span v-if="item.isAbnormal" class="order-info__checkin-flag">异常</span>
              <time class="order-info__checkin-time is-num">{{ formatDateTime(item.checkinTime) }}</time>
            </div>
            <p v-if="item.address" class="order-info__checkin-address">{{ item.address }}</p>
          </li>
        </ul>

        <p v-else class="nl-caption nl-text-muted">尚无打卡记录</p>
      </section>
    </template>
  </section>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.order-info {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-5);
  min-width: 0;
  font-size: var(--nl-font-body);

  /* ---------- 折叠态摘要头（仅 <1280） ---------- */
  &__summary {
    display: grid;
    grid-template-columns: auto auto minmax(40px, 1fr) auto;
    gap: var(--nl-space-2);
    align-items: center;
    min-height: var(--nl-touch-min);
    padding: 0 var(--nl-space-1);
  }
  &__summary-who {
    overflow: hidden;
    color: var(--nl-text-1);
    font-size: var(--nl-font-h3);
    font-weight: 600;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__summary-stage {
    overflow: hidden;
    max-width: 40%;
    color: var(--nl-primary-text);
    font-size: var(--nl-font-caption);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__summary-bar {
    display: block;
    height: 6px;
    overflow: hidden;
    background: var(--nl-bg-sunken);
    border-radius: var(--nl-radius-pill);
  }
  &__summary-fill {
    display: block;
    height: 100%;
    background: var(--nl-primary);
    border-radius: var(--nl-radius-pill);
    transition: width 0.25s ease;
  }
  &__summary-percent {
    color: var(--nl-text-2);
    font-family: var(--nl-font-num);
    font-size: var(--nl-font-caption);
  }

  /* ---------- 分块 ---------- */
  &__section {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-2);
    padding-bottom: var(--nl-space-4);
    border-bottom: 1px solid var(--nl-divider);

    &:last-child {
      padding-bottom: 0;
      border-bottom: 0;
    }
  }
  &__title {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
    margin: 0;
    color: var(--nl-text-1);
    font-size: var(--nl-font-h3);
    font-weight: 600;
  }
  &__badge {
    padding: 1px var(--nl-space-2);
    color: var(--nl-primary-text);
    font-size: var(--nl-font-caption);
    font-weight: 500;
    background: var(--nl-primary-light);
    border-radius: var(--nl-radius-chip);
  }
  &__degraded {
    margin: 0;
    color: var(--nl-danger-text);
    font-size: var(--nl-font-body);
  }

  /* ---------- 就诊概览 ---------- */
  &__facts {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-2);
    margin: 0;
  }
  &__fact {
    display: flex;
    gap: var(--nl-space-3);
    align-items: baseline;
    justify-content: space-between;
  }
  &__fact dt {
    flex-shrink: 0;
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }
  &__fact dd {
    margin: 0;
    overflow-wrap: anywhere;
    color: var(--nl-text-1);
    text-align: right;
  }

  /* ---------- 服务进度 ---------- */
  &__progress {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
  }
  &__progress-value {
    flex-shrink: 0;
    color: var(--nl-primary-text);
    font-size: var(--nl-font-h2);
    font-weight: 700;
  }
  &__progress-bar {
    flex: 1;
    height: 8px;
    overflow: hidden;
    background: var(--nl-bg-sunken);
    border-radius: var(--nl-radius-pill);
  }
  &__progress-fill {
    display: block;
    height: 100%;
    background: var(--nl-primary-gradient);
    border-radius: var(--nl-radius-pill);
    transition: width 0.25s ease;
  }
  &__nodes {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-1);
    margin: 0;
    padding: 0;
    list-style: none;
    font-size: var(--nl-font-body);

    li {
      display: flex;
      gap: var(--nl-space-2);
      align-items: center;
    }
    .is-current {
      color: var(--nl-text-1);
      font-weight: 600;
    }
    .is-next,
    .is-done {
      color: var(--nl-text-2);
      font-size: var(--nl-font-caption);
    }
  }
  &__node-dot {
    width: 6px;
    height: 6px;
    background: var(--nl-border-strong);
    border-radius: 50%;
  }
  .is-current &__node-dot {
    background: var(--nl-primary);
  }

  /* ---------- 陪诊员 ---------- */
  &__person {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
  }
  &__person-body {
    min-width: 0;
  }
  &__person-name {
    color: var(--nl-text-1);
    font-size: var(--nl-font-h3);
    font-weight: 600;
  }
  &__companion-facts {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-2);
    margin: 0;
    padding: 0;
    list-style: none;

    li {
      display: flex;
      gap: var(--nl-space-3);
      align-items: baseline;
      justify-content: space-between;
    }
    li > span:first-child {
      flex-shrink: 0;
      color: var(--nl-text-2);
      font-size: var(--nl-font-caption);
    }
    li > span:last-child {
      overflow-wrap: anywhere;
      color: var(--nl-text-1);
      text-align: right;
    }
  }
  &__chip {
    display: inline-block;
    padding: 1px var(--nl-space-2);
    color: var(--nl-primary-text);
    font-size: var(--nl-font-caption);
    background: var(--nl-primary-light);
    border-radius: var(--nl-radius-chip);
  }

  /* ---------- 最近打卡 ---------- */
  &__checkins {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-2);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  &__checkin {
    padding: var(--nl-space-2) var(--nl-space-3);
    background: var(--nl-bg-sunken);
    border-left: 3px solid var(--nl-border-strong);
    border-radius: var(--nl-radius-chip);
  }
  &__checkin--abnormal {
    background: var(--nl-danger-bg);
    border-left-color: var(--nl-danger);
  }
  &__checkin-head {
    display: flex;
    gap: var(--nl-space-2);
    align-items: baseline;
  }
  &__checkin-node {
    color: var(--nl-text-1);
    font-weight: 600;
  }
  &__checkin-flag {
    padding: 0 var(--nl-space-1);
    color: var(--nl-danger-text);
    font-size: var(--nl-font-caption);
    background: var(--nl-bg-card);
    border-radius: var(--nl-radius-chip);
  }
  &__checkin-time {
    margin-left: auto;
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }
  &__checkin-address {
    margin: var(--nl-space-1) 0 0;
    overflow-wrap: anywhere;
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }

  /* ---------- 降级与骨架 ---------- */
  &__fatal {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-2);
    align-items: flex-start;
    padding: var(--nl-space-5) var(--nl-space-4);
    text-align: center;
    background: var(--nl-bg-sunken);
    border-radius: var(--nl-radius-card);
  }
  &__fatal-text {
    margin: 0;
    color: var(--nl-text-1);
    font-size: var(--nl-font-h3);
    font-weight: 600;
  }
  &__skeleton {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-3);
  }
  &__skeleton-row {
    display: block;
    height: 48px;
    background: linear-gradient(90deg, var(--nl-bg-sunken) 25%, var(--nl-divider) 37%, var(--nl-bg-sunken) 63%);
    background-size: 400% 100%;
    border-radius: var(--nl-radius-card);
    animation: order-info-shimmer 1.4s ease infinite;
  }
}

@keyframes order-info-shimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .order-info__skeleton-row {
    animation: none;
  }
}
</style>
