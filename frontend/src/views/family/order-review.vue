<script setup>
/**
 * 评价订单（M-17 · design.md §4 / PRD §M7）
 *
 * 数据来源（全部在 @/api/order + @/api/review，已核对后端 DTO/VO）：
 *   - getOrder(orderId)              → 被评价订单摘要（只读）
 *   - getReviewByOrder(orderId)      → 查该订单是否已被评价（data 为 null 表示尚未评价）
 *   - createReview(data)             → 提交评价
 *
 * ⚠️ 字段真值（已读 ReviewCreateDTO / ReviewVO / ReviewController，未猜测）：
 *   - 评分 orderId 必填、score 为 1–5 整数（必填）；tags 最多 5 个、每个 ≤10 字（选填）
 *   - content 选填，但若填写则需 5–500 字；isAnonymous 布尔
 *   - 同一订单只能评价一次（后端唯一索引保证）；已评价则返回既有内容，不给提交按钮
 *   - 仅「已完成 COMPLETED」订单可评价；非 COMPLETED 直接提示，不让提交
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlCard, NlStatusChip, NlNoticeBar, NlEmpty, NlSkeleton } from '@/components'
import { getOrder } from '@/api/order'
import { getReviewByOrder, createReview } from '@/api/review'
import { formatVisitTime, formatMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const orderId = route.params.id

const order = ref(null)
const orderLoading = ref(false)
const existingReview = ref(null)
const checking = ref(false)
const submitting = ref(false)

const form = ref({
  score: 0,
  scoreLabel: '',
  tags: [],
  content: '',
  anonymous: false
})

const tags = ['准时', '耐心', '专业', '细心', '善沟通', '态度好', '经验丰富']
const scoreLabels = ['', '非常差', '差', '一般', '好', '非常好']

/** 是否处于可评价状态：订单存在 + 已完成 + 尚未评价 */
const canReview = computed(
  () => order.value != null && order.value.status === 'COMPLETED' && existingReview.value == null
)

const isCompleted = computed(() => order.value != null && order.value.status === 'COMPLETED')

function setScore(n) {
  form.value.score = n
  form.value.scoreLabel = scoreLabels[n]
}

function toggleTag(tag) {
  const i = form.value.tags.indexOf(tag)
  if (i === -1) {
    if (form.value.tags.length >= 5) {
      ElMessage.warning('最多选择 5 个标签')
      return
    }
    form.value.tags.push(tag)
  } else {
    form.value.tags.splice(i, 1)
  }
}

async function loadOrder() {
  orderLoading.value = true
  try {
    const data = await getOrder(orderId)
    order.value = data || null
  } catch {
    order.value = null
  } finally {
    orderLoading.value = false
  }
}

async function loadExistingReview() {
  checking.value = true
  try {
    const data = await getReviewByOrder(orderId)
    // 后端未评价时 data 为 null
    existingReview.value = data || null
  } catch {
    existingReview.value = null
  } finally {
    checking.value = false
  }
}

function submit() {
  if (form.value.score < 1 || form.value.score > 5) {
    ElMessage.warning('请先评分（1–5 星）')
    return
  }
  const content = form.value.content.trim()
  if (content.length > 0 && content.length < 5) {
    ElMessage.warning('文字评价需至少 5 个字，或留空只打分')
    return
  }
  if (content.length > 500) {
    ElMessage.warning('文字评价不能超过 500 个字')
    return
  }
  submitting.value = true
  createReview({
    orderId,
    score: form.value.score,
    tags: form.value.tags,
    content: content || undefined,
    isAnonymous: form.value.anonymous
  })
    .then(() => {
      ElMessage.success('评价已提交，感谢您的反馈')
      router.push(`/family/order/${orderId}`)
    })
    .catch(() => {
      // 拦截器已弹错误提示（如非 COMPLETED / 已评价 6001/6002），不再重复弹
    })
    .finally(() => {
      submitting.value = false
    })
}

onMounted(() => {
  loadOrder()
  loadExistingReview()
})
</script>

