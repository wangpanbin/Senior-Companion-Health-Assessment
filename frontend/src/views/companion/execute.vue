<script setup>
/**
 * 订单执行打卡（M-11 · docs/design/design.md §4 · PRD §4.1 陪诊员第二段旅程）
 *
 * 数据来源（全部在 @/api）：
 *   - getOrder(orderId)            → 订单详情（含医院/科室/老人/地址/服务费）
 *   - getProgress(orderId)         → 进度快照（当前节点 + 下一节点 + 已完成列表）【铺底必拉】
 *   - listCheckins(orderId)        → 打卡记录
 *   - checkin(orderId, data)       → 打卡，服务端做距离校验
 *   - uploadExecutionPhoto(...)    → 上传现场照 / 取药凭证（multipart）
 *   - startService / completeService → 状态流转
 *   - connectProgressSocket(...)   → WebSocket 实时进度推送
 *   - getLocation / setLocationSharing / useLocationReporter → 实时位置（ADR-0022）
 *
 * 注意：
 *   - 定位由浏览器 geolocation 取，服务端校验；地图仅展示直线距离，不做路线规划与导航（ADR-0022）。
 *   - 服务中每 30 秒上报一次位置（useLocationReporter），家属端因此能实时看到人现在到哪了。
 *   - 订阅前必须先 getProgress 铺底；onBeforeUnmount 必须 socket.close()。
 *   - 打卡节点：DEPART/ARRIVE/IN_CONSULT/TAKE_MEDICINE/LEAVE/FINISH。
 */
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import {
  NlPhoneShell, NlMobileOnlyPage, NlTimeline, NlCard, NlStatusChip, NlSkeleton, NlEmpty, NlNoticeBar,
  NlMapPanel
} from '@/components'
import VitalSignForm from '@/components/health/VitalSignForm.vue'
import { getOrder, startService, completeService } from '@/api/order'
import {
  getProgress, listCheckins, checkin, uploadExecutionPhoto, connectProgressSocket,
  getLocation, setLocationSharing
} from '@/api/execution'
import { getCompanionVitals, withdrawCompanionVital } from '@/api/vitalSign'
import { formatVisitTime, formatMoney, formatTime, formatDateTime } from '@/utils/format'
import { confirmThen } from '@/utils/confirm'
import { useLocationReporter } from '@/composables/useLocationReporter'

const route = useRoute()
/**
 * 订单 id 必须是**响应式**的：Vue Router 4 在「同一条路由记录只换 params」时会
 * 复用组件实例（onMounted 不再触发）。快照成常量会让「URL 是 B、页面还显示 A」，
 * 陪诊员据此点的「打卡」会记到上一单头上。
 */
const orderId = computed(() => route.params.id)

const NODE_KEYS = ['DEPART', 'ARRIVE', 'IN_CONSULT', 'TAKE_MEDICINE', 'LEAVE', 'FINISH']
const NODE_LABELS = {
  DEPART: '出发',
  ARRIVE: '到达医院',
  IN_CONSULT: '就诊中',
  TAKE_MEDICINE: '取药',
  LEAVE: '离开医院',
  FINISH: '订单完成'
}

const order = ref(null)
const progress = ref(null)
const checkins = ref([])
const loading = ref(true)
/** R4：主数据加载失败（与「未找到该订单」区分） */
const loadError = ref(false)

const connected = ref(false)
const checking = ref(false)
const completing = ref(false)
/** V3.x：「开始服务」的 in-flight 态，避免连点重复提交 */
const starting = ref(false)
const summary = ref('')

/** 现场照 / 取药凭证 URL（来自 uploadExecutionPhoto） */
const photoUrls = ref([])
const uploading = ref(false)

/**
 * 本单已录入的体征（V4 · P2-2）。
 *
 * 服务端按订单维度提供查询（GET /api/execution/{orderId}/vitals），
 * 首屏与刷新都会回显；录入成功后把服务端返回的记录就地插入，
 * 撤回成功后从列表移除，不额外整表重拉。
 *
 * 可读边界与服务端一致：服务中 / 已完成 / 已评价可读；
 * 可写边界更窄 —— 只有服务中能录入与撤回。
 */
