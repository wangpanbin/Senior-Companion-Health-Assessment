<script setup>
/**
 * 订单执行打卡（M-11 · design.md §4 · PRD §4.1 陪诊员第二段旅程）
 *
 * - 订单摘要头（医院 + 时段 + 距离 + 老人）
 * - 6 节点竖向时间线：DEPART / ARRIVE / IN_CONSULT / TAKE_MEDICINE / LEAVE / FINISH
 *   - done：实心主色 + 完成时间戳
 *   - current：脉冲描边（NlTimeline 实现）+ 「点击打卡」按钮
 *   - todo：灰色圆点 + 锁定文案「请先完成上一节点」
 * - 底部定位条：当前位置（文字 + 经纬度）+ 距离阈值提示
 *   - 偏离 ≤ 2000 m：定位正常
 *   - 偏离 > 2000 m：红色提示 + 按钮置灰
 * - 顶部 stepper 展示进度（4/6）
 */
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlTimeline, NlCard, NlStatusChip } from '@/components'

const route = useRoute()

/* ====== 6 节点 ====== */
const NODE_KEYS = ['DEPART', 'ARRIVE', 'IN_CONSULT', 'TAKE_MEDICINE', 'LEAVE', 'FINISH']
const NODE_LABELS = {
  DEPART: '出发',
  ARRIVE: '到达医院',
  IN_CONSULT: '就诊中',
  TAKE_MEDICINE: '取药',
  LEAVE: '离开医院',
  FINISH: '订单完成'
}

/** mock 当前节点状态：DEPART 已完成，ARRIVE 是当前，其余未开始 */
const order = ref({
  id: route.params.id || 'OD20250916001',
  hospital: '市第一人民医院',
  dept: '心血管内科',
  elder: '张大爷 · 72 岁',
  time: '今天 09:00 - 11:00',
  distance: '1.2 km',
  currentNode: 1 // 0-based, 1 = ARRIVE
})

const steps = computed(() =>
  NODE_KEYS.map((key, i) => {
    let status = 'todo'
    if (i < order.value.currentNode) status = 'done'
    else if (i === order.value.currentNode) status = 'current'
    return {
      key,
      label: NODE_LABELS[key],
      status,
      time: status === 'done' ? `09:${String(5 + i * 10).padStart(2, '0')}` : '',
      description:
        status === 'current' && !isOnSite.value
          ? '请先到达医院附近（2000 米内）才能打卡'
          : status === 'todo'
          ? '请先完成上一节点'
          : ''
    }
  })
)

const progress = computed(() => `${order.value.currentNode}/${NODE_KEYS.length}`)

/* ====== 定位 ====== */
const isOnSite = ref(true) // 模拟当前在正确位置
const location = ref({
  lng: 116.4826,
  lat: 39.9214,
  addr: '北京市朝阳区幸福路 123 号附近',
  distance: '12 米' // 与订单地址距离
})

function checkin(nodeKey) {
  if (!isOnSite.value) {
    ElMessage.error('当前定位偏离订单地址超过 2000 米，无法打卡')
    return
  }
  const next = order.value.currentNode + 1
  order.value.currentNode = Math.min(next, NODE_KEYS.length - 1)
  ElMessage.success(`已完成「${NODE_LABELS[nodeKey]}」打卡`)
  if (nodeKey === 'FINISH') {
    ElMessage.success('订单完成，请等待家属评价')
  }
}

function simulateLocation() {
  // 测试用：切换定位状态
  isOnSite.value = !isOnSite.value
  ElMessage.info(isOnSite.value ? '模拟定位已切换为「正常」' : '模拟定位已切换为「偏离」')
}
</script>

<template>
  <NlPhoneShell
    :nav="{ title: '订单执行' }"
  >
    <!-- 订单摘要 -->
    <NlCard class="summary">
      <div class="summary__title">{{ order.hospital }} <span class="nl-caption nl-text-muted">· {{ order.dept }}</span></div>
      <div class="nl-caption nl-text-muted summary__line">服务时间 <span class="is-num">{{ order.time }}</span> · 距离 <span class="is-num">{{ order.distance }}</span></div>
      <div class="summary__row">
        <NlStatusChip status="ACCEPTED" text="进行中" :dot="true" />
        <span class="nl-caption nl-text-muted is-num">进度 {{ progress }}</span>
      </div>
    </NlCard>

    <!-- 6 节点时间线 -->
    <NlCard title="服务节点">
      <p class="nl-caption nl-text-muted node-hint">
        节点顺序不可回退 · 同节点只可打卡一次 · 当前节点脉冲高亮
      </p>
      <NlTimeline :steps="steps">
        <template #current>
          <el-button
            v-if="steps[order.currentNode]"
            type="primary"
            round
            size="default"
            :disabled="!isOnSite"
            class="check-btn"
            @click="checkin(steps[order.currentNode].key)"
          >
            打卡 · {{ steps[order.currentNode].label }}
          </el-button>
        </template>
      </NlTimeline>
    </NlCard>

    <!-- 底部定位条 -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">当前位置</span>
          <span :class="['is-on-site', `is-${isOnSite ? 'ok' : 'fail'}`]">
            <span class="dot" />
            {{ isOnSite ? '定位正常' : '偏离超过 2000 米' }}
          </span>
        </div>
      </template>
      <div class="loc">
        <div class="loc__addr">
          <svg viewBox="0 0 16 16" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <path d="M8 14s5-4.5 5-8.5a5 5 0 1 0-10 0C3 9.5 8 14 8 14z" />
            <circle cx="8" cy="5.5" r="1.5" />
          </svg>
          {{ location.addr }}
        </div>
        <div class="loc__coord is-num">
          经度 {{ location.lng }} · 纬度 {{ location.lat }}
        </div>
        <div v-if="!isOnSite" class="loc__alert">
          偏离订单地址 {{ location.distance }}（阈值 2000 米），请就近打卡前确认已到达医院附近。
        </div>
        <el-button type="primary" plain round size="small" @click="simulateLocation">
          模拟切换定位（测试用）
        </el-button>
      </div>
    </NlCard>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.summary {
  background: linear-gradient(135deg, var(--nl-primary-light) 0%, #fff 90%);

  &__title {
    font-size: 16px;
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

.is-on-site {
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

  &.is-ok { color: var(--nl-success); .dot { background: var(--nl-success); } }
  &.is-fail { color: var(--nl-danger); .dot { background: var(--nl-danger); } }
}

.loc {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  &__addr {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
    font-size: 14px;
    font-weight: 500;
    color: var(--nl-text-1);

    svg {
      color: var(--nl-primary);
    }
  }

  &__coord {
    padding: var(--nl-space-2) var(--nl-space-3);
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border-radius: 6px;
  }

  &__alert {
    padding: var(--nl-space-2) var(--nl-space-3);
    font-size: var(--nl-font-caption);
    color: var(--nl-danger);
    background: var(--nl-danger-bg);
    border-radius: 6px;
  }
}
</style>
