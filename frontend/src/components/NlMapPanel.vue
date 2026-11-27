<script setup>
/**
 * NlMapPanel · 实时位置地图（ADR-0022）
 *
 * 同一份组件同时供 **家属端订单详情**（M-16）与 **陪诊员打卡页**（M-11）使用，
 * 差异只体现在传入的 props 上。
 *
 * ## 三级降级（每一级都必须说清「为什么没地图」）
 *
 * | 条件 | UI |
 * |---|---|
 * | 未配置 `VITE_BAIDU_MAP_AK` | 文字卡片 +「未配置地图 AK」 |
 * | AK 无效 / Referer 被拒 / 8 秒超时 | 文字卡片 +「地图暂不可用，已切换为文字模式」 |
 * | 加载成功 | 地图 |
 *
 * **降级是主路径而不是兜底**：CI 无网络无 AK、多数开发者本地也不配 AK，
 * 文字卡片才是评委机器上 100% 会被执行到的分支。
 *
 * ## 坐标
 *
 * 入参一律 **WGS84**（与 `companion_track` / `order_checkin` 同口径），
 * 只在渲染前用 `wgs84ToBd09` 转成百度口径。**不要在别处再做转换**。
 *
 * ## 文案红线
 *
 * 只显示**直线距离**，文案必须是「距医院直线约 x」。
 * 不出现「预计到达 / 导航 / 路线」——那会暗示我们提供导航服务（一期红线原文）；
 * 也不能写「距老人」——终点是医院坐标，写成老人就是界面在说谎。
 *
 * @see docs/adr/0022-map-live-tracking.md
 */
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import NlNoticeBar from './NlNoticeBar.vue'
import { hasMapAk, loadBaiduMap } from '@/utils/baiduMap'
import { wgs84ToBd09 } from '@/utils/coordConvert'

const props = defineProps({
  /** 医院经度（WGS84）；订单未登记坐标时传 null，地图只画陪诊员点 */
  orderLng: { type: [String, Number], default: null },
  /** 医院纬度（WGS84） */
  orderLat: { type: [String, Number], default: null },
  /**
   * 陪诊员最新位置（WGS84）
   * `{ lng, lat, recordTime, staleSeconds }`；从未上报时传 null
   */
  point: { type: Object, default: null },
  /** 轨迹点列表（WGS84）：`[{ lng, lat }]`，按时间升序 */
  track: { type: Array, default: () => [] },
  /** 已打卡节点：`[{ nodeLabel, time, lng, lat }]` */
  checkins: { type: Array, default: () => [] },
  /** 与医院坐标的直线距离（米）；null 表示算不出来 */
  distanceMeters: { type: Number, default: null },
  /** 位置共享是否开启 */
  sharingEnabled: { type: Boolean, default: true },
  /** 地图高度（px） */
  height: { type: Number, default: 200 },
  /** 是否显示「距今多久」那一行 */
  showUpdatedAt: { type: Boolean, default: true }
})

/** 地图状态：`loading` 加载中 / `ready` 可用 / `fallback` 降级 */
const state = ref('loading')
/** 降级原因，直接渲染在卡片上，让用户知道「为什么没地图」 */
const fallbackReason = ref('')
/** 地图容器 */
const containerRef = ref(null)
/** BMap 命名空间与实例不参与响应式（深度代理会破坏百度内部对象） */
const bmap = shallowRef(null)
const mapInstance = shallowRef(null)
/** 覆盖物引用，供数据变化时整体重绘 */
const overlays = shallowRef([])

let destroyed = false

/** 地图渲染看门狗（毫秒）：正常 1~2 秒即可见，4 秒还没画出来就是出了问题 */
const MAP_RENDER_WATCHDOG_MS = 4000

/* ------------------------------------------------------------------ */
/* 文案                                                                */
/* ------------------------------------------------------------------ */

/**
 * 距离文案：一律「距医院直线约 x」，且必须带「仅供参考」。
 *
 * 长度策略：< 1000 米用米（家属对「还有 300 米」有直觉），≥ 1000 米用公里一位小数。
 * 不显示「预计 3 分钟」这类由距离推断出的时间 —— 那需要速度假设，会变成一句假话。
 */