const orderVitals = ref([])
const vitalsLoading = ref(false)
const withdrawingVital = ref(null)
const READABLE_STATUSES = ['IN_SERVICE', 'COMPLETED', 'REVIEWED']

const orderStatus = computed(
  () => progress.value?.orderStatus || order.value?.status || ''
)
const subable = computed(
  () => orderStatus.value === 'ACCEPTED' || orderStatus.value === 'IN_SERVICE'
)
/** 本单体征可读（服务中 / 已完成 / 已评价） */
const canReadVitals = computed(() => READABLE_STATUSES.includes(orderStatus.value))
/** 本单体征可写（仅服务中） */
const canWriteVitals = computed(() => orderStatus.value === 'IN_SERVICE')

/** 设备定位（来自 geolocation，仅用于距离校验与地图展示） */
const location = ref({ lng: '', lat: '', addr: '', getting: false })

/* ==================== 实时位置（ADR-0022） ==================== */

/*
 * 三件事：
 *   ① 地图：把「当前定位」从纯坐标文字升级为地图（无 AK / 加载失败时自动降级为文字卡片）
 *   ② 上报：服务中每 30 秒推一次位置（只在页面可见时），家属端地图因此会动
 *   ③ 开关：陪诊员可随时暂停共享（位置是一个真实工作者的行踪信息）
 *
 * ⚠️ 打卡与上报的关系：打卡走 `/checkin`（履约证据，永远落库）；
 *    位置走 `/location`（可被抽稀）。**暂停共享不影响打卡**：
 *    共享只决定「是否给家属看实时位置」，不决定「是否记录履约证据」。
 */
const locationInfo = ref(null)
const sharingSaving = ref(false)

/** 地图上的打卡点（已打过的节点）；只保留有坐标的 */
const mapCheckins = computed(() =>
  (checkins.value || [])
    .filter((c) => c.longitude && c.latitude)
    .map((c) => ({
      nodeLabel: c.nodeLabel,
      time: formatDateTime(c.checkinTime, ''),
      lng: c.longitude,
      lat: c.latitude
    }))
)

/**
 * 地图上的「我」：**优先用刚取到的实时定位**（refreshLocation 的本地结果），
 * 没有再回落到服务端快照 —— 本地值更新更快，且刚刷新时用户就想看自己现在在哪。
 *
 * `staleSeconds` 只在用服务端快照时才有意义；本地定位一律视为 0（刚取的）。
 */
const mapPoint = computed(() => {
  if (location.value.lng && location.value.lat) {
    return { lng: location.value.lng, lat: location.value.lat, recordTime: null, staleSeconds: 0 }
  }
  const info = locationInfo.value
  if (!info?.longitude || !info?.latitude) return null
  return {
    lng: info.longitude,
    lat: info.latitude,
    recordTime: info.recordTime,
    staleSeconds: info.staleSeconds
  }
})

/** 地图上的医院点：来自服务端快照（订单坐标只在 /location 里下发） */
const orderPoint = computed(() => ({
  lng: locationInfo.value?.orderLongitude ?? null,
  lat: locationInfo.value?.orderLatitude ?? null
}))

/** 位置共享开关的 local 状态：`true` 即可上报，`false` 时 useLocationReporter 自动停止 */
const sharingEnabled = computed(() => locationInfo.value?.sharingEnabled !== false)

/** 是否应当上报位置：接单/服务中 + 共享开着 */
const shouldReportLocation = computed(() => subable.value && sharingEnabled.value)

/**
 * 上报调度：`useLocationReporter` 自己会在 enabled 变 true/false 时启停，
 * 也会在 onBeforeUnmount 里 stop，页面不需要手动调用 start/stop。
 * 这里保留引用是为了给「刷新定位」按钮手动补报一次（reportNow）。
 */
