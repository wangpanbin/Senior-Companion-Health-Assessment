<script setup>
/**
 * 老人端首页（M-04 / M-05 · design.md §4）
 *
 * - 顶部 NavBar（无返回 + 右上老人模式 / 通知图标）
 * - 问候卡：渐变背景 + 大头像 + 「张三爷 早上好」+ 日期 + 健康徽标
 * - 今日服药提醒：3 个时段（08:00 / 12:00 / 20:00）+ 药品名 + 状态 chip
 *   - 老人端只读视图：写操作按钮替换为「请家属帮您确认」提示
 * - 陪诊员实时位置卡：头像 + 姓名 + 「已在路上 预计 10 分钟到达」+ 「查看详情」
 * - 关爱之家 banner
 * - 底部胶囊 TabBar：首页 / 用药 / 消息 / 我的
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { HomeFilled, FirstAidKit, Bell, User } from '@element-plus/icons-vue'
import {
  NlPhoneShell, NlTabBar, NlCard, NlAvatar, NlStatusChip
} from '@/components'
import { useAppStore } from '@/store/modules/app'

const router = useRouter()
const appStore = useAppStore()

/** 当前时段问候语 */
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const today = computed(() => {
  const d = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][d.getDay()]
  return `${d.getMonth() + 1}月${d.getDate()}日 · 星期${week}`
})

/* ====== mock 数据（前端 demo，等 M11 上线后由后端提供） ====== */
const elder = {
  name: '张大爷',
  avatar: '',
  healthScore: 88,
  healthLabel: '健康状况良好'
}

const medications = [
  { time: '08:00', name: '阿司匹林', dose: '1 片', usage: '用于心血管保护', status: 'TAKEN',  label: '已服用' },
  { time: '12:00', name: '维生素 D', dose: '1 片', usage: '用于补钙',         status: 'PENDING', label: '待服用' },
  { time: '20:00', name: '降压药',   dose: '1 片', usage: '用于控制高血压',   status: 'PENDING', label: '待服用' }
]

const companion = {
  name: '李师傅',
  avatar: '',
  hospital: '市第一人民医院',
  eta: '预计 10 分钟到达',
  addressText: '北京市朝阳区幸福路 123 号',
  distance: '1.2 km'
}

const familyMembers = [
  { name: '张丽', avatar: '', relation: '女儿', phone: '138****8888' }
]

/* ====== Tab 配置 ====== */
const tabs = [
  { key: 'home',       label: '首页', icon: HomeFilled },
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
  if (key === 'medication') router.push('/elder/medication')
  if (key === 'message') router.push('/elder/message')
  if (key === 'profile') router.push('/profile')
}

function toggleElderly() {
  appStore.toggleElderlyMode()
  ElMessage.success(appStore.elderlyMode ? '已切换至老人模式（字号放大 / 菜单精简）' : '已退出老人模式')
}

function viewOrderDetail() {
  ElMessage.info('订单详情页：M-16 后续迭代')
}

function contactFamily(m) {
  ElMessage.info(`拨打 ${m.name}（${m.phone}）`)
}
</script>

