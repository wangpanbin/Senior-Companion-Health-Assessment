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
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPhoneShell, NlCard, NlStatusChip, NlTimeline, NlAvatar, NlNoticeBar, NlSkeleton, NlEmpty } from '@/components'
import { getOrder, getOrderTimeline, cancelOrder } from '@/api/order'
import { listCheckins, connectProgressSocket } from '@/api/execution'
import { formatDateTime, CHECKIN_NODE_ORDER } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const orderId = route.params.id
const order = ref(null)
const statusLogs = ref([])
const checkins = ref([])
const loading = ref(true)
const loadError = ref(false)
const liveConnected = ref(false)

let socket = null

async function loadAll() {
  try {
    // 三个请求互不依赖，并发拉取，减少首屏等待
    const [detail, logs, checks] = await Promise.all([
      getOrder(orderId),
      getOrderTimeline(orderId).catch(() => []),
      listCheckins(orderId).catch(() => [])
    ])
    order.value = detail
    statusLogs.value = logs || []
    checkins.value = checks || []
    loadError.value = false
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
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

const canCancel = computed(() => order.value?.status === 'PENDING')
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
  <NlPhoneShell :nav="{ title: '订单详情' }">
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

      <!-- 订单概览 -->
      <NlCard>
        <div class="overview">
          <div>
            <div class="nl-h2 is-num">{{ order.orderNo }}</div>
            <div class="nl-caption nl-text-muted">下单时间 {{ formatDateTime(order.createTime) }}</div>
          </div>
          <NlStatusChip :status="order.status" :text="order.statusLabel" />
        </div>
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
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter);
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
    font-size: 16px;
    font-weight: 600;
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
      color: var(--nl-text-3);
    }

    > span:last-child {
      flex: 1;
      font-size: 14px;
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
    font-size: 18px;
    color: var(--nl-primary) !important;
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
    color: var(--nl-text-3);
    white-space: nowrap;
  }

  &__main {
    font-size: 14px;
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