const { reportNow } = useLocationReporter({
  orderId: () => orderId.value,
  enabled: () => shouldReportLocation.value,
  onReported: (vo) => {
    locationInfo.value = vo
  },
  onDegraded: () => {
    // 连续失败不弹 toast（会打断打卡），只把状态行交给模板渲染
    locationFailed.value = true
  }
})
const locationFailed = ref(false)

/** 拉一次位置快照（铺底）。失败只降级地图卡，不影响打卡主流程 */
async function loadLocation() {
  const target = orderId.value
  const info = await getLocation(target).catch(() => null)
  if (String(target) !== String(orderId.value)) return
  locationInfo.value = info
  locationFailed.value = false
}

/** 开关位置共享 */
async function toggleSharing(enabled) {
  if (sharingSaving.value) return
  sharingSaving.value = true
  try {
    const vo = await setLocationSharing(orderId.value, enabled)
    locationInfo.value = vo
    locationFailed.value = false
    ElMessage.success(enabled ? '已恢复位置共享' : '已暂停位置共享，家属将看不到你的实时位置')
  } catch {
    // 拦截器已弹错
  } finally {
    sharingSaving.value = false
  }
}

/** 各节点完成时间（来自打卡记录） */
const checkinTimeMap = computed(() => {
  const m = {}
  for (const c of checkins.value || []) {
    if (c.node) m[c.node] = c.checkinTime
  }
  return m
})

const steps = computed(() => {
  const finished = new Set(progress.value?.finishedNodes || [])
  const next = progress.value?.nextNode || (finished.size ? null : 'DEPART')
  return NODE_KEYS.map((key) => {
    let status = 'todo'
    if (finished.has(key)) status = 'done'
    else if (key === next) status = 'current'
    const t = checkinTimeMap.value[key]
    return {
      key,
      label: NODE_LABELS[key],
      status,
      time: t ? formatTime(t) : '',
      description:
        status === 'current'
          ? '点击打卡完成该节点'
          : status === 'todo'
            ? '请先完成上一个节点'
            : ''
    }
  })
})

/* ===================== 数据加载 ===================== */

async function loadAll() {
  // 记下本次请求针对的订单：请求在途时 URL 可能又换了订单，回来时要能判断响应是否过期
  const target = orderId.value
  loading.value = true
  try {
    const [o, p, c] = await Promise.all([
      getOrder(target),
      getProgress(target),
      listCheckins(target)
    ])
    // 过期响应直接丢弃：否则会把上一单的数据显示在当前 URL 下
    if (String(target) !== String(orderId.value)) return
    order.value = o ?? null
    progress.value = p ?? null
    checkins.value = c || []
    loadError.value = false
  } catch {
    if (String(target) !== String(orderId.value)) return
    order.value = null
    progress.value = null
    checkins.value = []
    loadError.value = true
  } finally {
    if (String(target) === String(orderId.value)) loading.value = false
  }
  // 实时位置（ADR-0022）：接单后才有意义。单独拉，失败只降级地图卡
  if (subable.value) {
    await loadLocation()
  } else {
    locationInfo.value = null
  }
}

/** 收到推送 / 操作后只重拉进度 + 打卡记录（轻量） */
async function reloadProgress() {
  const target = orderId.value
  try {
    const [p, c] = await Promise.all([getProgress(target), listCheckins(target)])
    if (String(target) !== String(orderId.value)) return
    progress.value = p ?? null
    checkins.value = c || []
  } catch {
    // 拦截器已弹错
  } finally {
    maybeCloseSocket()
  }
}
/* ===================== 体征代录（V4 · P2-2） ===================== */

/**
 * 拉取本单已录入的体征。
 *
 * 状态不可读时不发请求（服务端也会返回 3002，但没必要让用户看到一次红色提示）；
 * 响应回来时 URL 可能已换成另一单，按订单 id 比对后丢弃过期响应。
 */
async function loadVitals() {
  const target = orderId.value
  if (!canReadVitals.value) {
    orderVitals.value = []
    return
  }
  vitalsLoading.value = true
  try {
    const res = await getCompanionVitals(target, { size: 100 })
    if (String(target) !== String(orderId.value)) return
    orderVitals.value = res?.records || []
  } catch {
    if (String(target) === String(orderId.value)) orderVitals.value = []
  } finally {
    if (String(target) === String(orderId.value)) vitalsLoading.value = false
  }
}

