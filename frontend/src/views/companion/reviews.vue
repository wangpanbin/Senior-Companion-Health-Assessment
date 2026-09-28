<script setup>
/**
 * 陪诊员「收到的评价」页（E4 评价公信力闭环 · M-26）
 *
 * 数据来源：GET /api/review/companion/{meId}
 *   - 后端已自动过滤掉被管理员裁定无效的评价（is_valid=0 不出现）
 *   - 仅返回陪诊员本人可操作的「未回复」评价，可点击「回复 / 申诉」
 *
 * 关键交互：
 *   ① 回复表单：5–200 字，提交后该评价「companion_reply」立即出现；
 *   ② 申诉按钮：复用 POST /api/complaint，type=REVIEW_APPEAL + reviewId；
 *      仅在「评价 ≤ 15 日」时显示（与后端 Service 的 appealDeadlineDays 对齐）。
 *   ③ 「无回复时显示，回复后隐藏」：靠 v-if="!review.companionReply"。
 *
 * @author 银龄伴诊团队
 * @since E4
 */
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { NlPageShell, NlStatusChip, NlEmpty, NlSkeleton } from '@/components'
import { listCompanionReviews, replyReview } from '@/api/review'
import { useUserStore } from '@/store/modules/user'

const userStore = useUserStore()
const myId = computed(() => userStore.userInfo?.id)

const reviews = ref([])
const loading = ref(false)
const replyDraft = ref({}) // { reviewId: string }

async function load() {
  if (!myId.value) return
  loading.value = true
  try {
    const data = await listCompanionReviews(myId.value, { page: 1, size: 50 })
    reviews.value = data?.records || []
    // 初始填充草稿框
    for (const r of reviews.value) {
      if (!(r.id in replyDraft.value)) replyDraft.value[r.id] = ''
    }
  } catch {
    reviews.value = []
  } finally {
    loading.value = false
  }
}

const isEmpty = computed(() => !loading.value && !reviews.value.length)

/**
 * 提交回复。
 *
 * 后端契约（docs/api/06-review-complaint.md §回复评价）：
 * 6005 评价不存在 / 6006 已回复（含并发穿透）/ 6003 敏感词 / 3004 非该评价陪诊员
 */
async function submitReply(reviewId) {
  const content = (replyDraft.value[reviewId] || '').trim()
  if (content.length < 5 || content.length > 200) {
    ElMessage.warning('回复内容需 5–200 字')
    return
  }
  try {
    await replyReview(reviewId, { content })
    ElMessage.success('回复已提交')
    await load() // 拉一次新数据刷新「回复时间」+ 「已回复」状态
  } catch {
    // request.js 已弹 message；不重复弹
  }
}

/**
 * 「距评价提交 ≤ 15 日」判断（与后端 nianglin.review.appeal-deadline-days=15 对齐）。
 *
 * 后端会在超期时返回 409 + 「评价申诉须在评价提交后 15 日内发起」；
 * 这里前端先把按钮藏掉，少发一次失败请求。
 *
 * ⚠️ 本期页面先不发「申诉」按钮（PR 拆票时已规划，但实际留给后续迭代）：
 * - 申诉与回复是两个不同的心理动作，「回复」是补充事实，「申诉」是申请裁定，
 *   一行列表里同时放两个入口会让陪诊员在差评时不知所措；
 * - 详细方案见 docs/spec/E4-review-credibility.md §10.1。
 * 此函数留作占位以便后续 PR 引用。
 */
// eslint-disable-next-line no-unused-vars
function withinAppealWindow(createTime) {
  if (!createTime) return false
  const ts = new Date(createTime.replace(/-/g, '/')).getTime()
  return Date.now() - ts <= 15 * 24 * 60 * 60 * 1000
}

onMounted(load)
</script>

<template>
  <NlPageShell title="收到的评价" module="M7" :show-back="true">
    <template v-if="loading">
      <NlSkeleton :rows="3" />
    </template>
    <template v-else-if="isEmpty">
      <NlEmpty text="暂无评价" />
    </template>
    <template v-else>
      <div v-for="r in reviews" :key="r.id" class="rev-card">
        <div class="rev-card__head">
          <NlStatusChip :status="`SCORE_${r.score}`" :label="`${r.score} 星`" />
          <span class="rev-card__time">{{ r.createTime }}</span>
        </div>
        <div class="rev-card__author">{{ r.familyName }}</div>
        <div v-if="r.content" class="rev-card__content">{{ r.content }}</div>
        <div v-if="r.tags?.length" class="rev-card__tags">
          <span v-for="t in r.tags" :key="t" class="rev-card__tag">{{ t }}</span>
        </div>

        <!-- 已有回复 -->
        <div v-if="r.companionReply" class="rev-card__reply">
          <div class="rev-card__reply-label">我的回复 · {{ r.replyTime }}</div>
          <div class="rev-card__reply-body">{{ r.companionReply }}</div>
        </div>

        <!-- 未回复：行内表单 -->
        <div v-else class="rev-card__form">
          <textarea
            v-model="replyDraft[r.id]"
            class="rev-card__textarea"
            placeholder="向后续下单的家属补充服务过程的事实依据（5–200 字）"
            maxlength="200"
            minlength="5"
            rows="3"
          />
          <div class="rev-card__actions">
            <span class="rev-card__count">{{ (replyDraft[r.id] || '').length }} / 200</span>
            <el-button
              type="primary"
              size="small"
              :disabled="(replyDraft[r.id] || '').trim().length < 5"
              @click="submitReply(r.id)"
            >
              提交回复
            </el-button>
          </div>
        </div>
      </div>
    </template>
  </NlPageShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.rev-card {
  margin: $nl-space-4;
  padding: $nl-space-4;
  background: var(--nl-bg-card);
  border-radius: $nl-radius-card;
  box-shadow: $nl-shadow-card;

  &__head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: $nl-space-2;
  }

  &__time {
    color: var(--nl-text-2);
    font-size: 13px;
  }

  &__author {
    font-size: 16px;
    font-weight: 600;
    color: var(--nl-text-1);
    margin-bottom: $nl-space-2;
  }

  &__content {
    color: var(--nl-text-2);
    line-height: 1.6;
    margin-bottom: $nl-space-2;
  }

  &__tags {
    display: flex;
    flex-wrap: wrap;
    gap: $nl-space-2;
    margin-bottom: $nl-space-3;
  }

  &__tag {
    padding: 2px 10px;
    background: var(--nl-primary-light);
    color: var(--nl-primary);
    border-radius: $nl-radius-pill;
    font-size: 12px;
  }

  &__reply {
    margin-top: $nl-space-3;
    padding: $nl-space-3;
    background: var(--nl-bg);
    border-radius: $nl-radius-chip;
    border-left: 3px solid var(--nl-primary);
  }

  &__reply-label {
    font-size: 12px;
    color: var(--nl-text-2);
    margin-bottom: $nl-space-1;
  }

  &__reply-body {
    color: var(--nl-text-1);
    line-height: 1.5;
  }

  &__form {
    margin-top: $nl-space-3;
    padding-top: $nl-space-3;
    border-top: 1px dashed var(--nl-divider);
  }

  &__textarea {
    width: 100%;
    padding: $nl-space-3;
    border: 1px solid var(--nl-divider);
    border-radius: $nl-radius-chip;
    font-size: 14px;
    font-family: inherit;
    color: var(--nl-text-1);
    background: var(--nl-bg);
    resize: vertical;
  }

  &__actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: $nl-space-2;
  }

  &__count {
    font-size: 12px;
    color: var(--nl-text-2);
  }
}
</style>