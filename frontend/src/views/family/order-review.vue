<script setup>
/**
 * 评价订单（M-17 · design.md §4 / PRD §M7）
 *
 * - 评价信息提示：订单必须 COMPLETED 才能评价
 * - 服务综合评分：1-5 星
 * - 标签多选（准时 / 耐心 / 专业 / 细心 / 善沟通）
 * - 文字评价
 * - 匿名开关
 * - 提交后回到订单详情
 */
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { NlPhoneShell, NlCard, NlNoticeBar } from '@/components'

const route = useRoute()
const router = useRouter()

const form = ref({
  score: 0,
  scoreLabel: '',
  tags: [],
  content: '',
  anonymous: false
})

const tags = ['准时', '耐心', '专业', '细心', '善沟通', '有耐心', '态度好', '经验丰富']
const scoreLabels = ['', '非常差', '差', '一般', '好', '非常好']

function setScore(n) {
  form.value.score = n
  form.value.scoreLabel = scoreLabels[n]
}

function toggleTag(tag) {
  const i = form.value.tags.indexOf(tag)
  if (i === -1) form.value.tags.push(tag)
  else form.value.tags.splice(i, 1)
}

function submit() {
  if (form.value.score === 0) {
    ElMessage.warning('请先评分')
    return
  }
  if (form.value.content.trim().length < 5) {
    ElMessage.warning('请输入文字评价（至少 5 个字）')
    return
  }
  ElMessage.success('评价已提交，感谢您的反馈')
  setTimeout(() => router.push(`/family/order/${route.params.id}`), 600)
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '评价订单' }">
    <NlNoticeBar tone="info">
      订单必须在「已完成」状态下才可评价 · 评价提交后陪诊员可见，但匿名评价不会展示您的昵称
    </NlNoticeBar>

    <!-- 综合评分 -->
    <NlCard title="综合评分">
      <div class="score">
        <ul class="stars">
          <li v-for="n in 5" :key="n" @click="setScore(n)">
            <svg viewBox="0 0 24 24" width="36" height="36" fill="currentColor">
              <path
                d="M12 2.5l3 6.6 7 .9-5.1 4.9 1.3 7-6.2-3.4-6.2 3.4 1.3-7L2 10l7-.9 3-6.6z"
                :fill="n <= form.score ? '#F79009' : '#E7EDF5'"
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

    <!-- 标签 -->
    <NlCard title="印象标签（多选）">
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

    <!-- 文字评价 -->
    <NlCard title="文字评价" plain>
      <el-input
        v-model="form.content"
        type="textarea"
        :rows="5"
        placeholder="请如实描述本次陪诊体验，最少 5 个字；敏感词会被自动拒绝"
        maxlength="500"
        show-word-limit
      />
    </NlCard>

    <!-- 匿名开关 -->
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
      <el-button type="primary" size="large" round class="submit-bar__btn" @click="submit">
        提交评价
      </el-button>
    </div>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

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