/**
 * 顶部快捷入口：跳到「本单体征记录」卡片。
 *
 * 只滚动定位，不改变板块顺序；目标不存在时静默返回（例如订单状态不可读）。
 */
function jumpToVitals() {
  const target = document.getElementById('order-vitals')
  if (target) {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}

/** 录入成功：把服务端返回的这条记录就地加进本单列表 */
function handleVitalCreated(record) {
  if (record && record.id != null) {
    orderVitals.value = [record, ...orderVitals.value]
  }
}

/** 撤回本人本单的记录：服务端会再校验一次「订单仍在服务中」 */
async function handleWithdrawVital(record) {
  const ok = await confirmThen(() =>
    ElMessageBox.confirm('撤回后这条记录将不再显示，可重新录入。确认撤回？', '撤回体征记录', {
      confirmButtonText: '撤回',
      cancelButtonText: '取消',
      type: 'warning'
    })
  )
  if (!ok) return
  withdrawingVital.value = record.id
  try {
    await withdrawCompanionVital(orderId.value, record.id)
    ElMessage.success('记录已撤回')
    orderVitals.value = orderVitals.value.filter((item) => item.id !== record.id)
  } finally {
    withdrawingVital.value = null
  }
}

/* ===================== 定位 ===================== */

function acquireLocation() {
  return new Promise((resolve) => {
    if (!('geolocation' in navigator)) {
      resolve(null)
      return
    }
    navigator.geolocation.getCurrentPosition(
      (pos) =>
        resolve({
          lng: String(pos.coords.longitude),
          lat: String(pos.coords.latitude),
          addr: ''
        }),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: 8000 }
    )
  })
}

async function refreshLocation() {
  location.value.getting = true
  const loc = await acquireLocation()
  if (loc) {
    location.value = { ...loc, getting: false }
    // 手动刷新后立刻上报一次：让家属端即时看到新位置，而不是等下一个 30 秒周期。
    // 若后端限流（<10 秒）会回 4004，reportNow 内部已把它当作正常答复忽略
    reportNow()
  } else {
    location.value = { lng: '', lat: '', addr: '', getting: false }
    ElMessage.warning('无法获取定位，请检查浏览器定位权限')
  }
}

/* ===================== 操作 ===================== */

/**
 * URL 里的订单 id 与页面正在展示的订单 id 是否一致。
 *
 * 路由复用组件实例时存在一个竞态窗口：URL 已经换成 B，详情还没回来，
 * 页面显示的仍是 A。此时打卡 / 开始服务会作用到 B 这一单 —— 用户看到的是 A，
 * 记的却是 B 的账。
 */
function isDisplayedOrderCurrent() {
  const shown = order.value?.id ?? progress.value?.orderId
  // 详情里没有 id 时没有可比对的基准，不做拦截（避免把用户锁死在页面上）
  if (shown == null) return true
  return String(shown) === String(orderId.value)
}

async function doCheckin() {
  const node = progress.value?.nextNode
  if (!node) return
  // 二次校验：页面展示的订单必须就是 URL 指向的订单，否则拒绝提交、不发请求
  if (!isDisplayedOrderCurrent()) {
    ElMessage.warning('页面已切换到其它订单，请刷新后重试')
    return
  }
  const loc = location.value
  if (!loc.lng || !loc.lat) {
    ElMessage.warning('尚未获取到定位，请先点击「刷新定位」')
    return
  }
  checking.value = true
  try {
    await checkin(orderId.value, {
      node,
      longitude: loc.lng,
      latitude: loc.lat,
      address: loc.addr || undefined,
      photos: photoUrls.value.length ? [...photoUrls.value] : undefined
    })
    ElMessage.success(`「${NODE_LABELS[node]}」打卡成功`)
    // M5 验收：打卡后 3 秒内必须有显性提醒（老人对静默反馈无感知）
    ElNotification({
      title: '打卡成功',
      message: `「${NODE_LABELS[node]}」已记录`,
      type: 'success',
      duration: 3000
    })
    await reloadProgress()
  } catch {
    // 拦截器已弹错（距离超限 4001 / 重复 4002 / 状态 3002 等）；刷新同步状态
    await reloadProgress()
  } finally {
    checking.value = false
  }
}