const distanceText = computed(() => {
  const meters = props.distanceMeters
  if (meters == null || Number.isNaN(meters)) {
    return '尚未获取到位置'
  }
  if (meters < 1000) {
    return `陪诊员距医院直线约 ${meters} 米`
  }
  return `陪诊员距医院直线约 ${(meters / 1000).toFixed(1)} 公里`
})

/**
 * 「多久之前更新」文案。
 *
 * `staleSeconds` 由**服务端**盖章（不信客户端时钟 —— 老人机与家属手机的时钟
 * 经常差几分钟，用本地时间算会出现「30 秒前」显示成「-2 分钟前」）。
 */
const updatedText = computed(() => {
  if (!props.showUpdatedAt) return ''
  const stale = props.point?.staleSeconds
  if (stale == null) return ''
  if (stale < 60) return `${Math.max(0, Math.round(stale))} 秒前更新`
  const minutes = Math.round(stale / 60)
  if (minutes < 60) return `${minutes} 分钟前更新`
  return `${Math.round(minutes / 60)} 小时前更新`
})

/**
 * 位置是否已陈旧（超过 180 秒未更新）。
 *
 * 超过阈值时**不允许**继续显示成「在线」：手机锁屏 / 切后台时 Web 拿不到定位，
 * 这是平台限制，但界面不能因此说谎。
 */
const isStale = computed(() => {
  const stale = props.point?.staleSeconds
  return stale != null && stale > 180
})

/** 降级卡片里显示的「状态行」：暂停共享优先于陈旧，因为它是确定性的事实 */
const statusText = computed(() => {
  if (!props.sharingEnabled) return '陪诊员已暂停位置共享'
  if (isStale.value) return '位置已较长时间未更新（手机锁屏或网络中断时无法定位）'
  return updatedText.value
})

/* ------------------------------------------------------------------ */
/* 地图初始化与重绘                                                    */
/* ------------------------------------------------------------------ */

function clearOverlays() {
  const map = mapInstance.value
  if (!map) return
  overlays.value.forEach((item) => {
    try {
      map.removeOverlay(item)
    } catch {
      // 已被移除 / 地图已销毁：忽略，不让一次清理失败打断整个重绘
    }
  })
  overlays.value = []
}

function addOverlay(item) {
  const map = mapInstance.value
  if (!map || !item) return
  map.addOverlay(item)
  overlays.value.push(item)
}

/**
 * 圆形覆盖物（用 {@code BMap.Circle} 而不是 {@code BMap.Symbol} 图标）。
 *
 * <p>⚠️ <b>不要改成 Symbol 图标</b>：JSAPI 4.0 下把 `new BMap.Symbol(...)` 当 Marker 的 icon 用
 * 会在内部 `createMarkerData` 抛 `Cannot set properties of null (setting 'id')`，
 * 控制台报错、点也不画（2026-11-27 实测踩过）。</p>
 *
 * <p>半径按**米**算 —— 缩放时自动跟着变大变小，比固定像素图标更符合「位置点」的语义。</p>
 *
 * @param {number} radius 半径（米）。30~60 米在 16 级缩放下是明显的圆形。
 */
function dotOverlay(BMap, point, color, radius = 40) {
  return new BMap.Circle(point, radius, {
    strokeColor: '#fff',
    strokeWeight: 3,
    strokeOpacity: 0.95,
    fillColor: color,
    fillOpacity: 0.9
  })
}

/**
 * 文字标签（代替图标作视觉标记）。
 *
 * <p>只用圆点会在缩得很远的图上变成一个像素；带文字的标签才能让家属一眼看出
 * 「哪个是陪诊员、哪个是医院」—— 这正是这张图存在的意义。</p>
 *
 * <p>百度 4.0 的 `BMap.Label` 默认带白底圆角边框，与我们的设计令牌差得远，
 * 所以这里用 `setStyle` 改成小胶囊（底色 + 白字）。注意 `setStyle` 不接受 `color`
 * 之外的字体设置，字号必须在 CSS 里写。</p>
 */
