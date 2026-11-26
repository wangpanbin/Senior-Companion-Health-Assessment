<script setup>
/**
 * ReviewRulingDialog · 管理员评价有效性裁定对话框（E4）
 *
 * 两个入口共用这一个组件：
 *   · `views/admin/complaint.vue` —— 评价申诉（REVIEW_APPEAL）行内发起；
 *   · `views/admin/review-audit.vue` —— 评价巡查页主动发起。
 *
 * ## 为什么抽成组件而不是各写一份
 *
 * 裁定是**不可逆的写操作**（本期只支持「有效 → 无效」单向裁定），
 * 它的每一处细节 —— 理由长度校验、in-flight 锁、单向提示 ——
 * 漏掉任何一处都会变成「同一个操作在两个入口行为不一致」。
 * 管理员在申诉里裁过、在巡查页又裁一次，若两次的校验口径不同，
 * 就得靠回忆去判断哪个入口才是对的 —— 这正是「复制粘贴两份实现」的典型后果。
 *
 * ## 三条硬约束
 *
 * 1. **单向裁定**：只提交 `isValid: false`。后端对 `isValid: true` 直接 400，
 *    前端干脆不给这个选项 —— 让「恢复有效」不成为一个可点的按钮。
 * 2. **理由 10–200 字**（后端 `ReviewRulingDTO` 的 `@Size(min=10, max=200)`）。
 *    前端先拦一道，避免无效请求往返一趟。
 * 3. **in-flight 锁**：置位必须在第一个 `await` **之前同步**完成。
 *    置位晚于 await 的话，连点两次会一起穿过 await，锁形同虚设 ——
 *    用户看到「成功 + 报错」双提示，以为系统出了问题。
 *
 * @since E4
 */
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { rulingReview } from '@/api/admin'
import { confirmThen } from '@/utils/confirm'
import { formatDateTime } from '@/utils/format'

/** 理由长度下限（与后端 `ReviewRulingDTO` 的 `@Size(min = 10)` 一致） */
const REASON_MIN = 10
/** 理由长度上限（与后端 `@Size(max = 200)` 一致） */
const REASON_MAX = 200

const props = defineProps({
  /** 对话框可见性（v-model） */
  modelValue: { type: Boolean, default: false },
  /**
   * 待裁定的评价。
   *
   * 形如 `{ id, orderNo, score, content, companionReply, replyTime, isValid }`。
   * `id` 为空表示「评价还没取到」—— 此时不允许提交。
   */
  review: { type: Object, default: null }
})

const emit = defineEmits(['update:modelValue', 'success'])

const reason = ref('')
/** 行内错误文案（前端校验失败时展示） */
const reasonError = ref('')
/** in-flight 锁 —— 置位必须在第一个 await 之前同步完成 */
const submitting = ref(false)

/** 对话框标题带上订单号，管理员能确认自己裁的是哪一单 */
const dialogTitle = computed(() => {
  const orderNo = props.review?.orderNo
  return orderNo ? `裁定评价无效 - ${orderNo}` : '裁定评价无效'
})

/** 评价是否已取到（取不到就不给裁） */
const hasReview = computed(() => !!props.review?.id)

/** 已被裁定无效的评价不允许再裁（后端会返 409，这里提前挡住） */
const alreadyInvalid = computed(() => props.review?.isValid === false)

/** 理由字数（用于字数统计展示） */
const reasonLength = computed(() => reason.value.trim().length)

/** 关闭时清空理由，避免下次打开还留着上次的草稿 */
watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      reason.value = ''
      reasonError.value = ''
    }
  }
)

function close() {
  emit('update:modelValue', false)
}

/**
 * 提交裁定。
 *
 * 流程：本地校验 → 二次确认 → 调接口 → 成功回调。
 * 错误一律由 `request.js` 拦截器统一弹提示，这里不重复弹。
 */
