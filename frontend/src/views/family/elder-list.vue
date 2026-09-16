<script setup>
/**
 * 我的老人（M-19 列表 · design.md §1.2 P1）
 */
import { useRouter } from 'vue-router'
import { NlPhoneShell, NlCard, NlAvatar, NlEmpty } from '@/components'

const router = useRouter()

const elders = [
  { id: 1, name: '张大爷', age: 72, relation: '父亲', tag: '健康', phone: '138****7777', address: '北京市朝阳区幸福路 123 号', boundAt: '2026-08-12' },
  { id: 2, name: '王奶奶', age: 68, relation: '母亲', tag: '糖尿病', phone: '138****6666', address: '北京市海淀区中关村南大街 5 号', boundAt: '2026-09-01' }
]

function bind() {
  router.push('/family/elder/bind')
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '我的老人' }">
    <NlEmpty
      v-if="!elders.length"
      title="还没有绑定老人"
      description="家属可绑定多位老人，帮他们预约陪诊 / 管理用药"
      action-text="绑定老人"
      @action="bind"
    />

    <ul v-else class="elders">
      <li v-for="e in elders" :key="e.id" class="elder">
        <NlAvatar :fallback="e.name.slice(0, 1)" :size="48" tone="primary" :badge="e.tag" />
        <div class="elder__body">
          <div class="elder__name">
            {{ e.name }} <span class="elder__rel">（{{ e.relation }} · {{ e.age }} 岁）</span>
          </div>
          <div class="nl-caption nl-text-muted is-num">{{ e.phone }}</div>
          <div class="nl-caption nl-text-weak">{{ e.address }}</div>
          <div class="nl-caption nl-text-weak is-num">绑定于 {{ e.boundAt }}</div>
        </div>
        <el-button type="primary" plain round size="small">解绑</el-button>
      </li>
    </ul>

    <div class="bind-bar">
      <el-button type="primary" size="large" round class="bind-bar__btn" @click="bind">
        + 绑定新老人
      </el-button>
    </div>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.elders {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  padding: 0 var(--nl-gutter);
  margin: 0;
  list-style: none;
}

.elder {
  display: flex;
  gap: var(--nl-space-3);
  align-items: center;
  padding: var(--nl-space-3);
  background: var(--nl-bg-card);
  border: 1px solid var(--nl-border);
  border-radius: var(--nl-radius-card);

  &__body {
    flex: 1;
  }

  &__name {
    font-size: 15px;
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__rel {
    font-size: 13px;
    font-weight: 400;
    color: var(--nl-text-2);
  }
}

.bind-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}
</style>