function labelOverlay(BMap, point, text, bg) {
  const label = new BMap.Label(text, {
    position: point,
    offset: new BMap.Size(-20, 18)
  })
  label.setStyle({
    color: '#fff',
    backgroundColor: bg,
    border: '1px solid #fff',
    borderRadius: '10px',
    padding: '1px 6px',
    fontSize: '12px',
    lineHeight: '16px',
    whiteSpace: 'nowrap'
  })
  return label
}

/**
 * 把 props 画到地图上（全量重绘）。
 *
 * 全量重绘而不是增量 diff：单订单的点数是几十个量级（轨迹已抽稀），
 * 重绘成本远低于维护「哪个点对应哪个覆盖物」的映射表 —— 后者才是 bug 温床。
 */
function renderOverlays() {
  const BMap = bmap.value
  const map = mapInstance.value
  if (!BMap || !map || destroyed) return

  clearOverlays()
  const points = []

  // ① 医院点（红）：圆点 + 文字标签
  const hospital = wgs84ToBd09(props.orderLng, props.orderLat)
  if (hospital) {
    const p = new BMap.Point(hospital.lng, hospital.lat)
    points.push(p)
    const marker = new BMap.Marker(p)
    marker.addEventListener('click', () => {
      map.openInfoWindow(new BMap.InfoWindow(`医院<br>${distanceText.value}`), p)
    })
    addOverlay(marker)
    addOverlay(labelOverlay(BMap, p, '医院', '#c94f4f'))
  }

  // ② 轨迹折线（灰）
  const polylinePoints = (props.track || [])
    .map((item) => wgs84ToBd09(item.lng, item.lat))
    .filter(Boolean)
    .map((c) => new BMap.Point(c.lng, c.lat))
  if (polylinePoints.length >= 2) {
    addOverlay(new BMap.Polyline(polylinePoints, {
      strokeColor: '#8a94a6',
      strokeWeight: 4,
      strokeOpacity: 0.6
    }))
    points.push(...polylinePoints)
  }

  // ③ 打卡节点（蓝灰圆点 + 节点名）
  ;(props.checkins || []).forEach((item) => {
    const c = wgs84ToBd09(item.lng, item.lat)
    if (!c) return
    const p = new BMap.Point(c.lng, c.lat)
    points.push(p)
    const dot = dotOverlay(BMap, p, '#8a94a6', 30)
    dot.addEventListener('click', () => {
      map.openInfoWindow(new BMap.InfoWindow(`${item.nodeLabel || '打卡'}<br>${item.time || ''}`), p)
    })
    addOverlay(dot)
    if (item.nodeLabel) {
      addOverlay(labelOverlay(BMap, p, item.nodeLabel, '#8a94a6'))
    }
  })

  // ④ 陪诊员最新点（蓝，最后画，保证在最上层）
  const latest = props.point ? wgs84ToBd09(props.point.lng, props.point.lat) : null
  if (latest) {
    const p = new BMap.Point(latest.lng, latest.lat)
    points.push(p)
    const dot = dotOverlay(BMap, p, '#1d6ff2', 45)
    dot.addEventListener('click', () => {
      map.openInfoWindow(new BMap.InfoWindow(`陪诊员<br>${statusText.value}`), p)
    })
    addOverlay(dot)
    addOverlay(labelOverlay(BMap, p, '陪诊员', '#1d6ff2'))
  }

  if (points.length === 0) return
  if (points.length === 1) {
    map.centerAndZoom(points[0], 16)
    return
  }
  // setViewport 自动缩放并居中，留一点内边距免得太贴边
  map.setViewport(points, { margins: [24, 24, 24, 24] })
}

/** 地图实例销毁/重建时的看门狗定时器 */
let watchdogTimer = null

function clearWatchdog() {
  if (watchdogTimer) {
    clearTimeout(watchdogTimer)
    watchdogTimer = null
  }
}

/**
 * 地图是否真的画出来了。
 *
 * <p>判据用「容器里出现了 canvas 或百度的版权控件」—— 两者都是百度自己插入的，
 * 我们无法伪造，而正常情况下 1 秒内必到。</p>
 *
 * <p>⚠️ 为什么要这个：**AK 被百度拒绝时，JSAPI 仍然会 resolve**（脚本 HTTP 200，
 * 只是内部不干活），然后抛一堆异步的 `Cannot read properties of null (reading 'logCbk…')`，
 * 而我们的 `try/catch` 一个字都接不到 —— 结果是页面上留一个**空灰框**，不降级。
 * 2026-11-27 用假 AK 实测踩到。只能靠「过几秒看看画没画出来」来判。</p>
 */
