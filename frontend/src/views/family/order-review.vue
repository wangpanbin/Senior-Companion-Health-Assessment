<script setup>
/**
 * 评价订单（M-17 · docs/design/design.md §4 / PRD §M7）
 *
 * - 形态自适应：正文只写一份，由 NlPageShell 决定套 Mobile 还是 Desktop 壳（ADR-0007）
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
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPageShell, NlCard, NlStatusChip, NlNoticeBar, NlEmpty, NlSkeleton } from '@/components'
import { getOrder } from '@/api/order'
import { getReviewByOrder, createReview } from '@/api/review'
import { formatVisitTime, formatMoney } from '@/utils/format'

const route = useRoute()
const router = useRouter()
/**
 * 订单 id 必须是**响应式**的：Vue Router 4 在「同一条路由记录只换 params」时会
 * 复用组件实例（onMounted 不再触发）。快照成常量会让「URL 是 B、页面还显示 A」，
 * 家属点「提交评价」就把评价写给了上一单。
 */
const orderId = computed(() => route.params.id)

const order = ref(null)
const orderLoading = ref(false)
/** R4：加载失败与「订单不存在」必须分开 */
const orderError = ref(false)
const existingReview = ref(null)
const checking = ref(false)
/** R4：是否已评价的预检失败（不阻断展示，但阻断重复提交） */
const reviewCheckError = ref(false)
const submitting = ref(false)

const form = ref({
  score: 0,
  scoreLabel: '',
  tags: [],
  content: '',
  anonymous: false
})

/* ==================== 表单校验（落到字段，而不是一次性 toast） ==================== */

/*
 * 校验规则与文案语义与改造前完全一致，只是把「一次性 toast」换成字段级错误：
 *   - 评分 1–5，必填（未选 0 视为未评分）
 *   - 文字评价选填；一旦填写则需 5–500 字（留空等价于只打分）
 *
 * ⚠️ 用自定义 validator 而不是 min/max，是为了保留原先「先 trim 再判长度」的口径：
 *    纯空格在改造前会被当成「没填」而放行，用 min/max 会被误判成「不足 5 字」。
 */
const formRef = ref(null)

const rules = {
  score: [
    {
      required: true,
      validator: (_rule, value, cb) => {
        const n = Number(value)
        if (!Number.isInteger(n) || n < 1 || n > 5) {
          cb(new Error('请先评分（1–5 星）'))
          return
        }
        cb()
      },
      trigger: 'change'
    }
  ],
  content: [
    {
      validator: (_rule, value, cb) => {
        const text = (value || '').trim()
        // 选填：留空即「只打分」
        if (text.length === 0) {
          cb()
          return
        }
        if (text.length < 5) {
          cb(new Error('文字评价需至少 5 个字，或留空只打分'))
          return
        }
        if (text.length > 500) {
          cb(new Error('文字评价不能超过 500 个字'))
          return
        }
        cb()
      },
      trigger: 'blur'
    }
  ]
}

const tags = ['准时', '耐心', '专业', '细心', '善沟通', '态度好', '经验丰富']
const scoreLabels = ['', '非常差', '差', '一般', '好', '非常好']

const isCompleted = computed(() => order.value != null && order.value.status === 'COMPLETED')

function setScore(n) {
  form.value.score = n
  form.value.scoreLabel = scoreLabels[n]
  // 星星不是 el-input，没有原生 change 事件：选完分要主动清掉「未评分」的错误
  formRef.value?.clearValidate('score')
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
  // 记下本次请求针对的订单：请求在途时 URL 可能又换了订单，回来时要能判断响应是否过期
  const target = orderId.value
  orderLoading.value = true
  try {
    const data = await getOrder(target)
    // 过期响应直接丢弃：否则会把上一单的数据显示在当前 URL 下
    if (String(target) !== String(orderId.value)) return
    order.value = data || null
    orderError.value = false
  } catch {
    if (String(target) !== String(orderId.value)) return
    order.value = null
    orderError.value = true
  } finally {
    if (String(target) === String(orderId.value)) orderLoading.value = false
  }
}

async function loadExistingReview() {
  const target = orderId.value
  checking.value = true
  try {
    const data = await getReviewByOrder(target)
    if (String(target) !== String(orderId.value)) return
    // 后端未评价时 data 为 null
    existingReview.value = data || null
    reviewCheckError.value = false
  } catch {
    if (String(target) !== String(orderId.value)) return
    existingReview.value = null
    reviewCheckError.value = true
  } finally {
    if (String(target) === String(orderId.value)) checking.value = false
  }
}