async function submit() {
  if (!hasReview.value || alreadyInvalid.value) return

  const text = reason.value.trim()
  // ⚠️ 前端先拦一道：10 字以下写不出可被核对的裁定理由
  if (text.length < REASON_MIN) {
    reasonError.value = `裁定理由至少 ${REASON_MIN} 个字（当前 ${text.length} 字）`
    return
  }
  if (text.length > REASON_MAX) {
    reasonError.value = `裁定理由最多 ${REASON_MAX} 个字（当前 ${text.length} 字）`
    return
  }
  reasonError.value = ''

  // ⚠️ in-flight 锁必须在第一个 await 之前同步置位
  if (submitting.value) return
  submitting.value = true

  try {
    // confirmThen 把「用户是否点了确认」变成布尔：取消时静默返回 false，不发请求
    const confirmed = await confirmThen(() =>
      ElMessageBox.confirm(
        '裁定后该评价将从公开列表移除，且不可撤销（本期仅支持「有效 → 无效」单向裁定）。确认继续？',
        '确认裁定',
        { confirmButtonText: '确认裁定', type: 'warning' }
      )
    )
    if (!confirmed) return

    await rulingReview(props.review.id, { isValid: false, reason: text })
    ElMessage.success('裁定已生效')
    emit('success')
    close()
  } catch {
    // 后端报错由 request.js 拦截器统一提示，这里不重复弹
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="600px"
    align-center
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="ruling">
      <!-- 关联评价摘要：管理员必须看到「正在裁的是哪条评价」 -->
      <section v-if="hasReview" class="ruling__summary">
        <div class="ruling__summary-head">
          <span class="ruling__score">{{ review.score }} 星</span>
          <span v-if="alreadyInvalid" class="ruling__badge">已被裁定无效</span>
        </div>
        <p v-if="review.content" class="ruling__content">{{ review.content }}</p>
        <div v-if="review.companionReply" class="ruling__reply">
          <span class="ruling__reply-label">
            陪诊员回复 · {{ formatDateTime(review.replyTime) }}
          </span>
          <span class="ruling__reply-body">{{ review.companionReply }}</span>
        </div>
        <p v-else class="ruling__no-reply">该评价尚未有陪诊员回复</p>
      </section>

      <el-empty
        v-else
        type="empty"
        title="未找到关联评价"
        description="该订单还没有评价，无法裁定"
      />

      <section v-if="hasReview && !alreadyInvalid" class="ruling__form">
        <label class="ruling__label" for="review-ruling-reason">
          <b>裁定理由</b>
          <span>必填，{{ REASON_MIN }}–{{ REASON_MAX }} 字，对评价双方可见</span>
        </label>
        <el-input
          id="review-ruling-reason"
          v-model="reason"
          type="textarea"
          :rows="3"
          :maxlength="REASON_MAX"
          show-word-limit
          placeholder="请说明判定依据（如：家属描述与打卡记录明显不符）"
          @input="reasonError = ''"
        />
        <p v-if="reasonError" class="ruling__error">{{ reasonError }}</p>

        <p class="ruling__notice">
          单向裁定：仅支持「有效 → 无效」，本期不支持恢复有效。裁定后该评价
          从公开列表移除，同时向评价家属与陪诊员各发一条站内信。
        </p>
      </section>
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button
        v-if="hasReview && !alreadyInvalid"
        type="primary"
        :loading="submitting"
        :disabled="reasonLength === 0"
        @click="submit"
      >
        确认裁定
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.ruling {
  display: flex;
  flex-direction: column;
  gap: $nl-space-4;

  &__summary {
    display: flex;
    flex-direction: column;
    gap: $nl-space-2;
    padding: $nl-space-3;
    background: var(--nl-bg-sunken);
    border-radius: $nl-radius-input;
  }

  &__summary-head {
    display: flex;
    align-items: center;
    gap: $nl-space-2;
  }

  &__score {
    font-size: var(--nl-font-h3);
    font-weight: 600;
    color: var(--nl-text-1);
  }

  &__badge {
    padding: 2px $nl-space-2;
    font-size: var(--nl-font-caption);
    color: var(--nl-danger-text);
    background: var(--nl-danger-bg);
    border-radius: $nl-radius-chip;
  }

  &__content {
    font-size: var(--nl-font-body);
    color: var(--nl-text-1);
    line-height: 1.6;
  }

  &__reply {
    display: flex;
    flex-direction: column;
    gap: $nl-space-1;
    padding-top: $nl-space-2;
    border-top: 1px dashed var(--nl-divider);
  }

  &__reply-label {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
  }

  &__reply-body {
    font-size: var(--nl-font-body);
    color: var(--nl-text-2);
    line-height: 1.6;
  }

  &__no-reply {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);
  }

  &__form {
    display: flex;
    flex-direction: column;
    gap: $nl-space-2;
  }

  &__label {
    display: flex;
    flex-direction: column;
    gap: 2px;

    b {
      font-size: var(--nl-font-body);
      color: var(--nl-text-1);
    }

    span {
      font-size: var(--nl-font-caption);
      color: var(--nl-text-2);
    }
  }

  &__error {
    font-size: var(--nl-font-caption);
    color: var(--nl-danger-text);
  }

  &__notice {
    padding: $nl-space-2 $nl-space-3;
    font-size: var(--nl-font-caption);
    line-height: 1.6;
    color: var(--nl-warning-text);
    background: var(--nl-warning-bg);
    border-radius: $nl-radius-input;
  }
}
</style>
