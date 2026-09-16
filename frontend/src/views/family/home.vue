<script setup>
/**
 * 家属端首页（M-06 · design.md §4）
 *
 * - 顶部 NavBar + 通知 / 老人模式 / 头像区
 * - 多老人横滑卡（可点击切换「当前就诊人」上下文，默认第一个）
 * - 三宫格快捷入口：预约挂号 / 用药计划 / 进度查看（M4 / M6 / M5 / M16 入口）
 * - 进行中订单卡：医院 / 时间 / 状态 chip / 陪诊员 + 查看详情
 * - 关爱之家 banner
 * - 底部 TabBar：首页 / 订单 / 用药 / 消息 / 我的
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  HomeFilled, Tickets, FirstAidKit, Bell, User,
  Calendar, FirstAidKit as Med, Clock, Plus
} from '@element-plus/icons-vue'
import {
  NlPhoneShell, NlTabBar, NlCard, NlAvatar, NlStatusChip, NlIconBox
} from '@/components'
import { useAppStore } from '@/store/modules/app'

const router = useRouter()
const appStore = useAppStore()

const activeElder = ref(0)

/** 多老人数据（前端 mock，等 M3 上线后由 /api/user/my-elders 提供） */
const elders = [
  { id: 1, name: '张大爷',  age: 72, avatar: '', tag: '健康', phone: '138****7777', address: '北京市朝阳区幸福路 123 号' },
  { id: 2, name: '王奶奶',  age: 68, avatar: '', tag: '糖尿病', phone: '138****6666', address: '北京市海淀区中关村南大街 5 号' }
]

const quickEntries = [
  { key: 'order',   icon: Calendar, label: '预约挂号', tone: 'primary', desc: '帮父母约医院陪诊',     path: '/family/order/step1' },
  { key: 'med',     icon: Med,      label: '用药计划', tone: 'success', desc: '管理药品 / 服药提醒', path: '/family/medication' },
  { key: 'track',   icon: Clock,    label: '进度查看', tone: 'warning', desc: '看正在进行的订单',     path: '/family/order' }
]

const orders = [
  {
    id: 'OD20250916001',
    status: 'IN_SERVICE',
    hospital: '市第一人民医院',
    dept: '心血管内科',
    time: '今天 09:00 - 11:00',
    elder: '张大爷',
    companion: { name: '李师傅', avatar: '' },
    amount: '120',
    distance: '1.2 km'
  },
  {
    id: 'OD20250918002',
    status: 'PENDING',
    hospital: '市中医院',
    dept: '中医骨伤科',
    time: '明天 14:00 - 16:00',
    elder: '张大爷',
    companion: null,
    amount: '100',
    distance: '3.5 km'
  }
]

const tabs = [
  { key: 'home',       label: '首页', icon: HomeFilled },
  { key: 'order',      label: '订单', icon: Tickets },
  { key: 'medication', label: '用药', icon: FirstAidKit },
  { key: 'message',    label: '消息', icon: Bell },
  { key: 'profile',    label: '我的', icon: User }
]
const activeTab = computed({
  get: () => 'home',
  set: () => {}
})
function onTabChange(key) {
  if (key === 'home') return
  if (key === 'order') router.push('/family/order')
  if (key === 'medication') router.push('/family/medication')
  if (key === 'message') router.push('/family/message')
  if (key === 'profile') router.push('/profile')
}

function toggleElderly() {
  appStore.toggleElderlyMode()
  ElMessage.success(appStore.elderlyMode ? '已切换至老人模式' : '已退出老人模式')
}

function pickElder(i) {
  activeElder.value = i
}

function quickEnter(item) {
  router.push(item.path)
}

function addElder() {
  router.push('/family/elder/bind')
}

function viewOrder(o) {
  router.push(`/family/order/${o.id}`)
}

function createOrder() {
  router.push('/family/order/step1')
}
</script>

