<script setup>
/**
 * 下单 · Step3（M-09 · design.md §4 · PRD §6.3「本服务为线下结算」硬口径）
 *
 * - 顶部 NlStepHeader 3-2-3
 * - 「本服务为线下结算」顶部提示条（合规）
 * - 信息确认：就诊人 / 医院 + 科室 / 就诊时间 / 医院地址（文字，无地图）/ 服务费
 * - 底部 Primary「提交订单」 + Ghost「返回修改」
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
 *
 * ⚠️ 与骨架版的差异（联调时暴露的两处真实问题）：
 *
 *   1. **删掉了臆造的「上门接送费 ¥30」。** 后端 `companion_order` 只有 `fee` 一个金额，
 *      `OrderServiceImpl#resolveFee` 也只算 128 / 158 两档。骨架版凭空加了一笔
 *      「上门接送费」，让页面合计变成 158/188，而落库只有 `fee` ——
 *      用户看到的钱和系统记的钱永远对不上。这是一期「线上记账 + 线下结算」
 *      下最容易被追着问的地方，不能留。
 *
 *   2. **提交时不传 `fee`。** 让后端按 `visitTime` 算（工作日白天 128 / 夜间或周末 158）。
 *      前端已有一套 `estimateFee` 用于展示，但那是**镜像不是真源** ——
 *      一旦两边口径漂移，传自家算的值就会把错误金额写进库。
 */
import { computed, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPageShell, NlStepHeader, NlCard } from '@/components'
import { estimateFee } from '@/constants/hospitals'
import { useOrderDraftStore } from '@/store/modules/orderDraft'
import { createOrder } from '@/api/order'
import { formatDateTime } from '@/utils/format'

const router = useRouter()
const draft = useOrderDraftStore()

const form = reactive({
  remark: '',
  submitting: false
})

/* ---------- 闸门：draft 不完整就退回 step1 ---------- */
onMounted(() => {
  if (!draft.isComplete) {
    ElMessage.warning('下单信息不完整，请重新填写')
    // 用 replace 而不是 push：避免用户在浏览器里「后退」又回到这个空页面
    router.replace('/family/order/step1')
  }
})

/** 就诊时间字符串 → Date，用于算预估费用 */
const visitDate = computed(() => {
  if (!draft.visitTime) return null
  // 后端口径 "yyyy-MM-dd HH:mm:ss"，手工拆解而不用 new Date(string) ——
  // 后者在 iOS Safari 上对带空格的格式解析失败，会得到 Invalid Date
  const [d, t = '00:00:00'] = draft.visitTime.split(' ')
  const [y, m, day] = d.split('-').map(Number)
  const [hh, mm] = t.split(':').map(Number)
  return new Date(y, m - 1, day, hh, mm)
})

const previewFee = computed(() => (visitDate.value ? estimateFee(visitDate.value) : null))