<template>
  <NlPhoneShell
    :nav="{ title: '银龄伴诊', back: false }"
    :has-tabs="true"
    :status-time="today"
  >
    <template #nav-right>
      <button class="elder-home__navbtn" :title="appStore.elderlyMode ? '退出老人模式' : '开启老人模式'" @click="toggleElderly">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
          <text x="12" y="16" text-anchor="middle" font-size="14" fill="currentColor" stroke="none" font-family="serif">大</text>
          <circle cx="12" cy="12" r="11" />
        </svg>
      </button>
      <button class="elder-home__navbtn" title="消息" @click="router.push('/elder/message')">
        <el-badge :value="3" :max="99">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M6 8a6 6 0 1 1 12 0v4l2 3H4l2-3V8z" />
            <path d="M10 19a2 2 0 0 0 4 0" />
          </svg>
        </el-badge>
      </button>
    </template>

    <!-- ==================== 问候卡 ==================== -->
    <NlCard class="greeting">
      <div class="greeting__inner">
        <NlAvatar :fallback="elder.name.slice(0, 1)" :size="56" tone="success" :badge="elder.healthLabel" />
        <div class="greeting__body">
          <h2 class="nl-h2 greeting__title">{{ elder.name }}，{{ greeting }}</h2>
          <p class="nl-caption greeting__date">{{ today }} · 健康分 {{ elder.healthScore }}</p>
        </div>
      </div>
      <NlStatusChip tone="success" text="健康状况良好" />
    </NlCard>

    <!-- ==================== 今日服药提醒 ==================== -->
    <NlCard>
      <template #title>
        <div class="block-head">
          <span class="nl-h2">今日服药提醒</span>
          <span class="nl-caption">3 项</span>
        </div>
      </template>
      <p class="nl-caption nl-text-muted elder-home__readonly-tip">
        您正在使用「老人模式」只读视图，服药确认请由您的家属操作。
      </p>
      <ul class="med-list">
        <li v-for="m in medications" :key="m.time" class="med-item">
          <div class="med-item__time is-num">{{ m.time }}</div>
          <div class="med-item__body">
            <div class="med-item__row">
              <span class="nl-h3">{{ m.name }}</span>
              <NlStatusChip :status="m.status" :text="m.label" :dot="m.status === 'PENDING'" />
            </div>
            <p class="nl-caption nl-text-muted">{{ m.dose }} · {{ m.usage }}</p>
          </div>
        </li>
      </ul>
    </NlCard>

    <!-- ==================== 陪诊员实时位置 ==================== -->
    <NlCard class="companion-card">
      <template #title>
        <div class="block-head">
          <span class="nl-h2">陪诊员已经在路上</span>
          <span class="nl-caption">实时定位</span>
        </div>
      </template>
      <div class="companion-card__inner">
        <NlAvatar :fallback="companion.name.slice(0, 1)" :size="48" tone="primary" />
        <div class="companion-card__body">
          <div class="companion-card__name">{{ companion.name }} · 已出发</div>
          <div class="companion-card__addr">
            目的地：{{ companion.hospital }}（{{ companion.distance }}）
          </div>
          <div class="companion-card__addr companion-card__addr--strong">
            {{ companion.eta }}
          </div>
        </div>
      </div>
      <el-button type="primary" round size="large" class="companion-card__btn" @click="viewOrderDetail">
        查看详情
      </el-button>
    </NlCard>

    <!-- ==================== 家人联系 ==================== -->
    <NlCard plain>
      <template #title>
        <span class="nl-h2">我的家人</span>
      </template>
      <ul class="family-list">
        <li v-for="m in familyMembers" :key="m.name" class="family-item">
          <NlAvatar :fallback="m.name.slice(-1)" :size="40" tone="primary" />
          <div class="family-item__body">
            <div class="family-item__name">{{ m.name }} <span class="family-item__rel">（{{ m.relation }}）</span></div>
            <div class="nl-caption nl-text-muted is-num">{{ m.phone }}</div>
          </div>
          <el-button type="primary" plain round size="small" @click="contactFamily(m)">联系</el-button>
        </li>
      </ul>
    </NlCard>

    <template #tabbar>
      <NlTabBar v-model="activeTab" :tabs="tabs" @change="onTabChange" />
    </template>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.elder-home {
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

    &:active {
      background: var(--nl-primary-ghost);
    }
  }

  &__readonly-tip {
    margin: 0 0 var(--nl-space-3);
    padding: var(--nl-space-2) var(--nl-space-3);
    background: var(--nl-warning-bg);
    border-radius: 8px;
  }
}

.greeting {
  background: linear-gradient(135deg, var(--nl-primary-ghost) 0%, #f7faff 100%);
  border: 1px solid var(--nl-primary-light);

  &__inner {
    display: flex;
    align-items: center;
    gap: var(--nl-space-4);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    margin: 0;
  }

  &__date {
    margin-top: 2px;
  }
}

.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--nl-space-2);
}

.med-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
}

.med-item {
  display: flex;
  align-items: center;
  gap: var(--nl-space-3);
  padding: var(--nl-space-3);
  background: var(--nl-bg-sunken);
  border-radius: 12px;

  &__time {
    flex-shrink: 0;
    width: 56px;
    padding: 6px 0;
    font-size: 16px;
    font-weight: 600;
    color: var(--nl-primary);
    text-align: center;
    background: var(--nl-primary-light);
    border-radius: 8px;
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }
}

.companion-card {
  background: linear-gradient(135deg, var(--nl-primary-light) 0%, #fff 90%);

  &__inner {
    display: flex;
    align-items: center;
    gap: var(--nl-space-3);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__name {
    font-size: 16px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__addr {
    margin-top: 4px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);

    &--strong {
      color: var(--nl-primary);
      font-weight: 600;
    }
  }

  &__btn {
    width: 100%;
    margin-top: var(--nl-space-3);
  }
}

.family-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.family-item {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;

  & + .family-item {
    margin-top: var(--nl-space-3);
    padding-top: var(--nl-space-3);
    border-top: 1px solid var(--nl-divider);
  }

  &__body {
    flex: 1;
  }

  &__name {
    font-size: 15px;
    font-weight: 500;
  }

  &__rel {
    font-size: 13px;
    font-weight: 400;
    color: var(--nl-text-2);
  }
}
</style>
