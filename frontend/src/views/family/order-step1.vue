<script setup>
/**
 * 下单 · Step1（M-07 · design.md §4）
 *
 * - 顶部 NlStepHeader 1-2-3
 * - 选择就诊人卡：单选列表（已绑定的老人）
 * - 底部固定「下一步」主行动按钮（CTA；桌面端改为正文内联，见 NlPageShell）
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
 *
 * 数据来源：`GET /api/user/elder`（M3 已交付）。
 * ⚠️ 该接口只有家属角色可用（`@PreAuthorize("hasRole('FAMILY')")`）。
 * ⚠️ 列表口径下姓名与手机号是**脱敏**的（张*海 / 139****0001）—— 这是后端刻意的
 *    隐私设计（一屏可能展示十几个老人，容易被旁人扫到），不是缺陷。
 *    下单只需要 elderId，脱敏不影响功能。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPageShell, NlStepHeader, NlAvatar, NlEmpty, NlSkeleton } from '@/components'
import { listElder } from '@/api/user'
import { useOrderDraftStore } from '@/store/modules/orderDraft'

const router = useRouter()
const draft = useOrderDraftStore()

const elders = ref([])
const loading = ref(false)
const selectedId = ref(null)

async function loadElders() {
  loading.value = true
  try {
    // size 上限 100（后端 PageQuery 的 @Max 约束），一次拉全，老人数量不会多到需要翻页
    const data = await listElder({ page: 1, size: 100 })
    elders.value = data?.records || []
    // 默认选中第一位：家属进页面多半就是给常用老人下单，少点一次是一次
    if (elders.value.length && !selectedId.value) {
      selectedId.value = elders.value[0].id
    }
  } catch {
    elders.value = []
  } finally {
    loading.value = false
  }
}

function pick(id) {
  selectedId.value = id
}

function next() {
  const elder = elders.value.find((e) => e.id === selectedId.value)
  if (!elder) {
    ElMessage.warning('请先选择就诊人')
    return
  }
  // 写入下单草稿，后续 step2 / step3 从中读取，避免把一长串参数塞进 URL
  draft.setElder(elder.id, elder.name)
  router.push({
    path: '/family/order/step2',
    query: { elderId: elder.id }
  })
}

onMounted(loadElders)
</script>

<template>
  <NlPageShell title="预约陪诊" has-cta>
    <NlStepHeader :model-value="1" />

    <section class="step">
      <h2 class="nl-h2 step__title">请选择本次陪诊的就诊人</h2>
      <p class="nl-caption nl-text-muted">系统会自动按就诊人匹配医院偏好与服务地址</p>

      <NlSkeleton v-if="loading" :count="3" />

      <NlEmpty
        v-else-if="!elders.length"
        type="empty"
        title="还没有绑定老人"
        description="先绑定一位老人，才能为他预约陪诊"
        action-text="去绑定"
        @action="router.push('/family/elder/bind')"
      />

      <ul v-else class="elderpick">
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
              {{ e.genderLabel }}<template v-if="e.phone"> · {{ e.phone }}</template>
              <template v-if="e.favoriteHospital"> · {{ e.favoriteHospital }}</template>
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
      <el-button
        type="primary"
        size="large"
        round
        class="step__cta"
        :disabled="!elders.length"
        @click="next"
      >
        下一步
      </el-button>
    </template>
  </NlPageShell>
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