async function doStart() {
  // in-flight 锁必须在第一行**同步**置位：放到 await 之后的话，
  // 两次连点会一起穿过 await，锁形同虚设（重复调 start 接口）
  if (starting.value) return
  starting.value = true
  // 二次校验：页面展示的订单必须就是 URL 指向的订单
  if (!isDisplayedOrderCurrent()) {
    ElMessage.warning('页面已切换到其它订单，请刷新后重试')
    starting.value = false
    return
  }
  try {
    await startService(orderId.value)
    ElMessage.success('已开始服务')
    await reloadProgress()
  } catch {
    await reloadProgress()
  } finally {
    starting.value = false
  }
}

async function doComplete() {
  // 二次校验：页面展示的订单必须就是 URL 指向的订单
  if (!isDisplayedOrderCurrent()) {
    ElMessage.warning('页面已切换到其它订单，请刷新后重试')
    return
  }
  completing.value = true
  try {
    await completeService(orderId.value, {
      summary: summary.value ? summary.value.trim() : undefined,
      photos: photoUrls.value.length ? [...photoUrls.value] : undefined
    })
    ElMessage.success('服务已完成，等待家属评价')
    summary.value = ''
    await reloadProgress()
  } catch {
    await reloadProgress()
  } finally {
    completing.value = false
  }
}

/* ===================== 照片上传 ===================== */

async function onPhotoChange(e) {
  const file = e.target.files && e.target.files[0]
  if (!file) return
  if (photoUrls.value.length >= 6) {
    ElMessage.warning('最多上传 6 张照片')
    e.target.value = ''
    return
  }
  // 前端预校验大小（≤10MB）：文案承诺了上限，就不能让它只是说说
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('单张照片超过 10MB，无法上传')
    e.target.value = ''
    return
  }
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const res = await uploadExecutionPhoto(orderId.value, fd)
    if (res?.url) {
      photoUrls.value.push(res.url)
      ElMessage.success('照片已上传')
    }
  } catch {
    // 拦截器已弹错
  } finally {
    uploading.value = false
    e.target.value = ''
  }
}

function removePhoto(i) {
  photoUrls.value.splice(i, 1)
}

/* ===================== WebSocket ===================== */

let socket = null

function setupSocket() {
  if (!subable.value || socket) return
  socket = connectProgressSocket(orderId.value, {
    onEvent: (msg) => {
      if (msg && msg.type === 'ORDER_PROGRESS') reloadProgress()
    },
    onStatus: (s) => {
      connected.value = s === 'open'
    }
  })
}

/** 断开推送订阅（换订单 / 组件卸载都要走这里） */
function closeSocket() {
  if (socket) {
    socket.close()
    socket = null
  }
  connected.value = false
}

function maybeCloseSocket() {
  if (!subable.value && socket) {
    closeSocket()
  }
}

onMounted(async () => {
  await loadAll()
  await loadVitals()
  if (subable.value) setupSocket()
  refreshLocation()
})

// 同一条路由记录只换 params 时组件实例会被复用，onMounted 不再触发 —— 这里补上重拉。
// 换订单必须先断开旧订阅再按新订单重连，否则会出现「推送的是 A、页面看的是 B」。
watch(orderId, async () => {
  closeSocket()
  orderVitals.value = []
  await loadAll()
  await loadVitals()
  if (subable.value) setupSocket()
})

onBeforeUnmount(() => {
  closeSocket()
})
</script>

