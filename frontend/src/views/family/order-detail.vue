<script setup>
/**
 * 订单详情（M-16 · design.md §4）
 *
 * - 订单状态条 + 订单号
 * - 服务进度时间线（NlTimeline：6 打卡节点 + 订单终态，WebSocket 实时更新）
 * - 信息卡：就诊人 / 医院 + 科室 / 就诊时间 / 医院地址 / 服务费 / 结算方式
 * - 陪诊员信息卡（已接单才出现）
 * - 操作：按当前状态切换「取消订单 / 我要投诉 / 去评价」
 *
 * 数据来源（M4 + M5 已交付）：
 *   GET /api/order/{id}                 详情（全名、地址、备注、结算状态、服务小结、照片）
 *   GET /api/order/{id}/timeline        状态流转日志（含操作人快照与备注）
 *   GET /api/execution/{orderId}/checkins  6 节点打卡记录
 *   GET /api/order/{id}/recommend-companions  候选陪诊员推荐（V3.4，仅「待接单」时展示）
 *   WS  /ws/progress?token=&orderId=     打卡时实时推送
 *
 * ⚠️ 三处与骨架版的实质差异：
 *   1. 删掉「已支付的费用将原路退回」—— **一期明令不做在线支付**
 *      （线上记账 + 线下结算），这句话描述了一个不存在的退款能力。
 *   2. 取消订单必须填原因：后端 `OrderCancelDTO.reason` 是 `@NotBlank`，
 *      且原因会写进双方可见的时间线。前端不收集就必然 400。
 *   3. 不再自己拼 `fee + pickupFee`：后端只有 `fee` 一个金额，
 *      详情另给 `actualFee`（实际结算）与 `paymentStatus`。
 *
 * ⚠️ 隐私口径：详情接口返回的是**全名**（`OrderVO.ofDetail`），
 *    因为访问者已通过 `OrderService#requireInvolved` 判定为该订单相关方。
 *    这不是泄露 —— 相关方本来就该看到完整信息。
 *
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPageShell, NlCard, NlStatusChip, NlTimeline, NlAvatar, NlNoticeBar, NlSkeleton, NlEmpty } from '@/components'
import OrderFeeItems from '@/components/OrderFeeItems.vue'
import { getOrder, getOrderTimeline, cancelOrder, urgeOrder, recommendCompanions } from '@/api/order'
import { listCheckins, connectProgressSocket } from '@/api/execution'
import { getReviewByOrder } from '@/api/review'
import { formatDateTime, CHECKIN_NODE_ORDER } from '@/utils/format'
import {
  showFulfillmentBar,
  fulfillmentTone,
  waitMinutesText,
  canUrgeOrder
} from '@/utils/fulfillment'

const route = useRoute()
const router = useRouter()

const orderId = route.params.id
const order = ref(null)
const statusLogs = ref([])
const checkins = ref([])
const review = ref(null)
const loading = ref(true)
const loadError = ref(false)
const liveConnected = ref(false)
/** V3.1：催单按钮的 in-flight 态，避免连点 */
const urging = ref(false)

/* ==================== 候选陪诊员推荐（V3.4 · T9） ==================== */

/*
 * 只在「待接单」时展示：订单一旦被接走，用户关心的是「谁在服务我」，
 * 而不是「还有谁可能来」—— 此时再列候选人只会制造噪音与误解。
 *
 * ⚠️ 推荐 ≠ 派单。该端点只做排序与解释，**不产生任何订单状态变更**，
 *    接单权仍在陪诊员。所以页面文案必须写明「最终由陪诊员确认接单」，
 *    不得出现「已指派 / 已分配 / 自动派单」这类暗示系统替用户拍板的表述。
 *
 * 三态：Loading（骨架）/ Empty（无候选）/ Error（可重试）。
 * 单块失败只降级候选区，不影响页面其余部分。
 */
const recommendLoading = ref(false)
const recommendError = ref(false)
const recommendTotal = ref(0)
const candidates = ref([])

