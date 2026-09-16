<script setup>
/**
 * 下单 · Step1（M-07 · design.md §4）
 *
 * - 顶部 NlStepHeader 1-2-3
 * - 选择就诊人卡：单选列表（已绑定的老人）
 * - 底部固定「下一步」主行动按钮（CTA）
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlStepHeader, NlAvatar } from '@/components'

const router = useRouter()

const elders = [
  { id: 1, name: '张大爷', age: 72, tag: '健康', phone: '138****7777' },
  { id: 2, name: '王奶奶', age: 68, tag: '糖尿病', phone: '138****6666' }
]

const selectedId = ref(1)

function pick(id) {
  selectedId.value = id
}

function next() {
  const elder = elders.find((e) => e.id === selectedId.value)
  if (!elder) {
    ElMessage.warning('请先选择就诊人')
    return
  }
  router.push({
    path: '/family/order/step2',
    query: { elderId: elder.id }
  })
}
</script>

<template>
  <NlPhoneShell
    :nav="{ title: '预约陪诊' }"
    :has-cta="true"
  >
    <NlStepHeader :model-value="1" />

    <section class="step">
      <h2 class="nl-h2 step__title">请选择本次陪诊的就诊人</h2>
      <p class="nl-caption nl-text-muted">系统会自动按就诊人匹配医院偏好与服务地址</p>

      <ul class="elderpick">
        <li
          v-for="e in elders"
          :key="e.id"
          :class="['elderpick__item', { 'is-active': selectedId === e.id }]"
          @click="pick(e.id)"
        >
          <NlAvatar :fallback="e.name.slice(0, 1)" :size="48" tone="primary" />
          <div class="elderpick__body">
            <div class="elderpick__row">
              <span class="nl-h3">{{ e.name }}</span>
              <span class="elderpick__age">{{ e.age }} 岁</span>
            </div>
            <p class="nl-caption nl-text-muted">
              {{ e.tag }} · {{ e.phone }}
            </p>
          </div>
          <span class="elderpick__radio" aria-hidden="true">
            <svg v-if="selectedId === e.id" viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 8l3.5 3.5L13 5" />
            </svg>
          </span>
        </li>
      </ul>
    </section>

    <template #cta>
      <el-button type="primary" size="large" round class="step__cta" @click="next">
        下一步
      </el-button>
    </template>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.step {
  padding: 0 var(--nl-gutter);
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  &__title {
    margin: 0;
  }

  &__cta {
    width: 100%;
    font-size: 16px;
    font-weight: 500;
  }
}

.elderpick {
  list-style: none;
  padding: 0;
  margin: var(--nl-space-2) 0 0;
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);

  &__item {
    display: flex;
    gap: var(--nl-space-3);
    align-items: center;
    padding: var(--nl-space-3);
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border);
    border-radius: var(--nl-radius-card);
    cursor: pointer;
    transition: border-color 0.18s, background 0.18s;

    &.is-active {
      border-color: var(--nl-primary);
      background: var(--nl-primary-ghost);
    }
  }

  &__body {
    flex: 1;
    min-width: 0;
  }

  &__row {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
  }

  &__age {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
  }

  &__radio {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    width: 24px;
    height: 24px;
    color: var(--nl-bg-card);
    background: var(--nl-border-strong);
    border-radius: 50%;

    .is-active & {
      background: var(--nl-primary);
    }
  }
}
</style>
