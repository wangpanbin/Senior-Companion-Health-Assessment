<script setup>
/**
 * 下单 · Step3（M-09 · design.md §4 · PRD §6.3「本服务为线下结算」硬口径）
 *
 * - 顶部 NlStepHeader 3-2-3
 * - 「本服务为线下结算」顶部提示条（合规）
 * - 信息确认行：就诊人 / 医院 / 时间 / 服务地址（文字，无地图）/ 服务费
 * - 底部 Primary「提交订单」 + Ghost「返回修改」
 */
import { computed, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPhoneShell, NlStepHeader } from '@/components'

const route = useRoute()
const router = useRouter()

const form = reactive({
  elderName: '张大爷',
  elderAge: 72,
  elderPhone: '138****7777',
  hospitalName: route.query.hospitalName || '市第一人民医院',
  hospitalLevel: '三甲',
  visitDate: route.query.date || '今天',
  visitSlot: route.query.slotRange || '09:00 - 11:00',
  serviceAddress: '北京市朝阳区幸福路 123 号（上门接老人）',
  remark: ''
})

const fee = computed(() => {
  if (form.hospitalName.includes('妇幼')) return 150
  if (form.hospitalName.includes('中医院')) return 100
  if (form.hospitalName.includes('社区')) return 80
  return 120
})

const pickUpFee = computed(() => 30)

async function submit() {
  try {
    await ElMessageBox.confirm(
      `提交后订单将进入接单大厅，陪诊员接单后会推送通知。服务费 ¥${fee.value} 为线上记账，到院后线下结算。`,
      '确认提交',
      { confirmButtonText: '提交订单', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  ElMessage.success('订单提交成功，正在跳转大厅…')
  setTimeout(() => {
    router.push('/family/order')
  }, 800)
}

function back() {
  router.back()
}
</script>

<template>
  <NlPhoneShell
    :nav="{ title: '确认订单' }"
    :has-cta="true"
  >
    <NlStepHeader :model-value="3" />

    <section class="block">
      <!-- 顶部「本服务为线下结算」提示条 (design.md §6.3 硬约束) -->
      <div class="notice">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="8" cy="8" r="7" />
          <path d="M8 5v3.5" />
          <circle cx="8" cy="11" r="0.6" fill="currentColor" />
        </svg>
        <span>本服务为线下结算：服务费 ¥{{ fee + pickUpFee }} 仅做线上记账，到院后由陪诊员收取凭证。</span>
      </div>

      <h2 class="nl-h2 block__title">订单信息</h2>
      <ul class="confirm-list">
        <li class="row">
          <span class="row__key">就诊人</span>
          <span class="row__val">
            <span class="nl-h3">{{ form.elderName }}</span>
            <span class="nl-caption nl-text-muted">{{ form.elderAge }} 岁 · {{ form.elderPhone }}</span>
          </span>
        </li>
        <li class="row">
          <span class="row__key">陪诊医院</span>
          <span class="row__val">
            <span class="nl-h3">{{ form.hospitalName }}</span>
            <span class="nl-caption nl-text-muted">{{ form.hospitalLevel }}</span>
          </span>
        </li>
        <li class="row">
          <span class="row__key">就诊时间</span>
          <span class="row__val">
            <span class="nl-h3 is-num">{{ form.visitDate }} · {{ form.visitSlot }}</span>
          </span>
        </li>
        <li class="row">
          <span class="row__key">服务地址</span>
          <span class="row__val">
            <span class="nl-body">{{ form.serviceAddress }}</span>
            <span class="nl-caption nl-text-muted">（上门接老人·无地图导航）</span>
          </span>
        </li>
        <li class="row row--remark">
          <span class="row__key">备注信息</span>
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="选填，如：老人腿脚不便、需轮椅 / 有过敏史"
          />
        </li>
      </ul>

      <h2 class="nl-h2 block__title fee-title">费用明细</h2>
      <ul class="fee-list">
        <li>
          <span>服务费</span>
          <span class="is-num">¥{{ fee }}</span>
        </li>
        <li>
          <span>上门接送费</span>
          <span class="is-num">¥{{ pickUpFee }}</span>
        </li>
        <li class="fee-list__total">
          <span>合计（线上记账）</span>
          <span class="is-num fee-list__total-val">¥{{ fee + pickUpFee }}</span>
        </li>
      </ul>
    </section>

    <template #cta>
      <div class="cta">
        <el-button size="large" round @click="back">返回修改</el-button>
        <el-button type="primary" size="large" round class="cta__primary" @click="submit">
          提交订单
        </el-button>
      </div>
    </template>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.block {
  padding: 0 var(--nl-gutter);

  &__title {
    margin: var(--nl-space-5) 0 var(--nl-space-3);
  }
}

.notice {
  display: flex;
  gap: var(--nl-space-2);
  align-items: flex-start;
  padding: var(--nl-space-3);
  font-size: var(--nl-font-caption);
  line-height: 1.6;
  color: var(--nl-warning);
  background: var(--nl-warning-bg);
  border-radius: var(--nl-radius-card);

  svg {
    flex-shrink: 0;
    margin-top: 2px;
  }
}

.confirm-list {
  padding: 0;
  margin: 0;
  background: var(--nl-bg-card);
  border-radius: var(--nl-radius-card);
  list-style: none;
  overflow: hidden;
}

.row {
  display: flex;
  gap: var(--nl-space-3);
  align-items: flex-start;
  padding: var(--nl-space-3) var(--nl-space-4);
  border-bottom: 1px solid var(--nl-divider);

  &:last-child {
    border-bottom: none;
  }

  &__key {
    flex-shrink: 0;
    width: 80px;
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
    padding-top: 2px;
  }

  &__val {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
}

.fee-title {
  margin-top: var(--nl-space-5);
}

.fee-list {
  padding: 0 var(--nl-space-4);
  margin: 0;
  background: var(--nl-bg-card);
  border-radius: var(--nl-radius-card);
  list-style: none;
  overflow: hidden;

  li {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--nl-space-3) 0;
    font-size: var(--nl-font-body);
    color: var(--nl-text-2);
    border-bottom: 1px solid var(--nl-divider);
  }

  li:last-child {
    border-bottom: none;
  }

  &__total {
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__total-val {
    font-size: 18px;
    color: var(--nl-primary);
  }
}

.cta {
  display: flex;
  gap: var(--nl-space-3);
  padding: var(--nl-space-4);
  background: var(--nl-bg-card);
  box-shadow: var(--nl-shadow-float);

  :deep(.el-button) {
    flex: 1;
  }

  &__primary {
    flex: 2;
    font-size: 16px;
    font-weight: 500;
  }
}
</style>