/** 五个维度的展示名；得分全部由后端给出，前端只做「字段 → 标签」映射，不参与任何计算 */
const SCORE_DIMS = [
  { key: 'regionScore', label: '区域' },
  { key: 'timeScore', label: '时间' },
  { key: 'distanceScore', label: '距离' },
  { key: 'skillScore', label: '服务' },
  { key: 'ratingScore', label: '评分' }
]

async function loadCandidates() {
  // 只有「待接单」才需要候选推荐；其它状态下清空即可（模板也不会渲染这一块）
  if (order.value?.status !== 'PENDING') {
    candidates.value = []
    recommendTotal.value = 0
    recommendError.value = false
    recommendLoading.value = false
    return
  }
  recommendLoading.value = true
  recommendError.value = false
  try {
    const data = await recommendCompanions(orderId, { limit: 10 })
    candidates.value = data?.candidates || []
    recommendTotal.value = data?.total ?? candidates.value.length
  } catch {
    candidates.value = []
    recommendTotal.value = 0
    recommendError.value = true
  } finally {
    recommendLoading.value = false
  }
}

let socket = null

async function loadAll() {
  try {
    // 四个请求互不依赖，并发拉取，减少首屏等待
    const [detail, logs, checks, rev] = await Promise.all([
      getOrder(orderId),
      getOrderTimeline(orderId).catch(() => []),
      listCheckins(orderId).catch(() => []),
      getReviewByOrder(orderId).catch(() => null)
    ])
    order.value = detail
    statusLogs.value = logs || []
    checkins.value = checks || []
    review.value = rev || null
    loadError.value = false
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
  // 候选推荐依赖详情里的订单状态（只有「待接单」才需要），
  // 故放在详情之后单独拉；它失败只降级候选区，不牵连页面其余部分。
  await loadCandidates()
}

/* ==================== 履约状态条（V3.1 · T5） ==================== */

/*
 * 等级、文案、能否催单全部来自后端字段，前端只做「等级 → 视觉」的映射（utils/fulfillment）。
 * 不在待接单时整条消失 —— 订单被接走后继续挂着「等待时间较长」是在提示一件已经结束的事。
 */
const fulfillment = computed(() => {
  const o = order.value
  if (!showFulfillmentBar(o)) {
    return { visible: false }
  }
  const tone = fulfillmentTone(o.fulfillmentRisk)
  return {
    visible: true,
    toneKey: tone.key,
    toneRank: tone.rank,
    waitText: waitMinutesText(o.waitMinutes),
    canUrge: canUrgeOrder(o)
  }
})

async function doUrge() {
  urging.value = true
  try {
    await urgeOrder(orderId, {})
    ElMessage.success('已催促，陪诊员会尽快接单')
    await loadAll()
  } catch {
    // 3008 / 3009 的提示已由请求拦截器统一处理
  } finally {
    urging.value = false
  }
}

/* ==================== 服务进度时间线 ==================== */

/**
 * 把「订单状态流转」与「6 节点打卡」合成一条时间线。
 *
 * 为什么合并而不是只显示其中一个：
 *   只看状态流转，家属看不到「到院了 / 正在就诊」这些他真正关心的颗粒度；
 *   只看打卡，又漏掉「已接单 / 已完成」这两个订单级事件。
 */
const steps = computed(() => {
  const o = order.value
  if (!o) return []

  const nodes = []
  const doneCheckin = new Map((checkins.value || []).map((c) => [c.node, c]))

  // 1. 下单
  nodes.push({
    key: 'PENDING',
    label: '待接单',
    time: formatDateTime(o.createTime, ''),
    done: true
  })

  // 2. 已接单
  const accepted = !!o.companionId
  nodes.push({
    key: 'ACCEPTED',
    label: '已接单',
    time: accepted ? formatDateTime(o.acceptTime, '') : '',
    desc: accepted && o.companionName ? `陪诊员 ${o.companionName}` : '',
    done: accepted
  })

  // 3. 六个打卡节点。订单取消后后续节点不再有意义，但仍把已打过的显示出来
  CHECKIN_NODE_ORDER.forEach((nodeName) => {
    const c = doneCheckin.get(nodeName)
    nodes.push({
      key: nodeName,
      label: c?.nodeLabel || NODE_LABEL[nodeName] || nodeName,
      time: c ? formatDateTime(c.checkinTime, '') : '',
      // 异常打卡（超出距离阈值）要显式提示，否则家属不知道这次打卡有问题
      desc: c?.isAbnormal ? '该次打卡超出约定距离，已标记为异常' : c?.address || '',
      done: !!c
    })
  })

  // 4. 完成 / 已评价
  const finished = o.status === 'COMPLETED' || o.status === 'REVIEWED'
  nodes.push({
    key: 'COMPLETED',
    label: '服务完成',
    time: finished ? formatDateTime(o.finishTime, '') : '',
    desc: o.serviceSummary || '',
    done: finished
  })
  nodes.push({
    key: 'REVIEWED',
    label: '已评价',
    time: o.status === 'REVIEWED' ? formatDateTime(o.finishTime, '') : '',
    done: o.status === 'REVIEWED'
  })

  // 已取消：把取消作为终态节点插进来。不插的话时间线会停在一个中间节点上，
  // 家属会以为服务还在进行
  if (o.status === 'CANCELLED') {
    nodes.push({
      key: 'CANCELLED',
      label: '订单已取消',
      time: formatDateTime(o.cancelTime, ''),
      desc: o.cancelReason || '',
      done: true
    })
  }

  // 标记 current：第一个未完成节点。
  // 已取消的订单不再有「进行中」的节点，避免出现「已取消但显示就诊中正在等你」的矛盾画面
  let currentAssigned = false
  return nodes.map((n) => {
    if (n.done) return { ...n, status: 'done' }
    if (!currentAssigned && o.status !== 'CANCELLED') {
      currentAssigned = true
      return { ...n, status: 'current' }
    }
    return { ...n, status: 'todo' }
  })
})

const NODE_LABEL = {
  DEPART: '陪诊员已出发',
  ARRIVE: '已到达医院',
  IN_CONSULT: '就诊中',
  TAKE_MEDICINE: '取药',
  LEAVE: '离院',
  FINISH: '陪诊结束'
}

/** 是否有进行中的服务（决定要不要开实时推送） */
const isLive = computed(() =>
  order.value && (order.value.status === 'ACCEPTED' || order.value.status === 'IN_SERVICE')
)

/** 是否处于「待接单」（决定候选推荐区是否渲染） */
const isPending = computed(() => order.value?.status === 'PENDING')

const canCancel = computed(() => isPending.value)
const canReview = computed(() => order.value?.status === 'COMPLETED')
const canComplaint = computed(() =>
  order.value && ['COMPLETED', 'REVIEWED'].includes(order.value.status)
)

/* ==================== 实时推送 ==================== */

function openSocket() {
  if (!isLive.value) return
  try {
    socket = connectProgressSocket(orderId, {
      onStatus: (s) => {
        liveConnected.value = s === 'open'
      },
      onEvent: (payload) => {
        // PONG 是心跳回应，不是业务事件
        if (payload?.type === 'ORDER_PROGRESS') {
          loadAll()
        }
      }
    })
  } catch {
    // 推送连不上不影响页面可用性：进度快照已经拉过一次，
    // 用户手动下拉刷新也能拿到最新状态
    liveConnected.value = false
  }
}

/* ==================== 操作 ==================== */

async function doCancel() {
  let reason
  try {
    const { value } = await ElMessageBox.prompt(
      '取消原因会同时展示给陪诊员，请简单说明（如：老人临时身体不适）。',
      '取消订单',
      {
        confirmButtonText: '确认取消',
        cancelButtonText: '再想想',
        inputPlaceholder: '请输入取消原因（必填）',
        inputValidator: (v) => (v && v.trim().length > 0 ? true : '请填写取消原因'),
        type: 'warning'
      }
    )
    reason = value.trim()
  } catch {
    // 用户点了「再想想」
    return
  }

  try {
    await cancelOrder(orderId, { reason })
    ElMessage.success('订单已取消')
    await loadAll()
  } catch {
    // 提示已由拦截器统一处理（例如 3006 当前状态不允许取消）
  }
}

function goReview() {
  router.push(`/family/order/${orderId}/review`)
}
function goComplaint() {
  router.push(`/family/order/${orderId}/complaint`)
}

onMounted(async () => {
  await loadAll()
  openSocket()
})

onBeforeUnmount(() => {
  // 必须主动关闭：不关的话离开页面后 WebSocket 还挂着，
  // 用户来回进出几次就会堆出好几条订阅，服务端侧每个会话都占资源
  socket?.close()
  socket = null
})
</script>

<template>
  <NlPageShell title="订单详情">
    <NlSkeleton v-if="loading" :count="3" class="pad" />

    <NlEmpty
      v-else-if="loadError || !order"
      type="network"
      title="订单加载失败"
      description="请检查网络后重试，或返回订单列表"
      action-text="重新加载"
      @action="loadAll"
    />

    <template v-else>
      <NlNoticeBar :tone="liveConnected ? 'primary' : 'warning'">
        <template v-if="liveConnected">进度实时推送中 · 陪诊员每完成一个节点会即时通知</template>
        <template v-else-if="isLive">实时推送未连接 · 下拉可刷新最新进度</template>
        <template v-else>本服务为线下结算，平台不代收费用</template>
      </NlNoticeBar>

      <!-- 履约状态条：图标 + 文案 + 左侧色条。不用纯红 alarming 底色（老人模式下情绪负担过重） -->
      <div v-if="fulfillment.visible" class="fulfillment" :class="`is-${fulfillment.toneKey}`" role="status">
        <span class="fulfillment__bar" aria-hidden="true" />
        <span class="fulfillment__icon" aria-hidden="true">
          <svg viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
            <circle cx="10" cy="10" r="7.5" />
            <template v-if="fulfillment.toneRank >= 1">
              <path d="M10 5.6v4.8" />
              <circle cx="10" cy="13.6" r="0.9" fill="currentColor" stroke="none" />
            </template>
          </svg>
        </span>
        <div class="fulfillment__body">
          <div class="fulfillment__label">{{ order.fulfillmentRiskLabel }}</div>
          <div v-if="fulfillment.waitText" class="fulfillment__wait">{{ fulfillment.waitText }}</div>
        </div>
        <el-button
          v-if="fulfillment.canUrge"
          class="fulfillment__urge"
          type="primary"
          plain
          round
          :loading="urging"
          @click="doUrge"
        >
          催促接单
        </el-button>
      </div>

      <!-- 订单概览 -->
      <NlCard>
        <div class="overview">
          <div>
            <div class="nl-h2 is-num">{{ order.orderNo }}</div>
            <div class="nl-caption nl-text-muted">下单时间 {{ formatDateTime(order.createTime) }}</div>
          </div>
          <NlStatusChip :status="order.status" :text="order.statusLabel" />
        </div>
        <el-button v-if="order.companionId" type="primary" plain @click="router.push(`/chat/${orderId}`)">联系对方</el-button>
      </NlCard>

      <!-- 候选陪诊员推荐（V3.4 · T9）
           ⚠️ 只做推荐，不产生任何订单状态变更 —— 接单权仍在陪诊员。
              文案必须写明「最终由陪诊员确认接单」，不得出现暗示自动派单的表述。 -->
      <NlCard v-if="isPending" title="系统正在为你匹配" class="cand-card">
        <template #extra>
          <span class="nl-caption nl-text-muted">符合条件 {{ recommendTotal }} 位</span>
        </template>

        <NlSkeleton v-if="recommendLoading" :count="2" />

        <NlEmpty
          v-else-if="recommendError"
          type="network"
          title="匹配信息加载失败"
          description="请检查网络后重试"
          action-text="重试"
          @action="loadCandidates"
        />

        <NlEmpty
          v-else-if="!candidates.length"
          type="empty"
          title="暂无匹配候选"
          description="陪诊员会在大厅看到你的订单，由对方决定是否接单"
        />

        <template v-else>
          <ul class="cand">
            <li v-for="c in candidates" :key="c.companionId" class="cand__item">
              <div class="cand__head">
                <span class="cand__name">{{ c.companionName }}</span>
                <span class="cand__score is-num">综合 {{ c.totalScore }} 分</span>
              </div>
              <div class="cand__dims">
                <span v-for="d in SCORE_DIMS" :key="d.key" class="cand__dim">
                  {{ d.label }} {{ c[d.key] ?? 0 }}
                </span>
              </div>
              <ul v-if="c.reasons?.length" class="cand__reasons">
                <li v-for="(r, i) in c.reasons" :key="i">{{ r }}</li>
              </ul>
              <ul v-if="c.mismatches?.length" class="cand__mismatches">
                <li v-for="(m, i) in c.mismatches" :key="i">{{ m }}</li>
              </ul>
              <ul v-if="c.notes?.length" class="cand__notes">
                <li v-for="(n, i) in c.notes" :key="i">{{ n }}</li>
              </ul>
            </li>
          </ul>
        </template>

        <p class="cand-card__note nl-caption nl-text-weak">
          推荐仅供参考，最终由陪诊员本人确认接单；接单前订单状态不会改变。
        </p>
      </NlCard>

      <!-- 陪诊员 -->
      <NlCard v-if="order.companionName" plain>
        <template #title><span class="nl-h2">陪诊员</span></template>
        <div class="comp">
          <NlAvatar :fallback="order.companionName.slice(0, 1)" :size="48" tone="warning" />
          <div class="comp__body">
            <div class="comp__name">{{ order.companionName }}</div>
            <div class="nl-caption nl-text-muted">
              <template v-if="order.acceptTime">接单时间 {{ formatDateTime(order.acceptTime) }}</template>
              <template v-else>已接单</template>
            </div>
          </div>
        </div>
      </NlCard>

      <!-- 服务进度 -->
      <NlCard title="服务进度">
        <NlTimeline :steps="steps" />
        <div v-if="order.servicePhotos?.length" class="photos">
          <p class="nl-caption nl-text-muted">服务现场 / 取药凭证</p>
          <div class="photos__grid">
            <img v-for="(p, i) in order.servicePhotos" :key="i" :src="p" alt="服务照片" />
          </div>
        </div>
      </NlCard>

      <!-- 订单信息 -->
      <NlCard title="订单信息" plain>
        <ul class="info">
          <li>
            <span>就诊人</span>
            <span>{{ order.elderName }}<template v-if="order.elderAge != null"> · {{ order.elderAge }} 岁</template></span>
          </li>
          <li><span>陪诊医院</span><span>{{ order.hospital }} · {{ order.department }}</span></li>
          <li><span>就诊时间</span><span class="is-num">{{ formatDateTime(order.visitTime) }}</span></li>
          <li><span>医院地址</span><span class="info__addr">{{ order.address }}</span></li>
          <li v-if="order.remark"><span>备注</span><span class="info__addr">{{ order.remark }}</span></li>
          <li class="info__fee">
            <span>服务费</span>
            <span class="is-num info__fee-val">¥{{ order.fee }}</span>
          </li>
          <li>
            <span>结算方式</span>
            <span>{{ order.paymentStatusLabel || '线上记账 + 线下结算' }}</span>
          </li>
          <li v-if="order.cancelReason">
            <span>取消原因</span>
            <span class="info__addr">{{ order.cancelReason }}</span>
          </li>
        </ul>
      </NlCard>

      <!-- 费用明细（ADR-0009：代垫/服务费分账，明细是真源、actual_fee 是汇总缓存） -->
      <NlCard title="费用明细" plain>
        <OrderFeeItems :order-id="orderId" />
      </NlCard>

      <!-- 我的评价（E4 评价公信力闭环） -->
      <!--
        byOrder 接口总是返回该订单的评价（含 is_valid=0 的），
        无效评价仍对相关方可见但带「平台已裁定为无效评价」灰色标注；
        公开列表与聚合口径已在后端过滤掉，订单详情是「相关方能看到自己
        提交过什么、收到什么回复、是否被裁定」的唯一视角。
      -->
      <NlCard v-if="review" title="我的评价">
        <div class="review">
          <div class="review__head">
            <span class="review__score">{{ review.score }} 星</span>
            <span class="review__time">{{ formatDateTime(review.createTime) }}</span>
            <el-tag
              v-if="review.isValid === false"
              type="info"
              effect="plain"
              size="small"
              class="review__invalid"
            >
              平台已裁定为无效评价
            </el-tag>
          </div>
          <div v-if="review.content" class="review__content">{{ review.content }}</div>
          <div v-if="review.tags?.length" class="review__tags">
            <span v-for="t in review.tags" :key="t" class="review__tag">{{ t }}</span>
          </div>
          <div v-if="review.companionReply" class="review__reply">
            <div class="review__reply-label">
              陪诊员回复 · {{ formatDateTime(review.replyTime) }}
            </div>
            <div class="review__reply-body">{{ review.companionReply }}</div>
          </div>
        </div>
      </NlCard>

      <!-- 操作记录（含操作人快照与备注，来自 order_status_log） -->
      <NlCard v-if="statusLogs.length" title="操作记录" plain>
        <ul class="logs">
          <li v-for="(l, i) in statusLogs" :key="i">
            <span class="logs__time is-num">{{ formatDateTime(l.operateTime) }}</span>
            <span class="logs__main">
              <strong>{{ l.statusLabel }}</strong>
              <template v-if="l.operatorName"> · {{ l.operatorName }}</template>
            </span>
            <span v-if="l.remark" class="nl-caption nl-text-muted logs__remark">{{ l.remark }}</span>
          </li>
        </ul>
      </NlCard>

      <!-- 操作栏 -->
      <section class="actions">
        <el-button v-if="canCancel" round @click="doCancel">取消订单</el-button>
        <el-button v-if="canComplaint" round @click="goComplaint">我要投诉</el-button>
        <el-button v-if="canReview" type="primary" round @click="goReview">去评价</el-button>
      </section>
    </template>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter);
}