<template>
  <!-- mobile-only 路由（ADR-0008）：宽屏下由 NlMobileOnlyPage 换成 NlMobileOnlyNotice -->
  <NlMobileOnlyPage>
  <NlPhoneShell :nav="{ title: '订单执行' }">
    <NlSkeleton v-if="loading" :count="3" class="pad" />

    <NlEmpty
      v-else-if="loadError"
      type="network"
      title="订单数据加载失败"
      action-text="重试"
      @action="loadAll"
    />

    <NlEmpty
      v-else-if="!order"
      type="empty"
      title="未找到该订单"
      description="订单可能不存在或您无查看权限"
    />

    <template v-else>
      <!-- 订单摘要 -->
      <NlCard class="summary">
        <div class="summary__title">
          {{ order.hospital }}
          <span class="nl-caption nl-text-muted">· {{ order.department }}</span>
        </div>
        <div class="nl-caption nl-text-muted summary__line">
          服务时间 <span class="is-num">{{ formatVisitTime(order.visitTime) }}</span>
          <template v-if="order.address"> · {{ order.address }}</template>
        </div>
        <div class="nl-caption nl-text-muted summary__line">
          <template v-if="order.elderName">就诊人 {{ order.elderName }}<template v-if="order.elderAge != null"> · {{ order.elderAge }}岁</template></template>
          · 服务费 <span class="is-num">{{ formatMoney(order.fee) }}</span>
        </div>
        <div class="summary__row">
          <NlStatusChip :status="orderStatus" :text="progress?.orderStatusLabel || order?.statusLabel" :dot="subable" />
          <span class="nl-caption nl-text-muted is-num">
            进度 {{ progress?.progressPercent != null ? progress.progressPercent : 0 }}%
          </span>
        </div>
        <el-button v-if="canReadVitals" link type="primary" class="vitals-jump" @click="jumpToVitals">
          ↓ 本单体征记录
        </el-button>
        <el-button v-if="order.companionId" type="primary" plain @click="$router.push(`/chat/${orderId}`)">联系对方</el-button>
      </NlCard>

      <!-- 6 节点时间线 -->
      <NlCard title="服务节点">
        <p class="nl-caption nl-text-muted node-hint">
          节点顺序不可回退 · 同节点只可打卡一次 · 当前节点脉冲高亮
        </p>
        <NlTimeline :steps="steps">
          <template #current>
            <el-button
              v-if="subable && progress?.nextNode"
              type="primary"
              round
              size="default"
              :loading="checking"
              class="check-btn"
              @click="doCheckin"
            >
              打卡 · {{ NODE_LABELS[progress.nextNode] }}
            </el-button>
          </template>
        </NlTimeline>
      </NlCard>

      <!-- 本单体征（V4 · P2-2）：服务中可代录，已完成 / 已评价只读回看 -->
      <NlCard v-if="canReadVitals" id="order-vitals" title="本单体征记录">
        <p class="nl-caption nl-text-muted node-hint">
          <template v-if="canWriteVitals">
            记录会进入这位老人的体征时间线，家属与老人端可查看；本单已录入的记录在下方列出
          </template>
          <template v-else>
            订单已结束，仅可回看本单已录入的体征记录，不可再录入或撤回
          </template>
        </p>

        <VitalSignForm
          v-if="canWriteVitals"
          mode="companion"
          :order-id="orderId"
          @created="handleVitalCreated"
        />

        <p v-if="vitalsLoading" class="nl-caption nl-text-muted vitals-loading">正在加载本单记录…</p>
        <NlEmpty
          v-else-if="!orderVitals.length"
          title="本单暂无体征记录"
          :description="canWriteVitals ? '可在上方录入血压、血糖或体重' : '本单没有代录过体征记录'"
        />
        <ul v-else class="vlist">
          <li v-for="v in orderVitals" :key="v.id" class="vrow">
            <div class="vrow__main">
              <span class="nl-h3">{{ v.vitalTypeLabel }}</span>
              <span class="vrow__value is-num">{{ v.valueText }}</span>
            </div>
            <div class="vrow__meta">
              <span class="nl-caption nl-text-muted is-num">{{ formatDateTime(v.measuredAt) }}</span>
              <el-button
                v-if="canWriteVitals"
                link
                type="danger"
                size="small"
                :loading="withdrawingVital === v.id"
                @click="handleWithdrawVital(v)"
              >
                撤回
              </el-button>
            </div>
          </li>
        </ul>
      </NlCard>

      <!-- 打卡记录 -->
      <NlCard v-if="checkins.length" title="打卡记录">
        <ul class="clist">
          <li v-for="c in checkins" :key="c.id" class="crow">
            <div class="crow__main">
              <span class="nl-h3">{{ c.nodeLabel }}</span>
              <span class="nl-caption nl-text-muted is-num">{{ formatDateTime(c.checkinTime) }}</span>
            </div>
            <div v-if="c.address" class="nl-caption nl-text-weak crow__addr">{{ c.address }}</div>
            <div v-if="c.isAbnormal" class="nl-caption crow__abn">异常打卡（距离校验未通过）</div>
          </li>
        </ul>
      </NlCard>

      <!-- 当前定位 + 地图（ADR-0022）：地图打不开时自动降级为文字卡片 -->
      <NlCard title="当前定位">
        <template #extra>
          <span :class="['on-site', location.lng && location.lat ? 'is-ok' : 'is-fail']">
            <span class="dot" />
            {{ location.getting ? '定位中…' : location.lng && location.lat ? '已获取' : '未获取' }}
          </span>
        </template>
        <div class="loc">
          <NlMapPanel
            :order-lng="orderPoint.lng"
            :order-lat="orderPoint.lat"
            :point="mapPoint"
            :checkins="mapCheckins"
            :distance-meters="locationInfo?.distanceMeters ?? null"
            :sharing-enabled="sharingEnabled"
            :height="180"
            :show-updated-at="false"
          />
          <div v-if="location.lng && location.lat" class="loc__coord is-num">
            经度 {{ location.lng }} · 纬度 {{ location.lat }}
          </div>
          <el-button
            type="primary"
            plain
            round
            size="small"
            :loading="location.getting"
            @click="refreshLocation"
          >
            刷新定位
          </el-button>

          <!--
            位置共享开关（ADR-0022 D12/D13）：
            位置是一个真实工作者的行踪信息，陪诊员可随时暂停；
            **暂停不影响打卡** —— 那是履约证据，不归这个开关管。
          -->
          <div v-if="subable" class="loc__share">
            <div class="loc__share-label">
              <span>共享实时位置给家属</span>
              <span class="nl-caption nl-text-weak">
                关闭后家属看不到你的实时位置，打卡不受影响
              </span>
            </div>
            <el-switch
              :model-value="sharingEnabled"
              :loading="sharingSaving"
              aria-label="共享实时位置给家属"
              @update:model-value="toggleSharing"
            />
          </div>
          <NlNoticeBar v-if="locationFailed" tone="warning">
            定位暂时不可用，请检查浏览器定位权限；打卡仍需先刷新定位
          </NlNoticeBar>
          <p class="nl-caption nl-text-weak loc__note">
            地图只显示与医院的直线距离（仅供参考），不做路线规划与导航；
            位置由本机上报，锁屏或切到后台时会暂停。
          </p>
        </div>
      </NlCard>

      <!-- 现场照 / 取药凭证 -->
      <NlCard v-if="subable" title="现场照 / 取药凭证">
        <ul class="photos">
          <li v-for="(u, i) in photoUrls" :key="u" class="photo">
            <span class="photo__url nl-caption is-num">{{ u }}</span>
            <el-button text size="small" type="danger" @click="removePhoto(i)">删除</el-button>
          </li>
        </ul>
        <div class="upload">
          <input type="file" accept="image/*" :disabled="uploading" @change="onPhotoChange" />
          <span class="nl-caption nl-text-weak">支持 jpg/png/webp，单张 ≤10MB，最多 6 张</span>
        </div>
      </NlCard>

      <!-- 开始 / 完成服务 -->
      <NlCard v-if="subable" title="服务状态">
        <el-button
          v-if="orderStatus === 'ACCEPTED'"
          type="primary"
          round
          size="large"
          class="act-btn"
          :loading="starting"
          @click="doStart"
        >
          开始服务
        </el-button>
        <template v-else-if="orderStatus === 'IN_SERVICE'">
          <el-input
            v-model="summary"
            type="textarea"
            :rows="3"
            maxlength="500"
            placeholder="服务小结（仅记录过程，如：09:10 到院、全程陪同就诊、协助取药）"
          />
          <el-button
            type="primary"
            round
            size="large"
            class="act-btn"
            :loading="completing"
            @click="doComplete"
          >
            完成服务
          </el-button>
        </template>
        <!--
          推送断开时必须显式提示：原来 v-if="connected" 让这行直接不渲染，
          用户不知道自己看的是过期进度（范式同 family/order-detail.vue）。
        -->
        <NlNoticeBar v-if="!connected" tone="warning">实时推送未连接 · 进度可能有延迟，请重新进入页面查看</NlNoticeBar>
        <p v-else class="nl-caption nl-text-muted live">● 实时进度已连接</p>
      </NlCard>
    </template>
  </NlPhoneShell>
  </NlMobileOnlyPage>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.pad {
  padding: 0 var(--nl-gutter);
}