function mapReallyRendered() {
  const el = containerRef.value
  if (!el) return false
  return el.querySelector('canvas') !== null || el.querySelector('.BMap_cpyCtrl') !== null
}

/**
 * 启动渲染看门狗：超时仍未画出地图 → 降级为文字卡片。
 *
 * <p>轮询而不是单次 setTimeout：慢网下瓦片可能 4~6 秒才到，
 * 单次检查会把「慢」误判成「挂」。每 500ms 看一眼，命中就停，到截止时间仍未命中才降级。</p>
 */
function startRenderWatchdog() {
  clearWatchdog()
  const deadline = Date.now() + MAP_RENDER_WATCHDOG_MS
  const tick = () => {
    watchdogTimer = null
    if (destroyed || state.value !== 'ready') return
    if (mapReallyRendered()) return
    if (Date.now() >= deadline) {
      console.warn('[NlMapPanel] 地图超时未渲染（AK 被拒 / 瓦片不可达），已降级为文字卡片')
      // 拆掉半成品实例：留着它只会继续抛异步异常
      destroyMapInstance()
      state.value = 'fallback'
      fallbackReason.value = '地图暂不可用，已切换为文字模式'
      return
    }
    watchdogTimer = setTimeout(tick, 500)
  }
  watchdogTimer = setTimeout(tick, 500)
}

/** 只销毁地图实例，不改 destroyed 标志（看门狗降级后仍可 retry） */
function destroyMapInstance() {
  clearOverlays()
  const map = mapInstance.value
  if (map && typeof map.destroy === 'function') {
    try {
      map.destroy()
    } catch {
      // 百度在容器已被 Vue 卸载时销毁会抛，忽略即可
    }
  }
  mapInstance.value = null
  bmap.value = null
}

function destroyMap() {
  destroyed = true
  clearWatchdog()
  destroyMapInstance()
}

async function initMap() {
  if (destroyed) return
  state.value = 'loading'
  fallbackReason.value = ''

  if (!hasMapAk()) {
    state.value = 'fallback'
    fallbackReason.value = '未配置地图 AK，已切换为文字模式'
    return
  }

  try {
    const BMap = await loadBaiduMap()
    if (destroyed) return
    bmap.value = BMap

    // ⚠️ 先把 state 置 ready 让容器真正可见，再 new Map。
    //    容器还在 display:none 时 new BMap.Map(...) 会拿到 offsetWidth/Height = 0，
    //    百度内部初始化会在拿不到尺寸的节点上写属性，控制台报
    //    `Cannot set properties of null (setting 'id')`，地图也永远不渲染。
    state.value = 'ready'
    await nextTick()
    if (destroyed || !containerRef.value) return
    mapInstance.value = new BMap.Map(containerRef.value)
    mapInstance.value.enableScrollWheelZoom(false)
    renderOverlays()
    // AK 被拒时 BMap 不报错、只是不画 —— 靠看门狗兜住（见 mapReallyRendered 注释）
    startRenderWatchdog()
  } catch (err) {
    if (destroyed) return
    // 不弹 toast：地图降级是设计内的分支，不是异常。
    // 控制台留一条 warn 供排查（答辩现场断网时能当场看到原因）
    console.warn('[NlMapPanel] 地图加载失败，已降级为文字卡片：', err?.message || err)
    state.value = 'fallback'
    fallbackReason.value = '地图暂不可用，已切换为文字模式'
  }
}

/* ------------------------------------------------------------------ */
/* 生命周期                                                            */
/* ------------------------------------------------------------------ */

// props 变化时重绘（.lng/.lat 变化同样触发）；地图还没就绪时 renderOverlays 自行返回
watch(
  () => [props.point, props.track, props.checkins, props.orderLng, props.orderLat],
  () => renderOverlays(),
  { deep: true }
)