/* 履约状态条：颜色只走 --tone（色条与图标），正文色固定走 --nl-text-1，
   这样四档的风险等级不会因为令牌色差而牺牲文案对比度 */
.fulfillment {
  --tone: var(--nl-border-strong);
  display: flex;
  align-items: center;
  gap: var(--nl-space-3);
  margin: 0 var(--nl-gutter) var(--nl-space-3);
  padding: var(--nl-space-3);
  padding-left: 0;
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &.is-warning { --tone: var(--nl-warning-text); }
  &.is-urgent { --tone: var(--nl-danger-text); }
  &.is-manual { --tone: var(--nl-purple-text); }

  &__bar {
    align-self: stretch;
    flex-shrink: 0;
    width: 4px;
    background: var(--tone);
    border-radius: var(--nl-radius-card) 0 0 var(--nl-radius-card);
  }

  &__icon {
    display: flex;
    flex-shrink: 0;
    color: var(--tone);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__label {
    font-size: var(--nl-font-body);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__wait {
    margin-top: 2px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }

  /* 触控高度走令牌，老人模式下自动抬到 56px */
  &__urge {
    flex-shrink: 0;
    min-height: var(--nl-touch-min);
  }
}

.overview {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--nl-space-3);
}

.comp {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;

  &__body {
    flex: 1;
  }

  &__name {
    font-size: var(--nl-font-h3);
    font-weight: 600;
  }
}

/* 候选陪诊员推荐（V3.4 · T9） */
.cand-card {
  &__note {
    margin: var(--nl-space-3) 0 0;
  }
}

.cand {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    padding: var(--nl-space-3);
    background: var(--nl-bg-sunken);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
  }

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }

  &__name {
    font-size: var(--nl-font-h3);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__score {
    flex-shrink: 0;
    font-size: var(--nl-font-h3);
    font-weight: 700;
    color: var(--nl-primary-text);
  }

  /* 维度得分药丸：flex-wrap 兜底，375px 窄屏不横向溢出 */
  &__dims {
    display: flex;
    flex-wrap: wrap;
    gap: var(--nl-space-2);
    margin-top: var(--nl-space-2);
  }

  &__dim {
    padding: 2px 10px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: var(--nl-bg-card);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-pill);
  }

  &__reasons,
  &__mismatches,
  &__notes {
    padding: 0;
    margin: var(--nl-space-2) 0 0;
    list-style: none;
  }

  &__reasons li {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    line-height: 1.6;
  }

  /* 未命中说明：这一维得了 0 分（跨城市 / 超出服务半径）——
     它是「为什么排这么后」，不是加分项，视觉上比命中理由弱一级 */
  &__mismatches li {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
    line-height: 1.6;
  }

  /* 降级说明比推荐理由更弱一级：它是「数据缺失」的如实交代 */
  &__notes li {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
    line-height: 1.6;
  }
}