.summary {
  background: linear-gradient(135deg, var(--nl-primary-light) 0%, var(--nl-bg-card) 90%);

  &__title {
    font-size: var(--nl-font-h3);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__line {
    margin-top: 4px;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--nl-space-3);
  }
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--nl-space-2);
}

.node-hint {
  margin: 0 0 var(--nl-space-3);
  padding: var(--nl-space-2) var(--nl-space-3);
  background: var(--nl-primary-ghost);
  border-radius: 8px;
}

.check-btn {
  width: 100%;
}

/* 顶部快捷入口不占满整行，保持摘要卡片的既有节奏 */
.vitals-jump {
  align-self: flex-start;
  padding-left: 0;
}

.clist {
  padding: 0;
  margin: 0;
  list-style: none;

  .crow {
    padding: var(--nl-space-2) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }

    &__main {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--nl-space-2);
    }

    &__addr {
      margin-top: 2px;
    }

    &__abn {
      margin-top: 2px;
      color: var(--nl-danger-text);
    }
  }
}

.on-site {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  font-size: var(--nl-font-caption);
  font-weight: 500;

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
  }

  &.is-ok { color: var(--nl-success-text); .dot { background: var(--nl-success); } }
  &.is-fail { color: var(--nl-danger-text); .dot { background: var(--nl-danger); } }
}