async function submit() {
  if (!draft.isComplete || form.submitting) return

  try {
    await ElMessageBox.confirm(
      `提交后订单将进入接单大厅，陪诊员接单后会推送通知。`
        + `服务费约 ¥${previewFee.value} 为线上记账，最终由线下结算。`,
      '确认提交',
      { confirmButtonText: '提交订单', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }

  form.submitting = true
  try {
    const result = await createOrder({
      elderId: draft.elderId,
      hospital: draft.hospital.name,
      department: draft.department,
      visitTime: draft.visitTime,
      address: draft.hospital.address,
      // ⚠️ 经纬度必须带：不传的话订单坐标写成 NULL，
      //    M5 打卡的「是否到达陪诊地点」距离校验会被静默跳过（见 OrderCreateDTO 类注释）
      longitude: draft.hospital.longitude,
      latitude: draft.hospital.latitude,
      remark: form.remark || undefined
      // 刻意不传 fee：由后端 resolveFee 按 visitTime 计算，前端不参与定价
    })

    ElMessage.success(`订单已提交，订单号 ${result.orderNo}`)
    // 提交成功即清草稿，避免用户再下一单时带出上一单的老人/医院
    const orderId = result.orderId
    draft.reset()
    // 直接进详情页而不是列表页：用户此刻最想知道的是「这一单现在是什么状态」，
    // 列表页还要他自己再点一次。replace 避免后退回到已提交的表单
    router.replace(`/family/order/${orderId}`)
  } catch {
    // 失败提示已由 axios 拦截器统一弹出；草稿保留，用户可以改完再提交
  } finally {
    form.submitting = false
  }
}

function back() {
  router.back()
}
</script>

<template>
  <NlPageShell title="确认订单" has-cta>
    <NlStepHeader :model-value="3" />

    <section class="block">
      <!-- 顶部「本服务为线下结算」提示条 (design.md §6.3 硬约束) -->
      <div class="notice">
        <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="8" cy="8" r="7" />
          <path d="M8 5v3.5" />
          <circle cx="8" cy="11" r="0.6" fill="currentColor" />
        </svg>
        <span>本服务为线下结算：服务费仅做线上记账，实际费用在服务完成后由双方线下结清，平台不代收。</span>
      </div>

      <h2 class="nl-h2 block__title">订单信息</h2>
      <ul class="confirm-list">
        <li class="row">
          <span class="row__key">就诊人</span>
          <span class="row__val">
            <span class="nl-h3">{{ draft.elderName || '—' }}</span>
          </span>
        </li>
        <li class="row">
          <span class="row__key">陪诊医院</span>
          <span class="row__val">
            <span class="nl-h3">{{ draft.hospital?.name || '—' }}</span>
            <span class="nl-caption nl-text-muted">{{ draft.hospital?.level }}</span>
          </span>
        </li>
        <li class="row">
          <span class="row__key">就诊科室</span>
          <span class="row__val">
            <span class="nl-body">{{ draft.department || '—' }}</span>
          </span>
        </li>
        <li class="row">
          <span class="row__key">就诊时间</span>
          <span class="row__val">
            <span class="nl-h3 is-num">{{ formatDateTime(draft.visitTime) }}</span>
          </span>
        </li>
        <li class="row">
          <span class="row__key">医院地址</span>
          <span class="row__val">
            <span class="nl-body">{{ draft.hospital?.address || '—' }}</span>
            <span class="nl-caption nl-text-muted">（文字地址·一期无地图导航）</span>
          </span>
        </li>
        <li class="row row--remark">
          <span class="row__key">备注信息</span>
          <span class="row__val">
            <el-input
              v-model="form.remark"
              type="textarea"
              :rows="2"
              maxlength="500"
              show-word-limit
              placeholder="选填，如：老人腿脚不便、需轮椅、听力较差请大声沟通"
            />
          </span>
        </li>
      </ul>

      <NlCard title="费用说明" class="fee-card">
        <div class="fee-row">
          <span>预估服务费</span>
          <span class="is-num fee-row__val">{{ previewFee ? `¥${previewFee}` : '以订单为准' }}</span>
        </div>
        <p class="nl-caption nl-text-muted fee-hint">
          工作日上午 8 点至下午 6 点为 ¥128，夜间或周末为 ¥158；
          最终金额以后端按下单时的就诊时间计算为准，此处仅供预览。
        </p>
      </NlCard>
    </section>

    <template #cta>
      <div class="cta">
        <el-button size="large" round :disabled="form.submitting" @click="back">返回修改</el-button>
        <el-button
          type="primary"
          size="large"
          round
          class="cta__primary"
          :loading="form.submitting"
          @click="submit"
        >
          提交订单
        </el-button>
      </div>
    </template>
  </NlPageShell>
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

.fee-card {
  margin-top: var(--nl-space-5);
}

.fee-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--nl-font-body);
  color: var(--nl-text-2);

  &__val {
    font-size: 18px;
    font-weight: 700;
    color: var(--nl-primary);
  }
}

.fee-hint {
  margin: 0;
  line-height: 1.6;
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