.photos {
  margin-top: var(--nl-space-3);

  &__grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: var(--nl-space-2);
    margin-top: var(--nl-space-2);

    img {
      width: 100%;
      aspect-ratio: 1;
      object-fit: cover;
      border-radius: 8px;
      border: 1px solid var(--nl-border);
    }
  }
}

.info {
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
    padding: var(--nl-space-3) 0;
    border-bottom: 1px solid var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }

    > span:first-child {
      flex-shrink: 0;
      width: 96px;
      font-size: var(--nl-font-caption);
      color: var(--nl-text-2);
    }

    > span:last-child {
      flex: 1;
      font-size: var(--nl-font-body);
      color: var(--nl-text-1);
    }
  }

  &__addr {
    line-height: 1.5;
    color: var(--nl-text-2);
  }

  &__fee {
    background: var(--nl-primary-ghost);
    margin: 0 calc(var(--nl-space-3) * -1);
    padding-left: var(--nl-space-3);
    padding-right: var(--nl-space-3);
    border-radius: 8px;

    > span {
      color: var(--nl-text-1) !important;
      font-weight: 600;
    }
  }

  &__fee-val {
    font-size: var(--nl-font-h2);
    color: var(--nl-primary-text) !important;
  }
}

/* 我的评价（E4 评价公信力闭环） */
.review {
  &__head {
    display: flex;
    align-items: center;
    gap: $nl-space-3;
    margin-bottom: $nl-space-2;
  }

  &__score {
    font-weight: 600;
    color: var(--nl-primary-text);
    font-size: var(--nl-font-h3);
  }

  &__time {
    color: var(--nl-text-2);
    font-size: var(--nl-font-caption);
  }

  &__invalid {
    margin-left: auto;
  }

  &__content {
    color: var(--nl-text-1);
    line-height: 1.6;
    margin-bottom: $nl-space-2;
  }

  &__tags {
    display: flex;
    flex-wrap: wrap;
    gap: $nl-space-2;
    margin-bottom: $nl-space-3;
  }

  &__tag {
    padding: 2px 10px;
    background: var(--nl-primary-light);
    color: var(--nl-primary-text);
    border-radius: $nl-radius-pill;
    font-size: var(--nl-font-caption);
  }

  &__reply {
    margin-top: $nl-space-3;
    padding: $nl-space-3;
    background: var(--nl-bg);
    border-radius: $nl-radius-chip;
    border-left: 3px solid var(--nl-primary);
  }

  &__reply-label {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    margin-bottom: $nl-space-1;
  }

  &__reply-body {
    color: var(--nl-text-1);
    line-height: 1.5;
  }
}

.logs {
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: 2px var(--nl-space-3);
    padding: var(--nl-space-2) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }
  }

  &__time {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    white-space: nowrap;
  }

  &__main {
    font-size: var(--nl-font-body);
    color: var(--nl-text-1);
  }

  &__remark {
    grid-column: 2;
  }
}

.actions {
  display: flex;
  gap: var(--nl-space-3);
  justify-content: center;
  padding: var(--nl-space-5) var(--nl-gutter);

  :deep(.el-button) {
    flex: 1;
  }
}
</style>
