<script setup>
/**
 * 站内信（M-14 · design.md §4 · 4 角色共用）
 *
 * - 顶部：「消息（N 条未读）」+ Ghost「全部已读」
 * - 4 类模板分组（design.md 设计文案）
 *   - 订单事件 / 资质审核 / 漏服提醒 / 系统公告
 * - 列表行：类型图标容器（36×36 圆角 10 不同 tone）+ 标题 + 摘要 + 时间 + 未读蓝点
 * - 空态：「暂无新消息」
 * - 合规：站内信是单向通知，不出现输入框与「发送」按钮（PRD §5.2）
 */
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlEmpty, NlIconBox } from '@/components'

/** mock 通知分组 */
const groups = ref([
  {
    key: 'order',
    title: '订单事件',
    items: [
      {
        id: 1,
        title: '订单 OD20250916001 已被接单',
        brief: '李师傅已确认您的订单，将在 09:00 出发前往北京市朝阳区幸福路 123 号',
        time: '刚刚',
        unread: true,
        tone: 'primary'
      },
      {
        id: 2,
        title: '订单 OD20250915002 已完成',
        brief: '陪诊员已完成今日服务，请您对本次服务进行评价',
        time: '昨天 18:30',
        unread: true,
        tone: 'primary'
      }
    ]
  },
  {
    key: 'audit',
    title: '资质审核',
    items: [
      {
        id: 3,
        title: '您的陪诊员资质已通过审核',
        brief: '恭喜您通过资质审核，现在可以开始接单了',
        time: '今天 09:12',
        unread: true,
        tone: 'success'
      }
    ]
  },
  {
    key: 'missed',
    title: '漏服提醒',
    items: [
      {
        id: 4,
        title: '张大爷「阿司匹林」已超时未服',
        brief: '系统已记录漏服，请与家人确认老人状态',
        time: '今天 08:35',
        unread: false,
        tone: 'danger'
      }
    ]
  },
  {
    key: 'system',
    title: '系统公告',
    items: [
      {
        id: 5,
        title: '服务协议更新',
        brief: '《用户服务协议》v2.0 已生效，请前往「我的 - 设置」查阅',
        time: '2026-09-12',
        unread: false,
        tone: 'info'
      }
    ]
  }
])

/** 总未读数 */
const unreadCount = computed(() =>
  groups.value.reduce((sum, g) => sum + g.items.filter((i) => i.unread).length, 0)
)

const hasAny = computed(() => groups.value.some((g) => g.items.length))

function markAllRead() {
  let n = 0
  groups.value.forEach((g) => {
    g.items.forEach((i) => {
      if (i.unread) {
        i.unread = false
        n++
      }
    })
  })
  ElMessage.success(`已全部标记为已读（共 ${n} 条）`)
}

function openItem(item) {
  item.unread = false
  ElMessage.info(`打开通知：「${item.title}」`)
}
</script>

<template>
  <NlPhoneShell :nav="{ title: `消息（${unreadCount} 条未读）` }">
    <!-- 顶部操作条 -->
    <section class="msg-bar">
      <span class="nl-caption nl-text-muted">共 {{ groups.reduce((s, g) => s + g.items.length, 0) }} 条 · {{ unreadCount }} 条未读</span>
      <el-button
        text
        size="small"
        :disabled="!unreadCount"
        @click="markAllRead"
      >
        全部已读
      </el-button>
    </section>

    <NlEmpty
      v-if="!hasAny"
      type="empty"
      title="暂无新消息"
      description="订单事件、资质审核、漏服提醒、系统公告会在这里出现"
    />

    <template v-else>
      <NlCard
        v-for="g in groups"
        :key="g.key"
        :title="g.title"
        plain
      >
        <ul class="msg-list">
          <li
            v-for="item in g.items"
            :key="item.id"
            class="msg-item"
            @click="openItem(item)"
          >
            <NlIconBox :tone="item.tone" :size="36">
              <svg viewBox="0 0 16 16" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <path v-if="g.key === 'order'" d="M3 4h10v8H3z M3 4l5 4 5-4" />
                <path v-else-if="g.key === 'audit'" d="M3 8l3.5 3.5L13 5" />
                <path v-else-if="g.key === 'missed'" d="M8 2v8M8 13v0.5" />
                <path v-else d="M2 8h12 M8 2v12" />
              </svg>
            </NlIconBox>
            <div class="msg-item__body">
              <div class="msg-item__title">
                <span :class="['nl-h3', { 'is-unread': item.unread }]">{{ item.title }}</span>
              </div>
              <p class="nl-caption msg-item__brief">{{ item.brief }}</p>
            </div>
            <div class="msg-item__right">
              <span class="nl-caption nl-text-muted is-num">{{ item.time }}</span>
              <span v-if="item.unread" class="msg-item__dot" aria-label="未读" />
            </div>
          </li>
        </ul>
      </NlCard>
    </template>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.msg-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--nl-gutter);
}

.msg-list {
  padding: 0;
  margin: 0;
  list-style: none;
}

.msg-item {
  display: flex;
  gap: var(--nl-space-3);
  align-items: flex-start;
  padding: var(--nl-space-3) 0;

  & + .msg-item {
    border-top: 1px solid var(--nl-divider);
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: var(--nl-space-2);
  }

  .is-unread {
    color: var(--nl-text-1);
    font-weight: 600;
  }

  &__brief {
    margin: 4px 0 0;
    color: var(--nl-text-2);
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    overflow: hidden;
  }

  &__right {
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 4px;
    padding-top: 2px;
  }

  &__dot {
    width: 8px;
    height: 8px;
    background: var(--nl-primary);
    border-radius: 50%;
  }
}
</style>