// 地图容器在降级 ↔ 就绪之间切换时会被重建，所以按 state 触发初始化。
// 这里只关心「重新回到 loading 后的初始化」，其余情形由 initMap / retry 驱动

onBeforeUnmount(destroyMap)

// 首次进入直接初始化；组件是懒加载的，容器在这一刻已经挂上
initMap()

/* 供父组件在容器从 fallback 切回来时手动重试（如「重试」按钮） */
defineExpose({ retry: initMap })
</script>

<template>
  <div class="nl-map">
    <!-- 地图（就绪时显示）：用 v-if 而不是 v-show ——
         隐藏状态下百度地图拿到 0 尺寸容器会静默不渲染。降级时根本不该留着空容器 -->
    <div
      v-if="state === 'ready'"
      ref="containerRef"
      class="nl-map__canvas"
      :style="{ height: `${height}px` }"
      role="img"
      aria-label="陪诊员与医院的实时位置地图"
    />

    <!-- 加载中：骨架占位，不白屏 -->
    <div
      v-if="state === 'loading'"
      class="nl-map__canvas is-placeholder"
      :style="{ height: `${height}px` }"
    >
      <span class="nl-caption nl-text-weak">地图加载中…</span>
    </div>

    <!--
      降级卡片（主路径）：无论哪种原因没地图，都必须给出「距离 + 更新时间」这两条
      家属真正要的信息 —— 地图没有，信息不能没有。
    -->
    <div v-if="state === 'fallback'" class="nl-map__fallback">
      <p class="nl-map__distance">{{ distanceText }}</p>
      <p v-if="statusText" class="nl-caption nl-map__updated">
        <span class="dot" :class="{ 'is-warn': isStale || !sharingEnabled }" />
        {{ statusText }}
      </p>
      <NlNoticeBar tone="warning" class="nl-map__noticebar">{{ fallbackReason }}</NlNoticeBar>
    </div>

    <!--
      地图下方的状态行：距离与新鲜度。
      位置陈旧时明确标注「未更新」，不允许显示成「在线」——
      冻住的红点会被读成「出事了」，那是比没有地图更坏的结果。
    -->
    <p v-if="state === 'ready'" class="nl-caption nl-map__status">
      <span class="dot" :class="{ 'is-warn': isStale || !sharingEnabled }" />
      <span class="nl-map__status-text">{{ distanceText }}</span>
      <template v-if="statusText">
        <span class="sep">·</span>
        <span>{{ statusText }}</span>
      </template>
    </p>

    <p v-if="state === 'ready'" class="nl-caption nl-text-weak nl-map__disclaimer">
      直线距离仅供参考，不代表实际到院距离；位置由陪诊员手机上报，锁屏或切到后台时会暂停。
    </p>
  </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.nl-map {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-2);

  &__canvas {
    width: 100%;
    border-radius: var(--nl-radius-card);
    overflow: hidden;
    background: var(--nl-bg-subtle, #f2f4f7);

    // 百度地图会在容器内注入 .BMap_cpyCtrl / .BMap_scaleCtrl 等绝对定位元素，
    // 这里把容器设成 relative 让它们相对本容器定位而不是相对整页
    position: relative;

    &.is-placeholder {
      display: flex;
      align-items: center;
      justify-content: center;
    }
  }

  &__fallback {
    display: flex;
    flex-direction: column;
    gap: var(--nl-space-2);
    padding: var(--nl-space-4);
    border: 1px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    background: var(--nl-bg-card);
  }

  &__distance {
    margin: 0;
    font-size: var(--nl-font-body);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__updated,
  &__status {
    display: flex;
    align-items: center;
    gap: var(--nl-space-1);
    margin: 0;
    color: var(--nl-text-2);
  }

  &__status-text {
    font-variant-numeric: tabular-nums;
  }

  &__noticebar {
    margin-top: var(--nl-space-1);
  }

  &__disclaimer {
    margin: 0;
    line-height: 1.5;
  }

  // 状态点：正常为主色，陈旧 / 暂停为警示色
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--nl-success);
    flex-shrink: 0;

    &.is-warn {
      background: var(--nl-warning);
    }
  }

  .sep {
    color: var(--nl-text-3);
  }
}
</style>