/**
 * URL 里的订单 id 与页面正在展示的订单 id 是否一致。
 *
 * 路由复用组件实例时存在一个竞态窗口：URL 已经换成 B，详情还没回来，
 * 页面显示的仍是 A。此时提交评价就会把评价写给上一单。
 */
function isDisplayedOrderCurrent() {
  const shown = order.value?.id
  // 详情里没有 id 时没有可比对的基准，不做拦截（避免把用户锁死在页面上）
  if (shown == null) return true
  return String(shown) === String(orderId.value)
}

async function submit() {
  // ⚠️ in-flight 锁必须在**任何 await 之前**同步置位（含下面的表单校验）：
  //    若等校验通过后才置位，两次调用会一起穿过 await，锁形同虚设 ——
  //    与登录页「回车触发隐式提交 + keyup」双发是同一类问题。
  if (submitting.value) return
  submitting.value = true
  try {
    // 二次校验：页面展示的订单必须就是 URL 指向的订单，否则拒绝提交、不发请求
    if (!isDisplayedOrderCurrent()) {
      ElMessage.warning('页面已切换到其它订单，请刷新后重试')
      return
    }
    // 字段级校验：一次跑完，错误直接落在对应字段下方（不再逐条弹 toast）
    try {
      await formRef.value?.validate()
    } catch {
      return
    }
    const content = form.value.content.trim()
    await createReview({
      orderId: orderId.value,
      score: form.value.score,
      tags: form.value.tags,
      content: content || undefined,
      isAnonymous: form.value.anonymous
    })
    ElMessage.success('评价已提交，感谢您的反馈')
    router.push(`/family/order/${orderId.value}`)
  } catch {
    // 拦截器已弹错误提示（如非 COMPLETED / 已评价 6001/6002），不再重复弹
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadOrder()
  loadExistingReview()
})

// 同一条路由记录只换 params 时组件实例会被复用，onMounted 不再触发 —— 这里补上重拉
watch(orderId, () => {
  // 换订单：先清掉上一单的「已评价」状态，避免等新数据时还显示着上一单的内容
  existingReview.value = null
  reviewCheckError.value = false
  loadOrder()
  loadExistingReview()
})
</script>

<template>
  <NlPageShell title="评价订单">
    <NlSkeleton v-if="orderLoading || checking" :count="3" />

    <NlEmpty
      v-else-if="orderError"
      type="network"
      title="订单信息加载失败"
      action-text="重试"
      @action="loadOrder"
    />

    <NlEmpty
      v-else-if="reviewCheckError"
      type="network"
      title="评价状态检查失败"
      description="无法确认该订单是否已评价，请重试后再提交"
      action-text="重试"
      @action="loadExistingReview"
    />

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

        <!--
          字段级校验：错误落在对应字段下方，而不是「一次性 toast」——
          原来一次只报一个错、错在哪个字段不定位、toast 3 秒后就消失。
        -->
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
          <NlCard title="综合评分">
            <el-form-item prop="score">
              <div class="score">
                <ul role="list" class="stars">
                  <li v-for="n in 5" :key="n" role="button" tabindex="0" @keydown.enter.prevent="setScore(n)" @keydown.space.prevent="setScore(n)" @click="setScore(n)">
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
            </el-form-item>
          </NlCard>

          <NlCard title="印象标签（多选，最多 5 个）">
            <ul role="list" class="tags">
              <li
  v-for="t in tags" :key="t" role="button" tabindex="0"
                :class="['tags__item', { 'is-active': form.tags.includes(t) }]"
                @keydown.enter.prevent="toggleTag(t)"
                @keydown.space.prevent="toggleTag(t)"
                @click="toggleTag(t)"
              >
                {{ t }}
              </li>
            </ul>
          </NlCard>

          <NlCard title="文字评价" plain>
            <el-form-item prop="content">
              <el-input
                v-model="form.content"
                type="textarea"
                :rows="5"
                placeholder="请如实描述本次陪诊体验（选填，填写则需 5–500 字）"
                maxlength="500"
                show-word-limit
              />
            </el-form-item>
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
        </el-form>

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
  </NlPageShell>
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
    color: var(--nl-text-2);

    &.is-active {
      color: var(--nl-warning-text);
      font-weight: 600;
      font-size: var(--nl-font-h2);
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
    font-size: var(--nl-font-caption);
    color: var(--nl-text-2);
    background: var(--nl-bg-sunken);
    border: 1px solid transparent;
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-primary-text);
      background: var(--nl-primary-light);
      border-color: var(--nl-primary);
    }
  }
}

.review-content {
  margin: var(--nl-space-3) 0 0;
  font-size: var(--nl-font-body);
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
