<script setup>
/**
 * 接单大厅（M-10 · design.md §4 · PRD §4.1 陪诊员主旅程）
 *
 * - 顶部资质状态条
 *   - 已认证 chip + 「接单中」开关（status: APPROVED / PENDING / REJECTED）
 * - 分段筛选：全部 / 附近 / 高额
 * - 订单卡：医院名 + 服务费 ¥120（display 字号）+ 时间 + 距离 + 右侧 Primary「抢单」
 * - 未通过资质：整页拦截 + 「去提交资质」按钮
 * - 抢单乐观锁失败提示：设计预留 (PRD §9.1 演示指标)
 * - 底部 TabBar：大厅 / 订单 / 收入 / 我的
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  HomeFilled as _, Tickets, Bell, User
} from '@element-plus/icons-vue'
import {
  NlPhoneShell, NlTabBar, NlCard, NlStatusChip, NlEmpty
} from '@/components'

const router = useRouter()

/** mock 资质状态 (M3 上线后由 /api/companion/profile 提供) */
const cert = ref({ status: 'APPROVED', text: '已认证' })

/** 接单开关（控制是否接收推送） */
const accepting = ref(true)

const filter = ref('all') // all / near / high

const tabs = [
  { key: 'hall', label: '大厅', icon: 'List' },
  { key: 'order', label: '订单', icon: 'Tickets' },
  { key: 'income', label: '收入', icon: 'Money' },
  { key: 'mine', label: '我的', icon: 'User' }
]

const activeTab = ref('hall')

const orders = ref([
  { id: 'OD20250916001', hospital: '市第一人民医院', dept: '心血管内科', elder: '张大爷 · 72 岁', time: '今天 09:00-11:00', distance: '2.1km', fee: 120, level: '三甲' },
  { id: 'OD20250916002', hospital: '市中医院', dept: '中医骨伤科', elder: '王奶奶 · 68 岁', time: '今天 11:00-13:00', distance: '1.4km', fee: 150, level: '三甲' },
  { id: 'OD20250916003', hospital: '市妇幼保健院', dept: '内科', elder: '李阿姨 · 65 岁', time: '今天 14:00-16:00', distance: '3.2km', fee: 180, level: '三甲' },
  { id: 'OD20250916004', hospital: '社区卫生服务中心', dept: '全科', elder: '陈大爷 · 70 岁', time: '今天 16:00-18:00', distance: '0.8km', fee: 100, level: '社区' }
])

const filtered = computed(() => {
  const list = orders.value
  if (filter.value === 'near') return list.filter((o) => parseFloat(o.distance) < 2)
  if (filter.value === 'high') return list.filter((o) => o.fee >= 150)
  return list
})

function grab(o) {
  // 模拟乐观锁：80% 抢单成功；20% 返回 3003「手慢了」
  const fail = Math.random() < 0.2
  if (fail) {
    orders.value = orders.value.filter((x) => x.id !== o.id)
    ElMessage.error('手慢了，订单已被接走')
    return
  }
  orders.value = orders.value.filter((x) => x.id !== o.id)
  ElMessage.success('抢单成功，请前往订单详情确认')
  setTimeout(() => router.push(`/companion/execute/${o.id}`), 600)
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
</script>

<template>
  <NlPhoneShell
    :nav="{ title: '接单大厅', back: false }"
    :has-tabs="true"
  >
    <!-- 资质状态条 -->
    <section class="cert-bar">
      <div class="cert-bar__left">
        <NlStatusChip status="APPROVED" :text="cert.text" />
        <span class="nl-caption nl-text-muted">资质已通过 · 可接单</span>
      </div>
      <div class="cert-bar__right">
        <span class="nl-caption nl-text-muted">接单中</span>
        <el-switch v-model="accepting" size="default" />
      </div>
    </section>

    <!-- 资质未通过拦截态 -->
    <NlEmpty
      v-if="cert.status !== 'APPROVED'"
      type="empty"
      title="您还未通过陪诊员资质认证"
      description="通过后可查看接单大厅、抢单结算"
      action-text="去提交资质"
      @action="goEntry"
    />

    <!-- 资质通过：筛选 + 订单卡 -->
    <template v-else>
      <div class="filters">
        <button
          v-for="f in [
            { key: 'all', label: '全部' },
            { key: 'near', label: '附近' },
            { key: 'high', label: '高额' }
          ]"
          :key="f.key"
          :class="['filters__btn', { 'is-active': filter === f.key }]"
          @click="filter = f.key"
        >
          {{ f.label }}
        </button>
      </div>

      <NlEmpty
        v-if="!filtered.length"
        type="empty"
        title="暂无订单"
        description="附近用户暂时没有陪诊需求，请稍后下拉刷新"
      />

      <ul v-else class="orders">
        <li v-for="o in filtered" :key="o.id" class="order">
          <div class="order__head">
            <NlStatusChip status="PENDING" text="待接单" :dot="true" />
            <span class="order__fee is-num">¥{{ o.fee }}</span>
          </div>
          <div class="order__hospital">{{ o.hospital }}</div>
          <div class="nl-caption nl-text-muted">{{ o.dept }} · {{ o.level }}</div>
          <ul class="order__meta">
            <li>
              <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="8" cy="6.5" r="3" />
                <path d="M2.5 13.5c1-2 3-3 5.5-3s4.5 1 5.5 3" />
              </svg>
              {{ o.elder }}
            </li>
            <li>
              <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="8" cy="8" r="6.5" />
                <path d="M8 4v4l3 2" />
              </svg>
              <span class="is-num">{{ o.time }}</span>
            </li>
            <li>
              <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <path d="M8 14s5-4.5 5-8.5a5 5 0 1 0-10 0C3 9.5 8 14 8 14z" />
                <circle cx="8" cy="5.5" r="1.5" />
              </svg>
              <span class="is-num">距离您 {{ o.distance }}</span>
            </li>
          </ul>
          <el-button type="primary" round size="large" class="order__grab" @click="grab(o)">
            抢 单
          </el-button>
        </li>
      </ul>
    </template>

    <template #tabbar>
      <NlTabBar v-model="activeTab" :tabs="tabs" @change="onTabChange" />
    </template>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

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
      }
    }
  }

  &__grab {
    width: 100%;
    font-size: 16px;
    font-weight: 500;
  }
}
</style>