<template>
  <NlPhoneShell
    :nav="{ title: '家属工作台', back: false }"
    :has-tabs="true"
  >
    <template #nav-right>
      <button class="fam-home__navbtn" title="切换老人模式" @click="toggleElderly">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="11" />
          <text x="12" y="16" text-anchor="middle" font-size="14" fill="currentColor" stroke="none" font-family="serif">大</text>
        </svg>
      </button>
      <button class="fam-home__navbtn" title="消息">
        <el-badge :value="5" :max="99">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M6 8a6 6 0 1 1 12 0v4l2 3H4l2-3V8z" />
            <path d="M10 19a2 2 0 0 0 4 0" />
          </svg>
        </el-badge>
      </button>
    </template>

    <!-- ==================== 老人横滑卡 ==================== -->
    <section class="elder-track">
      <ul class="elder-track__list">
        <li
          v-for="(e, i) in elders"
          :key="e.id"
          :class="['elder-track__item', { 'is-active': activeElder === i }]"
          @click="pickElder(i)"
        >
          <NlAvatar :fallback="e.name.slice(0, 1)" :size="56" tone="primary" :badge="e.tag" />
          <div class="elder-track__name">{{ e.name }}</div>
          <div class="elder-track__age">{{ e.age }} 岁</div>
        </li>
        <li class="elder-track__item elder-track__add" @click="addElder">
          <div class="elder-track__addbox">
            <el-icon size="22"><Plus /></el-icon>
          </div>
          <div class="elder-track__name">添加</div>
        </li>
      </ul>
    </section>

    <!-- ==================== 三宫格 ==================== -->
    <NlCard plain>
      <ul class="quickgrid">
        <li v-for="q in quickEntries" :key="q.key" class="quickgrid__item" @click="quickEnter(q)">
          <NlIconBox :tone="q.tone" :size="48">
            <component :is="q.icon" />
          </NlIconBox>
          <div class="quickgrid__label">{{ q.label }}</div>
          <div class="quickgrid__desc">{{ q.desc }}</div>
        </li>
      </ul>
    </NlCard>

    <!-- ==================== 进行中订单 ==================== -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">进行中订单</span>
          <el-link type="primary" :underline="false" @click="router.push('/family/order')">查看全部</el-link>
        </div>
      </template>
      <ul v-if="orders.length" class="order-list">
        <li v-for="o in orders" :key="o.id" class="order-card" @click="viewOrder(o)">
          <div class="order-card__head">
            <NlStatusChip :status="o.status" :dot="true" />
            <span class="is-num order-card__amt">¥{{ o.amount }}</span>
          </div>
          <div class="order-card__body">
            <div class="order-card__hospital">{{ o.hospital }} · {{ o.dept }}</div>
            <div class="nl-caption nl-text-muted is-num">{{ o.time }}</div>
            <div class="order-card__foot">
              <div v-if="o.companion" class="order-card__comp">
                <NlAvatar :fallback="o.companion.name.slice(0, 1)" :size="24" tone="warning" />
                <span class="nl-caption">{{ o.companion.name }} · {{ o.distance }}</span>
              </div>
              <span v-else class="nl-caption nl-text-muted">尚无陪诊员接单</span>
              <span class="order-card__chev">›</span>
            </div>
          </div>
        </li>
      </ul>
      <div v-else class="fam-home__empty">
        <NlStatusChip tone="neutral" text="暂无订单" />
        <el-button type="primary" round size="small" class="mt-3" @click="createOrder">去下单</el-button>
      </div>
    </NlCard>

    <!-- ==================== 关爱之家 banner ==================== -->
    <section class="love-banner">
      <div class="love-banner__title">关爱家人，从陪诊开始</div>
      <ul class="love-banner__tags">
        <li>专业</li>
        <li>安心</li>
        <li>便捷</li>
      </ul>
      <p class="love-banner__sub">专业陪诊员 · 全程 6 节点打卡 · 家属端实时进度</p>
    </section>

    <template #tabbar>
      <NlTabBar v-model="activeTab" :tabs="tabs" @change="onTabChange" />
    </template>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.fam-home {
  &__navbtn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: var(--nl-touch-min);
    height: var(--nl-touch-min);
    color: var(--nl-text-1);
    background: transparent;
    border: none;
    border-radius: 50%;
    cursor: pointer;
    -webkit-tap-highlight-color: transparent;

    &:active {
      background: var(--nl-primary-ghost);
    }
  }

  &__empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: var(--nl-space-3);
  }
}

