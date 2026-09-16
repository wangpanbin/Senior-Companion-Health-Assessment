<script setup>
/**
 * 我要投诉（M-18 · design.md §4 / PRD §M7）
 *
 * - 投诉类型选择
 * - 投诉描述（敏感词拦截提示）
 * - 凭证图片上传（≤10MB）
 * - 提交后状态：待处理 → 处理中 → 已结案
 * - 列表显示我的历史投诉（mock）
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPhoneShell, NlCard, NlStatusChip, NlNoticeBar, NlAvatar } from '@/components'

const router = useRouter()

const types = [
  { value: 'SERVICE',   label: '服务态度' },
  { value: 'TIME',       label: '迟到/失约' },
  { value: 'SAFETY',     label: '安全隐患' },
  { value: 'FINANCIAL',  label: '费用纠纷' },
  { value: 'OTHER',      label: '其他' }
]

const form = ref({
  type: 'SERVICE',
  content: '',
  files: []
})

/** mock 敏感词 */
const BANNED = ['操你妈', '傻逼', '骗子']

function pickFiles() {
  // mock：实际走 Element Plus Upload
  form.value.files.push({ name: `evidence-${form.value.files.length + 1}.jpg`, size: '180 KB' })
}

function removeFile(idx) {
  form.value.files.splice(idx, 1)
}

async function submit() {
  if (!form.value.content.trim()) {
    ElMessage.warning('请填写投诉描述')
    return
  }
  if (form.value.content.length < 10) {
    ElMessage.warning('投诉描述至少 10 个字')
    return
  }
  const hit = BANNED.find((w) => form.value.content.includes(w))
  if (hit) {
    await ElMessageBox.alert(
      '我们检测到敏感词，已拦截本次提交。请用事实陈述，避免使用过激用语。',
      '内容不合规',
      { type: 'warning' }
    )
    return
  }
  ElMessage.success('已提交投诉，将由平台介入处理')
  setTimeout(() => router.back(), 600)
}

const history = [
  { id: 'CP20250910001', type: '时间迟到', content: '陪诊员迟到 30 分钟', status: 'CLOSED',     submitAt: '09-10 14:00' },
  { id: 'CP20250916003', type: '费用纠纷', content: '重复收取陪诊费',     status: 'PROCESSING', submitAt: '09-16 11:00' }
]
</script>

<template>
  <NlPhoneShell :nav="{ title: '我要投诉' }">
    <NlNoticeBar tone="warning">
      投诉将由管理员仲裁处理，提交后不可修改；请如实陈述并附凭证图片。
    </NlNoticeBar>

    <!-- 类型选择 -->
    <NlCard title="投诉类型">
      <ul class="types">
        <li
          v-for="t in types"
          :key="t.value"
          :class="['types__item', { 'is-active': form.type === t.value }]"
          @click="form.type = t.value"
        >
          {{ t.label }}
        </li>
      </ul>
    </NlCard>

    <!-- 描述 -->
    <NlCard title="投诉描述">
      <el-input
        v-model="form.content"
        type="textarea"
        :rows="6"
        placeholder="请详细描述事件经过、影响、期望处理结果（≥10 字）"
        maxlength="500"
        show-word-limit
      />
    </NlCard>

    <!-- 凭证 -->
    <NlCard title="凭证材料" plain>
      <ul v-if="form.files.length" class="filelist">
        <li v-for="(f, i) in form.files" :key="i">
          <span>{{ f.name }}</span>
          <span class="nl-caption nl-text-muted">{{ f.size }}</span>
          <el-button text type="danger" size="small" @click="removeFile(i)">删除</el-button>
        </li>
      </ul>
      <el-button :disabled="form.files.length >= 6" type="primary" plain round size="small" @click="pickFiles">
        + 添加图片（每张 ≤10MB，至多 6 张）
      </el-button>
    </NlCard>

    <!-- 提交 -->
    <div class="submit-bar">
      <el-button type="primary" size="large" round class="submit-bar__btn" @click="submit">
        提交投诉
      </el-button>
    </div>

    <!-- 历史 -->
    <NlCard title="我的投诉记录" plain>
      <p v-if="!history.length" class="nl-caption nl-text-muted">暂无历史投诉</p>
      <ul v-else class="hist">
        <li v-for="h in history" :key="h.id" class="hist__item">
          <div class="hist__head">
            <span class="nl-h3">{{ h.id }}</span>
            <NlStatusChip :status="h.status" />
          </div>
          <p class="nl-caption nl-text-muted">{{ h.type }} · {{ h.content }}</p>
          <span class="nl-caption nl-text-weak is-num">{{ h.submitAt }}</span>
        </li>
      </ul>
    </NlCard>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.types {
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
    background: var(--nl-bg-card);
    border: 1.5px solid var(--nl-border);
    border-radius: 999px;
    cursor: pointer;

    &.is-active {
      color: var(--nl-primary);
      background: var(--nl-primary-light);
      border-color: var(--nl-primary);
    }
  }
}

.filelist {
  padding: 0;
  margin: 0 0 var(--nl-space-3);
  list-style: none;

  li {
    display: flex;
    gap: var(--nl-space-2);
    align-items: center;
    padding: 6px 0;
    font-size: 13px;
    border-bottom: 1px dashed var(--nl-divider);

    > span:first-child {
      flex: 1;
    }
  }
}

.submit-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}

.hist {
  padding: 0;
  margin: 0;
  list-style: none;

  &__item {
    padding: var(--nl-space-3) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }
  }

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
  }
}
</style>