.loc {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  &__coord {
    padding: var(--nl-space-2) var(--nl-space-3);
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border-radius: 6px;
  }

  // 位置共享开关：左边是标签 + 说明，右边是开关（适老化：整个行高 ≥ 48px）
  &__share {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--nl-space-3);
    min-height: 48px;
    padding: var(--nl-space-2) 0;
    border-top: 1px solid var(--nl-border);
  }

  &__share-label {
    display: flex;
    flex-direction: column;
    gap: 2px;
    font-size: var(--nl-font-body);
    color: var(--nl-text-1);
  }

  &__note {
    margin: 0;
  }
}

.photos {
  padding: 0;
  margin: 0 0 var(--nl-space-3);
  list-style: none;

  .photo {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
    padding: var(--nl-space-2) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &__url {
      flex: 1;
      word-break: break-all;
    }
  }
}

.upload {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.act-btn {
  width: 100%;
  margin-top: var(--nl-space-3);
}

.live {
  margin: var(--nl-space-2) 0 0;
  color: var(--nl-success-text);
}

/* ---------- 服务中代录体征（V4 · P2-2） ---------- */

.vitals-loading {
  margin: var(--nl-space-3) 0 0;
  text-align: center;
}

.vlist {
  padding: 0;
  margin: var(--nl-space-3) 0 0;
  list-style: none;

  .vrow {
    padding: var(--nl-space-2) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }

    &__main {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--nl-space-2);
    }

    &__value {
      color: var(--nl-text-1);
      font-family: var(--nl-font-num);
      font-size: var(--nl-font-h3);
      font-weight: 650;
    }

    &__meta {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--nl-space-2);
      margin-top: 2px;
    }
  }
}
</style>