.elder-track {
  margin: 0 calc(var(--nl-space-2) - var(--nl-gutter)) var(--nl-gap-section);

  &__list {
    display: flex;
    gap: var(--nl-space-3);
    padding: 0 var(--nl-gutter);
    margin: 0;
    overflow-x: auto;
    list-style: none;
    scrollbar-width: none;

    &::-webkit-scrollbar {
      display: none;
    }
  }

  &__item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
    flex-shrink: 0;
    padding: var(--nl-space-3) var(--nl-space-2);
    width: 96px;
    background: var(--nl-bg-card);
    border: 1.5px solid transparent;
    border-radius: var(--nl-radius-card);
    cursor: pointer;
    box-shadow: var(--nl-shadow-card);
    transition: border-color 0.18s;

    &.is-active {
      border-color: var(--nl-primary);
      background: var(--nl-primary-ghost);
    }
  }

  &__name {
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__age {
    font-size: 12px;
    color: var(--nl-text-3);
  }

  &__add {
    background: var(--nl-bg-sunken);
    border-style: dashed;

    &:active {
      background: var(--nl-primary-ghost);
    }
  }

  &__addbox {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 56px;
    height: 56px;
    color: var(--nl-primary);
    background: var(--nl-bg-card);
    border-radius: 50%;
    box-shadow: var(--nl-shadow-card);
  }
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--nl-space-2);
}

.quickgrid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--nl-space-2);
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    align-items: center;
    justify-content: center;
    padding: var(--nl-space-3) 0;
    text-align: center;
    cursor: pointer;

    &:active {
      opacity: 0.7;
    }
  }

  &__label {
    margin-top: 6px;
    font-size: 14px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__desc {
    font-size: 11px;
    color: var(--nl-text-3);
  }
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0;
  margin: 0;
  list-style: none;
}

.order-card {
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);
  cursor: pointer;
  transition: box-shadow 0.18s, transform 0.18s;

  &:active {
    transform: scale(0.99);
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--nl-space-2);
  }

  &__amt {
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-primary);
  }

  &__body {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__hospital {
    font-size: 15px;
    font-weight: 500;
    color: var(--nl-text-1);
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-top: var(--nl-space-2);
  }

  &__comp {
    display: flex;
    gap: 6px;
    align-items: center;
    color: var(--nl-text-2);
  }

  &__chev {
    font-size: 20px;
    color: var(--nl-text-3);
  }
}

.love-banner {
  padding: var(--nl-space-4);
  background: linear-gradient(135deg, var(--nl-primary-light) 0%, var(--nl-primary-ghost) 100%);
  border-radius: var(--nl-radius-card);

  &__title {
    font-size: 16px;
    font-weight: 600;
    color: var(--nl-primary-dark, var(--nl-primary));
  }

  &__tags {
    display: flex;
    gap: 6px;
    padding: 0;
    margin: var(--nl-space-2) 0 0;
    list-style: none;

    li {
      padding: 2px 10px;
      font-size: 11px;
      font-weight: 500;
      color: var(--nl-primary);
      background: rgba(255, 255, 255, 0.8);
      border-radius: 999px;
    }
  }

  &__sub {
    margin: var(--nl-space-2) 0 0;
    font-size: 12px;
    color: var(--nl-text-2);
  }
}

.mt-3 {
  margin-top: 12px;
}
</style>