<template>
  <NlPhoneShell :nav="{ title: '评价订单' }">
    <NlSkeleton v-if="orderLoading || checking" :count="3" />

    <template v-else-if="order">
      <!-- 订单摘要（只读） -->
      <NlCard title="被评价订单">
        <div class="order-sum">
          <div class="order-sum__line">
            <span class="nl-h3">{{ order.hospital }}</span>
            <NlStatusChip :status="order.status" :text="order.statusLabel" />
          </div>
          <div class="nl-caption nl-text-muted order-sum__meta">
            {{ order.department }}<template v-if="order.elderName"> · {{ order.elderName }}</template><template v-if="order.elderAge != null"> {{ order.elderAge }}岁</template>
            · {{ formatVisitTime(order.visitTime) }}
          </div>
          <div class="nl-caption nl-text-muted order-sum__meta">
            <template v-if="order.companionName">陪诊员：{{ order.companionName }}</template><template v-else>尚无陪诊员</template>
            · 服务费 {{ formatMoney(order.fee) }}
          </div>
          <div v-if="order.orderNo" class="nl-caption nl-text-weak is-num order-sum__no">{{ order.orderNo }}</div>
        </div>
      </NlCard>

      <!-- 已评价：展示既有内容，不给提交按钮 -->
      <NlCard v-if="existingReview" title="我的评价">
        <div class="score">
          <ul class="stars">
            <li v-for="n in 5" :key="n">
              <svg viewBox="0 0 24 24" width="32" height="32">
                <path
                  d="M12 2.5l3 6.6 7 .9-5.1 4.9 1.3 7-6.2-3.4-6.2 3.4 1.3-7L2 10l7-.9 3-6.6z"
                  :style="{ fill: n <= existingReview.score ? 'var(--nl-warning)' : 'var(--nl-border)' }"
                  stroke="none"
                />
              </svg>
            </li>
          </ul>
          <div class="score__label is-active">{{ scoreLabels[existingReview.score] || ('评分 ' + existingReview.score) }}</div>
        </div>
        <div v-if="existingReview.tags && existingReview.tags.length" class="tags tags--readonly">
          <span v-for="t in existingReview.tags" :key="t" class="tags__item is-active">{{ t }}</span>
        </div>
        <p v-if="existingReview.content" class="review-content">{{ existingReview.content }}</p>
        <p class="nl-caption nl-text-weak review-foot">
          {{ existingReview.isAnonymous ? '匿名评价' : (existingReview.familyName || '本人') }}
          <template v-if="existingReview.createTime"> · {{ existingReview.createTime }}</template>
        </p>
        <NlNoticeBar tone="info">该订单已评价，评价提交后不可修改。</NlNoticeBar>
      </NlCard>

      <!-- 非已完成：明确提示，不渲染表单 -->
      <NlNoticeBar v-else-if="!isCompleted" tone="warning">
        仅「已完成」状态的订单可以评价；当前订单状态为「{{ order.statusLabel || order.status }}」，暂不可评价。
      </NlNoticeBar>

      <!-- 可评价：表单 -->
      <template v-else>
        <NlNoticeBar tone="info">
          订单必须在「已完成」状态下才可评价 · 评价提交后陪诊员可见，但匿名评价不会展示您的昵称
        </NlNoticeBar>

        <NlCard title="综合评分">
          <div class="score">
            <ul class="stars">
              <li v-for="n in 5" :key="n" @click="setScore(n)">
                <svg viewBox="0 0 24 24" width="36" height="36">
                  <path
                    d="M12 2.5l3 6.6 7 .9-5.1 4.9 1.3 7-6.2-3.4-6.2 3.4 1.3-7L2 10l7-.9 3-6.6z"
                    :style="{ fill: n <= form.score ? 'var(--nl-warning)' : 'var(--nl-border)' }"
                    stroke="none"
                  />
                </svg>
              </li>
            </ul>
            <div class="score__label" :class="{ 'is-active': form.score > 0 }">
              {{ form.scoreLabel || '请选择评分' }}
            </div>
          </div>
        </NlCard>

        <NlCard title="印象标签（多选，最多 5 个）">
          <ul class="tags">
            <li
              v-for="t in tags"
              :key="t"
              :class="['tags__item', { 'is-active': form.tags.includes(t) }]"
              @click="toggleTag(t)"
            >
              {{ t }}
            </li>
          </ul>
        </NlCard>

        <NlCard title="文字评价" plain>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            placeholder="请如实描述本次陪诊体验（选填，填写则需 5–500 字）"
            maxlength="500"
            show-word-limit
          />
        </NlCard>

        <NlCard plain>
          <div class="anon">
            <span class="nl-h3">匿名提交</span>
            <el-switch v-model="form.anonymous" size="default" />
          </div>
          <p class="nl-caption nl-text-muted">
            开启后评价内容公开，但陪诊员和管理员都看不到您的昵称与头像。
          </p>
        </NlCard>

        <div class="submit-bar">
          <el-button type="primary" size="large" round class="submit-bar__btn" :loading="submitting" @click="submit">
            提交评价
          </el-button>
        </div>
      </template>
    </template>

    <NlEmpty
      v-else
      type="empty"
      title="订单不存在"
      description="无法加载该订单，请返回订单列表重试"
    />
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.order-sum {
  &__line {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--nl-space-2);
  }

  &__meta {
    margin-top: 6px;
  }

  &__no {
    margin-top: 4px;
  }
}

.score {
  display: flex;
  flex-direction: column;
  gap: var(--nl-space-3);
  align-items: center;
  padding: var(--nl-space-3) 0;

  &__label {
    font-size: var(--nl-font-caption);
    color: var(--nl-text-3);

    &.is-active {
      color: var(--nl-warning);
      font-weight: 600;
      font-size: 18px;
    }
  }
}

.stars {
  display: flex;
  gap: 4px;
  padding: 0;
  margin: 0;
  list-style: none;

  li {
    cursor: pointer;
    line-height: 0;
    transition: transform 0.15s;

    &:active {
      transform: scale(0.92);
    }
  }
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;

  &--readonly {
    margin-top: var(--nl-space-2);
  }

  &__item {
    padding: 6px 14px;
    font-size: 13px;
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border: 1px solid transparent;
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-primary);
      background: var(--nl-primary-light);
      border-color: var(--nl-primary);
    }
  }
}

.review-content {
  margin: var(--nl-space-3) 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--nl-text-1);
}

.review-foot {
  margin: var(--nl-space-2) 0 0;
}

.anon {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.submit-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}
</style>
