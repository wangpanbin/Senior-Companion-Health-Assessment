<script setup>
/**
 * 陪诊员入驻 · 资质提交（M-20 · design.md §1.2 P1）
 *
 * - 实名信息（姓名 / 身份证号必填）
 * - 资质证件上传（身份证正反面 / 健康证，单文件 ≤10MB）
 * - 接单偏好（区域 + 时段 多选）
 * - 提交后进入待审核状态
 * - 设计原则：所有证件文件按 magic bytes 校验，不信任前端 Content-Type
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { NlPhoneShell, NlCard, NlStatusChip, NlNoticeBar } from '@/components'

const router = useRouter()

const form = ref({
  realName: '',
  idCard: '110101********4567',  // 仅作为预填，提交时再加密
  phone: '138****1234',           // 来自注册账号
  area: '',
  periods: []
})

const areas = ['朝阳区', '海淀区', '丰台区', '西城区', '东城区']
const periodOptions = [
  { key: 'morning', label: '上午（06:00 - 12:00）' },
  { key: 'noon',    label: '中午（12:00 - 14:00）' },
  { key: 'afternoon', label: '下午（14:00 - 18:00）' },
  { key: 'evening', label: '晚上（18:00 - 22:00）' }
]

const files = ref([
  { key: 'id_front', name: '身份证（正面）', file: null },
  { key: 'id_back',  name: '身份证（反面）', file: null },
  { key: 'health',   name: '健康证',         file: null }
])

const status = ref('PENDING') // 当前提交流程进度（PENDING / SUBMITTED / APPROVED）

function pickFile(key) {
  // mock：实际触发 input[type=file]
  files.value.find((f) => f.key === key).file = {
    name: `${key}-${Date.now()}.jpg`,
    size: '1.2 MB'
  }
  ElMessage.success('已选择文件（前端 mock）')
}

function removeFile(key) {
  const f = files.value.find((x) => x.key === key)
  f.file = null
}

async function submit() {
  if (!form.value.realName) {
    ElMessage.warning('请填写真实姓名')
    return
  }
  if (!form.value.area) {
    ElMessage.warning('请选择接单区域')
    return
  }
  if (!form.value.periods.length) {
    ElMessage.warning('请至少选择 1 个接单时段')
    return
  }
  if (!files.value.every((f) => f.file)) {
    ElMessage.warning('请上传全部资质证件')
    return
  }

  await ElMessageBox.confirm(
    '提交后将进入审核（一般 1-3 个工作日）。审核通过前不可接单。',
    '确认提交资质',
    { confirmButtonText: '提交审核' }
  )

  status.value = 'SUBMITTED'
  ElMessage.success('已提交，请耐心等待审核')
}
</script>

<template>
  <NlPhoneShell :nav="{ title: '陪诊员入驻' }">
    <NlNoticeBar v-if="status === 'SUBMITTED'" tone="success">
      资质审核中，请耐心等待。审核结果将通过站内信通知。
    </NlNoticeBar>

    <!-- 当前状态 -->
    <NlCard v-if="status === 'SUBMITTED'" plain>
      <div class="status">
        <NlStatusChip status="PENDING" text="审核中" :dot="true" />
        <span class="nl-caption nl-text-muted">预计 1-3 个工作日反馈</span>
      </div>
    </NlCard>

    <!-- 实名信息 -->
    <NlCard title="实名信息">
      <el-form size="large" label-position="top">
        <el-form-item label="真实姓名" required>
          <el-input v-model="form.realName" placeholder="请输入身份证上的姓名" maxlength="20" />
        </el-form-item>
        <el-form-item label="身份证号" required>
          <el-input v-model="form.idCard" placeholder="18 位身份证号" maxlength="18" />
        </el-form-item>
        <el-form-item label="注册手机号">
          <el-input :model-value="form.phone" disabled />
        </el-form-item>
      </el-form>
    </NlCard>

    <!-- 证件上传 -->
    <NlCard title="资质证件（每张 ≤10MB）" plain>
      <ul class="files">
        <li v-for="f in files" :key="f.key" class="file">
          <span class="nl-body file__name">{{ f.name }}</span>
          <span v-if="f.file" class="nl-caption file__size is-num">{{ f.file.name }} · {{ f.file.size }}</span>
          <span v-else class="nl-caption nl-text-weak">未上传</span>
          <el-button v-if="f.file" text size="small" type="danger" @click="removeFile(f.key)">删除</el-button>
          <el-button v-else type="primary" plain round size="small" @click="pickFile(f.key)">上传</el-button>
        </li>
      </ul>
    </NlCard>

    <!-- 接单偏好 -->
    <NlCard title="接单偏好">
      <p class="nl-caption nl-text-muted">选择常驻区域与可接单时段（可多选）</p>
      <div class="pref-area">
        <span class="pref-label">区域</span>
        <div class="chips">
          <button
            v-for="a in areas"
            :key="a"
            :class="['chips__item', { 'is-active': form.area === a }]"
            @click="form.area = a"
          >
            {{ a }}
          </button>
        </div>
      </div>
      <div class="pref-area">
        <span class="pref-label">时段</span>
        <el-checkbox-group v-model="form.periods" class="periods">
          <el-checkbox v-for="p in periodOptions" :key="p.key" :label="p.key">
            {{ p.label }}
          </el-checkbox>
        </el-checkbox-group>
      </div>
    </NlCard>

    <div class="submit-bar">
      <el-button type="primary" size="large" round class="submit-bar__btn" :disabled="status === 'SUBMITTED'" @click="submit">
        {{ status === 'SUBMITTED' ? '已提交审核' : '提交审核' }}
      </el-button>
    </div>
  </NlPhoneShell>
</template>

<style scoped lang="scss">
@use '@/styles/variables.scss' as *;

.status {
  display: flex;
  gap: var(--nl-space-2);
  align-items: center;
}

.files {
  padding: 0;
  margin: 0;
  list-style: none;

  .file {
    display: grid;
    grid-template-columns: 1fr auto auto;
    gap: var(--nl-space-2);
    align-items: center;
    padding: var(--nl-space-3) 0;
    border-bottom: 1px dashed var(--nl-divider);

    &:last-child {
      border-bottom: none;
    }
  }

  &__name {
    font-weight: 500;
  }
}

.pref-area {
  margin-top: var(--nl-space-3);

  &:first-of-type {
    margin-top: 0;
  }
}

.pref-label {
  display: block;
  margin-bottom: var(--nl-space-2);
  font-size: var(--nl-font-caption);
  color: var(--nl-text-3);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;

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

.periods {
  :deep(.el-checkbox) {
    display: flex;
    margin-right: 0;
    margin-bottom: var(--nl-space-2);
  }
}

.submit-bar {
  padding: var(--nl-space-5) var(--nl-gutter);

  &__btn {
    width: 100%;
  }
}
</style>
